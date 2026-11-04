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

    /** 旧账号的网络请求即使迟到，也不能写入或显示到新会话中。 */
    private suspend fun requireCurrentSession(snapshot: com.glassous.betterhrbust.core.datastore.AppPreferences?) {
        val current = prefs.preferencesFlow.firstOrNull()
        if (snapshot == null || current == null || snapshot.username != current.username ||
            snapshot.studentId != current.studentId || snapshot.lastLoginAt != current.lastLoginAt) {
            throw AccountSwitchedException()
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
                val cached = json.decodeFromString<TimetableResult>(localEntity.json)
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
                val cached = json.decodeFromString<TimetableResult>(localEntity.json)
                if (cached.cells.isNotEmpty() || cached.unarranged.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
            }

            // Cache to database
            val encoded = json.encodeToString(parsed)
            requireCurrentSession(session)
            database.timetableDao().insert(TimetableEntity(studentId = studentId, json = encoded))

            requireCurrentSession(session)
            emit(Resource.Success(parsed, isOfflineCache = false))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (localEntity != null) {
                try {
                    val cached = json.decodeFromString<TimetableResult>(localEntity.json)
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
            emit(Resource.Error("本地暂无培养方案缓存，请在顶部刷新数据"))
            return@flow
        }

        try {
            // Step 1: Query studentSelfSchedule.jsdo to extract encrypted studentId
            val jumpHtml = client.get("manager/studyschedule/studentSelfSchedule.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            val encIdMatch = Regex("""studentId=([^&"']+)""").find(jumpHtml)
            val encStudentId = encIdMatch?.groupValues?.get(1) ?: studentId

            // Step 2: Query studentScheduleShowByTerm.do
            val planHtml = client.get(
                "manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId=$encStudentId",
                preferredCharset = CharsetDecoderHelper.GBK
            )
            val parsed = AcademicParsers.parseCurriculumPlan(planHtml)

            // 远端返回空（异常页 / 登录页被解析为空结果）时保留既有缓存，
            // 避免一次失败的刷新把已持久化的培养方案永久覆盖为空
            if (parsed.groups.isEmpty() && localEntity != null) {
                val cached = json.decodeFromString<CurriculumPlanResult>(localEntity.json)
                if (cached.groups.isNotEmpty()) {
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
                }
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
                    requireCurrentSession(session)
                    emit(Resource.Success(cached, isOfflineCache = true))
                    return@flow
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
                "keyvalue" to keyword,
                "depid" to "1",
                "terms" to "",
                "status" to ""
            )
            val html = client.post(
                "manager/querycourse/course_list.jsdo",
                formBody = form,
                preferredCharset = CharsetDecoderHelper.GBK,
                encodeFormWithGbk = true
            )
            val list = AcademicParsers.parseCourseList(html)
            emit(Resource.Success(list))
        } catch (switched: AccountSwitchedException) {
            throw switched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
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
