package com.glassous.betterhrbust.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小部件统一刷新入口。
 *
 * 触发源：
 * 1. 应用内数据变化（同步结束、会话清理）——由界面层调用 [refreshAsync]；
 * 2. 系统广播（跨天 / 改时间 / 时区 / 开机）——由 [TimetableWidgetRefreshReceiver] 调用；
 * 3. 系统周期刷新（appwidget-provider 的 `updatePeriodMillis`，12 小时）兜底。
 *    12 小时是 vivo 原子组件规范要求的最小间隔；实际时效由数据变化、跨天/改时间/开机广播
 *    与 [WidgetRefreshScheduler] 的课程边界闹钟保证。
 *
 * 刷新只读取本地缓存，不联网；三个小部件独立刷新，任一失败不影响其余（互不干扰）。
 */
object TimetableWidgetUpdater {

    /**
     * 小部件刷新用的进程级作用域。
     *
     * 广播接收需要在 `goAsync()` 的受限窗口内完成刷新（不阻塞系统广播分发），
     * 因此刷新动作必须能脱离调用方的生命周期独立运行。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 异步刷新全部小部件；失败静默（小部件刷新不应影响调用方主流程）。 */
    fun refreshAsync(onFinished: () -> Unit = {}) {
        scope.launch {
            try {
                refreshAllWidgets(BetterHrbustApp.instance)
            } finally {
                onFinished()
            }
        }
    }

    /** 刷新三个 Glance 小部件并维护课程边界闹钟（传统双日课表见 [refreshAllWidgets]）。 */
    suspend fun refreshAll(context: Context = BetterHrbustApp.instance) {
        val app = context.applicationContext
        withContext(Dispatchers.Default) {
            runCatching { TimetableWeekWidget().updateAll(app) }
            runCatching { TimetableDayNarrowWidget().updateAll(app) }
            runCatching { TimetableDayWideWidget().updateAll(app) }
            // 12 小时系统周期只是兜底；课程结束与午夜仍由闹钟准点刷新。
            // 排程必须独立于「双日课表」是否存在：只添加 Glance 组件的用户同样需要。
            val snapshot = runCatching { WidgetDataSource.load(app) }.getOrNull()
            if (snapshot != null && hasAnyWidget(app)) {
                WidgetRefreshScheduler.schedule(app, snapshot)
            } else {
                WidgetRefreshScheduler.cancel(app)
            }
        }
    }

    /** 桌面是否还挂着任一课表组件（双日课表或三个 Glance 组件）。 */
    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return WidgetProviders.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /** 课程边界 / 主动刷新：传统组件与 Glance 组件一起更新。 */
    suspend fun refreshAllWidgets(context: Context = BetterHrbustApp.instance) {
        TimetableWidgetProvider.refreshAll(context)
        refreshAll(context)
    }

    private val WidgetProviders = listOf(
        TimetableWidgetProvider::class.java,
        TimetableWeekWidgetReceiver::class.java,
        TimetableDayNarrowWidgetReceiver::class.java,
        TimetableDayWideWidgetReceiver::class.java
    )
}

/**
 * 点击小部件 → 打开 App 并直达「课表」页。
 *
 * 使用启动器入口意图（MAIN / LAUNCHER）+ `singleTop` + `CLEAR_TOP`：
 * 应用已在后台时复用同一任务实例并走 `onNewIntent`，不重建界面。
 */
internal fun openTimetableAction(context: Context): Action {
    val intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TIMETABLE)
    }
    return actionStartActivity(intent)
}
