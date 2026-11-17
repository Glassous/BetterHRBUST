package com.glassous.betterhrbust.core.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * 接收系统精确闹钟与时间/开机广播，唤醒或解冻进程执行上课提示重算。
 *
 * 在 `goAsync()` 窗口内配合短时 `PARTIAL_WAKE_LOCK`（最长 3 秒），防止澎湃 OS Millet
 * 在异步读取本地 Room/DataStore 缓存与刷新通知前将进程二次冻结。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in AcceptedActions) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val wakeLock = runCatching {
            appContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterHRBUST:ClassReminder")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(3_000L)
                }
        }.getOrNull()

        ClassReminderTicker.refreshAsync(appContext, forceReschedule = true) {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            runCatching { pendingResult.finish() }
        }
    }

    private companion object {
        val AcceptedActions = setOf(
            ClassReminderScheduler.ACTION_REMINDER_TICK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
