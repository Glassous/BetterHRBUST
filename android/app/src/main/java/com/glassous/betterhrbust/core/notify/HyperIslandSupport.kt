package com.glassous.betterhrbust.core.notify

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「上课提示」的呈现能力检测：通知始终是标准通知，系统按设备能力决定呈现形态。
 *
 * 三个维度互相独立：
 * 1. [isIslandDevice]：设备与系统是否声明支持超级岛能力；
 * 2. [protocolVersion]：焦点通知协议版本，OS3 起为 3，才存在「超级岛」形态；
 * 3. [canShowFocus]：当前应用是否被允许展示焦点通知（权限 / 白名单态，会随用户在系统设置中
 *    开关或平台审核结果变化，因此只做短时缓存）。
 *
 * 前两者合成 [supportsIsland]，只描述「这台设备能否上岛」，不再是功能开关：
 * 无论判定结果如何，通知都会真实下发，由 [presentationTier] 决定最终形态。
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
     * 这是 AOSP 标准能力，原生 Android 16 与澎湃 OS 3 均适用；它不走「焦点通知」白名单，
     * 因此是超级岛之外的第二档呈现通道。
     */
    fun canPostPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() ?: false
        }.getOrDefault(false)
    }

    /**
     * 当前设备最终会把这条提醒呈现成什么：超级岛 → 实况通知 → 普通通知，逐级降级。
     *
     * 无论返回哪一档，调用方都应照常下发通知；档位只用于调试日志。
     * 先判 [supportsIsland] 再查焦点通知权限：非小米设备上该 provider 必然查不到，
     * 提前短路可省下每轮一次的跨进程调用与异常构造开销。
     */
    suspend fun presentationTier(context: Context): PresentationTier = when {
        supportsIsland(context) && canShowFocus(context) -> PresentationTier.SUPER_ISLAND
        canPostPromoted(context) -> PresentationTier.LIVE_UPDATE
        else -> PresentationTier.PLAIN
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

/**
 * 上课提示的呈现档位，由高到低逐级降级。
 *
 * 同一条通知始终真实下发，系统按设备与系统能力选择能用的最高档；
 * 档位只用于调试日志，不参与是否发送的判断。
 */
enum class PresentationTier(val label: String) {
    /** 小米澎湃 OS 3 + 焦点通知已放行：系统升级为超级岛 */
    SUPER_ISLAND("超级岛"),

    /** Android 16 起系统允许提升为实况通知（Live Updates） */
    LIVE_UPDATE("实况通知"),

    /** 其余设备：普通通知栏通知，行为与常规提醒一致 */
    PLAIN("普通通知")
}
