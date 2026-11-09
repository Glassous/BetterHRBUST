package com.glassous.betterhrbust.core.notify

import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.feature.timetable.CombineSlots
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

    fun resolve(
        cells: List<TimetableCell>,
        date: LocalDate,
        week: Int?,
        now: LocalTime,
        windowMinutes: Long = WINDOW_MINUTES
    ): UpcomingClass? {
        // 教学周未知（假期 / 尚未同步）时不做任何猜测性提醒
        if (week == null || cells.isEmpty()) return null
        val today = date.dayOfWeek.value
        val candidates = cells.asSequence()
            .filter { it.day == today && AcademicParsers.isCourseActiveInWeek(it.weeks, week) }
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

    /** 大节序号 → 起止时间；序号越界或时间解析失败返回 null，绝不抛异常。 */
    private fun TimetableCell.toSchedule(): Triple<LocalTime, LocalTime, TimetableCell>? {
        val slot = CombineSlots.getOrNull(sectionIndex - 1) ?: return null
        val start = runCatching { LocalTime.parse(slot.start) }.getOrNull() ?: return null
        val end = runCatching { LocalTime.parse(slot.end) }.getOrNull() ?: return null
        return Triple(start, end, this)
    }
}
