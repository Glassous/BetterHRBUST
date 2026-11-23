package com.glassous.betterhrbust.core.util

import com.glassous.betterhrbust.core.model.CustomCourseEntry
import com.glassous.betterhrbust.core.model.RescheduleSourceMatch
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableOverridesPayload
import com.glassous.betterhrbust.core.model.TimetableRescheduleEntry
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.feature.timetable.BaseSlots
import com.glassous.betterhrbust.feature.timetable.CombineSlots
import com.glassous.betterhrbust.feature.timetable.DayNames
import com.glassous.betterhrbust.feature.timetable.SectionMode
import com.glassous.betterhrbust.feature.timetable.TimeSlot
import com.glassous.betterhrbust.feature.timetable.slotRangeText
import java.time.LocalTime
import java.util.UUID
import kotlin.math.abs

/**
 * 课程表本地覆盖与增强纯逻辑（单周临时调课 + 手动实验/自定义课程）。
 *
 * 与 Web 端 `web/src/utils/timetableOverrides.js` 保持完全一致的数据口径：
 * - 不修改教务同步的原始课表数据，以运行时投影叠加；
 * - 单周临时调课与手动实验课仅作用于目标教学周；
 * - 支持自定义起止时间（如 `14:00 - 16:30`）与按节次选择。
 */
object TimetableOverrides {

    data class CustomScheduleAnchor(
        val combineSection: Int,
        val baseSection: Int
    )

    data class ResolvedCustomSchedule(
        val combineSection: Int,
        val baseSection: Int,
        val startTime: String,
        val endTime: String,
        val customTimeText: String,
        val slotNote: String
    )

    fun newId(prefix: String): String = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

    fun parseTimeToMinutes(hhmm: String?): Int? {
        val trimmed = hhmm?.trim().orEmpty()
        val match = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(trimmed) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun formatMinutes(minutes: Int): String {
        val clamped = minutes.coerceIn(0, 23 * 60 + 59)
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }

    fun formatDaySectionLabel(day: Int, period: Int, sectionMode: SectionMode): String {
        val dayName = DayNames.getOrElse((day - 1).coerceIn(0, DayNames.lastIndex)) { "周一" }
        val unit = if (sectionMode == SectionMode.BASE) "节" else "大节"
        return "$dayName 第 $period $unit"
    }

    private fun findAnchorPeriod(slots: List<TimeSlot>, startMinutes: Int, endMinutes: Int): Int {
        var bestOverlapPeriod: Int? = null
        var bestOverlap = 0
        for (slot in slots) {
            val s = parseTimeToMinutes(slot.start) ?: continue
            val e = parseTimeToMinutes(slot.end) ?: continue
            val overlap = minOf(endMinutes, e) - maxOf(startMinutes, s)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestOverlapPeriod = slot.period
            }
        }
        if (bestOverlapPeriod != null) return bestOverlapPeriod

        val targetMid = (startMinutes + endMinutes) / 2.0
        var nearestPeriod = slots.firstOrNull()?.period ?: 1
        var nearestDistance = Double.MAX_VALUE
        for (slot in slots) {
            val s = parseTimeToMinutes(slot.start) ?: continue
            val e = parseTimeToMinutes(slot.end) ?: continue
            val dist = abs(targetMid - (s + e) / 2.0)
            if (dist < nearestDistance) {
                nearestDistance = dist
                nearestPeriod = slot.period
            }
        }
        return nearestPeriod
    }

    fun resolveCustomTimeAnchor(startTime: String, endTime: String): CustomScheduleAnchor {
        val startMin = parseTimeToMinutes(startTime)
        val endMin = parseTimeToMinutes(endTime)
        if (startMin == null || endMin == null || endMin <= startMin) {
            return CustomScheduleAnchor(1, 1)
        }
        return CustomScheduleAnchor(
            combineSection = findAnchorPeriod(CombineSlots, startMin, endMin),
            baseSection = findAnchorPeriod(BaseSlots, startMin, endMin)
        )
    }

    fun resolveCustomCourseSchedule(entry: CustomCourseEntry): ResolvedCustomSchedule {
        if (entry.timeMode == "custom") {
            val start = entry.startTime.trim()
            val end = entry.endTime.trim()
            val anchor = resolveCustomTimeAnchor(start, end)
            val text = if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else ""
            return ResolvedCustomSchedule(
                combineSection = anchor.combineSection,
                baseSection = anchor.baseSection,
                startTime = start,
                endTime = end,
                customTimeText = text,
                slotNote = if (text.isNotEmpty()) "自定义时段 ($text)" else "自定义时段"
            )
        }

        if (entry.slotType == "base") {
            val startPeriod = entry.slotPeriod.coerceIn(1, BaseSlots.size)
            val span = entry.slotSpan.coerceIn(1, 4)
            val endPeriod = (startPeriod + span - 1).coerceAtMost(BaseSlots.size)
            val startTime = BaseSlots[startPeriod - 1].start
            val endTime = BaseSlots[endPeriod - 1].end
            val isStandardCombinePair = span == 2 && startPeriod % 2 == 1
            return ResolvedCustomSchedule(
                combineSection = (startPeriod + 1) / 2,
                baseSection = startPeriod,
                startTime = startTime,
                endTime = endTime,
                customTimeText = if (isStandardCombinePair) "" else "$startTime - $endTime",
                slotNote = if (span > 1) "第 $startPeriod-$endPeriod 节" else "第 $startPeriod 节"
            )
        }

        val period = entry.slotPeriod.coerceIn(1, CombineSlots.size)
        val slot = CombineSlots[period - 1]
        return ResolvedCustomSchedule(
            combineSection = period,
            baseSection = (period - 1) * 2 + 1,
            startTime = slot.start,
            endTime = slot.end,
            customTimeText = "",
            slotNote = "第 $period 大节"
        )
    }

    fun normalizeCustomCourse(raw: CustomCourseEntry): CustomCourseEntry {
        val now = System.currentTimeMillis()
        val category = if (raw.category == "custom") "custom" else "lab"
        val timeMode = if (raw.timeMode == "custom") "custom" else "slot"
        val slotType = if (raw.slotType == "base") "base" else "combine"
        val maxPeriod = if (slotType == "base") BaseSlots.size else CombineSlots.size
        return raw.copy(
            id = raw.id.ifBlank { newId("custom") },
            category = category,
            courseName = raw.courseName.trim(),
            location = raw.location.trim(),
            teacher = raw.teacher.trim(),
            week = raw.week.coerceIn(1, 26),
            day = raw.day.coerceIn(1, 7),
            timeMode = timeMode,
            slotType = slotType,
            slotPeriod = raw.slotPeriod.coerceIn(1, maxPeriod),
            slotSpan = if (slotType == "base") raw.slotSpan.coerceIn(1, 4) else 1,
            startTime = raw.startTime.trim(),
            endTime = raw.endTime.trim(),
            note = raw.note.trim(),
            createdAt = if (raw.createdAt > 0L) raw.createdAt else now,
            updatedAt = now
        )
    }

    fun customCourseToCell(entry: CustomCourseEntry, sectionMode: SectionMode): TimetableCell {
        val schedule = resolveCustomCourseSchedule(entry)
        val sectionIndex = if (sectionMode == SectionMode.BASE) schedule.baseSection else schedule.combineSection
        val categoryLabel = if (entry.category == "lab") "实验" else "自定义"
        return TimetableCell(
            id = entry.id,
            courseId = entry.id,
            day = entry.day.coerceIn(1, 7),
            sectionIndex = sectionIndex,
            sectionLabel = schedule.slotNote,
            courseName = entry.courseName,
            courseSeq = "",
            location = entry.location,
            teacher = entry.teacher,
            weeks = "第${entry.week}周",
            hoursType = categoryLabel,
            credit = null,
            rawLines = emptyList(),
            isCustomCourse = true,
            customCategory = entry.category,
            customTimeText = schedule.customTimeText,
            customStartTime = schedule.startTime,
            customEndTime = schedule.endTime,
            note = entry.note
        )
    }

    fun buildRescheduleSourceMatch(
        cell: TimetableCell,
        existingReschedule: TimetableRescheduleEntry? = null
    ): RescheduleSourceMatch {
        if (existingReschedule != null) return existingReschedule.sourceMatch
        val combineSec = cell.sectionIndex.coerceIn(1, CombineSlots.size)
        return RescheduleSourceMatch(
            cellId = cell.id,
            courseId = cell.courseId,
            courseName = cell.courseName,
            courseSeq = cell.courseSeq,
            originDay = cell.day.coerceIn(1, 7),
            originCombineSection = combineSec,
            originBaseSections = listOf((combineSec - 1) * 2 + 1, combineSec * 2),
            originLocation = cell.location,
            originTeacher = cell.teacher
        )
    }

    fun isDifferentRescheduleTarget(
        sourceMatch: RescheduleSourceMatch,
        targetDay: Int,
        targetPeriod: Int,
        sectionMode: SectionMode
    ): Boolean {
        val day = targetDay.coerceIn(1, 7)
        val period = targetPeriod.coerceIn(1, if (sectionMode == SectionMode.BASE) BaseSlots.size else CombineSlots.size)
        if (day != sourceMatch.originDay) return true
        return if (sectionMode == SectionMode.BASE) {
            if (sourceMatch.originBaseSections.isNotEmpty()) period !in sourceMatch.originBaseSections
            else (period + 1) / 2 != sourceMatch.originCombineSection
        } else {
            period != sourceMatch.originCombineSection
        }
    }

    fun normalizeReschedule(raw: TimetableRescheduleEntry): TimetableRescheduleEntry {
        val now = System.currentTimeMillis()
        val mode = if (raw.targetSectionMode == "base") "base" else "combine"
        val targetCombine = if (mode == "base") {
            (raw.targetBaseSection.coerceIn(1, BaseSlots.size) + 1) / 2
        } else {
            raw.targetCombineSection.coerceIn(1, CombineSlots.size)
        }
        val targetBase = if (mode == "base") {
            raw.targetBaseSection.coerceIn(1, BaseSlots.size)
        } else {
            (targetCombine - 1) * 2 + 1
        }
        return raw.copy(
            id = raw.id.ifBlank { newId("resched") },
            week = raw.week.coerceIn(1, 26),
            targetDay = raw.targetDay.coerceIn(1, 7),
            targetSectionMode = mode,
            targetCombineSection = targetCombine,
            targetBaseSection = targetBase,
            location = raw.location.trim(),
            teacher = raw.teacher.trim(),
            note = raw.note.trim(),
            createdAt = if (raw.createdAt > 0L) raw.createdAt else now,
            updatedAt = now
        )
    }

    fun matchesRescheduleSource(cell: TimetableCell, sourceMatch: RescheduleSourceMatch): Boolean {
        if (cell.day != sourceMatch.originDay) return false
        if (cell.sectionIndex != sourceMatch.originCombineSection) return false
        if (sourceMatch.cellId.isNotEmpty() && cell.id.isNotEmpty()) {
            return cell.id == sourceMatch.cellId && cell.courseName == sourceMatch.courseName
        }
        if (cell.courseName != sourceMatch.courseName) return false
        if (sourceMatch.courseSeq.isNotEmpty() && cell.courseSeq.isNotEmpty()) {
            return cell.courseSeq == sourceMatch.courseSeq
        }
        if (sourceMatch.courseId.isNotEmpty() && cell.courseId.isNotEmpty()) {
            return cell.courseId == sourceMatch.courseId
        }
        return true
    }

    /**
     * 计算指定教学周与节次模式下的最终课表单元格列表。
     *
     * 注：Android 课表底层数据固定为大节粒度（`sectionIndex` 1..6），
     * 展示为小节模式时由 [TimetableWeekGrid] 把大节跨两行绘制；对于调入小节视图或自定义节次的单元格，
     * 通过 `customStartTime`/`customEndTime` 与 `sectionIndex`（仍按大节 1..6 锚定或自定义时间文本）配合渲染。
     */
    fun buildEffectiveTimetableCells(
        rawCells: List<TimetableCell>,
        week: Int,
        sectionMode: SectionMode = SectionMode.COMBINE,
        overrides: TimetableOverridesPayload
    ): List<TimetableCell> {
        val weekReschedules = overrides.reschedules.filter { it.week == week }
        val weekCustomCourses = overrides.customCourses.filter { it.week == week }

        val triggeredReschedules = LinkedHashMap<String, TimetableRescheduleEntry>()
        val matchedOriginCells = HashMap<String, TimetableCell>()
        val result = ArrayList<TimetableCell>(rawCells.size + weekReschedules.size + weekCustomCourses.size)

        for (cell in rawCells) {
            val activeThisWeek = AcademicParsers.isCourseActiveInWeek(cell.weeks, week)
            val matched = if (activeThisWeek) {
                weekReschedules.firstOrNull { matchesRescheduleSource(cell, it.sourceMatch) }
            } else {
                null
            }
            if (matched == null) {
                result.add(cell)
                continue
            }

            if (!triggeredReschedules.containsKey(matched.id)) {
                triggeredReschedules[matched.id] = matched
                matchedOriginCells[matched.id] = cell
                val targetPeriod = if (sectionMode == SectionMode.BASE) {
                    matched.targetBaseSection
                } else {
                    matched.targetCombineSection
                }
                result.add(
                    cell.copy(
                        id = "${cell.id.ifEmpty { cell.courseName }}__moved_out__${matched.id}",
                        isMovedOut = true,
                        rescheduleId = matched.id,
                        movedToLabel = formatDaySectionLabel(matched.targetDay, targetPeriod, sectionMode)
                    )
                )
            }
        }

        for (record in triggeredReschedules.values) {
            val originCell = matchedOriginCells[record.id]
            val targetSectionCombine = record.targetCombineSection.coerceIn(1, CombineSlots.size)
            val originPeriod = if (sectionMode == SectionMode.BASE) {
                record.sourceMatch.originBaseSections.firstOrNull()
                    ?: ((record.sourceMatch.originCombineSection - 1) * 2 + 1)
            } else {
                record.sourceMatch.originCombineSection
            }
            val customBaseRange = if (record.targetSectionMode == "base") {
                val baseSlot = BaseSlots.getOrNull(record.targetBaseSection - 1)
                if (baseSlot != null) slotRangeText(baseSlot) else ""
            } else {
                ""
            }
            val baseSlot = if (record.targetSectionMode == "base") {
                BaseSlots.getOrNull(record.targetBaseSection - 1)
            } else null

            result.add(
                TimetableCell(
                    id = "reschedule_${record.id}",
                    courseId = originCell?.courseId ?: record.sourceMatch.courseId,
                    day = record.targetDay,
                    sectionIndex = targetSectionCombine,
                    sectionLabel = if (record.targetSectionMode == "base") {
                        "第 ${record.targetBaseSection} 节"
                    } else {
                        "第 $targetSectionCombine 大节"
                    },
                    courseName = originCell?.courseName ?: record.sourceMatch.courseName,
                    courseSeq = originCell?.courseSeq ?: record.sourceMatch.courseSeq,
                    location = record.location,
                    teacher = record.teacher.ifBlank { originCell?.teacher ?: record.sourceMatch.originTeacher },
                    weeks = "第${record.week}周",
                    hoursType = originCell?.hoursType ?: "临时调课",
                    credit = originCell?.credit,
                    rawLines = emptyList(),
                    customTimeText = customBaseRange,
                    customStartTime = baseSlot?.start.orEmpty(),
                    customEndTime = baseSlot?.end.orEmpty(),
                    note = record.note,
                    isRescheduled = true,
                    rescheduleId = record.id,
                    originSummary = "${formatDaySectionLabel(record.sourceMatch.originDay, originPeriod, sectionMode)} · ${record.sourceMatch.originLocation.ifBlank { "待定教室" }}"
                )
            )
        }

        for (custom in weekCustomCourses) {
            // Android 网格坐标始终按 sectionIndex (1..6) 定位；若在小节模式且是单小节，通过 customStartTime/EndTime 展示精确时间
            result.add(customCourseToCell(custom, SectionMode.COMBINE))
        }

        return result
    }

    /**
     * 解析单元格的有效起止时间（优先使用自定义时间 / 单小节精确时间，回落到大节时间）。
     */
    fun effectiveTimeRangeOf(cell: TimetableCell): Pair<LocalTime, LocalTime>? {
        if (cell.customStartTime.isNotBlank() && cell.customEndTime.isNotBlank()) {
            val s = runCatching { LocalTime.parse(cell.customStartTime.padStart(5, '0')) }.getOrNull()
            val e = runCatching { LocalTime.parse(cell.customEndTime.padStart(5, '0')) }.getOrNull()
            if (s != null && e != null) return s to e
        }
        val slot = CombineSlots.getOrNull(cell.sectionIndex - 1) ?: return null
        val s = runCatching { LocalTime.parse(slot.start) }.getOrNull() ?: return null
        val e = runCatching { LocalTime.parse(slot.end) }.getOrNull() ?: return null
        return s to e
    }
}
