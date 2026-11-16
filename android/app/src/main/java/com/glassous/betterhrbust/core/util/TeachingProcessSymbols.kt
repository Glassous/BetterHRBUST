package com.glassous.betterhrbust.core.util

import com.glassous.betterhrbust.core.model.StagePart

/**
 * 本科教学进程表的符号字典（与 Web 端 `teachingProcessSymbols.js` 同一套口径）。
 *
 * 教务处原表用单个字形表示整周安排（□理论教学、★军训、＝假期……），
 * 同一含义在不同年份会出现不同写法（Ä/◆ 都是电工电子实习，：/: 都是考试），
 * 「⊕/：」这样的斜杠周表示一周跨两个阶段。数据层只存原始字形，解释全部在这里。
 */

/** 一个语义阶段：展示名、短名、分组与细分色相（null 表示中性色）。 */
data class SymbolMeta(
    val label: String,
    val short: String,
    val group: String,
    val hue: Int?,
    val glyphs: List<String>
)

/** 阶段分组：色相为 null 时使用中性色。 */
data class SymbolGroupMeta(
    val label: String,
    val hue: Int?
)

data class LegendSymbol(val key: String, val label: String, val glyph: String)

data class LegendGroup(
    val group: String,
    val label: String,
    val hue: Int?,
    val symbols: List<LegendSymbol>
)

/** 单元格解析结果：parts 按周内先后顺序排列，unknown 记录未收录字形。 */
data class CellParse(
    val parts: List<StagePart>,
    val unknown: List<String>
)

object TeachingProcessSymbols {
    const val UNKNOWN_KEY = "unknown"
    const val IDLE_KEY = "idle"

    /** 原表出现过、但教务处图例从未给出含义的字形：不猜测，按原样展示。 */
    val undocumented: List<String> = listOf("♥", "❉", "[1]")

    val groups: Map<String, SymbolGroupMeta> = linkedMapOf(
        "theory" to SymbolGroupMeta("理论教学", 214),
        "design" to SymbolGroupMeta("设计与论文", 268),
        "practice" to SymbolGroupMeta("实习与实践", 158),
        "exam" to SymbolGroupMeta("考试", 352),
        "ceremony" to SymbolGroupMeta("入学与毕业教育", 38),
        "break" to SymbolGroupMeta("假期与节假日", null),
        "idle" to SymbolGroupMeta("常规教学", null),
        UNKNOWN_KEY to SymbolGroupMeta("未收录符号", null)
    )

    val groupOrder: List<String> = listOf(
        "theory", "design", "practice", "exam", "ceremony", "break", "idle", UNKNOWN_KEY
    )

    /**
     * 语义字典。glyphs 按匹配优先级排列：多字字形（`::`）必须排在单字（`:`）之前，
     * 因此匹配索引在初始化时按字形长度倒序排列。
     */
    val dict: Map<String, SymbolMeta> = linkedMapOf(
        "theory" to SymbolMeta("理论教学", "理论教学", "theory", null, listOf("□", "口")),
        "courseDesign" to SymbolMeta("课程设计", "课程设计", "design", null, listOf("※")),
        "thesis" to SymbolMeta("毕业设计", "毕业设计", "design", null, listOf("﹡", "＊", "*")),
        "yearDesign" to SymbolMeta("学年设计（论文）", "学年设计", "design", null, listOf("■")),
        "research" to SymbolMeta("科研训练", "科研训练", "practice", 152, listOf("::", "²", "§")),
        "cognition" to SymbolMeta("认识实习", "认识实习", "practice", 176, listOf("©")),
        "eePractice" to SymbolMeta("电工电子实习", "电工电子实习", "practice", 188, listOf("Ä", "◆")),
        "computer" to SymbolMeta("计算机实践", "计算机实践", "practice", 191, listOf("#", "﹟", "＃")),
        "metalwork" to SymbolMeta("金工实习/工程训练", "工程训练", "practice", 163, listOf("╳", "Ⅹ", "X")),
        "production" to SymbolMeta("生产实习", "生产实习", "practice", 145, listOf("△")),
        "enrollment" to SymbolMeta("入学教育", "入学教育", "ceremony", null, listOf("○")),
        "exam" to SymbolMeta("考试", "考试", "exam", null, listOf("：", ":")),
        "military" to SymbolMeta("军训", "军训", "ceremony", null, listOf("★", "☆")),
        "graduation" to SymbolMeta("毕业教育", "毕业教育", "ceremony", null, listOf("＋", "+")),
        "vacation" to SymbolMeta("假期", "假期", "break", null, listOf("＝", "=")),
        "idle" to SymbolMeta("常规教学", "常规教学", "idle", null, listOf("—", "－", "-", "–")),
        "sports" to SymbolMeta("运动会、节假日", "运动会/节假日", "break", null, listOf("∨", "V")),
        "offCampus" to SymbolMeta("外地教学", "外地教学", "practice", 190, listOf("⊙")),
        "skills" to SymbolMeta("技能训练", "技能训练", "practice", 184, listOf("◎")),
        "coursePractice" to SymbolMeta("课程实践", "课程实践", "practice", 168, listOf("▲")),
        "majorIntern" to SymbolMeta("专业实习", "专业实习", "practice", 158, listOf("◇")),
        "majorPractice" to SymbolMeta("专业实践", "专业实践", "practice", 180, listOf("∮")),
        "language" to SymbolMeta("语言实践", "语言实践", "practice", 170, listOf("¤")),
        "comprehensive" to SymbolMeta("综合实践（自主学习）", "综合实践", "practice", 172, listOf("⊕")),
        "innovation" to SymbolMeta("创新创业实践", "创新创业实践", "practice", 136, listOf("∞"))
    )

    /** 字形 → 语义 key，按字形长度倒序（多字优先）。 */
    private val glyphIndex: List<Pair<String, String>> = dict.entries
        .flatMap { (key, meta) -> meta.glyphs.map { glyph -> glyph to key } }
        .sortedByDescending { it.first.length }

    fun groupMeta(group: String): SymbolGroupMeta =
        groups[group] ?: groups.getValue(UNKNOWN_KEY)

    /** 阶段细分色相：字典里显式声明的优先，否则回落到分组色相。 */
    fun hueOf(group: String, key: String): Int? = dict[key]?.hue ?: groupMeta(group).hue

    /**
     * 解析单个单元格。
     * 返回的 parts 按该周内的先后顺序排列；空单元格视为「未安排」（常规教学）。
     */
    fun parseCell(raw: String?): CellParse {
        val text = normalizeCellText(raw)
        if (text.isEmpty()) return CellParse(emptyList(), emptyList())

        // 极少数历史表格会出现三段以上，合并多余部分，避免图例失真
        val rawSegments = text.split('/')
        val segments = mutableListOf<String>()
        for (segment in rawSegments) {
            if (segments.size >= 3) segments[2] = segments[2] + "/" + segment else segments.add(segment)
        }

        val parts = mutableListOf<StagePart>()
        val unknown = mutableListOf<String>()
        for (segment in segments) {
            val parsed = parseSegment(segment)
            if (parsed.isEmpty()) {
                parts.add(makePart(IDLE_KEY))
                continue
            }
            parsed.forEach { part ->
                if (part.key == UNKNOWN_KEY) unknown.add(part.label)
                parts.add(part)
            }
        }
        return CellParse(parts, unknown)
    }

    /** 某一周的有效分段：空单元格按「常规教学」处理，供时间轴渲染使用。 */
    fun cellParts(raw: String?): List<StagePart> {
        val parts = parseCell(raw).parts
        return parts.ifEmpty { listOf(makePart(IDLE_KEY)) }
    }

    /** 图例分组：把字典按组聚合，供「符号与阶段」卡片渲染。 */
    fun legendGroups(): List<LegendGroup> = groupOrder
        .filter { it != UNKNOWN_KEY }
        .map { group ->
            val meta = groupMeta(group)
            LegendGroup(
                group = group,
                label = meta.label,
                hue = meta.hue,
                symbols = dict.entries
                    .filter { it.value.group == group }
                    .map { (key, symbol) -> LegendSymbol(key, symbol.label, symbol.glyphs.first()) }
            )
        }

    /** 去掉空白与全角斜杠，保留其余字形（含 ² § 这类变体）。 */
    private fun normalizeCellText(raw: String?): String =
        (raw ?: "")
            .replace(WHITESPACE, "")
            .replace('／', '/')

    private val WHITESPACE = Regex("[\\s\\u00A0\\u3000]+")

    private fun makePart(key: String, note: String = ""): StagePart {
        if (key == UNKNOWN_KEY) {
            return StagePart(
                key = UNKNOWN_KEY,
                label = note.ifBlank { "未收录符号" },
                short = note.ifBlank { "未收录" },
                group = UNKNOWN_KEY,
                note = ""
            )
        }
        val meta = dict[key] ?: return makePart(UNKNOWN_KEY, note)
        return StagePart(
            key = key,
            label = meta.label,
            short = meta.short.ifBlank { meta.label },
            group = meta.group,
            note = note
        )
    }

    /** 解析单个斜杠分段：允许连写（□⊕、◎：），字形后的中文并入 note。 */
    private fun parseSegment(rawSegment: String): List<StagePart> {
        val text = rawSegment.replace(LEADING_PAREN, "").replace(TRAILING_PAREN, "")
        if (text.isEmpty()) return emptyList()

        val parts = mutableListOf<MutablePart>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isEmpty()) return
            if (parts.isNotEmpty()) {
                parts.last().note.append(buffer)
            } else {
                parts.add(MutablePart(UNKNOWN_KEY, StringBuilder(buffer)))
            }
            buffer.clear()
        }

        var index = 0
        while (index < text.length) {
            val matched = glyphIndex.firstOrNull { (glyph, _) ->
                text.regionMatches(index, glyph, 0, glyph.length)
            }
            if (matched == null) {
                buffer.append(text[index])
                index += 1
                continue
            }
            flush()
            parts.add(MutablePart(matched.second))
            index += matched.first.length
        }
        flush()
        return parts.map { makePart(it.key, it.note.toString()) }
    }

    private val LEADING_PAREN = Regex("^[（(]+")
    private val TRAILING_PAREN = Regex("[）)]+$")

    private class MutablePart(val key: String, val note: StringBuilder = StringBuilder())
}
