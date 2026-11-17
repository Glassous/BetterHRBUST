package com.glassous.betterhrbust.core.notify

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * 仅在「课前 20 分钟提醒窗口内」存活的轻量前台服务（`specialUse`）。
 *
 * 设计目的：
 * 1. **零额外通知**：直接复用 [ClassIslandNotifier.NOTIFICATION_ID] 对应的超级岛常驻通知；
 * 2. **防后台冻结**：窗口期保持前台服务进程态，避免被澎湃 OS Millet 冻结后协程停摆导致倒计时卡死；
 * 3. **到点即退**：课程一开始或用户关闭开关即刻 `stopSelf()`，平时全天不驻留后台。
 */
class ClassReminderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var boundaryJob: Job? = null
    private var receiverRegistered = false
    private var enteredForeground = false

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ClassReminderTicker.refreshAsync(applicationContext, forceReschedule = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                systemEventReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPending = false
        val notification = activeNotification ?: buildFallbackNotification()
        val promoted = promoteForeground(notification)
        enteredForeground = promoted
        if (!promoted || !keepAlive || activeCourse == null) {
            shutdownOnMain()
            return START_NOT_STICKY
        }
        restartBoundaryLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        boundaryJob?.cancel()
        enteredForeground = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(systemEventReceiver) }
            receiverRegistered = false
        }
        if (runningInstance === this) {
            runningInstance = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onActiveUpdatedOnMain(notification: Notification) {
        if (!enteredForeground) return
        promoteForeground(notification)
        restartBoundaryLoop()
    }

    private fun promoteForeground(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ClassIslandNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ClassIslandNotifier.NOTIFICATION_ID, notification)
        }
        true
    }.onFailure {
        islandLog(this, "提升窗口前台服务失败：${it.message}")
    }.getOrDefault(false)

    private fun restartBoundaryLoop() {
        boundaryJob?.cancel()
        boundaryJob = serviceScope.launch {
            while (isActive && keepAlive) {
                try {
                    val course = activeCourse ?: break
                    val now = LocalDateTime.now()
                    if (!course.start.isAfter(now)) {
                        ClassReminderTicker.evaluateOnce(applicationContext, forceReschedule = true)
                        break
                    }
                    val nextBoundary = UpcomingClassResolver.nextActiveBoundary(course, now)
                    val waitMs = (Duration.between(now, nextBoundary).toMillis() + 80L)
                        .coerceIn(250L, 61_000L)
                    delay(waitMs)
                    ClassReminderTicker.evaluateOnce(applicationContext, forceReschedule = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    islandLog(this@ClassReminderService, "窗口倒计时循环异常：${t.message}")
                    delay(5_000L)
                }
            }
        }
    }

    private fun shutdownOnMain() {
        boundaryJob?.cancel()
        if (enteredForeground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            enteredForeground = false
        }
        stopSelf()
    }

    private fun buildFallbackNotification(): Notification {
        ClassIslandNotifier.ensureChannel(this)
        return Notification.Builder(this, ClassIslandNotifier.CHANNEL_ID)
            .setSmallIcon(CountdownIconRenderer.create(1))
            .setContentTitle("上课提示")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeCourse: UpcomingClass? = null

        @Volatile
        private var activeNotification: Notification? = null

        @Volatile
        private var keepAlive: Boolean = false

        @Volatile
        private var startPending: Boolean = false

        @Volatile
        private var runningInstance: ClassReminderService? = null

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        }

        fun startOrUpdate(context: Context, upcoming: UpcomingClass, notification: Notification) {
            val appContext = context.applicationContext
            activeCourse = upcoming
            activeNotification = notification
            keepAlive = true
            runOnMain {
                if (!keepAlive) return@runOnMain
                val instance = runningInstance
                if (instance != null && instance.enteredForeground) {
                    instance.onActiveUpdatedOnMain(notification)
                    return@runOnMain
                }
                if (startPending) return@runOnMain
                startPending = true
                runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, ClassReminderService::class.java)
                    )
                }.onFailure {
                    startPending = false
                    // 后台受限时静默回退：通知本身已由 NotificationManager 下发，后续整分由精确闹钟与亮屏广播推进
                    islandLog(appContext, "窗口级前台服务后台启动受限，由精确闹钟兜底：${it.message}")
                }
            }
        }

        fun stop(context: Context) {
            keepAlive = false
            activeCourse = null
            runOnMain {
                val instance = runningInstance
                // 若正在等待 onStartCommand 回调，不在进入前台前直接 stopSelf，交由 onStartCommand 首行完成合规闭环
                if (instance != null && instance.enteredForeground && !startPending) {
                    instance.shutdownOnMain()
                }
            }
        }
    }
}
