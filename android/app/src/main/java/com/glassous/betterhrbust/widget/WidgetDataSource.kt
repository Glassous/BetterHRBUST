package com.glassous.betterhrbust.widget

import android.content.Context
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.datastore.AppPreferences
import com.glassous.betterhrbust.core.model.PersonalInfo
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

data class WidgetSnapshot(val prefs: AppPreferences,val date: LocalDate,val time: LocalTime,val week: Int?,
                          val today: List<WidgetCourse>,val tomorrow: List<WidgetCourse>,val tomorrowUnknown: Boolean,val message: String)

/** Provider 与旧版 Android 列表服务读取同一份已核验的离线缓存。 */
object WidgetDataSource {
    private val json=Json { ignoreUnknownKeys=true }
    suspend fun load(context: Context): WidgetSnapshot? {
        val app=context.applicationContext as BetterHrbustApp
        val prefs=app.preferencesManager.preferencesFlow.first()
        val date=LocalDate.now();val time=LocalTime.now()
        val week=prefs.teachingWeekOn(date)
        val profile=if(prefs.studentId.isBlank())null else app.database.profileDao().getProfile(prefs.username).first()
        val verified=profile?.let { runCatching { json.decodeFromString<PersonalInfo>(it.json).studentNumber==prefs.username }.getOrDefault(false) }==true
        val entity=if(verified)app.database.timetableDao().getTimetable(prefs.studentId).first() else null
        val table=entity?.let { runCatching { AcademicParsers.normalizeTimetableCells(json.decodeFromString<TimetableResult>(it.json)) }.getOrNull() }
        table?.let { CourseColorPalette.register(it.cells.map { cell->CourseColorPalette.keyOf(cell.courseName,cell.courseSeq,cell.courseId,cell.id) }) }
        val nextWeek=prefs.teachingWeekOn(date.plusDays(1))
        val today=if(week!=null && table!=null)TimetableWidgetModel.dayCourses(table.cells,week,date,time) else emptyList()
        val tomorrow=if(nextWeek!=null && table!=null)TimetableWidgetModel.dayCourses(table.cells,nextWeek,date.plusDays(1)) else emptyList()
        val current=app.preferencesManager.preferencesFlow.first()
        if(current.username!=prefs.username || current.studentId!=prefs.studentId || current.lastLoginAt!=prefs.lastLoginAt)return null
        val message=when {
            prefs.studentId.isBlank()->"打开应用登录"
            !verified->"打开应用核验账号"
            table==null->"打开课表同步数据"
            week==null && com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(date)!=null->"当前处于假期"
            week==null->"打开应用同步教学周"
            else->""
        }
        return WidgetSnapshot(prefs,date,time,week,today,tomorrow,nextWeek==null,message)
    }
}
