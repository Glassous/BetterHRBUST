package com.glassous.betterhrbust.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.*
import java.time.*

/** 只为已添加的组件调度：课程结束与午夜刷新，不轮询教务网络。 */
object WidgetRefreshScheduler {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var timer: Job?=null
    private var deadline=0L
    private var precise=false
    fun nextUpdate(date: LocalDate,time: LocalTime,courses: List<WidgetCourse>): LocalDateTime =
        courses.map { LocalTime.parse(it.time.substringAfter("–")) }.filter { it>time }.minOrNull()?.let { date.atTime(it) }
            ?: date.plusDays(1).atStartOfDay()
    private fun pending(context: Context)=PendingIntent.getBroadcast(context,20261010,
        Intent(context,TimetableWidgetRefreshReceiver::class.java).setAction(TimetableWidgetRefreshReceiver.ACTION_BOUNDARY),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context) {
        timer?.cancel();timer=null;deadline=0
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }
    fun schedule(context: Context,snapshot: WidgetSnapshot) {
        val manager=context.getSystemService(AlarmManager::class.java)
        val allowed=Build.VERSION.SDK_INT<31 || manager.canScheduleExactAlarms()
        val target=nextUpdate(snapshot.date,snapshot.time,snapshot.today).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if(target==deadline && allowed==precise && timer?.isActive==true)return
        timer?.cancel();deadline=target;precise=allowed
        val action=pending(context)
        try {
            if(allowed) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,target,action)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,target,action)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,target,action) }
        // 进程存活时准点刷新；系统闹钟用于进程回收后的恢复。
        timer=scope.launch {
            delay((target-System.currentTimeMillis()).coerceAtLeast(1))
            // 先清空自身引用：refreshAllWidgets 会重新排程，不能让它 cancel 掉当前协程
            timer=null;deadline=0L
            TimetableWidgetUpdater.refreshAllWidgets(context.applicationContext)
        }
    }
}
