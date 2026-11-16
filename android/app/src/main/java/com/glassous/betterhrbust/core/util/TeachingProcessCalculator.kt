package com.glassous.betterhrbust.core.util

import com.glassous.betterhrbust.core.model.PatternGroup
import com.glassous.betterhrbust.core.model.ProcessClass
import com.glassous.betterhrbust.core.model.ProcessWeek
import com.glassous.betterhrbust.core.model.StageCount
import com.glassous.betterhrbust.core.model.StageInsights
import com.glassous.betterhrbust.core.model.StageNode
import com.glassous.betterhrbust.core.model.StagePart
import com.glassous.betterhrbust.core.model.StageSegment
import com.glassous.betterhrbust.core.model.StageStatus
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 本科教学进程表的纯函数层（与 Web 端 `teachingProcess.js` 同一套算法）。
 *
 * 数据层只存「每周单元格原始字形」，本模块把逐周符号压缩成人能读的阶段区间：
 *   ['', '', '∨', '◎', '◎', '⊕/：', '＝', '＝']
 *   → 常规教学 1–2 / 运动会 3 / 技能训练 4–5 / 综合实践与考试各占第 6 周一半 / 假期 7–8
 */
object TeachingProcessCalculator {

    /** 解码 `|a|b|c` → [a, b, c]；首字符是哨兵，用来保留开头空单元格。 */
    fun decodePattern(encoded: String?): List<String> {
        if (encoded.isNullOrEmpty() || !encoded.startsWith("|")) return emptyList()
        return encoded.substring(1).split('|')
    }

    private fun isContiguous(end: StageNode, start: StageNode): Boolean =
        if (end.week == start.week) {
            end.idx + 1 == start.idx
        } else {
            end.week + 1 == start.week && end.idx == end.count - 1 && start.idx == 0
        }

    private fun segmentStartValue(node: StageNode): Float =
        node.week - 1 + if (node.count > 1) node.idx.toFloat() / node.count else 0f

    private fun segmentEndValue(node: StageNode): Float =
        node.week - 1 + if (node.count > 1) (node.idx + 1f) / node.count else 1f

    private class MutableSegment(
        val part: StagePart,
        val start: StageNode,
        var end: StageNode
    )

    /** 把逐周单元格合并成阶段区间；斜杠周的半周归属会精确到 idx/count。 */
    fun buildSegments(cells: List<String>): List<StageSegment> {
        val segments = mutableListOf<MutableSegment>()
        cells.forEachIndexed { weekIndex, cell ->
            val parts = TeachingProcessSymbols.cellParts(cell)
            for (idx in parts.indices) {
                val part = parts[idx]
                val node = StageNode(week = weekIndex + 1, idx = idx, count = parts.size)
                val last = segments.lastOrNull()
                if (last != null && last.part.key == part.key && last.part.note == part.note &&
                    isContiguous(last.end, node)
                ) {
                    last.end = node
                } else {
                    segments.add(MutableSegment(part, node, node))
                }
            }
        }

        return segments.map { segment ->
            val startValue = segmentStartValue(segment.start)
            val endValue = segmentEndValue(segment.end)
            val weekCount = ((endValue - startValue) * 100).roundToInt() / 100f
            StageSegment(
                key = segment.part.key,
                label = segment.part.label,
                short = segment.part.short,
                group = segment.part.group,
                note = segment.part.note,
                start = segment.start,
                end = segment.end,
                startWeek = segment.start.week,
                endWeek = segment.end.week,
                startValue = startValue,
                endValue = endValue,
                weekCount = weekCount,
                partial = segment.start.idx > 0 || segment.end.idx < segment.end.count - 1
            )
        }
    }

    private fun halfLabel(node: StageNode): String = when {
        node.count <= 1 -> ""
        node.idx == 0 -> "前半"
        node.idx == 1 -> "后半"
        else -> "第 ${node.idx + 1} 段"
    }

    /** 「第 12–19 周」「第 19 周后半」 */
    fun formatWeekRange(segment: StageSegment): String {
        if (segment.startWeek == segment.endWeek) {
            if (!segment.partial) return "第 ${segment.startWeek} 周"
            return "第 ${segment.startWeek} 周${halfLabel(segment.start)}"
        }
        if (!segment.partial) return "第 ${segment.startWeek}–${segment.endWeek} 周"
        if (segment.start.idx > 0) {
            return "第 ${segment.startWeek}–${segment.endWeek} 周（首周${halfLabel(segment.start)}）"
        }
        return "第 ${segment.startWeek}–${segment.endWeek} 周（末周${halfLabel(segment.end)}）"
    }

    /**
     * 一行摘要：「军训 3–4 周 · 理论教学 5–18 周 · 考试 19 周 · 假期 20–26 周」。
     * 阶段过多时保留开头与最后一个阶段，中间用省略号——放假时间往往在最后，不能丢。
     */
    fun summaryOf(segments: List<StageSegment>, max: Int = 4, includeIdle: Boolean = false): String {
        val items = segments.filter { includeIdle || it.group != TeachingProcessSymbols.IDLE_KEY }
        if (items.isEmpty()) return "全学期未安排"
        fun format(segment: StageSegment): String =
            "${segment.short} ${formatWeekRange(segment).replace(TRAILING_HALF_NOTE, "")}"

        if (items.size <= max) return items.joinToString(" · ") { format(it) }
        val head = items.take(max - 1).map { format(it) }
        return (head + "…" + format(items.last())).joinToString(" · ")
    }

    private val TRAILING_HALF_NOTE = Regex("（.*）$")

    /** 日期 → 教学周序号；不在任何一周内返回 null。 */
    fun weekIndexOn(weeks: List<ProcessWeek>, date: LocalDate = LocalDate.now()): Int? {
        if (weeks.isEmpty()) return null
        val iso = date.toString()
        return weeks.firstOrNull { iso >= it.monday && iso <= it.sunday }?.week
    }

    /** 指定周次的逐班级阶段统计，按班级数降序。 */
    fun snapshotAt(classes: List<ProcessClass>, patterns: List<String>, week: Int): List<StageCount> {
        val counts = LinkedHashMap<String, StageCount>()
        classes.forEach { item ->
            val cells = decodePattern(patterns.getOrNull(item.patternIndex))
            val parts = TeachingProcessSymbols.cellParts(cells.getOrNull(week - 1))
            val seen = HashSet<String>()
            parts.forEach { part ->
                if (!seen.add(part.key)) return@forEach
                val entry = counts[part.key]
                counts[part.key] = if (entry == null) {
                    StageCount(part.key, part.label, part.short, part.group, 1)
                } else {
                    entry.copy(count = entry.count + 1)
                }
            }
        }
        return counts.values.sortedByDescending { it.count }
    }

    /**
     * 当前阶段概览：现在处于哪一段、还有几周、下一段是什么、什么时候放假。
     * 视图顶部用它回答「我现在在什么阶段」。
     */
    fun stageInsights(segments: List<StageSegment>, week: Int, weekCount: Int): StageInsights {
        val active = if (week > 0) {
            segments.filter { week >= it.startWeek && week <= it.endWeek }
        } else {
            emptyList()
        }
        val current = active.firstOrNull { it.group != TeachingProcessSymbols.IDLE_KEY } ?: active.firstOrNull()
        val next = segments.firstOrNull {
            week > 0 && it.startWeek > week &&
                it.group != TeachingProcessSymbols.IDLE_KEY && it.key != "vacation"
        }
        val vacation = segments.firstOrNull { it.key == "vacation" && it.endWeek >= week }

        return StageInsights(
            current = current,
            currentRemaining = if (current != null) current.endWeek - week + 1 else 0,
            currentElapsed = if (current != null) week - current.startWeek + 1 else 0,
            next = next,
            nextIn = if (next != null) next.startWeek - week else 0,
            vacation = vacation,
            vacationIn = if (vacation != null) vacation.startWeek - week else 0,
            remaining = if (weekCount > 0) weekCount - week + 1 else 0
        )
    }

    /** 阶段相对当前周的位置：已结束 / 进行中 / 未开始。 */
    fun stageStatusOf(segment: StageSegment, week: Int): StageStatus = when {
        week <= 0 -> StageStatus.UNKNOWN
        week > segment.endWeek -> StageStatus.PAST
        week < segment.startWeek -> StageStatus.UPCOMING
        else -> StageStatus.CURRENT
    }

    /**
     * 按进程图案分组：进度完全相同的班级合并成一组。
     * 排序：我的班级所在组 → 班级数多 → 首个有效阶段更早。
     */
    fun groupByPattern(
        classes: List<ProcessClass>,
        patterns: List<String>,
        myClassName: String = ""
    ): List<PatternGroup> {
        val map = LinkedHashMap<Int, Pair<List<StageSegment>, MutableList<ProcessClass>>>()
        classes.forEach { item ->
            val existing = map[item.patternIndex]
            if (existing == null) {
                val cells = decodePattern(patterns.getOrNull(item.patternIndex))
                map[item.patternIndex] = buildSegments(cells) to mutableListOf(item)
            } else {
                existing.second.add(item)
            }
        }

        return map.map { (patternIndex, value) ->
            val segments = value.first
            val groupClasses = value.second
            val firstActive = segments.firstOrNull { it.group != TeachingProcessSymbols.IDLE_KEY }
            PatternGroup(
                patternIndex = patternIndex,
                segments = segments,
                classes = groupClasses,
                summary = summaryOf(segments),
                hasMine = myClassName.isNotEmpty() && groupClasses.any { it.name == myClassName },
                firstWeek = firstActive?.startWeek ?: Int.MAX_VALUE
            )
        }.sortedWith(
            compareByDescending<PatternGroup> { it.hasMine }
                .thenByDescending { it.classes.size }
                .thenBy { it.firstWeek }
        )
    }

    /** 阶段在列表中的周数文案：1 周 / 2 周半 / 半周。 */
    fun weekText(segment: StageSegment): String {
        val count = segment.weekCount
        if (count <= 0f) return ""
        if (count < 1f) return "半周"
        val whole = count.toInt()
        return if (count == whole.toFloat()) "$whole 周" else "$whole 周半"
    }

    /** 阶段覆盖的日期范围（如 8/31 – 9/06）；周次缺失时返回空串。 */
    fun dateRange(segment: StageSegment, weeks: List<ProcessWeek>): String {
        val start = weeks.getOrNull(segment.startWeek - 1) ?: return ""
        val end = weeks.getOrNull(segment.endWeek - 1) ?: return ""
        return "${shortDate(start.monday)} – ${shortDate(end.sunday)}"
    }

    /** `2026-08-31` → `8/31` */
    fun shortDate(iso: String): String {
        val parts = iso.split('-')
        if (parts.size != 3) return iso
        val month = parts[1].trimStart('0')
        return "$month/${parts[2]}"
    }
}
