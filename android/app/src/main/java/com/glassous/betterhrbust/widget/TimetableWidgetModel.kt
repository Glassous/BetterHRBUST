package com.glassous.betterhrbust.widget

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.util.TimetableOverrides
import com.glassous.betterhrbust.feature.timetable.CombineSlots
import java.time.LocalDate
import java.time.LocalTime

data class WidgetCourse(
    val name: String,
    val location: String,
    val time: String,
    val ongoing: Boolean,
    val colorKey: String = name,
    val tag: String = ""
)

/** 数据与布局解耦；只读已验证账号的离线课表，不在桌面后台请求教务。 */
data class WidgetDayProgress(val remaining: List<WidgetCourse>,val completed: Int,val total: Int)

object TimetableWidgetModel {
    fun progress(courses: List<WidgetCourse>, now: LocalTime): WidgetDayProgress {
        val remaining=courses.filter { now.isBefore(LocalTime.parse(it.time.substringAfter("–"))) }
        return WidgetDayProgress(remaining,courses.size-remaining.size,courses.size)
    }

    fun teachingWeek(referenceWeek: Int, referenceDate: String, today: LocalDate): Int? =
        com.glassous.betterhrbust.core.util.TeachingWeek.resolve(referenceWeek, referenceDate, today)

    fun dayCourses(cells: List<TimetableCell>, week: Int, today: LocalDate, now: LocalTime? = null): List<WidgetCourse> =
        cells.asSequence()
            .filter { !it.isMovedOut && it.day == today.dayOfWeek.value && AcademicParsers.isCourseActiveInWeek(it.weeks, week) }
            .mapNotNull { cell ->
                val range = TimetableOverrides.effectiveTimeRangeOf(cell) ?: return@mapNotNull null
                Triple(range.first, range.second, cell)
            }
            .sortedWith(compareBy<Triple<LocalTime, LocalTime, TimetableCell>> { it.first }.thenBy { it.third.courseName })
            .map { (start, end, cell) ->
                val startStr = "%02d:%02d".format(start.hour, start.minute)
                val endStr = "%02d:%02d".format(end.hour, end.minute)
                val tag = when {
                    cell.customCategory == "lab" -> "实验"
                    cell.isCustomCourse -> "自定"
                    cell.isRescheduled -> "调课"
                    else -> ""
                }
                WidgetCourse(
                    name = cell.courseName,
                    location = cell.location.ifBlank { "地点待公布" },
                    time = "$startStr–$endStr",
                    ongoing = now != null && !now.isBefore(start) && now.isBefore(end),
                    colorKey = com.glassous.betterhrbust.core.ui.theme.CourseColorPalette.keyOf(
                        cell.courseName,
                        cell.courseSeq,
                        cell.courseId,
                        cell.id
                    ),
                    tag = tag
                )
            }
            .toList()

    fun remainingCourses(cells: List<TimetableCell>, week: Int, today: LocalDate, now: LocalTime): List<WidgetCourse> =
        dayCourses(cells, week, today, now).filter { now.isBefore(LocalTime.parse(it.time.substringAfter("–"))) }
}

