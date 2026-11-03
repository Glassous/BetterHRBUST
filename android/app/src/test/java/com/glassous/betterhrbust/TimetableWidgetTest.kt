package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.widget.TimetableWidgetModel
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class TimetableWidgetTest {
    @Test fun publishedCalendarRepairsStaleWeek26AndExcludesWinterHoliday() {
        val calendar=com.glassous.betterhrbust.core.util.SchoolCalendar
        assertEquals(6,calendar.weekOn(LocalDate.parse("2026-10-10")))
        assertEquals(19,calendar.weekOn(LocalDate.parse("2027-01-10")))
        assertNull(calendar.weekOn(LocalDate.parse("2027-01-11")))
        assertEquals(1,calendar.weekOn(LocalDate.parse("2027-03-01")))
        assertEquals(19,calendar.weekOn(LocalDate.parse("2027-07-11")))
        assertNull(calendar.weekOn(LocalDate.parse("2027-07-12")))
        val stale=com.glassous.betterhrbust.core.datastore.AppPreferences(currentWeek=26,currentWeekReferenceDate="2026-10-10")
        assertEquals(6,stale.teachingWeekOn(LocalDate.parse("2026-10-10")))
    }

    @Test fun calendarWeekWinsOverGenericNavigationAndSemesterLimitStopsAtTwenty() {
        val html="<div>第26周</div><div id='date'>当前第6周</div><div class='curweek'>2026 秋 第<strong>6</strong>周</div><div class='semester-calendar'>本学期共20教学周</div>"
        assertEquals(6,com.glassous.betterhrbust.core.parser.AcademicParsers.parseTeachingWeek(html))
        val calendar=com.glassous.betterhrbust.core.parser.AcademicParsers.parseCalendarInfo(html)
        assertEquals(6,calendar.currentWeek)
        assertEquals(20,calendar.teachingWeeks)
        assertNull(com.glassous.betterhrbust.core.util.TeachingWeek.resolve(6,"2026-10-10",LocalDate.parse("2027-01-25")))
        assertEquals(20,com.glassous.betterhrbust.core.util.TeachingWeek.resolve(6,"2026-10-10",LocalDate.parse("2027-01-16")))
        assertNull(com.glassous.betterhrbust.core.parser.AcademicParsers.parseCalendarInfo("<div class='week'><a>26</a></div>").teachingWeeks)
    }

    @Test fun selectedWeekDatesCrossYearAndLeapDayWithoutGuessingMissingAnchors() {
        val dates=com.glassous.betterhrbust.core.util.TeachingWeek
        assertEquals(LocalDate.parse("2025-12-29"),dates.mondayForWeek(6,"2026-01-01",6))
        assertEquals(LocalDate.parse("2026-01-05"),dates.mondayForWeek(6,"2026-01-01",7))
        assertEquals(LocalDate.parse("2024-02-26"),dates.mondayForWeek(6,"2024-02-29",6))
        assertNull(dates.mondayForWeek(6,"",7))
    }

    @Test fun completedLessonsDisappearExactlyAtEndAndKeepTheDailyDenominator() {
        val courses=listOf(com.glassous.betterhrbust.widget.WidgetCourse("A","D510","08:10–09:50",false),com.glassous.betterhrbust.widget.WidgetCourse("B","D501","10:10–11:50",false))
        val boundary=TimetableWidgetModel.progress(courses,LocalTime.parse("09:50"))
        assertEquals(1,boundary.completed);assertEquals(2,boundary.total)
        assertEquals(listOf("B"),boundary.remaining.map {it.name})
        val done=TimetableWidgetModel.progress(courses,LocalTime.parse("11:50"))
        assertEquals(2,done.completed);assertEquals(2,done.total);assertTrue(done.remaining.isEmpty())
    }

    @Test fun refreshScheduleMovesToNextEndAndThenMidnight() {
        val date=LocalDate.parse("2026-10-10")
        val courses=listOf(com.glassous.betterhrbust.widget.WidgetCourse("A","D510","08:10–09:50",false),com.glassous.betterhrbust.widget.WidgetCourse("B","D501","10:10–11:50",false))
        assertEquals(date.atTime(11,50),com.glassous.betterhrbust.widget.WidgetRefreshScheduler.nextUpdate(date,LocalTime.parse("09:50"),courses))
        assertEquals(date.plusDays(1).atStartOfDay(),com.glassous.betterhrbust.widget.WidgetRefreshScheduler.nextUpdate(date,LocalTime.parse("11:50"),courses))
    }

    @Test fun appAndWidgetAgreeOnTheOfflineWeekWithoutDoubleAdvancingTheStoredReference() {
        val prefs=com.glassous.betterhrbust.core.datastore.AppPreferences(currentWeek=6,currentWeekReferenceDate="2026-10-09")
        val monday=LocalDate.parse("2026-10-12")
        assertEquals(7,prefs.teachingWeekOn(monday))
        assertEquals(TimetableWidgetModel.teachingWeek(6,"2026-10-09",monday),prefs.teachingWeekOn(monday))
        assertEquals(6,prefs.currentWeek)
        assertNull(com.glassous.betterhrbust.core.util.TeachingWeek.resolve(0,"2026-10-09",monday))
    }

    @Test fun tomorrowUsesItsOwnWeekAcrossSundayAndTodayKeepsCompletedCourses() {
        val sunday=LocalDate.parse("2026-10-11")
        val monday=sunday.plusDays(1)
        val cells=listOf(TimetableCell("next", "next", 1, 1, "第一大节", "周一单周课", "1", "D510", "教师", "7-9单周", ""))
        val week=TimetableWidgetModel.teachingWeek(6,"2026-10-09",monday)!!
        assertEquals(7,week)
        assertEquals(1,TimetableWidgetModel.dayCourses(cells,week,monday).size)
        assertTrue(TimetableWidgetModel.dayCourses(cells,6,monday).isEmpty())
        val completed=TimetableWidgetModel.dayCourses(cells,week,monday,LocalTime.parse("12:00"))
        assertEquals(1,completed.size)
        assertFalse(completed.first().ongoing)
    }

    @Test fun weekViewHidesSectionsAndWeekdaysWithoutCourses() {
        val ink = androidx.compose.ui.graphics.Color.Black
        fun cell(day: Int, section: Int) = com.glassous.betterhrbust.widget.WidgetCell(
            course = TimetableCell("c$day-$section", "c$day-$section", day, section, "第一大节", "课程", "1", "D510", "教师", "1-18周", ""),
            activeInWeek = true,
            colors = com.glassous.betterhrbust.widget.WidgetCardColors(ink, ink, ink, ink, ink, ink, ink, ink)
        )
        // 只有第 2 大节的周三、第 4 大节的周五有课
        val rows = (1..6).map { section ->
            com.glassous.betterhrbust.widget.WidgetWeekRow(
                section = section,
                cellsByDay = when (section) {
                    2 -> mapOf(3 to listOf(cell(3, 2)))
                    4 -> mapOf(5 to listOf(cell(5, 4)))
                    else -> emptyMap()
                }
            )
        }
        val data = com.glassous.betterhrbust.widget.TimetableWidgetData(
            hasSession = true, hasTimetable = true, currentWeek = 6, todayDay = 3,
            weekRows = rows, todayRows = emptyList()
        )
        assertEquals(listOf(2, 4), data.visibleWeekRows.map { it.section })
        assertEquals(listOf(3, 5), data.visibleWeekDays)
        assertTrue(com.glassous.betterhrbust.widget.TimetableWidgetData.empty(3).visibleWeekDays.isEmpty())
    }

    @Test fun offlineTeachingWeekAdvancesOnMondayAndDoesNotInventMissingReference() {
        assertEquals(6, TimetableWidgetModel.teachingWeek(6, "2026-10-09", LocalDate.parse("2026-10-11")))
        assertEquals(7, TimetableWidgetModel.teachingWeek(6, "2026-10-09", LocalDate.parse("2026-10-12")))
        assertNull(TimetableWidgetModel.teachingWeek(6, "", LocalDate.parse("2026-10-12")))
        assertNull(TimetableWidgetModel.teachingWeek(26, "2026-10-09", LocalDate.parse("2026-10-12")))
    }
    @Test fun widgetSharesSchoolWeeksAndExactCourseEndBoundary() {
        fun cell(name: String, weeks: String, day: Int = 5, section: Int = 1) =
            TimetableCell(name, name, day, section, "第一大节", name, "1", "D510", "教师", weeks, "")
        val cells = listOf(cell("当前课", "1-18周"), cell("隔周课", "1-18单周"), cell("其他日", "1-18周", 4), cell("下一课", "1-18周", section=2))
        val date=LocalDate.parse("2026-10-09")
        val before=TimetableWidgetModel.remainingCourses(cells, 6, date, LocalTime.parse("09:49"))
        assertEquals(listOf("当前课", "下一课"), before.map { it.name })
        assertTrue(before.first().ongoing)
        val after=TimetableWidgetModel.remainingCourses(cells, 6, date, LocalTime.parse("09:50"))
        assertEquals(listOf("下一课"), after.map { it.name })
        assertFalse(after.first().ongoing)
        assertTrue(TimetableWidgetModel.remainingCourses(cells, 6, date, LocalTime.parse("21:30")).isEmpty())
    }
}
