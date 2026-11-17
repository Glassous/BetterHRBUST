package com.glassous.betterhrbust.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.glassous.betterhrbust.MainActivity
import com.glassous.betterhrbust.R
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提示通知（常驻 + 倒计时），形态由系统按设备能力逐级增强：
 * 小米澎湃 OS 3 升级为超级岛，Android 16 呈现为实况通知，其余设备即普通通知。
 *
 * 胶囊左右布局：
 * - 左侧（`smallIcon` 及 `miui.focus.param` A 区）：单行中文简写倒计时，如「15分」；
 * - 右侧（`shortCriticalText` 及 `miui.focus.param` B 区）：去括号后缀的精简教室，如「西-新B406」；
 * - 展开态卡片正文仍展示完整教室（如「西-新B406（直播）」）及课程与教师详情。
 */
object ClassIslandNotifier {
    const val CHANNEL_ID = "course_reminder"
    private const val CHANNEL_NAME = "上课提示"
    private const val CHANNEL_DESC = "课前 20 分钟提醒课程与教室"
    const val NOTIFICATION_ID = 20261109
    private const val CONTENT_INTENT_REQUEST = 20261110
    private const val LARGE_ICON_PX = 192

    @Volatile
    private var cachedAppLargeIcon: Bitmap? = null

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

    /**
     * 构造当前时刻对应的上课提醒通知；未授予通知权限时返回 null。
     * 同时供普通 `notify()` 与窗口级前台服务 `startForeground()` 复用。
     */
    fun buildNotification(
        context: Context,
        upcoming: UpcomingClass,
        now: LocalDateTime,
        firstFloat: Boolean = false
    ): Notification? {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            islandLog(context, "通知权限未授予，保持静默")
            cancel(context)
            return null
        }

        val minutes = upcoming.minutesUntil(now).coerceAtLeast(1)
        val title = "$minutes 分钟后上课"
        // 展开态保留完整教室原文，胶囊右侧使用去除括号备注的精简教室名
        val fullRoom = upcoming.location.ifBlank { "地点待公布" }
        val compactRoom = HyperIslandParams.compactRoomForIsland(fullRoom)
        val detail = listOf(upcoming.courseName, upcoming.teacher)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val expandedBody = listOf(fullRoom, detail).filter { it.isNotBlank() }.joinToString("\n")
        val text = IslandNotificationText(
            title = title,
            content = fullRoom,
            ticker = "${upcoming.courseName} · $title",
            aodTitle = "${upcoming.courseName} $compactRoom".trim(),
            countdownNumber = minutes.toString(),
            countdownUnit = "分",
            room = compactRoom,
            timeoutMinutes = (minutes + 1).toInt(),
            islandTimeoutSeconds = (Duration.between(now, upcoming.start).seconds + 60).coerceAtLeast(60).toInt(),
            firstFloat = firstFloat
        )

        val contentIntent = contentIntent(context)
        val islandParam = HyperIslandParams.build(text)
        val startMillis = upcoming.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 极端情况下进程被强杀后的系统兜底：超时后自动移除，绝不留下僵尸通知
        val timeoutAfter = (Duration.between(now, upcoming.start).toMillis() + 60_000L).coerceAtLeast(60_000L)

        val builder = Notification.Builder(context, CHANNEL_ID)
            // 超级岛折叠态胶囊左侧槽位固定读取 smallIcon：传入透明底纯白单行「X分」动态图标
            .setSmallIcon(CountdownIconRenderer.create(minutes))
            // 展开态卡片与通知面板左侧圆圈使用应用桌面图标（不复用 smallIcon 的倒计时文字）
            .setLargeIcon(appLargeIcon(context))
            .setContentTitle(title)
            .setContentText(fullRoom)
            // 展开通知：完整教室单独一行，课程与教师另起一行；BigTextStyle 同时满足实况通知样式要求
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

        // Android 16+ 实况通知（Live Updates）：澎湃 OS 3 据此在超级岛呈现，右侧胶囊槽位展示精简教室名
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                builder.setRequestPromotedOngoing(true)
                builder.setShortCriticalText(compactRoom)
            }.onFailure { islandLog(context, "申请实况通知失败：${it.message}") }
        }

        return builder.build().apply {
            extras.putString(HyperIslandParams.PARAM_KEY, islandParam)
        }
    }

    /**
     * 下发或原地更新上课提醒通知；成功时返回实际下发的 [Notification]（供前台服务绑定同一通知 ID），
     * 未授予权限或系统拒绝时返回 null。
     */
    fun post(
        context: Context,
        upcoming: UpcomingClass,
        now: LocalDateTime,
        firstFloat: Boolean = false
    ): Notification? {
        val notification = buildNotification(context, upcoming, now, firstFloat) ?: return null
        val minutes = upcoming.minutesUntil(now).coerceAtLeast(1)
        val manager = NotificationManagerCompat.from(context)
        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            notification
        }
            .onFailure { islandLog(context, "发送岛通知失败：${it.message}") }
            .onSuccess { islandLog(context, "已下发岛通知：${upcoming.courseName}（${minutes}分，教室=${upcoming.location}）") }
            .getOrNull()
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 将自适应应用图标渲染为平铺 ARGB_8888 位图，供展开卡片左侧圆圈稳定展示。 */
    private fun appLargeIcon(context: Context): Bitmap? {
        cachedAppLargeIcon?.let { return it }
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
            ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?: return null
        val bitmap = Bitmap.createBitmap(LARGE_ICON_PX, LARGE_ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, LARGE_ICON_PX, LARGE_ICON_PX)
        drawable.draw(canvas)
        cachedAppLargeIcon = bitmap
        return bitmap
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
