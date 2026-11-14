package com.glassous.betterhrbust.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

data class AppPreferences(
    val username: String = "",
    val studentId: String = "",
    val year: String = "",
    val term: String = "2",
    val semesterTeachingWeeks: Int = 20,
    val calendarWeekCountKnown: Boolean = false,
    val currentWeek: Int = 1,
    val currentWeekReferenceDate: String = "",
    val selectedWeek: Int = 1,
    /** 课表视图形态的持久化标识（`TimetableViewMode.name`：WEEK / DAY） */
    val timetableViewMode: String = "WEEK",
    /** 课表节次粒度的持久化标识（`SectionMode.name`：COMBINE / BASE） */
    val timetableSectionMode: String = "COMBINE",
    val darkTheme: Boolean? = null,
    val offlineMode: Boolean = false,
    /** 上次成功登录时间（毫秒时间戳），用于会话失效提示的"一周节流"判定 */
    val lastLoginAt: Long = 0L,
    /** 上次会话失效提示时间（毫秒时间戳） */
    val lastPromptAt: Long = 0L,
    /** 上次全量同步日期（yyyy-MM-dd），用于"每天首次打开自动同步"判定 */
    val lastFullSyncDate: String = "",
    /**
     * 上一轮会话是否已判定失效。
     * 持久化以保证冷启动后登录页仍能提示"登录状态已失效"；
     * 而"是否弹出提示"不做持久化——用户看过一次后，下次启动不再重复弹出。
     */
    val sessionExpired: Boolean = false,
    /** 真实姓名（供概览页在档案缓存缺失时兜底展示，避免回退为占位文案） */
    val realName: String = "",
    /** 上次版本更新检测时间（毫秒时间戳），用于启动静默检测的"按天节流" */
    val lastUpdateCheckAt: Long = 0L,
    /** 导航坞折叠态（用户手动在坞内展开 / 折叠后持久化，冷启动沿用） */
    val navigationDockCollapsed: Boolean = false,
    /** 上课提示：课前 20 分钟提醒课程与教室，默认开启；全设备生效，小米呈现为超级岛，其余为实况 / 普通通知 */
    val classIslandReminder: Boolean = true,
    /** 「记住密码」保存的凭证（`学号\n混淆后的密码`），为空表示未保存 */
    val savedCredential: String = "",
    /** 空教室查询的默认教学楼（与教学区绑定，空串表示未设置） */
    val classroomDefaultAreaId: String = "",
    val classroomDefaultBuildingId: String = "",
    val classroomDefaultBuildingName: String = "",
) {
    fun teachingWeekOn(date: java.time.LocalDate): Int? {
        if (com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(date) != null)
            return com.glassous.betterhrbust.core.util.SchoolCalendar.weekOn(date)
        return com.glassous.betterhrbust.core.util.TeachingWeek.resolve(currentWeek, currentWeekReferenceDate, date, semesterTeachingWeeks)
    }

    val currentTeachingWeek: Int
        get() {
            val date=java.time.LocalDate.now()
            val semester=com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(date)
            return teachingWeekOn(date) ?: semester?.weeks ?: currentWeek.takeIf { it in 1..semesterTeachingWeeks } ?: 1
        }

}

class UserPreferencesManager(context: Context, private val store: DataStore<Preferences> = context.dataStore) {
    companion object {
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_STUDENT_ID = stringPreferencesKey("student_id")
        private val KEY_YEAR = stringPreferencesKey("year")
        private val KEY_TERM = stringPreferencesKey("term")
        private val KEY_WEEK_REFERENCE_DATE = stringPreferencesKey("week_reference_date")
        private val KEY_SEMESTER_WEEKS = intPreferencesKey("semester_teaching_weeks")
        private val KEY_CURRENT_WEEK = intPreferencesKey("current_week")
        private val KEY_SELECTED_WEEK = intPreferencesKey("selected_week")
        private val KEY_TIMETABLE_VIEW_MODE = stringPreferencesKey("timetable_view_mode")
        private val KEY_TIMETABLE_SECTION_MODE = stringPreferencesKey("timetable_section_mode")
        private val KEY_DARK_THEME = stringPreferencesKey("dark_theme") // "system", "dark", "light"
        private val KEY_OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        private val KEY_LAST_LOGIN_AT = longPreferencesKey("last_login_at")
        private val KEY_LAST_PROMPT_AT = longPreferencesKey("last_prompt_at")
        private val KEY_LAST_FULL_SYNC_DATE = stringPreferencesKey("last_full_sync_date")
        private val KEY_SESSION_EXPIRED = booleanPreferencesKey("session_expired")
        private val KEY_REAL_NAME = stringPreferencesKey("real_name")
        // 历史版本曾以该键明文持久化教务密码，保留常量仅用于启动时清除旧设备上的残留值；
        // 新的「记住密码」改用 KEY_SAVED_CREDENTIAL（只存混淆后的密文）
        private val KEY_SAVED_PASSWORD = stringPreferencesKey("saved_password")
        private val KEY_LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
        private val KEY_NAVIGATION_DOCK_COLLAPSED = booleanPreferencesKey("navigation_dock_collapsed")
        private val KEY_CLASS_ISLAND_REMINDER = booleanPreferencesKey("class_island_reminder")
        /**
         * 「记住密码」保存的凭证（`学号\n混淆后的密码`）。
         * DataStore 位于应用私有目录，且不参与系统云备份与换机迁移
         * （见 res/xml/backup_rules.xml 与 res/xml/data_extraction_rules.xml）。
         */
        private val KEY_SAVED_CREDENTIAL = stringPreferencesKey("saved_credential")
        private val KEY_CLASSROOM_DEFAULT_AREA = stringPreferencesKey("classroom_default_area")
        private val KEY_CLASSROOM_DEFAULT_BUILDING = stringPreferencesKey("classroom_default_building")
        private val KEY_CLASSROOM_DEFAULT_BUILDING_NAME = stringPreferencesKey("classroom_default_building_name")

        /**
         * 解析已保存凭证中属于 [username] 的密码。
         * 学号不匹配、格式损坏或未保存时返回 null，避免把 A 的密码填到 B 的账号上。
         */
        fun resolveSavedPassword(username: String, value: String): String? {
            val name = username.trim()
            if (name.isEmpty() || value.isEmpty()) return null
            val separator = value.indexOf('\n')
            if (separator <= 0) return null
            if (value.substring(0, separator).trim() != name) return null
            return CredentialObfuscator.deobfuscate(value.substring(separator + 1))
        }
    }

    val preferencesFlow: Flow<AppPreferences> = store.data.map { prefs ->
        val darkThemeStr = prefs[KEY_DARK_THEME] ?: "system"
        val darkThemeBool = when (darkThemeStr) {
            "dark" -> true
            "light" -> false
            else -> null
        }
        AppPreferences(
            username = prefs[KEY_USERNAME] ?: "",
            studentId = prefs[KEY_STUDENT_ID] ?: "",
            year = prefs[KEY_YEAR] ?: "",
            term = prefs[KEY_TERM] ?: "2",
            semesterTeachingWeeks = com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(java.time.LocalDate.now())?.weeks ?: prefs[KEY_SEMESTER_WEEKS] ?: 20,
            calendarWeekCountKnown = com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(java.time.LocalDate.now()) != null || prefs.contains(KEY_SEMESTER_WEEKS),
            currentWeek = prefs[KEY_CURRENT_WEEK] ?: 1,
            currentWeekReferenceDate = prefs[KEY_WEEK_REFERENCE_DATE] ?: "",
            selectedWeek = prefs[KEY_SELECTED_WEEK] ?: 1,
            timetableViewMode = prefs[KEY_TIMETABLE_VIEW_MODE] ?: "WEEK",
            timetableSectionMode = prefs[KEY_TIMETABLE_SECTION_MODE] ?: "COMBINE",
            darkTheme = darkThemeBool,
            offlineMode = prefs[KEY_OFFLINE_MODE] ?: false,
            lastLoginAt = prefs[KEY_LAST_LOGIN_AT] ?: 0L,
            lastPromptAt = prefs[KEY_LAST_PROMPT_AT] ?: 0L,
            lastFullSyncDate = prefs[KEY_LAST_FULL_SYNC_DATE] ?: "",
            sessionExpired = prefs[KEY_SESSION_EXPIRED] ?: false,
            realName = prefs[KEY_REAL_NAME] ?: "",
            lastUpdateCheckAt = prefs[KEY_LAST_UPDATE_CHECK_AT] ?: 0L,
            navigationDockCollapsed = prefs[KEY_NAVIGATION_DOCK_COLLAPSED] ?: false,
            classIslandReminder = prefs[KEY_CLASS_ISLAND_REMINDER] ?: true,
            savedCredential = prefs[KEY_SAVED_CREDENTIAL] ?: "",
            classroomDefaultAreaId = prefs[KEY_CLASSROOM_DEFAULT_AREA] ?: "",
            classroomDefaultBuildingId = prefs[KEY_CLASSROOM_DEFAULT_BUILDING] ?: "",
            classroomDefaultBuildingName = prefs[KEY_CLASSROOM_DEFAULT_BUILDING_NAME] ?: ""
        )
    }

    /** 记录真实姓名（用于概览页展示） */
    suspend fun setRealName(name: String, expected: AppPreferences? = null) {
        store.edit { prefs ->
            if (expected != null && (prefs[KEY_USERNAME] != expected.username ||
                (prefs[KEY_LAST_LOGIN_AT] ?: 0L) != expected.lastLoginAt)) return@edit
            if (name.isBlank()) prefs.remove(KEY_REAL_NAME) else prefs[KEY_REAL_NAME] = name
        }
    }

    /**
     * 清除历史版本残留的明文密码键（升级迁移）。
     * 该键存的是明文密码，旧安装上的值需在启动时主动删除，避免明文凭证留在
     * DataStore 文件中；「记住密码」改用 [setSavedCredential]（只存混淆后的密文）。
     */
    suspend fun purgeLegacySavedPassword() {
        store.edit { prefs -> prefs.remove(KEY_SAVED_PASSWORD) }
    }

    /**
     * 保存「记住密码」凭证（登录成功后按用户勾选调用）。
     * 密码混淆后落盘，并一并记录学号用于校验；入参为空等价于清除。
     */
    suspend fun setSavedCredential(username: String, password: String) {
        val name = username.trim()
        store.edit { prefs ->
            if (name.isEmpty() || password.isEmpty()) prefs.remove(KEY_SAVED_CREDENTIAL)
            else prefs[KEY_SAVED_CREDENTIAL] = "$name\n${CredentialObfuscator.obfuscate(password)}"
        }
    }

    /** 清除已保存的密码（取消勾选「记住密码」） */
    suspend fun clearSavedCredential() {
        store.edit { prefs -> prefs.remove(KEY_SAVED_CREDENTIAL) }
    }

    /**
     * 持久化会话失效状态。
     *
     * 只持久化「是否失效」，「是否弹提示」仅在本次运行期间有效：
     * 用户看过一次提示（或点了忽略）后，再次启动应用不再重复弹出，
     * 而是继续按一周节流来决定下一次提示时机。
     */
    suspend fun setSessionState(expired: Boolean, expected: AppPreferences? = null) {
        store.edit { prefs ->
            if (expected != null && (prefs[KEY_USERNAME] != expected.username ||
                (prefs[KEY_LAST_LOGIN_AT] ?: 0L) != expected.lastLoginAt)) return@edit
            if (expired) prefs[KEY_SESSION_EXPIRED] = true else prefs.remove(KEY_SESSION_EXPIRED)
        }
    }

    suspend fun saveAuth(username: String, studentId: String, year: String, term: String, realName: String = "") {
        store.edit { prefs ->
            if (prefs[KEY_USERNAME] != username) {
                prefs.remove(KEY_LAST_FULL_SYNC_DATE)
                prefs.remove(KEY_WEEK_REFERENCE_DATE)
                prefs.remove(KEY_CURRENT_WEEK)
                prefs.remove(KEY_SELECTED_WEEK)
            }
            // 学年或学期变化同样需要重置周次缓存（shared 校历学期切换）
            if (prefs[KEY_USERNAME] != username || prefs[KEY_YEAR] != year || prefs[KEY_TERM] != term) {
                prefs.remove(KEY_WEEK_REFERENCE_DATE)
                prefs.remove(KEY_CURRENT_WEEK)
                prefs.remove(KEY_SELECTED_WEEK)
                prefs.remove(KEY_SEMESTER_WEEKS)
            }
            // 无论是否换账号，都清掉历史版本可能残留的明文密码键（H-2，不恢复 savedPassword 存储）
            prefs.remove(KEY_SAVED_PASSWORD)
            prefs.remove(KEY_LAST_FULL_SYNC_DATE)
            prefs[KEY_REAL_NAME] = realName
            prefs[KEY_LAST_LOGIN_AT] = System.currentTimeMillis()
            prefs.remove(KEY_LAST_PROMPT_AT)
            prefs.remove(KEY_SESSION_EXPIRED)
            prefs[KEY_USERNAME] = username
            prefs[KEY_STUDENT_ID] = studentId
            prefs[KEY_YEAR] = year
            prefs[KEY_TERM] = term
        }
    }

    /** 记录上一次成功登录时间 */
    suspend fun setLastLoginAt(timestamp: Long) {
        store.edit { prefs ->
            prefs[KEY_LAST_LOGIN_AT] = timestamp
        }
    }

    /** 记录上一次会话失效提示时间 */
    suspend fun setLastPromptAt(timestamp: Long, expected: AppPreferences? = null) {
        store.edit { prefs ->
            if (expected != null && (prefs[KEY_USERNAME] != expected.username ||
                (prefs[KEY_LAST_LOGIN_AT] ?: 0L) != expected.lastLoginAt)) return@edit
            if (timestamp <= 0L) prefs.remove(KEY_LAST_PROMPT_AT) else prefs[KEY_LAST_PROMPT_AT] = timestamp
        }
    }

    /** 记录上一次全量同步日期（yyyy-MM-dd） */
    suspend fun setLastFullSyncDate(date: String, expected: AppPreferences? = null) {
        store.edit { prefs ->
            if (expected != null && (prefs[KEY_USERNAME] != expected.username ||
                (prefs[KEY_LAST_LOGIN_AT] ?: 0L) != expected.lastLoginAt)) return@edit
            prefs[KEY_LAST_FULL_SYNC_DATE] = date
        }
    }

    /** 记录上一次版本更新检测时间（毫秒时间戳，用于启动静默检测的按天节流） */
    suspend fun setLastUpdateCheckAt(timestamp: Long) {
        store.edit { prefs ->
            if (timestamp <= 0L) prefs.remove(KEY_LAST_UPDATE_CHECK_AT)
            else prefs[KEY_LAST_UPDATE_CHECK_AT] = timestamp
        }
    }

    /** 记忆导航坞折叠态（用户手动在坞内展开 / 折叠后调用，冷启动沿用） */
    suspend fun setNavigationDockCollapsed(collapsed: Boolean) {
        store.edit { prefs ->
            prefs[KEY_NAVIGATION_DOCK_COLLAPSED] = collapsed
        }
    }

    /** 上课提示开关；关闭后调度器会立即取消已下发通知 */
    suspend fun setClassIslandReminder(enabled: Boolean) {
        store.edit { prefs ->
            prefs[KEY_CLASS_ISLAND_REMINDER] = enabled
        }
    }

    suspend fun setSelectedWeek(week: Int) {
        store.edit { prefs ->
            prefs[KEY_SELECTED_WEEK] = week
        }
    }

    /** 记忆课表视图形态（周 / 日），下次进入课表页时沿用 */
    suspend fun setTimetableViewMode(mode: String) {
        store.edit { prefs ->
            prefs[KEY_TIMETABLE_VIEW_MODE] = mode
        }
    }

    /** 记忆课表节次粒度（大节 / 小节），下次进入课表页时沿用 */
    suspend fun setTimetableSectionMode(mode: String) {
        store.edit { prefs ->
            prefs[KEY_TIMETABLE_SECTION_MODE] = mode
        }
    }

    /**
     * 记忆空教室查询的默认教学楼（与教学区绑定）。
     * 属于界面偏好而非教务数据，退出登录 / 换号时不清除。
     */
    suspend fun setClassroomDefaultBuilding(
        areaId: String,
        buildingId: String,
        buildingName: String,
        expected: AppPreferences? = null
    ) {
        if (areaId.isBlank() || buildingId.isBlank()) return
        store.edit { prefs ->
            if (expected != null && prefs[KEY_USERNAME] != expected.username) return@edit
            prefs[KEY_CLASSROOM_DEFAULT_AREA] = areaId
            prefs[KEY_CLASSROOM_DEFAULT_BUILDING] = buildingId
            prefs[KEY_CLASSROOM_DEFAULT_BUILDING_NAME] = buildingName
        }
    }

    /** 取消默认教学楼 */
    suspend fun clearClassroomDefaultBuilding(expected: AppPreferences? = null) {
        store.edit { prefs ->
            if (expected != null && prefs[KEY_USERNAME] != expected.username) return@edit
            prefs.remove(KEY_CLASSROOM_DEFAULT_AREA)
            prefs.remove(KEY_CLASSROOM_DEFAULT_BUILDING)
            prefs.remove(KEY_CLASSROOM_DEFAULT_BUILDING_NAME)
        }
    }

    suspend fun setCurrentWeek(week: Int, expected: AppPreferences? = null, teachingWeeks: Int? = null) {
        store.edit { prefs ->
            if (expected != null && (prefs[KEY_USERNAME] != expected.username ||
                (prefs[KEY_LAST_LOGIN_AT] ?: 0L) != expected.lastLoginAt)) return@edit
            if (teachingWeeks != null && teachingWeeks in 1..26) prefs[KEY_SEMESTER_WEEKS] = teachingWeeks
            val limit = prefs[KEY_SEMESTER_WEEKS] ?: 20
            if (week !in 1..limit) return@edit
            prefs[KEY_CURRENT_WEEK] = week
            prefs[KEY_WEEK_REFERENCE_DATE] = java.time.LocalDate.now().toString()
        }
    }

    suspend fun setDarkTheme(mode: String) { // "system", "dark", "light"
        store.edit { prefs ->
            prefs[KEY_DARK_THEME] = mode
        }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        store.edit { prefs ->
            prefs[KEY_OFFLINE_MODE] = enabled
        }
    }

    /**
     * 退出登录时清理会话数据。
     * 保留「账号」以便下次登录免输入学号；勾选过「记住密码」时凭证同样保留，
     * 便于重新登录自动填入（取消勾选时由 [clearSavedCredential] 立即清除）。
     */
    suspend fun clearSession() {
        store.edit { prefs ->
            prefs.remove(KEY_SEMESTER_WEEKS)
            prefs.remove(KEY_WEEK_REFERENCE_DATE)
            prefs.remove(KEY_CURRENT_WEEK)
            prefs.remove(KEY_SELECTED_WEEK)
            prefs.remove(KEY_STUDENT_ID)
            prefs.remove(KEY_YEAR)
            prefs.remove(KEY_TERM)
            prefs.remove(KEY_LAST_LOGIN_AT)
            prefs.remove(KEY_LAST_PROMPT_AT)
            prefs.remove(KEY_LAST_FULL_SYNC_DATE)
            prefs.remove(KEY_SESSION_EXPIRED)
            prefs.remove(KEY_REAL_NAME)
        }
    }
}
