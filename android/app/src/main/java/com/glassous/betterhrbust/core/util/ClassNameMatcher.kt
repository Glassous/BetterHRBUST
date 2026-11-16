package com.glassous.betterhrbust.core.util

import com.glassous.betterhrbust.core.model.ClassMatch
import com.glassous.betterhrbust.core.model.PersonalInfo
import java.text.Normalizer

/**
 * 「我的班级」推测（与 Web 端 `classMatcher.js` 同一套评分）。
 *
 * 教务档案里的班级名与教学进程表的缩写（材型23、电气(大珩班)26-1）并不一致，
 * 这里用可解释的加权评分给出候选，但只作为建议——必须由用户确认才落库。
 */
object ClassNameMatcher {

    private val WHITESPACE = Regex("[\\s\\u00A0\\u3000]+")
    private val SEPARATORS = Regex("[·、,，]")
    private val CLASS_NAME = Regex("^([^\\d]*)(\\d{2})(.*)$")
    private val YEAR = Regex("(19|20)\\d{2}")
    private val TWO_DIGITS = Regex("\\d{2}")
    private val SHORT_NUMBERS = Regex("\\d{1,2}")
    private val DIGITS = Regex("\\d+")
    private val HAN = Regex("[\\u4e00-\\u9fff]")

    fun normalizeClassName(raw: String?): String =
        Normalizer.normalize(raw ?: "", Normalizer.Form.NFKC)
            .replace(WHITESPACE, "")
            .replace(SEPARATORS, "")
            .lowercase()

    /** 材型23（1-4）→ abbr=材型, grade=23, numbers=[1,4] */
    fun parseClassName(raw: String?): ParsedClass {
        val text = normalizeClassName(raw)
        val matched = CLASS_NAME.find(text) ?: return ParsedClass(text, "", emptyList())
        return ParsedClass(
            abbr = matched.groupValues[1],
            grade = matched.groupValues[2],
            numbers = DIGITS.findAll(matched.groupValues[3]).mapNotNull { it.value.toIntOrNull() }.toList()
        )
    }

    /** 档案里的年级：优先 grade 字段（2023 → 23），其次班级名里的两位年级。 */
    fun profileGrade(profile: PersonalInfo?): String {
        val raw = profile?.grade.orEmpty()
        YEAR.find(raw)?.let { return it.value.substring(2) }
        val text = trimYear(normalizeClassName(profile?.className))
        return TWO_DIGITS.find(text)?.value.orEmpty()
    }

    /** 档案班级里的班号：材料成型及控制工程23-1班 → 1（排除年级数字）。 */
    fun profileClassNumber(profile: PersonalInfo?): Int {
        val text = trimYear(normalizeClassName(profile?.className))
        val grade = profileGrade(profile)
        val numbers = SHORT_NUMBERS.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        val values = numbers.filterIndexed { index, value ->
            !(index == 0 && grade.isNotEmpty() && value == grade.toIntOrNull())
        }
        return values.lastOrNull() ?: 0
    }

    private fun trimYear(text: String): String = YEAR.replace(text, "")

    private fun commonPrefixLength(left: String, right: String): Int {
        var index = 0
        while (index < left.length && index < right.length && left[index] == right[index]) index += 1
        return index
    }

    private fun sharedCharCount(left: String, right: String): Int {
        val pool = right.toSet()
        return left.toSet().count { pool.contains(it) }
    }

    /**
     * 候选班级评分（0–100）：年级最强，其次是专业名公共前缀与班号区间。
     * 缩写里出现专业名中不存在的字（材化 vs 材料成型）要扣分。
     */
    fun scoreClass(profile: PersonalInfo?, name: String): Int {
        val className = normalizeClassName(profile?.className)
        val target = normalizeClassName(name)
        if (className.isNotEmpty() && className == target) return 100

        val candidate = parseClassName(name)
        val majorKey = normalizeClassName(profile?.major)
        val grade = profileGrade(profile)
        val prefix = commonPrefixLength(candidate.abbr, majorKey)
        val overlap = sharedCharCount(candidate.abbr, majorKey)
        val abbrChars = candidate.abbr.toSet().filter { HAN.matches(it.toString()) }
        val missing = if (majorKey.isNotEmpty()) abbrChars.count { !majorKey.contains(it) } else 0

        var score = 0
        if (grade.isNotEmpty() && candidate.grade.isNotEmpty()) {
            score += if (grade == candidate.grade) 35 else -40
        }
        score += minOf(25, prefix * 5)

        // 专业名毫无关联时，年级与班号巧合不足以说明是同一个班
        if ((prefix > 0 || overlap > 0) && candidate.numbers.isNotEmpty()) {
            val classNumber = profileClassNumber(profile)
            if (classNumber > 0) {
                val from = candidate.numbers.first()
                val to = candidate.numbers.getOrNull(1) ?: from
                score += if (classNumber in from..to) 20 else -5
            }
        }

        score += minOf(10, overlap * 2)
        score -= minOf(18, missing * 9)
        return score.coerceIn(0, 100)
    }

    /**
     * 在候选里挑出最可能的班级。
     * confident 需要同时满足「分数够高」与「明显领先第二名」，否则只提示用户手动选择。
     *
     * @param candidates 班级名 + 校区名
     */
    fun pickBestClass(
        profile: PersonalInfo?,
        candidates: List<Pair<String, String>>
    ): ClassMatch? {
        if (profile == null || candidates.isEmpty()) return null
        if (profile.className.isBlank() && profile.major.isBlank()) return null
        val scored = candidates
            .map { (name, campus) -> Triple(name, campus, scoreClass(profile, name)) }
            .sortedByDescending { it.third }
        val best = scored.first()
        val second = scored.getOrNull(1)
        val margin = best.third - (second?.third ?: 0)
        return ClassMatch(
            name = best.first,
            campus = best.second,
            score = best.third,
            margin = margin,
            confident = best.third >= 60 && margin >= 10
        )
    }

    data class ParsedClass(
        val abbr: String,
        val grade: String,
        val numbers: List<Int>
    )
}
