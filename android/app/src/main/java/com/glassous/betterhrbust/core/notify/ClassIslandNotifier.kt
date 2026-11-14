package com.glassous.betterhrbust.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.glassous.betterhrbust.MainActivity
import com.glassous.betterhrbust.R
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提示通知（常驻 + 倒计时），形态由系统按设备能力逐级增强：
 * 小米澎湃 OS 3 升级为超级岛，Android 16 呈现为实况通知，其余设备即普通通知。
 *
 * 通知本身始终是标准通知：中小米私有参数（`miui.focus.*`）对非小米系统是无效 extras，
 * 会被直接忽略，不会被当作「不支持」而拦下，因此这里不需要任何「能不能发」的判断。
 */
object ClassIslandNotifier {
    const val CHANNEL_ID = "course_reminder"
    private const val CHANNEL_NAME = "上课提示"
    private const val CHANNEL_DESC = "课前 20 分钟提醒课程与教室"
    private const val NOTIFICATION_ID = 20261109
    private const val CONTENT_INTENT_REQUEST = 20261110

    /** 通道幂等创建：重复调用无副作用；已存在时仅在名称 / 描述变化时同步一次。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // 只同步文案：用户自行调整过的重要性、声音、振动等设置保持不变
            if (existing.name != CHANNEL_NAME || existing.description != CHANNEL_DESC) {
                runCatching {
                    existing.name = CHANNEL_NAME
                    existing.description = CHANNEL_DESC
                    manager.createNotificationChannel(existing)
                }.onFailure { islandLog(context, "更新提醒通道文案失败：${it.message}") }
            }
            return
        }
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** @return 是否真正下发了通知；未授予通知权限时返回 false（调用方据此决定是否重试） */
    fun post(context: Context, upcoming: UpcomingClass, now: LocalDateTime): Boolean {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        // 通知权限被关闭时直接静默：不弹任何替代提示
        if (!manager.areNotificationsEnabled()) {
            islandLog(context, "通知权限未授予，保持静默")
            cancel(context)
            return false
        }

        val minutes = upcoming.minutesUntil(now).coerceAtLeast(1)
        val title = "$minutes 分钟后上课"
        // 教室单独成行；课程与教师合并成下一行
        val room = upcoming.location.ifBlank { "地点待公布" }
        val detail = listOf(upcoming.courseName, upcoming.teacher)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        // 通知展开后的正文：第二行教室、第三行课程与教师
        val expandedBody = listOf(room, detail).filter { it.isNotBlank() }.joinToString("\n")
        val text = IslandNotificationText(
            title = title,
            content = room,
            ticker = "${upcoming.courseName} · $title",
            aodTitle = "${upcoming.courseName} $room".trim(),
            countdownNumber = minutes.toString(),
            countdownUnit = "分钟",
            room = room,
            timeoutMinutes = (minutes + 1).toInt(),
            islandTimeoutSeconds = (Duration.between(now, upcoming.start).seconds + 60).toInt()
        )

        val contentIntent = contentIntent(context)
        val islandParam = HyperIslandParams.build(text)
        val startMillis = upcoming.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 进程被回收后的兜底：系统在超时后自动移除，保证「上课后收起」不会留下僵尸通知
        val timeoutAfter = (Duration.between(now, upcoming.start).toMillis() + 60_000L).coerceAtLeast(60_000L)

        val builder = Notification.Builder(context, CHANNEL_ID)
            // 通知头部的图标用应用桌面图标：之前用单色时钟图标，用户会误以为「应用图标没显示」
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(room)
            // 展开通知：教室单独一行，课程与教师另起一行；BigTextStyle 同时满足实况通知的样式要求
            .setStyle(Notification.BigTextStyle().bigText(expandedBody))
            .setOngoing(true)
            // 通道已静音（无铃声、无振动）；onlyAlertOnce 保证按分钟刷新时不重复提醒
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(startMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(timeoutAfter)
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addExtras(HyperIslandParams.extras(context, islandParam, contentIntent))

        // Android 16+ 实况通知（Live Updates）：显式申请提升为实时通知。
        // 这是 AOSP 标准能力，原生 Android 16 与澎湃 OS 3 都会用它呈现；
        // 系统不放行时该申请被忽略，通知按普通通知下发，无需额外分支。
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                builder.setRequestPromotedOngoing(true)
                builder.setShortCriticalText("$minutes 分钟")
            }.onFailure { islandLog(context, "申请实况通知失败：${it.message}") }
        }

        val notification = builder.build()
        // 官方示例的写法：岛参数直接写到 notification.extras 上（与上面的 addExtras 内容一致）
        notification.extras.putString(HyperIslandParams.PARAM_KEY, islandParam)

        return runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { islandLog(context, "发送岛通知失败：${it.message}") }
            .onSuccess { islandLog(context, "已下发岛通知：${upcoming.courseName}（$minutes 分钟后）") }
            .isSuccess
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 点击通知回到课表页（复用小部件已有的 Tab 跳转约定）。 */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TIMETABLE)
        }
        return PendingIntent.getActivity(
            context,
            CONTENT_INTENT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
