package com.glassous.betterhrbust.core.notify

import android.content.Context
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.database.TimetableEntity
import com.glassous.betterhrbust.core.datastore.AppPreferences
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.parser.AcademicParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

/**
 * 「上课提示」的进程内调度器：所有设备都启动，不再按机型短路。
 *
 * 只在应用进程存活时工作（前台或后台），进程被回收后自然停止——这是与产品确认过的范围，
 * 因此不引入 AlarmManager / 前台服务。
 *
 * 数据全部来自本地：DataStore 偏好 + Room 课表缓存，不触发任何联网。
 * 每轮只判断「有没有即将开始的课」，通知形态（超级岛 / 实况通知 / 普通通知）
 * 由 [HyperIslandSupport.presentationTier] 给出，仅供日志使用，不影响是否下发。
 */
object ClassReminderTicker {
    private const val TICK_MS = 30_000L

    /** 模拟课程距现在多久开始（分钟），仅调试用 */
    private const val MOCK_MINUTES = 15L

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var job: Job? = null

    /** 上一次真正下发的「课程 + 显示分钟数」，用于只在文案变化时刷新通知 */
    @Volatile
    private var lastPosted: Pair<UpcomingClass, Long>? = null

    @Volatile
    private var hideLogged = false

    /**
     * 调试用模拟课：非空时取代真实课表参与提醒。
     * 只存在内存里，进程重启即失效；release 构建不会写入该值。
     */
    private val mockCourse = MutableStateFlow<UpcomingClass?>(null)

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext as? BetterHrbustApp ?: return
        // 进程重启后先清掉上一轮遗留的通知（可能已过期）；若仍在窗口内，首个 tick 会重新下发
        lastPosted = null
        ClassIslandNotifier.cancel(app)
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                run(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 任何意外都不影响应用主流程；下一次启动会重新开始
                islandLog(app, "上课提醒调度异常：${t.message}")
            }
        }
        islandLog(context, "上课提醒调度已启动")
    }

    /** 调试：开启 / 关闭「模拟 15 分钟后上课」，返回切换后的状态。 */
    fun toggleMock(context: Context): Boolean {
        val enable = mockCourse.value == null
        if (enable) {
            mockCourse.value = buildMockCourse()
            islandLog(context, "已开启模拟提醒：$MOCK_MINUTES 分钟后上课")
        } else {
            mockCourse.value = null
            lastPosted = null
            ClassIslandNotifier.cancel(context)
            islandLog(context, "已关闭模拟提醒")
        }
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

    private suspend fun run(app: BetterHrbustApp) {
        // 模拟课开关变化时立即重新评估，无需等下一个 30 秒 tick
        val source = combine(reminderSource(app), mockCourse) { snapshot, mock ->
            snapshot.copy(mock = mock)
        }
        combine(source, tickFlow()) { snapshot, _ -> snapshot }
            .collect { snapshot ->
                try {
                    evaluate(app, snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    islandLog(app, "上课提醒计算失败：${t.message}")
                }
            }
    }

    /**
     * 只关心会影响提醒结果的偏好字段：开关、账号、教学周。
     * 其它偏好（主题、导航坞等）变化不再重置订阅与课表解析。
     */
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
                    app.database.timetableDao().getTimetable(prefs.studentId).map { entity ->
                        Snapshot(prefs, decode(entity))
                    }
                }
            }

    private fun tickFlow(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private fun decode(entity: TimetableEntity?): List<TimetableCell> {
        val raw = entity?.json ?: return emptyList()
        return runCatching {
            AcademicParsers.normalizeTimetableCells(json.decodeFromString<TimetableResult>(raw)).cells
        }.getOrDefault(emptyList())
    }

    private suspend fun evaluate(app: BetterHrbustApp, snapshot: Snapshot) {
        // 调试模拟数据优先于真实课表
        val mock = snapshot.mock
        if (mock != null) {
            evaluateMock(app, mock)
            return
        }
        val prefs = snapshot.prefs
        if (!prefs.classIslandReminder || prefs.studentId.isBlank()) {
            hide(app, "未开启提醒或未登录")
            return
        }
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime()
        val week = prefs.teachingWeekOn(date)
        val upcoming = UpcomingClassResolver.resolve(snapshot.cells, date, week, time)
        if (upcoming == null) {
            hide(app, "当前无即将开始的课程")
            return
        }
        val minutes = upcoming.minutesUntil(now)
        // 同一节课的同一分钟数已经下发过：倒计时由系统 chronometer 自走，无需重复 notify
        if (lastPosted == (upcoming to minutes)) return
        // 呈现档位只用于日志，不参与发送判断：能力未放行时同样下发，系统会退化为普通通知。
        // 查询涉及跨进程 IPC，只在「首次出现」且为调试构建时取一次。
        val tier = if (lastPosted == null && isDebuggable(app)) {
            HyperIslandSupport.presentationTier(app)
        } else {
            null
        }
        // 下发失败（如通知权限刚被撤销）时保留 lastPosted 为空，下一 tick 会重试
        if (ClassIslandNotifier.post(app, upcoming, now)) {
            lastPosted = upcoming to minutes
            hideLogged = false
            if (tier != null) islandLog(app, "上课提示已下发，呈现方式：${tier.label}")
        }
    }

    private fun hide(context: Context, reason: String) {
        if (lastPosted == null) {
            if (!hideLogged) {
                islandLog(context, "无提醒：$reason")
                hideLogged = true
            }
            return
        }
        ClassIslandNotifier.cancel(context)
        lastPosted = null
        hideLogged = false
        islandLog(context, "已收起上课提醒：$reason")
    }

    /**
     * 模拟课走简化路径：不依赖真实课表与教学周，到点前一直下发，便于在任意测试设备上
     * 验证文案、倒计时与呈现形态；真实提醒的数据判定逻辑不受影响。
     */
    private fun evaluateMock(app: BetterHrbustApp, mock: UpcomingClass) {
        val now = LocalDateTime.now()
        if (!mock.start.isAfter(now)) {
            // 到点即收起，演示「上课后自动消失」
            hide(app, "模拟课程已开始")
            return
        }
        val minutes = mock.minutesUntil(now)
        if (lastPosted == (mock to minutes)) return
        if (ClassIslandNotifier.post(app, mock, now)) {
            lastPosted = mock to minutes
            hideLogged = false
        }
    }

    private data class Snapshot(
        val prefs: AppPreferences,
        val cells: List<TimetableCell>,
        /** 调试用模拟课（非空时代替真实课表） */
        val mock: UpcomingClass? = null
    )
}
