package com.glassous.betterhrbust.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统广播触发的小部件刷新。
 *
 * 覆盖三类本地缓存「过期」的场景：
 * - `DATE_CHANGED`：跨天后日视图需要展示新的「今天」；
 * - `TIME_SET` / `TIMEZONE_CHANGED`：用户改时间或换时区后「今天」可能变化；
 * - `BOOT_COMPLETED`：开机后立即刷新，避免继续显示开机前的课表。
 *
 * 均为系统受保护广播，声明为 `exported=false` 亦可正常接收。
 * 周期性兜底刷新由 `appwidget-provider` 的 `updatePeriodMillis` 提供。
 */
class TimetableWidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 课程结束 / 午夜闹钟：传统组件与 Glance 组件都要刷新，并排下一次闹钟
        if (intent.action == ACTION_BOUNDARY) {
            val pending = goAsync()
            scope.launch {
                try { TimetableWidgetUpdater.refreshAllWidgets(context) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                finally { pending.finish() }
            }
            return
        }
        if (intent.action !in RefreshActions) return
        // 保持在 goAsync 窗口内完成刷新，避免进程被提前判定为空闲而回收
        val pending = goAsync()
        TimetableWidgetUpdater.refreshAsync { pending.finish() }
    }

    companion object {
        /** 课程结束 / 午夜闹钟，由 [WidgetRefreshScheduler] 触发。 */
        const val ACTION_BOUNDARY = "com.glassous.betterhrbust.WIDGET_BOUNDARY"

        private val RefreshActions = setOf(
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
