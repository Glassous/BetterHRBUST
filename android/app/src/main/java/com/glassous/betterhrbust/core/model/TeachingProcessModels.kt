package com.glassous.betterhrbust.core.model

/**
 * 本科教学进程（shared/teaching-process.json）的数据模型。
 *
 * 数据层只保留教务处原表的「班级 × 教学周」字形网格；字形含义与阶段合并
 * 分别由 `TeachingProcessSymbols` 与 `TeachingProcessCalculator` 解释。
 */

/** 某教学周的周一 / 周日（ISO 日期，如 `2026-08-31`）。 */
data class ProcessWeek(
    val week: Int,
    val monday: String,
    val sunday: String
)

/** 进程表中的一个班级：`[班级名, 周学时, patterns 索引]`。 */
data class ProcessClass(
    val name: String,
    val hours: Int,
    val patternIndex: Int
)

/** 一个校区下的全部班级。 */
data class ProcessCampus(
    val name: String,
    val classes: List<ProcessClass>
)

/** 一个学期的进程表：周次表头、图例原文与各校区班级。 */
data class ProcessSemester(
    val id: String,
    val label: String,
    val academicYear: String,
    val term: Int,
    val weeks: List<ProcessWeek>,
    val legend: List<String>,
    val campuses: List<ProcessCampus>
) {
    val weekCount: Int get() = weeks.size
}

/** 整份教学进程快照。 */
data class TeachingProcessData(
    val schemaVersion: Int,
    val source: String,
    val sourceUpdatedAt: String,
    val updatedAt: String,
    val patterns: List<String>,
    val semesters: List<ProcessSemester>,
    val warnings: List<String>
) {
    /** 学期按学年学期倒序，最新的在前。 */
    val semestersDesc: List<ProcessSemester> = semesters.sortedWith(
        compareByDescending<ProcessSemester> { it.academicYear.take(4).toIntOrNull() ?: 0 }
            .thenByDescending { it.term }
    )
}

/** 单个字形解释后的阶段片段（斜杠周内一周可跨多个阶段）。 */
data class StagePart(
    val key: String,
    val label: String,
    val short: String,
    val group: String,
    val note: String = ""
)

/** 图案中某个阶段片段所在的周与半周位置。 */
data class StageNode(
    val week: Int,
    val idx: Int,
    val count: Int
)

/** 合并后的阶段区间：连续且含义相同的单元格归为一段。 */
data class StageSegment(
    val key: String,
    val label: String,
    val short: String,
    val group: String,
    val note: String,
    val start: StageNode,
    val end: StageNode,
    val startWeek: Int,
    val endWeek: Int,
    val startValue: Float,
    val endValue: Float,
    val weekCount: Float,
    val partial: Boolean
)

/** 某一周全校区的阶段分布条目。 */
data class StageCount(
    val key: String,
    val label: String,
    val short: String,
    val group: String,
    val count: Int
)

/** 进度完全相同的班级合并后的一组。 */
data class PatternGroup(
    val patternIndex: Int,
    val segments: List<StageSegment>,
    val classes: List<ProcessClass>,
    val summary: String,
    val hasMine: Boolean,
    val firstWeek: Int
)

/** 当前阶段概览：现在处于哪一段、还剩几周、下一段与放假时间。 */
data class StageInsights(
    val current: StageSegment?,
    val currentRemaining: Int,
    val currentElapsed: Int,
    val next: StageSegment?,
    val nextIn: Int,
    val vacation: StageSegment?,
    val vacationIn: Int,
    val remaining: Int
)

/** 阶段相对当前周的位置。 */
enum class StageStatus { UNKNOWN, PAST, CURRENT, UPCOMING }

/** 学籍档案推测出的班级候选。 */
data class ClassMatch(
    val name: String,
    val campus: String,
    val score: Int,
    val margin: Int,
    val confident: Boolean
)
