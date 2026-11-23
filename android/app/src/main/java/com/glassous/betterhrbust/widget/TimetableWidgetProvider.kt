package com.glassous.betterhrbust.widget

import androidx.compose.ui.graphics.toArgb
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.MainActivity
import com.glassous.betterhrbust.R
import com.glassous.betterhrbust.core.model.PersonalInfo
import com.glassous.betterhrbust.core.model.TimetableResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TimetableWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,ACTION_REFRESH,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) update(context)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onDisabled(context: Context) { WidgetRefreshScheduler.cancel(context) }
    private fun update(context: Context) {
        val pending = goAsync()
        scope.launch {
            try { refreshAll(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 下次数据或系统事件继续刷新。 */ }
            finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REFRESH="com.glassous.betterhrbust.WIDGET_REFRESH"
        const val OPEN_TIMETABLE = "open_timetable"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun refreshAll(context: Context) = mutex.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimetableWidgetProvider::class.java))
            if (ids.isEmpty()) { WidgetRefreshScheduler.cancel(context);return@withLock }
            val snapshot=WidgetDataSource.load(context) ?: return@withLock
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                val views = render(context, snapshot.date, snapshot.time, snapshot.week, snapshot.today, snapshot.message, height, snapshot.tomorrow, width, snapshot.tomorrowUnknown, id, snapshot.prefs)
                manager.updateAppWidget(id, views)
                if (android.os.Build.VERSION.SDK_INT < 31) {
                    @Suppress("DEPRECATION")
                    manager.notifyAppWidgetViewDataChanged(id, R.id.widget_courses)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.widget_tomorrow_courses)
                }
            }
            WidgetRefreshScheduler.schedule(context,snapshot)
        }
        /** 双日布局由真实 RemoteViews 渲染；空课表与缺少可靠数据分别提示。 */
        fun render(context: Context, today: LocalDate, now: LocalTime, week: Int?, courses: List<WidgetCourse>, message: String, height: Int,
                   tomorrowCourses: List<WidgetCourse> = emptyList(), width: Int = 360, tomorrowUnknown: Boolean = false, widgetId: Int = 0, owner: com.glassous.betterhrbust.core.datastore.AppPreferences? = null): RemoteViews {
            val nextOnly = height < 140
            val singleDay = width < 260 || nextOnly
            val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            val progress=TimetableWidgetModel.progress(courses,now)
            val remaining=progress.remaining
            val completed=progress.completed
            val weekdays=listOf("周一","周二","周三","周四","周五","周六","周日")
            val views = RemoteViews(context.packageName, if(nextOnly) R.layout.widget_timetable_compact else R.layout.widget_timetable)
            // 每个底板只发送一次非零背景操作，避免宿主合并更新时保留清空操作。
            // 浅深色使用不同资源 ID，主题切换时无需先移除背景。
            val dark = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            for ((viewId, drawableId) in listOf(
                android.R.id.background to if (dark) R.drawable.widget_surface_dark else R.drawable.widget_surface_light,
                R.id.widget_today_panel to if (dark) R.drawable.widget_today_panel_dark else R.drawable.widget_today_panel_light,
                R.id.widget_tomorrow_panel to if (dark) R.drawable.widget_tomorrow_panel_dark else R.drawable.widget_tomorrow_panel_light
            )) views.setInt(viewId, "setBackgroundResource", drawableId)
            views.setViewVisibility(R.id.widget_tomorrow_panel,if(singleDay) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_column_gap,if(singleDay) android.view.View.GONE else android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_heading,if(nextOnly) "下一节课" else if(singleDay) "今日课表" else "双日课表")
            if(nextOnly) {
                views.setViewVisibility(R.id.widget_footer_container,android.view.View.GONE)
                views.setViewVisibility(R.id.widget_today_label,android.view.View.GONE)
                views.setViewVisibility(R.id.widget_footer,android.view.View.GONE)
                views.setViewVisibility(R.id.widget_done,android.view.View.GONE)
            }
            fun textColor(viewId: Int, color: Int) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // 重应用时解析色彩资源，兼容宿主缓存 RemoteViews 后切换主题。
                    views.setColor(viewId, "setTextColor", color)
                } else views.setTextColor(viewId, context.getColor(color))
            }
            for (viewId in listOf(R.id.widget_heading, R.id.widget_date, R.id.widget_footer, R.id.widget_done)) {
                textColor(viewId, R.color.widget_secondary)
            }
            textColor(R.id.widget_today_label, R.color.widget_text)
            textColor(R.id.widget_tomorrow_label, R.color.widget_text)
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            if (height < 240) {
                views.setViewPadding(android.R.id.background, dp(12), dp(if(nextOnly) 6 else 12), dp(12), dp(if(nextOnly) 6 else 12))
                views.setViewPadding(R.id.widget_today_panel, dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6))
                views.setViewPadding(R.id.widget_tomorrow_panel, dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6), dp(if(nextOnly) 4 else 6))
            }
            views.setTextViewText(R.id.widget_today_label,"今天 ${today.format(DateTimeFormatter.ofPattern("MM/dd"))}")
            views.setTextViewText(R.id.widget_tomorrow_label,"明天 ${today.plusDays(1).format(DateTimeFormatter.ofPattern("MM/dd"))}")
            val shownDate=if(nextOnly && remaining.isEmpty() && tomorrowCourses.isNotEmpty() && !tomorrowUnknown) today.plusDays(1) else today
            val shownWeek = com.glassous.betterhrbust.core.util.SchoolCalendar.weekOn(shownDate) ?: week
            views.setTextViewText(R.id.widget_date, "${shownDate.format(DateTimeFormatter.ofPattern("MM/dd"))} ${weekdays[shownDate.dayOfWeek.value-1]}" + if(fontScale<1.5f) (shownWeek?.let { " · ${it}周" } ?: "") else "")
            if(fontScale>=1.5f) views.setTextViewText(R.id.widget_heading,shownWeek?.let { "第${it}周" } ?: "课表")
            val open=Intent(context,MainActivity::class.java).putExtra(OPEN_TIMETABLE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            fun column(viewId: Int, emptyId: Int, titleId: Int, detailId: Int, items: List<WidgetCourse>, day: Int, unknown: Boolean) {
                val rows=if(message.isEmpty() && !unknown) items.map { courseRow(context,it,nextOnly) } else emptyList()
                views.setEmptyView(viewId,emptyId)
                views.setTextViewText(titleId, when {
                    message.isNotEmpty()->message
                    unknown->"教学周待同步"
                    day==0 && completed>0->"今天的课已上完"
                    else->"没有课程"
                })
                views.setTextViewText(detailId,if(message.isNotEmpty() || unknown) "点击打开课表" else "自由安排的一天")
                views.setViewVisibility(detailId,if(height>=260 && fontScale<1.6f) android.view.View.VISIBLE else android.view.View.GONE)
                if(android.os.Build.VERSION.SDK_INT>=31) {
                    val builder=RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(false)
                    rows.forEachIndexed { index,row->builder.addItem(index.toLong(),row) }
                    views.setRemoteAdapter(viewId,builder.build())
                } else {
                    val adapter=Intent(context,WidgetListService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                        putExtra("nextOnly",nextOnly);putExtra("column",day);putExtra("owner",owner?.username);putExtra("epoch",owner?.lastLoginAt ?: -1)
                        data=android.net.Uri.parse("betterhrbust-widget://$widgetId/$day/$nextOnly/${owner?.lastLoginAt ?: -1}")
                    }
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(viewId,adapter)
                }
                val pending=PendingIntent.getActivity(context,day+1,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                views.setPendingIntentTemplate(viewId,pending)
            }
            column(R.id.widget_courses,R.id.widget_today_empty,R.id.widget_today_empty_title,R.id.widget_today_empty_detail,if(nextOnly) (remaining.ifEmpty { if(tomorrowUnknown) emptyList() else tomorrowCourses }).take(1) else remaining,if(nextOnly) 2 else 0,false)
            column(R.id.widget_tomorrow_courses,R.id.widget_tomorrow_empty,R.id.widget_tomorrow_empty_title,R.id.widget_tomorrow_empty_detail,tomorrowCourses,1,tomorrowUnknown)
            views.setTextViewText(R.id.widget_footer,now.format(DateTimeFormatter.ofPattern("HH:mm")))
            views.setTextViewText(R.id.widget_done,if(message.isEmpty()) "今天已上 ${completed}/${courses.size} 节" else "今天已上 —/— 节")
            views.setContentDescription(android.R.id.background,"${today} ${weekdays[today.dayOfWeek.value-1]}，今天已上${completed}/${courses.size}节。$message")
            val intent = Intent(context, MainActivity::class.java).putExtra(OPEN_TIMETABLE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return views
        }
        fun courseRow(context: Context,course: WidgetCourse,compact: Boolean = false): RemoteViews {
            val row=RemoteViews(context.packageName,if(compact) R.layout.widget_course_compact else R.layout.widget_course)
            row.setTextViewText(R.id.widget_course_name,course.name)
            row.setTextViewText(R.id.widget_course_location,course.location)
            row.setTextViewText(R.id.widget_course_detail, if (course.tag.isNotEmpty()) "${course.time} · ${course.tag}" else course.time)
            val light=com.glassous.betterhrbust.core.ui.theme.CourseColorPalette.cardColors(course.colorKey,false)
            val dark=com.glassous.betterhrbust.core.ui.theme.CourseColorPalette.cardColors(course.colorKey,true)
            val lightBg=light.container.toArgb();val darkBg=dark.container.toArgb()
            val lightInk=light.content.toArgb();val darkInk=dark.content.toArgb()
            if(android.os.Build.VERSION.SDK_INT>=31) {
                row.setColorStateList(R.id.widget_course_detail,"setBackgroundTintList",android.content.res.ColorStateList.valueOf(lightBg),android.content.res.ColorStateList.valueOf(darkBg))
                row.setColorInt(R.id.widget_course_detail,"setTextColor",lightInk,darkInk)
            } else {
                val isDark=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                row.setInt(R.id.widget_course_detail,"setBackgroundColor",if(isDark)darkBg else lightBg)
                row.setTextColor(R.id.widget_course_detail,if(isDark)darkInk else lightInk)
            }
            row.setOnClickFillInIntent(R.id.widget_course_row,Intent())
            row.setContentDescription(R.id.widget_course_name,"${course.name}，${course.location}，${course.time}")
            return row
        }
    }
}
