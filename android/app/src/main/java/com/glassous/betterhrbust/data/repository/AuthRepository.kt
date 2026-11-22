package com.glassous.betterhrbust.data.repository

import com.glassous.betterhrbust.core.database.AppDatabase
import com.glassous.betterhrbust.core.database.CurrentCoursesEntity
import com.glassous.betterhrbust.core.database.ProfileEntity
import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.model.AuthState
import com.glassous.betterhrbust.core.model.StudentContext
import com.glassous.betterhrbust.core.network.AcademicHttpClient
import com.glassous.betterhrbust.core.network.CharsetDecoderHelper
import com.glassous.betterhrbust.core.parser.AcademicParsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AuthRepository(
    private val client: AcademicHttpClient,
    private val prefs: UserPreferencesManager,
    private val database: AppDatabase
) {
    companion object {
        /** 会话失效提示间隔：一周 */
        const val PROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        internal fun needsAccountPreparation(previous: String?, prepared: String?, target: String, hasSession: Boolean): Boolean =
            !previous.isNullOrBlank() && previous != target && prepared != target && hasSession
    }

    private var preparedLoginAccount: String? = null
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong()

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val _isSessionExpired = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSessionExpired: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSessionExpired

    /**
     * 是否应主动弹出"登录状态已失效"提示（按一周节流后的判定结果）。
     * 规则：上次登录/上次提示在一周内且用户未手动刷新 → 不提示（仅在登录页展示已失效）；
     * 手动刷新 → 无条件提示；距上次提示满一周 → 提示一次，用户忽略后再过一周再提示。
     */
    private val _shouldPromptReLogin = kotlinx.coroutines.flow.MutableStateFlow(false)
    val shouldPromptReLogin: kotlinx.coroutines.flow.StateFlow<Boolean> = _shouldPromptReLogin

    /** 用户已忽略本轮失效提示（点击"忽略"后收起横幅） */
    private val _sessionPromptDismissed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sessionPromptDismissed: kotlinx.coroutines.flow.StateFlow<Boolean> = _sessionPromptDismissed

    /** 用户点击忽略：收起本轮失效横幅 */
    fun dismissSessionPrompt() {
        _sessionPromptDismissed.value = true
    }

    /** 用户已发起手动刷新：随后的会话失效需要无条件要求重新登录 */
    private var pendingManualPrompt = false

    /**
     * 本次进程内是否已对"当前这一轮会话失效"做过节流判定。
     * 置位后同一进程内不再重复评估，避免后续请求把提示时间不断后推；
     * 进程重启（冷启动）后归零，因此"再过一周再提示"仍能正常生效。
     */
    private var promptDecided = false

    val authState: Flow<AuthState> = prefs.preferencesFlow.map { pref ->
        if (pref.username.isNotEmpty() && pref.studentId.isNotEmpty()) {
            AuthState.Authenticated(username = pref.username, studentId = pref.studentId)
        } else {
            AuthState.Unauthenticated
        }
    }

    init {
        // 冷启动只恢复"会话已失效"状态（用于登录页提示与刷新拦截），
        // 不恢复"是否弹提示"：用户已看过一次提示后，下次启动不再重复弹出，
        // 而是继续按一周节流（lastPromptAt）计算下一次提示时机。
        scope.launch {
            // 迁移清理：历史版本曾明文持久化教务密码，启动时删除残留键
            prefs.purgeLegacySavedPassword()
            val snapshot = prefs.preferencesFlow.firstOrNull() ?: return@launch
            _isSessionExpired.value = snapshot.sessionExpired
            _shouldPromptReLogin.value = false
            _sessionPromptDismissed.value = false
        }
    }

    /** 节流标志的同步锁：markSessionExpired 由网络线程并发触发，「本轮只判定一次」不能被并发打破 */
    private val promptLock = Any()

    /** 标记用户发起了手动刷新（刷新时若会话失效，必须重新登录） */
    fun notifyManualRefreshIntent() {
        synchronized(promptLock) { pendingManualPrompt = true }
    }

    fun markSessionExpired(expired: Boolean = true) {
        val generation = sessionGeneration.get()
        if (!expired) {
            synchronized(promptLock) {
                _isSessionExpired.value = false
                _shouldPromptReLogin.value = false
                _sessionPromptDismissed.value = false
                pendingManualPrompt = false
                promptDecided = false
            }
            scope.launch { if (generation == sessionGeneration.get()) prefs.setSessionState(expired = false) }
            return
        }

        val manual = synchronized(promptLock) {
            val manual = pendingManualPrompt
            pendingManualPrompt = false

            // 本次进程内本轮失效只判定一次，避免后续重复请求把提示时间不断后推
            if (promptDecided && !manual) return
            promptDecided = true
            manual
        }

        _isSessionExpired.value = true
        scope.launch {
            val snapshot = prefs.preferencesFlow.firstOrNull()
            if (generation != sessionGeneration.get()) return@launch
            val now = System.currentTimeMillis()
            val base = maxOf(snapshot?.lastLoginAt ?: 0L, snapshot?.lastPromptAt ?: 0L)
            val shouldPrompt = manual || base <= 0L || (now - base) >= PROMPT_INTERVAL_MS
            _shouldPromptReLogin.value = shouldPrompt
            if (shouldPrompt) {
                prefs.setLastPromptAt(now, expected = snapshot)
                // 新的一次提示：重新展示横幅（覆盖上一次的"忽略"）
                _sessionPromptDismissed.value = false
            }
            prefs.setSessionState(expired = true, expected = snapshot)
        }
    }

    suspend fun getCaptcha(): ByteArray {
        return client.downloadCaptcha()
    }

    suspend fun checkCaptcha(code: String): Boolean {
        return client.checkCaptcha(code)
    }

    /**
     * 登录教务系统。
     *
     * @param rememberPassword 用户在登录页勾选「记住密码」时为 true：
     *   登录成功后把密码混淆后存入本地，供下次打开自动填入；未勾选则清除本机已保存的密码。
     */
    fun login(
        username: String,
        password: String,
        captcha: String,
        rememberPassword: Boolean = false
    ): Flow<Resource<StudentContext>> = flow {
        sessionGeneration.incrementAndGet()
        emit(Resource.Loading)
        try {
            val previous = prefs.preferencesFlow.firstOrNull()
            if (needsAccountPreparation(previous?.username, preparedLoginAccount, username, client.cookieJar.hasSession())) {
                client.logout()
                preparedLoginAccount = username
                _isSessionExpired.value = true
                prefs.setSessionState(expired = true)
                emit(Resource.Error("账号已切换，请填写新验证码后登录"))
                return@flow
            }
            val responseHtml = client.login(username, password, captcha)
            if (AcademicParsers.isLoginPage(responseHtml)) {
                val errorReason = AcademicParsers.parseLoginFailureReason(responseHtml)
                emit(Resource.Error(errorReason))
                return@flow
            }

            // Successfully logged in! Now fetch student context
            val contextHtml = client.get("student/currcourse/currcourse.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            if (AcademicParsers.isLoginPage(contextHtml)) {
                emit(Resource.Error("提取学生上下文失败，会话未建立"))
                return@flow
            }

            val studentContext = AcademicParsers.parseStudentContext(contextHtml)
            if (studentContext.studentId.isEmpty()) {
                // If internal studentId wasn't found, try year calculation fallback
                emit(Resource.Error("未获取到内部学生 ID，请重试"))
                return@flow
            }

            val profile = AcademicParsers.parsePersonalInfo(client.get("showPersonalInfo.do", CharsetDecoderHelper.UTF_8))
            if (profile.studentNumber.trim() != username.trim()) {
                client.logout()
                emit(Resource.Error("教务返回的账号与输入学号不一致，请刷新验证码重新登录"))
                return@flow
            }

            database.withTransaction {
                database.timetableDao().clear(studentContext.studentId)
                database.scoreDao().clear(studentContext.studentId)
                database.examDao().clear(studentContext.studentId)
                database.curriculumDao().clear(studentContext.studentId)
                database.currentCoursesDao().clear(studentContext.studentId)
                if (studentContext.courses.isNotEmpty()) {
                    database.currentCoursesDao().insert(
                        CurrentCoursesEntity(
                            studentId = studentContext.studentId,
                            json = kotlinx.serialization.json.Json.encodeToString(studentContext.courses)
                        )
                    )
                }
                database.profileDao().insert(ProfileEntity(studentNumber = username, json = kotlinx.serialization.json.Json.encodeToString(profile)))
            }
            _isSessionExpired.value = false
            _shouldPromptReLogin.value = false
            _sessionPromptDismissed.value = false
            pendingManualPrompt = false
            promptDecided = false
            // 身份、姓名和登录时间一次性写入，账号重建界面时不会读取旧账号字段
            prefs.saveAuth(
                username = username,
                studentId = studentContext.studentId,
                year = studentContext.year,
                term = studentContext.term,
                realName = profile.realName
            )
            // 「记住密码」：勾选则保存混淆后的凭证，未勾选则清除本机已保存的密码
            if (rememberPassword) prefs.setSavedCredential(username, password)
            else prefs.clearSavedCredential()
            preparedLoginAccount = null
            emit(Resource.Success(studentContext))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "登录请求发生异常", e))
        }
    }

    fun logout(): Flow<Resource<Unit>> = flow {
        sessionGeneration.incrementAndGet()
        preparedLoginAccount = null
        emit(Resource.Loading)
        _isSessionExpired.value = false
        _shouldPromptReLogin.value = false
        _sessionPromptDismissed.value = false
        pendingManualPrompt = false
        promptDecided = false
        // clearSession() 会一并清理 lastLoginAt / lastPromptAt / lastFullSyncDate
        prefs.setSessionState(expired = false)
        val studentId = prefs.preferencesFlow.firstOrNull()?.studentId ?: ""
        try {
            client.logout()
        } catch (_: Exception) {
            // 登出请求失败不影响本地清理
        }
        try {
            if (studentId.isNotEmpty()) {
                database.timetableDao().clear(studentId)
                database.scoreDao().clear(studentId)
                database.examDao().clear(studentId)
                database.curriculumDao().clear(studentId)
                database.currentCoursesDao().clear(studentId)
            }
            database.profileDao().clearAll()
            database.noticeDao().clearAll()
        } catch (_: Exception) {
            // 本地缓存清理失败不阻塞登出
        }
        prefs.clearSession()
        emit(Resource.Success(Unit))
    }
}
