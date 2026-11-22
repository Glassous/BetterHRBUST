package com.glassous.betterhrbust.data.repository

import com.glassous.betterhrbust.core.database.*
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.model.*
import com.glassous.betterhrbust.core.network.AcademicHttpClient
import com.glassous.betterhrbust.core.network.CharsetDecoderHelper
import com.glassous.betterhrbust.core.network.SessionExpiredException
import com.glassous.betterhrbust.core.parser.AcademicParsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AcademicRepository(
    private val client: AcademicHttpClient,
    private val database: AppDatabase,
    private val prefs: UserPreferencesManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读取课表缓存时统一修复字段：旧版解析器按固定行号取「地点/教师/周次/学时类型」，
     * 缺教师行的课程（军训、体育等）会把学时类型当成周次，导致已结束的课程仍显示为正常颜色。
     * 修复只用缓存里已有的 rawLines，不需要重新联网。
     */
    private fun decodeCachedTimetable(jsonText: String): TimetableResult =
        AcademicParsers.normalizeTimetableCells(json.decodeFromString<TimetableResult>(jsonText))

    /** 旧账号的网络请求即使迟到，也不能写入或显示到新会话中。 */
    private suspend fun requireCurrentSession(snapshot: com.glassous.betterhrbust.core.datastore.AppPreferences?) {
        val current = prefs.preferencesFlow.firstOrNull()
        if (snapshot == null || current == null || snapshot.username != current.username ||
            snapshot.studentId != current.studentId || snapshot.lastLoginAt != current.lastLoginAt) {
            throw AccountSwitchedException()
        }
    }

    /**
     * 本学期课程列表（用于补齐课表学分，并同步落库供「我的课程名录」离线使用）。
     *
     * 该请求失败不应拖垮课表本身，因此除协程取消外一律退化为空列表；
     * 会话失效仍由 Http 客户端的回调统一上报。
     */
    private suspend fun fetchCurrentCourses(
        session: com.glassous.betterhrbust.core.datastore.AppPreferences? = null,
        studentId: String = ""
    ): List<CurrentCourseItem> {
        return try {
            val html = client.get("student/currcourse/currcourse.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            if (AcademicParsers.isLoginPage(html)) {
                emptyList()
            } else {
                val courses = AcademicParsers.parseStudentContext(html).courses
                if (courses.isNotEmpty() && studentId.isNotEmpty() && session != null) {
                    requireCurrentSession(session)
                    database.currentCoursesDao().insert(
                        CurrentCoursesEntity(studentId = studentId, json = json.encodeToString(courses))
                    )
                }
                courses
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取本学期在修课程（与成绩单共同构成「我的课程名录」）。
     */
    fun getCurrentCourses(
        studentId: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<List<CurrentCourseItem>>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.studentId != studentId) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        val localEntity = database.currentCoursesDao().getCurrentCourses(studentId).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                val cached = json.decodeFromString<List<CurrentCourseItem>>(localEntity.json)
                requireCurrentSession(session)
                emit(Resource.Success(cached, isOfflineCache = true))
                if (cacheOnly) return@flow
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无本学期课程缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            val html = client.get("student/currcourse/currcourse.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            if (AcademicParsers.isLoginPage(html)) {
                throw SessionExpiredException("会话已过期，请重新登录")
            }
            AcademicParsers.parseUrupErrorMessage(html)?.let { throw java.io.IOException(it) }
            val parsed = AcademicParsers.parseStudentContext(html).courses

            if (parsed.isEmpty() && localEntity != null) {
                val cached = json.decodeFromString<List<CurrentCourseItem>>(localEntity.json)
                if (cached.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
            }

            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.currentCoursesDao().insert(CurrentCoursesEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = json.decodeFromString<List<CurrentCourseItem>>(localEntity.json)
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取本学期课程失败", e))
        }
    }

    fun getTimetable(
        studentId: String,
        year: String,
        term: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<TimetableResult>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.studentId != studentId) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        // 1. Check local cache
        val localEntity = database.timetableDao().getTimetable(studentId).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                val cached = decodeCachedTimetable(localEntity.json)
                requireCurrentSession(session)
                emit(Resource.Success(cached, isOfflineCache = true))
                // 离线只读模式：命中缓存后不再联网
                if (cacheOnly) return@flow
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无课表缓存，请在顶部刷新数据"))
            return@flow
        }

        // 2. Fetch remote
        try {
            val url = "manager/coursearrange/showTimetable.do?id=$studentId&yearid=$year&termid=$term&timetableType=STUDENT&sectionType=COMBINE"
            val html = client.get(url, preferredCharset = CharsetDecoderHelper.GBK)
            val parsed = AcademicParsers.parseTimetable(html)

            // 远端返回空（异常页 / 登录页被解析为空结果）时保留既有缓存，
            // 避免一次失败的刷新把已持久化的课表永久覆盖为空
            if (parsed.cells.isEmpty() && parsed.unarranged.isEmpty() && localEntity != null) {
                val cached = decodeCachedTimetable(localEntity.json)
                if (cached.cells.isNotEmpty() || cached.unarranged.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
            }

            // 课表页不提供学分，落缓存前用本学期课程列表补齐（请求失败时保持原样）
            val courses = fetchCurrentCourses(session = session, studentId = studentId)
            val enriched = if (courses.isEmpty()) parsed
            else parsed.copy(cells = AcademicParsers.enrichTimetableCredits(parsed.cells, courses))

            // Cache to database
            val encoded = json.encodeToString(enriched)
            requireCurrentSession(session)
            database.timetableDao().insert(TimetableEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(enriched, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = decodeCachedTimetable(localEntity.json)
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取课表失败", e))
        }
    }

    fun getScores(
        studentId: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<ScoreResult>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.studentId != studentId) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        val localEntity = database.scoreDao().getScores(studentId).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                val cached = json.decodeFromString<ScoreResult>(localEntity.json)
                requireCurrentSession(session)
                emit(Resource.Success(cached, isOfflineCache = true))
                // 离线只读模式：命中缓存后不再联网
                if (cacheOnly) return@flow
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无成绩缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            val html = client.post(
                "manager/score/studentOwnScore.do",
                formBody = mapOf("para" to "0"),
                preferredCharset = CharsetDecoderHelper.UTF_8
            )
            val parsed = AcademicParsers.parseScores(html)

            // 远端返回空（异常页 / 登录页被解析为空结果）时保留既有缓存，
            // 避免一次失败的刷新把已持久化的成绩永久覆盖为空
            if (parsed.scores.isEmpty() && localEntity != null) {
                val cached = json.decodeFromString<ScoreResult>(localEntity.json)
                if (cached.scores.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
            }

            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.scoreDao().insert(ScoreEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = json.decodeFromString<ScoreResult>(localEntity.json)
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取成绩失败", e))
        }
    }

    /**
     * 拉取考试列表：主接口「全部考试」优先，为空时回退「近期考试」接口
     * （后者只返回 7 天内考试，编码也不同，见 docs/api/05-exam.md）
     */
    private suspend fun fetchExamList(): List<ExamItem> {
        try {
            val html = client.get(
                "manager/examstu/studentQueryAllExam.do?pagingNumberPerVLID=100",
                preferredCharset = CharsetDecoderHelper.UTF_8
            )
            val parsed = AcademicParsers.parseExams(html)
            if (parsed.isNotEmpty()) return parsed
        } catch (e: SessionExpiredException) {
            throw e
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 主接口异常时继续尝试备用接口
        }
        val fallbackHtml = client.get("student/exam/index.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
        return AcademicParsers.parseExams(fallbackHtml)
    }

    fun getExams(
        studentId: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<List<ExamItem>>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.studentId != studentId) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        val localEntity = database.examDao().getExams(studentId).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                // 缓存可能已落库数天，倒计时必须按当前日期重算
                val cached = AcademicParsers.refreshExamCountdown(
                    json.decodeFromString<List<ExamItem>>(localEntity.json)
                )
                requireCurrentSession(session)
                emit(Resource.Success(cached, isOfflineCache = true))
                // 离线只读模式：命中缓存后不再联网
                if (cacheOnly) return@flow
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无考试缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            val parsed = fetchExamList()

            // 远端返回空（教务未发布 / 解析异常）时保留既有缓存，
            // 避免一次空结果把已持久化的考试数据永久覆盖为空
            if (parsed.isEmpty() && localEntity != null) {
                val cached = AcademicParsers.refreshExamCountdown(
                    json.decodeFromString<List<ExamItem>>(localEntity.json)
                )
                if (cached.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
            }

            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.examDao().insert(ExamEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = AcademicParsers.refreshExamCountdown(
                        json.decodeFromString<List<ExamItem>>(localEntity.json)
                    )
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取考试安排失败", e))
        }
    }

    fun getPersonalInfo(
        studentNumber: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<PersonalInfo>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.username != studentNumber) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        // 档案只按当前账号读取，禁止跨账号兜底。
        val localEntity = database.profileDao().getProfile(studentNumber).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                val cached = json.decodeFromString<PersonalInfo>(localEntity.json)
                require(cached.studentNumber.trim() == studentNumber.trim()) { "档案缓存账号不一致" }
                requireCurrentSession(session)
                emit(Resource.Success(cached, isOfflineCache = true))
                // 离线只读模式：命中缓存后不再联网
                if (cacheOnly) return@flow
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无档案缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            val html = client.get("showPersonalInfo.do", preferredCharset = CharsetDecoderHelper.UTF_8)
            val parsed = AcademicParsers.parsePersonalInfo(html)
            require(parsed.studentNumber.trim() == studentNumber.trim()) { "教务档案账号与当前登录账号不一致，已拒绝保存" }

            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.profileDao().insert(ProfileEntity(studentNumber = studentNumber, json = encoded))
            // 姓名落到 DataStore，缓存被清理后概览页仍能正确显示
            if (parsed.realName.isNotBlank() && prefs.preferencesFlow.firstOrNull()?.username == studentNumber) {
                requireCurrentSession(session)
                prefs.setRealName(parsed.realName, expected = session)
            }

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = json.decodeFromString<PersonalInfo>(localEntity.json)
                    require(cached.studentNumber.trim() == studentNumber.trim()) { "档案缓存账号不一致" }
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取个人档案失败", e))
        }
    }

    companion object {
        /** 与 JS `decodeURIComponent` 一致：保留 `+` 原义，避免 Base64 加密串中的 `+` 被误解码为空格。 */
        internal fun decodeUriComponent(raw: String): String =
            runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)

        /** 与 JS `encodeURIComponent` 一致：将 `+` 编码为 `%2B`、空格编码为 `%20`。 */
        internal fun encodeUriComponent(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        internal fun buildCurriculumPlanUrl(rawEncId: String, classId: String? = null): String {
            val encodedId = encodeUriComponent(decodeUriComponent(rawEncId))
            val classQuery = classId?.takeIf { it.isNotBlank() }?.let {
                "&classId=${encodeUriComponent(decodeUriComponent(it))}"
            }.orEmpty()
            return "manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId=$encodedId$classQuery"
        }
    }

    private fun isValidPlan(plan: CurriculumPlanResult): Boolean =
        plan.groups.isNotEmpty() || plan.totalRequiredCredits != null

    private suspend fun fetchCurriculumPlanHtml(rawEncId: String, rawClassId: String?): String {
        // 与 Web 端 api.js 完全对齐：首选只带 z=z&studentId=...（不附带 classId）
        val primaryHtml = client.get(
            buildCurriculumPlanUrl(rawEncId, classId = null),
            preferredCharset = CharsetDecoderHelper.GBK
        )
        if (AcademicParsers.isLoginPage(primaryHtml)) {
            throw SessionExpiredException("会话已过期，请重新登录")
        }
        val primaryErr = AcademicParsers.parseUrupErrorMessage(primaryHtml)
        if (primaryErr == null) {
            return primaryHtml
        }
        if (rawClassId.isNullOrBlank()) {
            throw java.io.IOException(primaryErr)
        }
        // 兜底：部分部署要求补全链接里的 classId 参数
        val fallbackHtml = client.get(
            buildCurriculumPlanUrl(rawEncId, classId = rawClassId),
            preferredCharset = CharsetDecoderHelper.GBK
        )
        if (AcademicParsers.isLoginPage(fallbackHtml)) {
            throw SessionExpiredException("会话已过期，请重新登录")
        }
        AcademicParsers.parseUrupErrorMessage(fallbackHtml)?.let { throw java.io.IOException(it) }
        return fallbackHtml
    }

    fun getCurriculumPlan(
        studentId: String,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<CurriculumPlanResult>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        if (session?.studentId != studentId) throw AccountSwitchedException("请求账号已失效")
        emit(Resource.Loading)

        val localEntity = database.curriculumDao().getPlan(studentId).firstOrNull()
        if (localEntity != null && !forceRefresh) {
            try {
                val cached = json.decodeFromString<CurriculumPlanResult>(localEntity.json)
                if (isValidPlan(cached)) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    // 离线只读模式：命中有效缓存后不再联网
                    if (cacheOnly) return@flow
                }
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无培养方案缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            // Step 1: Query studentSelfSchedule.jsdo to extract encrypted studentId (and optional classId)
            val jumpHtml = client.get("manager/studyschedule/studentSelfSchedule.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            if (AcademicParsers.isLoginPage(jumpHtml)) {
                throw SessionExpiredException("会话已过期，请重新登录")
            }
            AcademicParsers.parseUrupErrorMessage(jumpHtml)?.let { throw java.io.IOException(it) }

            val (rawEncId, rawClassId) = AcademicParsers.parseCurriculumJumpParams(jumpHtml)
            if (rawEncId.isBlank()) {
                throw java.io.IOException("培养方案未返回学生查询参数，请重新同步")
            }

            // Step 2: Query studentScheduleShowByTerm.do (aligned with Web api.js)
            var planHtml = fetchCurriculumPlanHtml(rawEncId, rawClassId)
            var parsed = AcademicParsers.parseCurriculumPlan(planHtml)
            if (!isValidPlan(parsed) && !rawClassId.isNullOrBlank()) {
                val retryHtml = client.get(
                    buildCurriculumPlanUrl(rawEncId, classId = rawClassId),
                    preferredCharset = CharsetDecoderHelper.GBK
                )
                if (AcademicParsers.isLoginPage(retryHtml)) {
                    throw SessionExpiredException("会话已过期，请重新登录")
                }
                if (AcademicParsers.parseUrupErrorMessage(retryHtml) == null) {
                    val retryParsed = AcademicParsers.parseCurriculumPlan(retryHtml)
                    if (isValidPlan(retryParsed)) {
                        planHtml = retryHtml
                        parsed = retryParsed
                    }
                }
            }

            // 远端返回空（异常页 / 登录页 / 未返回课组要求）时保留既有有效缓存，
            // 避免一次失败的刷新把已持久化的培养方案覆盖为空，也不把空方案当成成功写入缓存
            if (!isValidPlan(parsed)) {
                if (localEntity != null) {
                    val cached = json.decodeFromString<CurriculumPlanResult>(localEntity.json)
                    if (isValidPlan(cached)) {
                        requireCurrentSession(session)
                        emit(Resource.Success(cached, isOfflineCache = true))
                        return@flow
                    }
                }
                emit(Resource.Error("学校未返回培养方案学分要求，请稍后重试"))
                return@flow
            }

            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.curriculumDao().insert(CurriculumPlanEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = json.decodeFromString<CurriculumPlanResult>(localEntity.json)
                    if (isValidPlan(cached)) {
                        requireCurrentSession(session)
                        emit(Resource.Success(cached, isOfflineCache = true))
                        return@flow
                    }
                } catch (switched: AccountSwitchedException) {
                    throw switched
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
            emit(Resource.Error(e.message ?: "获取培养方案失败", e))
        }
    }

    fun getClassroomQueryOptions(): Flow<Resource<ClassroomQueryOptions>> = flow {
        emit(Resource.Loading)
        try {
            val html = client.get("teacher/teachresource/roomschedulequery.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            AcademicParsers.parseUrupErrorMessage(html)?.let {
                emit(Resource.Error(it))
                return@flow
            }
            val options = AcademicParsers.parseClassroomQueryOptions(html)
            emit(Resource.Success(options))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "获取教室选项失败", e))
        }
    }

    /**
     * 选定教学区后取该区的教学楼列表
     * 教务的级联刷新是 POST 查询页自身（表单 method=post）
     */
    fun getClassroomBuildings(aid: String): Flow<Resource<ClassroomQueryOptions>> = flow {
        emit(Resource.Loading)
        try {
            val html = client.post(
                "teacher/teachresource/roomschedulequery.jsdo",
                formBody = classroomQueryForm(aid = aid),
                preferredCharset = CharsetDecoderHelper.GBK,
                encodeFormWithGbk = true
            )
            AcademicParsers.parseUrupErrorMessage(html)?.let {
                emit(Resource.Error(it))
                return@flow
            }
            emit(Resource.Success(AcademicParsers.parseClassroomQueryOptions(html)))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "获取教学楼列表失败", e))
        }
    }

    /**
     * 按周查询整栋教学楼的占用（一天 × 全部教室 × 12 小节）
     *
     * 注意：whichweek 是第几周（1-26），day 是星期几（1-7），两者都必须是数字；
     * 传 -1 教务会返回「提示信息」错误页（正文 -2）。
     */
    fun getClassroomDaySchedule(
        aid: String,
        buildingId: String,
        week: Int,
        day: Int
    ): Flow<Resource<ClassroomDaySchedule>> = flow {
        emit(Resource.Loading)
        try {
            require(week in 1..26) { "教学周需在 1-26 之间" }
            require(day in 1..7) { "星期需在 1-7 之间" }
            val html = client.post(
                "teacher/teachresource/roomschedule_week.jsdo",
                formBody = classroomQueryForm(
                    aid = aid,
                    buildingId = buildingId,
                    room = "-1",
                    whichweek = week.toString(),
                    week = day.toString()
                ),
                preferredCharset = CharsetDecoderHelper.GBK,
                encodeFormWithGbk = true
            )
            AcademicParsers.parseUrupErrorMessage(html)?.let {
                emit(Resource.Error(it))
                return@flow
            }
            emit(Resource.Success(AcademicParsers.parseClassroomDaySchedule(html, day)))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "查询教室占用失败", e))
        }
    }

    /** 教室查询页的表单字段（与教务页面保持一致的占位值） */
    private fun classroomQueryForm(
        aid: String = "-1",
        buildingId: String = "-1",
        room: String = "-1",
        whichweek: String = "-1",
        week: String = "1"
    ): Map<String, String> = mapOf(
        "aid" to aid,
        "buildingid" to buildingId,
        "room" to room,
        "whichweek" to whichweek,
        "week" to week
    )

    fun searchCourses(keyword: String): Flow<Resource<List<CourseSearchItem>>> = flow {
        emit(Resource.Loading)
        try {
            val form = mapOf(
                "depid" to "1",
                "depname" to "哈尔滨理工大学",
                "trgroup" to "-2",
                "trgroupname" to "全部",
                "keyword" to "coursename",
                "keywordname" to "课程名",
                "keyvalue" to keyword,
                "roomsort" to "-2",
                "roomsortname" to "全部",
                "emanner" to "-2",
                "emannername" to "全部",
                "emode" to "-2",
                "emodename" to "全部",
                "status" to "-2",
                "statusname" to "全部",
                "orderby" to "0",
                "orderseq" to "0"
            )
            val html = client.post(
                "manager/querycourse/course_list.jsdo",
                formBody = form,
                preferredCharset = CharsetDecoderHelper.GBK,
                encodeFormWithGbk = true
            )
            if (AcademicParsers.isLoginPage(html)) {
                throw SessionExpiredException("会话已过期，请重新登录")
            }
            AcademicParsers.parseUrupErrorMessage(html)?.let {
                emit(Resource.Error(it))
                return@flow
            }
            val list = AcademicParsers.parseCourseList(html)
            emit(Resource.Success(list))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "课程检索失败", e))
        }
    }

    fun getNotices(
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): Flow<Resource<List<NoticeItem>>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        emit(Resource.Loading)

        val localEntities = database.noticeDao().getNotices().firstOrNull()
        if (!localEntities.isNullOrEmpty() && !forceRefresh) {
            val list = localEntities.map { NoticeItem(id = it.id, title = it.title, content = it.content, date = it.date) }
            emit(Resource.Success(list, isOfflineCache = true))
            // 离线只读模式：命中缓存后不再联网
            if (cacheOnly) return@flow
        }

        if (cacheOnly) {
            emit(Resource.Error("本地暂无公告缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            val html = client.get("calendarinfo/viewCalendarInfo.do", preferredCharset = CharsetDecoderHelper.UTF_8)
            val calendarInfo = AcademicParsers.parseCalendarInfo(html)

            // 解析结果无效（异常页 / 登录页：无公告、无学期信息、周次回退为 1）时，
            // 保留既有缓存与已记录的当前周，避免旧数据被覆盖为空、教学周被写坏
            val parsedUsable = calendarInfo.notices.isNotEmpty() ||
                calendarInfo.semesterName.isNotEmpty() ||
                calendarInfo.currentWeek > 1
            if (!parsedUsable && !localEntities.isNullOrEmpty()) {
                val list = localEntities.map { NoticeItem(id = it.id, title = it.title, content = it.content, date = it.date) }
                emit(Resource.Success(list, isOfflineCache = true))
                return@flow
            }

            val entities = calendarInfo.notices.map {
                NoticeEntity(id = it.id, title = it.title, content = it.content, date = it.date)
            }
            requireCurrentSession(session)
            database.noticeDao().insertAll(entities)
            // 清理不在本次结果中的旧行；内容未变的公告因 id 哈希一致得以保留（含已读状态）
            database.noticeDao().retainIds(entities.map { it.id })

            if (parsedUsable) {
                requireCurrentSession(session)
                prefs.setCurrentWeek(calendarInfo.currentWeek, expected = session, teachingWeeks = calendarInfo.teachingWeeks)
            }

            emit(Resource.Success(calendarInfo.notices, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (!localEntities.isNullOrEmpty()) {
                val list = localEntities.map { NoticeItem(id = it.id, title = it.title, content = it.content, date = it.date) }
                emit(Resource.Success(list, isOfflineCache = true))
                return@flow
            }
            emit(Resource.Error(e.message ?: "获取教学公告失败", e))
        }
    }

    /**
     * 拉取教学周。返回 Resource：解析失败为 Error，不再裸发回退值 1——
     * syncAll 的「已全部同步」判定把裸 Int 一律当新数据，失败的教学周会
     * 错误地满足完成条件。
     */
    fun getTeachingWeek(): Flow<Resource<Int>> = flow {
        val session = prefs.preferencesFlow.firstOrNull()
        try {
            val html = client.get("listLeft.do", preferredCharset = CharsetDecoderHelper.UTF_8)
            var week = AcademicParsers.parseTeachingWeek(html)

            // 每次同时取得校历，读取明确的学期长度；旧导航中的26不作为当前教学周。
            // 校历请求失败不阻断主流程，但账号切换的业务信号必须穿透（M-7）
            var calendarWeeks: Int? = null
            try {
                val calHtml = client.get("calendarinfo/viewCalendarInfo.do", preferredCharset = CharsetDecoderHelper.UTF_8)
                val calendar = AcademicParsers.parseCalendarInfo(calHtml)
                calendarWeeks = calendar.teachingWeeks
                val limit = calendarWeeks ?: session?.semesterTeachingWeeks ?: 20
                if (week !in 1..limit && calendar.currentWeek in 1..limit) week = calendar.currentWeek
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}

            if (week in 1..(calendarWeeks ?: session?.semesterTeachingWeeks ?: 20)) {
                requireCurrentSession(session)
                prefs.setCurrentWeek(week, expected = session, teachingWeeks = calendarWeeks)
                emit(Resource.Success(week))
            } else {
                emit(Resource.Error("未能识别教学周"))
            }
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // listLeft.do 异常时兜底使用 calendarinfo/viewCalendarInfo.do
            try {
                val calHtml = client.get("calendarinfo/viewCalendarInfo.do", preferredCharset = CharsetDecoderHelper.UTF_8)
                val calWeek = AcademicParsers.parseCalendarInfo(calHtml).currentWeek
                // 仅接受明确识别出的周次（>1），避免把"解析失败"误当第 1 周写坏本地教学周
                if (calWeek > 1) {
                    requireCurrentSession(session)
                    prefs.setCurrentWeek(calWeek, expected = session)
                    emit(Resource.Success(calWeek))
                    return@flow
                }
            } catch (switched: AccountSwitchedException) {
                throw switched
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
            emit(Resource.Error("未能识别教学周"))
        }
    }
}
