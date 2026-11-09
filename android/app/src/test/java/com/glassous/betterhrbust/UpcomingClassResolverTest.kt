package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.notify.UpcomingClassResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 上课提醒的取课逻辑：只覆盖纯函数，不依赖 Android 运行时。 */
class UpcomingClassResolverTest {
    private val date = LocalDate.of(2026, 11, 9)
    private val today = date.dayOfWeek.value
    private val week = 5

    private fun cell(
        section: Int,
        weeks: String = "1-20",
        name: String = "高等数学",
        location: String = "教1-302",
        teacher: String = "张老师",
        day: Int = today
    ) = TimetableCell(
        id = "$name-$section-$day",
        courseId = "course-$name",
        day = day,
        sectionIndex = section,
        sectionLabel = "第${section}大节",
        courseName = name,
        courseSeq = "01",
        location = location,
        teacher = teacher,
        weeks = weeks,
        hoursType = ""
    )

    private fun at(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)

    @Test
    fun `恰好二十分钟仍提醒`() {
        val result = UpcomingClassResolver.resolve(listOf(cell(1)), date, week, at(7, 50))
        assertNotNull(result)
        assertEquals(LocalDateTime.of(date, at(8, 10)), result!!.start)
        assertEquals(20L, result.minutesUntil(LocalDateTime.of(date, at(7, 50))))
    }

    @Test
    fun `超过二十分钟不提醒`() {
        assertNull(UpcomingClassResolver.resolve(listOf(cell(1)), date, week, at(7, 49)))
    }

    @Test
    fun `已开始的大节不再作为候选`() {
        // 08:11 时第一节已开始，后面没有课
        assertNull(UpcomingClassResolver.resolve(listOf(cell(1)), date, week, at(8, 11)))
    }

    @Test
    fun `多节课时选中最近的一节`() {
        val cells = listOf(cell(1, name = "大学英语"), cell(2, name = "数据结构"))
        val result = UpcomingClassResolver.resolve(cells, date, week, at(9, 55))
        assertNotNull(result)
        assertEquals("数据结构", result!!.courseName)
        assertEquals(15L, result.minutesUntil(LocalDateTime.of(date, at(9, 55))))
    }

    @Test
    fun `周次不匹配的课程被过滤`() {
        assertNull(UpcomingClassResolver.resolve(listOf(cell(1, weeks = "1-4")), date, week, at(7, 55)))
    }

    @Test
    fun `教学周未知时不提醒`() {
        assertNull(UpcomingClassResolver.resolve(listOf(cell(1)), date, null, at(7, 55)))
    }

    @Test
    fun `非当天的课程被忽略`() {
        val otherDay = if (today == 7) 1 else today + 1
        assertNull(
            UpcomingClassResolver.resolve(
                listOf(cell(1, day = otherDay)), date, week, at(7, 55)
            )
        )
    }

    @Test
    fun `空课表返回空`() {
        assertNull(UpcomingClassResolver.resolve(emptyList(), date, week, at(7, 55)))
    }

    @Test
    fun `教室缺失时回退为待公布`() {
        val result = UpcomingClassResolver.resolve(
            listOf(cell(1, location = "")), date, week, at(7, 55)
        )
        assertEquals("地点待公布", result!!.location)
    }

    @Test
    fun `节次越界不会抛异常`() {
        assertNull(UpcomingClassResolver.resolve(listOf(cell(9)), date, week, at(7, 55)))
    }
}
