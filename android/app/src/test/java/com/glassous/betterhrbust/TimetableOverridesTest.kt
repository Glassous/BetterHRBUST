package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.CustomCourseEntry
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableOverridesPayload
import com.glassous.betterhrbust.core.model.TimetableRescheduleEntry
import com.glassous.betterhrbust.core.util.CourseSchedule
import com.glassous.betterhrbust.core.util.TimetableOverrides
import com.glassous.betterhrbust.feature.timetable.SectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TimetableOverridesTest {

    @Test
    fun parseTimeToMinutes_validAndInvalid() {
        assertEquals(490, TimetableOverrides.parseTimeToMinutes("08:10"))
        assertEquals(1290, TimetableOverrides.parseTimeToMinutes("21:30"))
        assertNull(TimetableOverrides.parseTimeToMinutes("24:00"))
        assertNull(TimetableOverrides.parseTimeToMinutes("14:88"))
        assertNull(TimetableOverrides.parseTimeToMinutes("bad"))
    }

    @Test
    fun resolveCustomTimeAnchor_mapsAfternoonAndNoonRanges() {
        val afternoon = TimetableOverrides.resolveCustomTimeAnchor("14:00", "16:30")
        assertEquals(3, afternoon.combineSection)
        assertEquals(6, afternoon.baseSection)

        val noon = TimetableOverrides.resolveCustomTimeAnchor("12:40", "13:25")
        assertEquals(3, noon.combineSection)
        assertEquals(5, noon.baseSection)
    }

    @Test
    fun customCourse_customTime_onlyVisibleInTargetWeekAndUsesExactEndTime() {
        val lab = TimetableOverrides.normalizeCustomCourse(
            CustomCourseEntry(
                id = "c1",
                category = "lab",
                courseName = "大学物理实验",
                location = "实验楼 B304",
                teacher = "王老师",
                week = 10,
                day = 3,
                timeMode = "custom",
                startTime = "18:30",
                endTime = "20:30"
            )
        )
        val week10 = TimetableOverrides.buildEffectiveTimetableCells(
            rawCells = emptyList(),
            week = 10,
            sectionMode = SectionMode.COMBINE,
            overrides = TimetableOverridesPayload(customCourses = listOf(lab))
        )
        val week11 = TimetableOverrides.buildEffectiveTimetableCells(
            rawCells = emptyList(),
            week = 11,
            sectionMode = SectionMode.COMBINE,
            overrides = TimetableOverridesPayload(customCourses = listOf(lab))
        )

        assertEquals(1, week10.size)
        assertTrue(week10[0].isCustomCourse)
        assertEquals("lab", week10[0].customCategory)
        assertEquals("18:30 - 20:30", week10[0].customTimeText)
        assertEquals(5, week10[0].sectionIndex)
        assertFalse(CourseSchedule.hasEnded(week10[0], LocalTime.of(20, 0)))
        assertTrue(CourseSchedule.hasEnded(week10[0], LocalTime.of(20, 30)))
        assertTrue(week11.isEmpty())
    }

    @Test
    fun singleWeekReschedule_rejectsSamePosition_andMovesOnlyInTargetWeek() {
        val rawCell = TimetableCell(
            id = "2-100",
            courseId = "100",
            day = 2,
            sectionIndex = 1,
            sectionLabel = "第1大节",
            courseName = "高等数学",
            courseSeq = "01",
            location = "西-新D101",
            teacher = "张老师",
            weeks = "1-16周",
            hoursType = "讲课学时"
        )
        val match = TimetableOverrides.buildRescheduleSourceMatch(rawCell)

        assertFalse(TimetableOverrides.isDifferentRescheduleTarget(match, 2, 1, SectionMode.COMBINE))
        assertTrue(TimetableOverrides.isDifferentRescheduleTarget(match, 4, 3, SectionMode.COMBINE))
        assertFalse(TimetableOverrides.isDifferentRescheduleTarget(match, 2, 2, SectionMode.BASE))
        assertTrue(TimetableOverrides.isDifferentRescheduleTarget(match, 2, 3, SectionMode.BASE))

        val reschedule = TimetableOverrides.normalizeReschedule(
            TimetableRescheduleEntry(
                id = "r1",
                week = 10,
                sourceMatch = match,
                targetDay = 4,
                targetSectionMode = "combine",
                targetCombineSection = 3,
                location = "东-2号楼405",
                note = "临时调课"
            )
        )

        val week10 = TimetableOverrides.buildEffectiveTimetableCells(
            rawCells = listOf(rawCell),
            week = 10,
            sectionMode = SectionMode.COMBINE,
            overrides = TimetableOverridesPayload(reschedules = listOf(reschedule))
        )
        val week11 = TimetableOverrides.buildEffectiveTimetableCells(
            rawCells = listOf(rawCell),
            week = 11,
            sectionMode = SectionMode.COMBINE,
            overrides = TimetableOverridesPayload(reschedules = listOf(reschedule))
        )

        assertEquals(2, week10.size)
        val movedOut = week10.first { it.isMovedOut }
        val movedIn = week10.first { it.isRescheduled }
        assertEquals(2, movedOut.day)
        assertEquals(1, movedOut.sectionIndex)
        assertEquals("周四 第 3 大节", movedOut.movedToLabel)

        assertEquals(4, movedIn.day)
        assertEquals(3, movedIn.sectionIndex)
        assertEquals("东-2号楼405", movedIn.location)
        assertEquals("第10周", movedIn.weeks)

        assertEquals(1, week11.size)
        assertFalse(week11[0].isMovedOut)
        assertFalse(week11[0].isRescheduled)
        assertEquals("西-新D101", week11[0].location)
    }
}
