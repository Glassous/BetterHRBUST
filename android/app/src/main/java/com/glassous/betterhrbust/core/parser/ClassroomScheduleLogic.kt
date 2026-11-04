package com.glassous.betterhrbust.core.parser

import com.glassous.betterhrbust.core.model.ClassroomOccupancy
import kotlin.math.max
import kotlin.math.min

/** 连续空闲区间：day 1-7（周一=1），start/end 为小节序号（1-12） */
data class ClassroomFreeRange(
    val day: Int,
    val start: Int,
    val end: Int
) {
    val span: Int get() = end - start + 1
}

/**
 * 空教室计算（与 Web 端 web/src/services/academic/classroomSchedule.js 等价）
 *
 * 教务只返回「占用」，空闲需要自行推算：
 *   空闲 = (星期 × 小节) 全集 − 已占用
 *
 * 注意：整楼按周接口一次只返回一天，只查了单日时只能用 [dayRanges]，
 * 否则未查询的星期会被误判为整天空闲。
 */
object ClassroomScheduleLogic {

    const val DAY_COUNT = 7
    const val SECTION_COUNT = 12
    const val MIN_FREE_SPAN = 2

    private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 合并重叠 / 相邻的占用区间，并裁剪到合法范围 */
    fun normalize(
        occupancy: List<ClassroomOccupancy>,
        dayCount: Int = DAY_COUNT,
        sectionCount: Int = SECTION_COUNT
    ): List<ClassroomOccupancy> {
        val byDay = mutableMapOf<Int, MutableList<ClassroomOccupancy>>()
        occupancy.forEach { item ->
            if (item.day !in 1..dayCount) return@forEach
            val span = max(1, item.span)
            val start = max(1, item.start)
            val end = min(sectionCount, item.start + span - 1)
            if (end < start) return@forEach
            byDay.getOrPut(item.day) { mutableListOf() }.add(
                item.copy(start = start, span = end - start + 1)
            )
        }

        val result = mutableListOf<ClassroomOccupancy>()
        byDay.keys.sorted().forEach { day ->
            val merged = mutableListOf<ClassroomOccupancy>()
            byDay.getValue(day).sortedBy { it.start }.forEach { item ->
                val last = merged.lastOrNull()
                val lastEnd = last?.let { it.start + it.span - 1 } ?: 0
                if (last != null && item.start <= lastEnd + 1) {
                    val newEnd = max(lastEnd, item.start + item.span - 1)
                    merged[merged.lastIndex] = last.copy(
                        span = newEnd - last.start + 1,
                        reason = last.reason.ifBlank { item.reason }
                    )
                } else {
                    merged.add(item)
                }
            }
            result.addAll(merged)
        }
        return result
    }

    private fun occupiedKeys(
        occupancy: List<ClassroomOccupancy>,
        sectionCount: Int
    ): Set<Pair<Int, Int>> {
        val keys = mutableSetOf<Pair<Int, Int>>()
        normalize(occupancy, DAY_COUNT, sectionCount).forEach { item ->
            for (offset in 0 until item.span) {
                keys.add(item.day to (item.start + offset))
            }
        }
        return keys
    }

    private fun scanDay(
        occupied: Set<Pair<Int, Int>>,
        day: Int,
        sectionCount: Int,
        minSpan: Int
    ): List<ClassroomFreeRange> {
        val ranges = mutableListOf<ClassroomFreeRange>()
        var start: Int? = null

        for (section in 1..sectionCount + 1) {
            val isFree = section <= sectionCount && !occupied.contains(day to section)
            if (isFree && start == null) {
                start = section
            } else if (!isFree && start != null) {
                val span = section - start
                if (span >= minSpan) ranges.add(ClassroomFreeRange(day, start, section - 1))
                start = null
            }
        }
        return ranges
    }

    /**
     * 单日空闲区间（整楼按周接口只返回一天时使用）
     * @param minSpan 最短连续节数，默认 1（单节空档在单日视图里也有意义）
     */
    fun dayRanges(
        occupancy: List<ClassroomOccupancy>,
        day: Int,
        sectionCount: Int = SECTION_COUNT,
        minSpan: Int = 1
    ): List<ClassroomFreeRange> {
        val occupied = occupiedKeys(occupancy, sectionCount)
        return scanDay(occupied, day, sectionCount, max(1, minSpan))
    }

    /**
     * 整周空闲区间（已加载周一至周日时使用）
     * @param minSpan 最短连续节数，默认 2（过滤零散单节）
     */
    fun weekRanges(
        occupancy: List<ClassroomOccupancy>,
        dayCount: Int = DAY_COUNT,
        sectionCount: Int = SECTION_COUNT,
        minSpan: Int = MIN_FREE_SPAN
    ): List<ClassroomFreeRange> {
        val occupied = occupiedKeys(occupancy, sectionCount)
        val ranges = mutableListOf<ClassroomFreeRange>()
        for (day in 1..dayCount) {
            ranges.addAll(scanDay(occupied, day, sectionCount, max(1, minSpan)))
        }
        return ranges
    }

    /** 已占用的小节总数 */
    fun occupiedSections(
        occupancy: List<ClassroomOccupancy>,
        sectionCount: Int = SECTION_COUNT
    ): Int = occupiedKeys(occupancy, sectionCount).size

    /** 格式化为「周一 3-4 节」 */
    fun formatRange(range: ClassroomFreeRange): String {
        val weekday = WEEKDAY_LABELS.getOrNull(range.day - 1) ?: "周${range.day}"
        return if (range.start == range.end) {
            "$weekday ${range.start} 节"
        } else {
            "$weekday ${range.start}-${range.end} 节"
        }
    }
}
