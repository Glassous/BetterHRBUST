package com.glassous.betterhrbust.core.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提示的系统闹钟调度器。
 *
 * - 提醒窗口外：仅注册下一节课前 20 分钟的单次唤醒，平时零轮询、零常驻开销；
 * - 提醒窗口内：注册下一整分跳变点（或上课到点收起时刻）的精确唤醒，作为窗口前台服务的双保险兜底。
 */
object ClassReminderScheduler {
    const val ACTION_REMINDER_TICK = "com.glassous.betterhrbust.action.CLASS_REMINDER_TICK"
    private const val REQUEST_CODE = 20261115

    @Volatile
    private var scheduledTargetMillis: Long = 0L

    @Volatile
    private var scheduledExact: Boolean = false

    fun schedule(context: Context, target: LocalDateTime?, force: Boolean = false) {
        if (target == null) {
            cancel(context)
            return
        }
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        val nowMillis = System.currentTimeMillis()
        val targetMillis = target.atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
            .coerceAtLeast(nowMillis + 1_000L)

        if (!force && targetMillis == scheduledTargetMillis && exactAllowed == scheduledExact) {
            return
        }

        val pending = pendingIntent(appContext)
        try {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pending)
            }
        } catch (_: SecurityException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pending)
            }
        }

        scheduledTargetMillis = targetMillis
        scheduledExact = exactAllowed
        islandLog(appContext, "已注册下次上课提醒闹钟：$target（精确=$exactAllowed）")
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        scheduledTargetMillis = 0L
        scheduledExact = false
        runCatching {
            appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(appContext))
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            action = ACTION_REMINDER_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
