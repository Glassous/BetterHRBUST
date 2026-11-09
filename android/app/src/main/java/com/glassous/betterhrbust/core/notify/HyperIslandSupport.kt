package com.glassous.betterhrbust.core.notify

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小米澎湃 OS「超级岛 / 焦点通知」能力检测。
 *
 * 三个维度互相独立：
 * 1. [isIslandDevice]：设备与系统是否声明支持超级岛能力；
 * 2. [protocolVersion]：焦点通知协议版本，OS3 起为 3，才存在「超级岛」形态；
 * 3. [canShowFocus]：当前应用是否被允许展示焦点通知（权限 / 白名单态，会随用户在系统设置中
 *    开关或平台审核结果变化，因此只做短时缓存）。
 *
 * 前两者合成 [supportsIsland]，用于设置项可见性与通知发送前置判断（设备级能力，进程内缓存）；
 * 后者只决定「此刻是否真的发通知」，不参与界面可见性。
 *
 * 所有系统查询一律按「异常即不支持」处理，绝不影响应用启动与课表主流程。
 */
object HyperIslandSupport {
    private const val MIN_ISLAND_PROTOCOL = 3
    private const val CAN_SHOW_FOCUS_CACHE_MS = 60_000L
    private const val FOCUS_PROVIDER = "content://miui.statusbar.notification.public"
    private const val KEY_FOCUS_PROTOCOL = "notification_focus_protocol"
    private const val PROP_SUPPORT_ISLAND = "persist.sys.feature.island"

    /** 设备级能力缓存：0 = 未求值，1 = 支持，2 = 不支持 */
    @Volatile
    private var deviceSupport = 0

    @Volatile
    private var canShowFocusCached = false

    @Volatile
    private var canShowFocusAt = 0L

    /** 设备级能力：澎湃 OS 3（协议 >= 3）且系统声明支持超级岛。轻量同步调用，结果进程内缓存。 */
    fun supportsIsland(context: Context): Boolean {
        val cached = deviceSupport
        if (cached != 0) return cached == 1
        val protocol = protocolVersion(context)
        val supported = isIslandDevice() && protocol >= MIN_ISLAND_PROTOCOL
        deviceSupport = if (supported) 1 else 2
        islandLog(context, "设备支持超级岛=$supported，协议版本=$protocol")
        return supported
    }

    /** 反射读取系统属性；非小米 ROM / 属性缺失 / 反射失败一律 false。 */
    fun isIslandDevice(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
        getBoolean.invoke(null, PROP_SUPPORT_ISLAND, false) as? Boolean ?: false
    }.getOrDefault(false)

    /** 1 = OS1 焦点通知，2 = OS2，3 = OS3 超级岛模板；读不到按 0 处理。 */
    fun protocolVersion(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, KEY_FOCUS_PROTOCOL, 0)
    }.getOrDefault(0)

    /**
     * 当前应用是否被系统允许展示焦点通知（耗时 IPC，须在 IO 线程调用）。
     * 结果缓存 60 秒，避免 30 秒轮询每次都跨进程查询。
     */
    suspend fun canShowFocus(context: Context): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - canShowFocusAt < CAN_SHOW_FOCUS_CACHE_MS) return@withContext canShowFocusCached
        val result = runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver
                .call(Uri.parse(FOCUS_PROVIDER), "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false) ?: false
        }.getOrDefault(false)
        canShowFocusCached = result
        canShowFocusAt = now
        islandLog(context, "焦点通知权限可用=$result")
        result
    }

    /**
     * Android 16+ 实况通知（Live Updates）：系统是否允许本应用提升为实时通知。
     *
     * 澎湃 OS 3 起超级岛会呈现这类标准通知，且不走「焦点通知」白名单，
     * 因此它是焦点通知参数之外的第二条上岛通道。
     */
    fun canPostPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() ?: false
        }.getOrDefault(false)
    }

    /** 一次性输出当前适配状态，供设置页「适配自检」与问题反馈使用。 */
    suspend fun diagnostics(context: Context): String = buildString {
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("系统声明支持超级岛：${isIslandDevice()}")
        appendLine("焦点通知协议版本：${protocolVersion(context)}")
        appendLine("焦点通知权限（开关 / 白名单）：${canShowFocus(context)}")
        appendLine("系统允许实况通知：${canPostPromoted(context)}")
        appendLine("通知总开关：${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
        appendLine("提醒通道重要性：${channelImportance(context)}")
        appendLine()
        appendLine(
            if (canShowFocus(context) || canPostPromoted(context)) {
                "结论：系统已放行，通知会按实况 / 焦点方式下发"
            } else {
                "结论：系统未放行。请到「设置 → 通知与控制中心 → 焦点通知」\n" +
                    "为本应用开启；若列表中找不到本应用，说明系统尚未\n" +
                    "对第三方应用开放该能力。"
            }
        )
        appendLine()
        appendLine("最近一次下发的岛参数：")
        append(ClassIslandNotifier.lastParamJson ?: "（尚未下发）")
    }

    private fun channelImportance(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return "未知"
        val channel = runCatching { manager.getNotificationChannel(ClassIslandNotifier.CHANNEL_ID) }
            .getOrNull() ?: return "未创建"
        return when (channel.importance) {
            NotificationManager.IMPORTANCE_NONE -> "已关闭"
            NotificationManager.IMPORTANCE_MIN -> "最低"
            NotificationManager.IMPORTANCE_LOW -> "低"
            NotificationManager.IMPORTANCE_DEFAULT -> "默认"
            NotificationManager.IMPORTANCE_HIGH -> "高"
            else -> channel.importance.toString()
        }
    }

    /** 清空缓存（用户刚在系统设置里改动过权限 / 切换账号时调用）。 */
    fun invalidate() {
        deviceSupport = 0
        canShowFocusCached = false
        canShowFocusAt = 0L
    }
}

/** 是否 debuggable 构建：决定模拟数据入口与调试日志是否生效。 */
internal fun isDebuggable(context: Context): Boolean =
    (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** 调试日志：仅 debuggable 构建输出，release 保持安静，也不打印账号等信息。 */
internal fun islandLog(context: Context, message: String) {
    if (isDebuggable(context)) Log.d("HyperIsland", message)
}
