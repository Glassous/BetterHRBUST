package com.glassous.betterhrbust.core.util

import com.glassous.betterhrbust.core.model.CurrentCourseItem
import com.glassous.betterhrbust.core.model.ScoreItem

enum class MyCourseFilter(val label: String) {
    ALL("全部"),
    CURRENT("本学期在修"),
    REQUIRED("必修课"),
    ELECTIVE("选修课")
}

data class MyCourseItem(
    val id: String,
    val name: String,
    val courseSeq: String = "",
    val property: String = "必修",
    val group: String = "",
    val credit: Double = 0.0,
    val hours: Int = 0,
    val teacher: String = "",
    val examWay: String = "",
    val examType: String = "",
    val timeAndPlace: String = "",
    val semester: String = "",
    val latestScore: String = "",
    val passed: Boolean? = null,
    val pending: Boolean = false,
    val isCurrent: Boolean = false
)

/**
 * 「我的课程名录」合并与筛选逻辑（与 Web CourseView.vue 口径一致）：
 * 1. 本学期在修课程（currcourse.jsdo）优先展示并标记 `isCurrent = true`；
 * 2. 历史成绩课程（studentOwnScore.do）先去重再按学期倒序追加，不与本学期重复；
 * 3. 若本学期课程在成绩中也有记录（如重修），可补齐学时或参考课组信息。
 */
object MyCourseCatalog {

    private fun courseKey(id: String, name: String): String =
        id.trim().ifEmpty { name.trim() }

    fun build(
        currentCourses: List<CurrentCourseItem>,
        scores: List<ScoreItem>
    ): List<MyCourseItem> {
        val dedupedScores = GpaCalculator.dedupeScores(scores)
            .map { it.item }
            .sortedByDescending { GpaCalculator.semesterSortKey(it.year, it.term) }

        val scoreByKey = LinkedHashMap<String, ScoreItem>()
        dedupedScores.forEach { score ->
            val key = courseKey(score.courseId, score.courseName)
            if (key.isNotEmpty() && !scoreByKey.containsKey(key)) {
                scoreByKey[key] = score
            }
        }

        val result = mutableListOf<MyCourseItem>()
        val seen = HashSet<String>()

        currentCourses.forEach { c ->
            val key = courseKey(c.courseId, c.courseName)
            if (key.isEmpty() || !seen.add(key)) return@forEach
            val matchedScore = scoreByKey[key]
            result.add(
                MyCourseItem(
                    id = c.courseId.trim(),
                    name = c.courseName.trim(),
                    courseSeq = c.courseSeq.trim(),
                    property = c.property.trim().ifEmpty { matchedScore?.property?.trim().orEmpty().ifEmpty { "必修" } },
                    group = matchedScore?.courseGroup?.trim().orEmpty().ifEmpty { "本学期排课" },
                    credit = if (c.credit > 0.0) c.credit else (matchedScore?.credit ?: 0.0),
                    hours = matchedScore?.hours ?: 0,
                    teacher = c.teacher.trim(),
                    examWay = c.examWay.trim(),
                    examType = c.examType.trim(),
                    timeAndPlace = c.timeAndPlace.trim(),
                    isCurrent = true
                )
            )
        }

        dedupedScores.forEach { s ->
            val key = courseKey(s.courseId, s.courseName)
            if (key.isEmpty() || !seen.add(key)) return@forEach
            result.add(
                MyCourseItem(
                    id = s.courseId.trim(),
                    name = s.courseName.trim(),
                    courseSeq = s.courseSeq.trim(),
                    property = s.property.trim().ifEmpty { "必修" },
                    group = s.courseGroup.trim(),
                    credit = s.credit,
                    hours = s.hours,
                    teacher = "",
                    examType = s.examType.trim(),
                    semester = GpaCalculator.semesterLabel(s.year, s.term),
                    latestScore = s.score.trim(),
                    passed = s.passed,
                    pending = s.pending,
                    isCurrent = false
                )
            )
        }

        return result
    }

    fun filter(
        items: List<MyCourseItem>,
        query: String,
        filter: MyCourseFilter
    ): List<MyCourseItem> {
        val q = query.trim()
        return items.filter { item ->
            val matchFilter = when (filter) {
                MyCourseFilter.ALL -> true
                MyCourseFilter.CURRENT -> item.isCurrent
                MyCourseFilter.REQUIRED -> GpaCalculator.isRequired(item.property)
                MyCourseFilter.ELECTIVE -> !GpaCalculator.isRequired(item.property) &&
                    item.property.contains("选")
            }
            if (!matchFilter) return@filter false
            if (q.isEmpty()) return@filter true

            item.name.contains(q, ignoreCase = true) ||
                item.id.contains(q, ignoreCase = true) ||
                item.teacher.contains(q, ignoreCase = true) ||
                item.group.contains(q, ignoreCase = true)
        }
    }
}
