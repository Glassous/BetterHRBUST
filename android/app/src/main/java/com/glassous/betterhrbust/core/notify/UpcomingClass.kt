package com.glassous.betterhrbust.core.notify

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableOverridesPayload
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.util.TimetableOverrides
import com.glassous.betterhrbust.feature.timetable.SectionMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 待提醒的下一节课（时间已解析，供 ticker 与 notifier 共用）。 */
data class UpcomingClass(
    val courseName: String,
    val location: String,
    val teacher: String,
    val sectionLabel: String,
    val start: LocalDateTime,
    val end: LocalDateTime
) {
    /**
     * 距上课的分钟数，向上取整：11 分 01 秒 → 12。
     * 向上取整可避免最后一分钟内显示「0 分钟后上课」。
     */
    fun minutesUntil(now: LocalDateTime): Long =
        ((Duration.between(now, start).seconds + 59) / 60).coerceAtLeast(0)
}

/**
 * 从离线课表里挑选「今天、当前教学周、最近一节尚未开始的课」。
 *
 * 纯函数：不读磁盘、不碰系统时间，便于单元测试；时间源由调用方注入。
 */
object UpcomingClassResolver {
    /** 提醒窗口：上课前 20 分钟内 */
    const val WINDOW_MINUTES = 20L

    /** 窗口外跨天查找下一节课的最大前瞻天数（覆盖整周课表循环） */
    private const val LOOKAHEAD_DAYS = 7L

    fun resolve(
        cells: List<TimetableCell>,
        date: LocalDate,
        week: Int?,
        now: LocalTime,
        windowMinutes: Long = WINDOW_MINUTES,
        overrides: TimetableOverridesPayload = TimetableOverridesPayload()
    ): UpcomingClass? {
        // 教学周未知（假期 / 尚未同步）时不做任何猜测性提醒
        if (week == null || (cells.isEmpty() && overrides.customCourses.isEmpty())) return null
        val effectiveCells = TimetableOverrides.buildEffectiveTimetableCells(
            rawCells = cells,
            week = week,
            sectionMode = SectionMode.COMBINE,
            overrides = overrides
        )
        val today = date.dayOfWeek.value
        val candidates = effectiveCells.asSequence()
            .filter { !it.isMovedOut && it.day == today && AcademicParsers.isCourseActiveInWeek(it.weeks, week) }
            .mapNotNull { cell -> cell.toSchedule() }
            .sortedBy { it.first }
            .toList()
        // 只关心「还没开始」的最近一节；已开始的课不再提醒（连堂课由下一节自己进入窗口）
        val next = candidates.firstOrNull { it.first.isAfter(now) } ?: return null
        val (start, end, cell) = next
        // 用秒比较而非分钟取整，保证「恰好 20 分钟」仍提醒、「20 分 01 秒」不提醒
        if (Duration.between(now, start).seconds > windowMinutes * 60) return null
        return UpcomingClass(
            courseName = cell.courseName,
            location = cell.location.ifBlank { "地点待公布" },
            teacher = cell.teacher,
            sectionLabel = cell.sectionLabel,
            start = date.atTime(start),
            end = date.atTime(end)
        )
    }

    /**
     * 当前已处于提醒窗口内时，计算下一次需要刷新或收起通知的精确时刻：
     * - 剩余显示分钟数 >= 2（如 15分）：返回分钟数跳变为 14分 的精确整分边界 `start - 14min`；
     * - 最后 1 分钟内（显示 1分）：返回课程开始时刻 `start`，到点立即收起通知。
     */
    fun nextActiveBoundary(upcoming: UpcomingClass, now: LocalDateTime): LocalDateTime {
        if (!upcoming.start.isAfter(now)) return upcoming.start
        val seconds = Duration.between(now, upcoming.start).seconds
        if (seconds <= 0L) return upcoming.start
        val displayMinutes = (seconds + 59) / 60
        return if (displayMinutes <= 1L) {
            upcoming.start
        } else {
            upcoming.start.minusMinutes(displayMinutes - 1)
        }
    }

    /**
     * 计算下一次系统闹钟应唤醒重算的时刻：
     * 1. 若此刻正处于某节课的课前提醒窗口内，返回 [nextActiveBoundary]（下一整分跳变点或上课到点时刻）；
     * 2. 否则向后扫描今天及未来 [LOOKAHEAD_DAYS] 天内最早一节尚未开始的课，返回其窗口开启时刻 `start - windowMinutes`；
     * 3. 若扫描范围内无课但本地课表非空（如假期或整周无课），返回次日 00:05 低频推进检查；
     * 4. 若本地课表为空，返回 null（不注册任何闹钟）。
     */
    fun nextReminderWakeup(
        cells: List<TimetableCell>,
        now: LocalDateTime,
        weekForDate: (LocalDate) -> Int?,
        windowMinutes: Long = WINDOW_MINUTES,
        overrides: TimetableOverridesPayload = TimetableOverridesPayload()
    ): LocalDateTime? {
        if (cells.isEmpty() && overrides.customCourses.isEmpty()) return null
        val today = now.toLocalDate()
        val activeNow = resolve(cells, today, weekForDate(today), now.toLocalTime(), windowMinutes, overrides)
        if (activeNow != null) {
            return nextActiveBoundary(activeNow, now)
        }
        for (offset in 0L..LOOKAHEAD_DAYS) {
            val candidateDate = today.plusDays(offset)
            val week = weekForDate(candidateDate) ?: continue
            val dayOfWeek = candidateDate.dayOfWeek.value
            val effectiveCells = TimetableOverrides.buildEffectiveTimetableCells(
                rawCells = cells,
                week = week,
                sectionMode = SectionMode.COMBINE,
                overrides = overrides
            )
            val earliest = effectiveCells.asSequence()
                .filter { !it.isMovedOut && it.day == dayOfWeek && AcademicParsers.isCourseActiveInWeek(it.weeks, week) }
                .mapNotNull { it.toSchedule() }
                .sortedBy { it.first }
                .map { (startTime, endTime, cell) ->
                    UpcomingClass(
                        courseName = cell.courseName,
                        location = cell.location.ifBlank { "地点待公布" },
                        teacher = cell.teacher,
                        sectionLabel = cell.sectionLabel,
                        start = candidateDate.atTime(startTime),
                        end = candidateDate.atTime(endTime)
                    )
                }
                .firstOrNull { it.start.isAfter(now) }
            if (earliest != null) {
                val windowStart = earliest.start.minusMinutes(windowMinutes)
                return if (windowStart.isAfter(now)) windowStart else nextActiveBoundary(earliest, now)
            }
        }
        return today.plusDays(1).atTime(0, 5)
    }

    /** 节次或自定义时段 → 起止时间；越界或格式非法返回 null，绝不抛异常。 */
    private fun TimetableCell.toSchedule(): Triple<LocalTime, LocalTime, TimetableCell>? {
        val range = TimetableOverrides.effectiveTimeRangeOf(this) ?: return null
        return Triple(range.first, range.second, this)
    }
}
