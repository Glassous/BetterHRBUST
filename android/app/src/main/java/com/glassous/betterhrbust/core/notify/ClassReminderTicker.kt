package com.glassous.betterhrbust.core.notify

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.database.TimetableEntity
import com.glassous.betterhrbust.core.database.TimetableOverrideEntity
import com.glassous.betterhrbust.core.datastore.AppPreferences
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableOverridesPayload
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.parser.AcademicParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

/**
 * 「上课提示」核心协调器：所有设备统一调度，数据全部来自本地 DataStore 偏好与 Room 课表缓存，不触发联网。
 *
 * 实时刷新保障（无需打开 App）：
 * 1. **窗口外**：由 [ClassReminderScheduler] 注册下一节课前 20 分钟的单次 `AlarmManager` 精确唤醒，零轮询；
 * 2. **窗口内**：将超级岛通知绑定至 [ClassReminderService] 窗口级前台服务防 Millet 冻结，并叠加每分钟精确闹钟兜底；
 * 3. **亮屏校准**：进程存活期间监听 `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`，用户亮屏瞬间即刻校准剩余分钟数。
 */
object ClassReminderTicker {
    /** 模拟课程距现在多久开始（分钟），仅调试用 */
    private const val MOCK_MINUTES = 15L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val evalMutex = Mutex()

    @Volatile
    private var observeJob: Job? = null

    @Volatile
    private var screenReceiverRegistered = false

    @Volatile
    private var latestSnapshot: Snapshot? = null

    /** 上一次成功下发的课程标识（课程名 + 开始时刻）与显示分钟数 */
    @Volatile
    private var lastCourseKey: String? = null

    @Volatile
    private var lastMinutes: Long? = null

    @Volatile
    private var lastNotification: Notification? = null

    /** 冷启动后首次确认无课时执行一次 cancel，清理可能残留的旧通知，同时避免有课时先撤再弹引起闪烁 */
    @Volatile
    private var staleClearedOnBoot = false

    @Volatile
    private var hideLogged = false

    /**
     * 调试用模拟课：非空时取代真实课表参与提醒。
     * 只存在内存里，进程重启即失效；release 构建不会写入该值。
     */
    private val mockCourse = MutableStateFlow<UpcomingClass?>(null)

    fun start(context: Context) {
        val app = context.applicationContext as? BetterHrbustApp ?: return
        registerScreenWakeReceiverIfNeeded(app)
        if (observeJob?.isActive == true) return

        observeJob = scope.launch {
            try {
                combine(reminderSource(app), mockCourse) { snapshot, mock ->
                    snapshot.copy(mock = mock)
                }.collect { snapshot ->
                    latestSnapshot = snapshot
                    evaluateOnce(app, forceReschedule = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                islandLog(app, "上课提醒监听异常：${t.message}")
            }
        }
        islandLog(app, "上课提醒调度已启动")
    }

    /** 供广播接收器、Activity 恢复等入口异步触发一次重算。 */
    fun refreshAsync(
        context: Context,
        forceReschedule: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        start(appContext)
        scope.launch {
            try {
                evaluateOnce(appContext, forceReschedule = forceReschedule)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** 串行化执行一次提醒状态评估、通知更新与下一次系统闹钟注册。 */
    suspend fun evaluateOnce(context: Context, forceReschedule: Boolean = false) {
        val app = context.applicationContext as? BetterHrbustApp ?: return
        evalMutex.withLock {
            try {
                val snapshot = latestSnapshot ?: loadSnapshotFromStorage(app).also {
                    latestSnapshot = it
                }
                evaluateLocked(app, snapshot, forceReschedule)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                islandLog(app, "上课提醒计算失败：${t.message}")
            }
        }
    }

    /** 调试：开启 / 关闭「模拟 15 分钟后上课」，返回切换后的状态。 */
    fun toggleMock(context: Context): Boolean {
        val enable = mockCourse.value == null
        if (enable) {
            mockCourse.value = buildMockCourse()
            islandLog(context, "已开启模拟提醒：$MOCK_MINUTES 分钟后上课")
        } else {
            mockCourse.value = null
            islandLog(context, "已关闭模拟提醒")
        }
        refreshAsync(context, forceReschedule = true)
        return enable
    }

    fun isMockEnabled(): Boolean = mockCourse.value != null

    private fun buildMockCourse(): UpcomingClass {
        val start = LocalDateTime.now().plusMinutes(MOCK_MINUTES)
        return UpcomingClass(
            courseName = "模拟课程（上课提示）",
            location = "教1-302",
            teacher = "测试教师",
            sectionLabel = "模拟节次",
            start = start,
            end = start.plusMinutes(100)
        )
    }

    private fun registerScreenWakeReceiverIfNeeded(app: BetterHrbustApp) {
        if (screenReceiverRegistered) return
        synchronized(this) {
            if (screenReceiverRegistered) return
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(
                    app,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            refreshAsync(app, forceReschedule = false)
                        }
                    },
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                screenReceiverRegistered = true
            }
        }
    }

    private suspend fun loadSnapshotFromStorage(app: BetterHrbustApp): Snapshot {
        val prefs = app.preferencesManager.preferencesFlow.first()
        val (cells, overrides) = if (!prefs.classIslandReminder || prefs.studentId.isBlank()) {
            emptyList<TimetableCell>() to TimetableOverridesPayload()
        } else {
            decode(app.database.timetableDao().getTimetable(prefs.studentId).firstOrNull()) to
                decodeOverrides(app.database.timetableOverrideDao().getOverrides(prefs.studentId).firstOrNull())
        }
        return Snapshot(prefs = prefs, cells = cells, overrides = overrides, mock = mockCourse.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun reminderSource(app: BetterHrbustApp): Flow<Snapshot> =
        app.preferencesManager.preferencesFlow
            .distinctUntilChanged { old, new ->
                old.classIslandReminder == new.classIslandReminder &&
                    old.studentId == new.studentId &&
                    old.currentWeek == new.currentWeek &&
                    old.currentWeekReferenceDate == new.currentWeekReferenceDate &&
                    old.semesterTeachingWeeks == new.semesterTeachingWeeks
            }
            .flatMapLatest { prefs ->
                if (!prefs.classIslandReminder || prefs.studentId.isBlank()) {
                    flowOf(Snapshot(prefs, emptyList()))
                } else {
                    combine(
                        app.database.timetableDao().getTimetable(prefs.studentId),
                        app.database.timetableOverrideDao().getOverrides(prefs.studentId)
                    ) { entity, overrideEntity ->
                        Snapshot(prefs, decode(entity), decodeOverrides(overrideEntity))
                    }
                }
            }

    private fun decode(entity: TimetableEntity?): List<TimetableCell> {
        val raw = entity?.json ?: return emptyList()
        return runCatching {
            AcademicParsers.normalizeTimetableCells(json.decodeFromString<TimetableResult>(raw)).cells
        }.getOrDefault(emptyList())
    }

    private fun decodeOverrides(entity: TimetableOverrideEntity?): TimetableOverridesPayload {
        val raw = entity?.json ?: return TimetableOverridesPayload()
        return runCatching {
            json.decodeFromString<TimetableOverridesPayload>(raw)
        }.getOrDefault(TimetableOverridesPayload())
    }

    private suspend fun evaluateLocked(
        app: BetterHrbustApp,
        snapshot: Snapshot,
        forceReschedule: Boolean
    ) {
        val now = LocalDateTime.now()

        // 1. 调试模拟课程优先于真实课表
        val mock = snapshot.mock
        if (mock != null) {
            if (mock.start.isAfter(now)) {
                val nextBoundary = UpcomingClassResolver.nextActiveBoundary(mock, now)
                ClassReminderScheduler.schedule(app, nextBoundary, force = forceReschedule)
                publishActiveCourse(app, mock, now, logTier = false)
                return
            } else {
                // 模拟课已到点：清空模拟态并继续交由真实课表判定下一次唤醒
                mockCourse.value = null
                islandLog(app, "模拟课程已开始，恢复真实课表调度")
            }
        }

        // 2. 真实课表与开关判定
        val prefs = snapshot.prefs
        if (!prefs.classIslandReminder || prefs.studentId.isBlank()) {
            ClassReminderScheduler.cancel(app)
            hide(app, "未开启提醒或未登录")
            return
        }

        val date = now.toLocalDate()
        val time = now.toLocalTime()
        val week = prefs.teachingWeekOn(date)
        val upcoming = UpcomingClassResolver.resolve(snapshot.cells, date, week, time, overrides = snapshot.overrides)
        val nextWakeup = UpcomingClassResolver.nextReminderWakeup(
            cells = snapshot.cells,
            now = now,
            weekForDate = prefs::teachingWeekOn,
            overrides = snapshot.overrides
        )
        ClassReminderScheduler.schedule(app, nextWakeup, force = forceReschedule)

        if (upcoming == null) {
            hide(app, "当前无即将开始的课程")
            return
        }

        publishActiveCourse(app, upcoming, now, logTier = true)
    }

    private suspend fun publishActiveCourse(
        app: BetterHrbustApp,
        upcoming: UpcomingClass,
        now: LocalDateTime,
        logTier: Boolean
    ) {
        val minutes = upcoming.minutesUntil(now).coerceAtLeast(1)
        val courseKey = "${upcoming.courseName}|${upcoming.start}|${upcoming.location}|${upcoming.teacher}|${upcoming.sectionLabel}"
        val isFirstForCourse = lastCourseKey != courseKey

        if (!isFirstForCourse && lastMinutes == minutes) {
            lastNotification?.let { ClassReminderService.startOrUpdate(app, upcoming, it) }
            return
        }

        val tier = if (isFirstForCourse && logTier && isDebuggable(app)) {
            HyperIslandSupport.presentationTier(app)
        } else {
            null
        }

        val notification = ClassIslandNotifier.post(
            context = app,
            upcoming = upcoming,
            now = now,
            firstFloat = isFirstForCourse
        )
        if (notification != null) {
            lastCourseKey = courseKey
            lastMinutes = minutes
            lastNotification = notification
            staleClearedOnBoot = true
            hideLogged = false
            ClassReminderService.startOrUpdate(app, upcoming, notification)
            if (tier != null) {
                islandLog(app, "上课提示已下发，呈现方式：${tier.label}")
            }
        } else {
            // 未授予通知权限等场景：确保前台服务不空跑
            ClassReminderService.stop(app)
            lastCourseKey = null
            lastMinutes = null
            lastNotification = null
        }
    }

    private fun hide(context: Context, reason: String) {
        ClassReminderService.stop(context)
        if (lastCourseKey == null && staleClearedOnBoot) {
            if (!hideLogged) {
                islandLog(context, "无提醒：$reason")
                hideLogged = true
            }
            return
        }
        ClassIslandNotifier.cancel(context)
        val hadActive = lastCourseKey != null
        lastCourseKey = null
        lastMinutes = null
        lastNotification = null
        staleClearedOnBoot = true
        hideLogged = !hadActive
        if (hadActive) {
            islandLog(context, "已收起上课提醒：$reason")
        } else {
            islandLog(context, "无提醒：$reason")
        }
    }

    private data class Snapshot(
        val prefs: AppPreferences,
        val cells: List<TimetableCell>,
        val overrides: TimetableOverridesPayload = TimetableOverridesPayload(),
        /** 调试用模拟课（非空时代替真实课表） */
        val mock: UpcomingClass? = null
    )
}
