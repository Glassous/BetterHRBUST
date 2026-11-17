package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.notify.UpcomingClassResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 上课提醒的取课逻辑与后台精确唤醒计算：纯函数测试，不依赖 Android 运行时。 */
class UpcomingClassResolverTest {
    private val date = LocalDate.of(2026, 11, 9) // 周一
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

    private fun at(hour: Int, minute: Int, second: Int = 0, nano: Int = 0): LocalTime =
        LocalTime.of(hour, minute, second, nano)

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

    @Test
    fun `窗口内下一整分跳变点严格递增且在最后一分钟指向开课时刻`() {
        val upcoming = UpcomingClassResolver.resolve(listOf(cell(1)), date, week, at(7, 50))!!
        // 07:50:00（显示 20分）-> 下次在 07:51:00 跳变为 19分
        val b1 = UpcomingClassResolver.nextActiveBoundary(upcoming, LocalDateTime.of(date, at(7, 50, 0)))
        assertEquals(LocalDateTime.of(date, at(7, 51, 0)), b1)
        assertEquals(19L, upcoming.minutesUntil(b1))

        // 07:50:59.900（Duration.seconds 已为 1140，显示 19分）-> 下次在 07:52:00 跳变为 18分，绝不原地死循环
        val almost51 = LocalDateTime.of(date, at(7, 50, 59, 900_000_000))
        val b2 = UpcomingClassResolver.nextActiveBoundary(upcoming, almost51)
        assertEquals(LocalDateTime.of(date, at(7, 52, 0)), b2)
        assertTrue(b2.isAfter(almost51))

        // 08:09:15（显示 1分）-> 下次直接指向 08:10:00 准点收起通知
        val bLast = UpcomingClassResolver.nextActiveBoundary(upcoming, LocalDateTime.of(date, at(8, 9, 15)))
        assertEquals(LocalDateTime.of(date, at(8, 10, 0)), bLast)
    }

    @Test
    fun `窗口外计算当天与跨天下一节课前二十分钟唤醒时刻`() {
        val tuesday = date.plusDays(1)
        val cells = listOf(
            cell(1, day = date.dayOfWeek.value),
            cell(2, day = tuesday.dayOfWeek.value, weeks = "6-6")
        )

        // 当天早上 07:00 -> 指向当天第一节课前 20 分钟（07:50）
        val sameDayWake = UpcomingClassResolver.nextReminderWakeup(
            cells = cells,
            now = LocalDateTime.of(date, at(7, 0)),
            weekForDate = { week }
        )
        assertEquals(LocalDateTime.of(date, at(7, 50)), sameDayWake)

        // 周日晚上 21:00，下周一进入第 6 周 -> 跨天找到周二第 6 周第二节课（10:05 开始，09:45 唤醒）
        val sundayBeforeWeek6 = tuesday.minusDays(2) // 周日
        val crossWeekWake = UpcomingClassResolver.nextReminderWakeup(
            cells = listOf(cell(2, day = tuesday.dayOfWeek.value, weeks = "6-6")),
            now = LocalDateTime.of(sundayBeforeWeek6, at(21, 0)),
            weekForDate = { d -> if (!d.isBefore(tuesday.minusDays(1))) 6 else 5 }
        )
        val expectedSection2Window = UpcomingClassResolver
            .resolve(listOf(cell(2, day = tuesday.dayOfWeek.value, weeks = "6-6")), tuesday, 6, at(9, 55))!!
            .start
            .minusMinutes(20)
        assertEquals(expectedSection2Window, crossWeekWake)

        // 空课表不注册任何闹钟
        assertNull(
            UpcomingClassResolver.nextReminderWakeup(
                cells = emptyList(),
                now = LocalDateTime.of(date, at(7, 0)),
                weekForDate = { week }
            )
        )
    }
}
