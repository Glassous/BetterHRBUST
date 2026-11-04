package com.glassous.betterhrbust.core.parser

/**
 * 轻量 Markdown 解析（零依赖，与 Web 端 `web/src/utils/markdown.js` 同口径）
 *
 * 只服务于「版本更新说明」这类发布日志，覆盖常用语法：
 * 标题、段落、无序 / 有序列表、引用、代码块、表格、分割线，
 * 以及行内的粗体 / 斜体 / 删除线 / 行内代码 / 链接。
 *
 * 不支持的扩展语法：嵌套列表、任务列表、脚注、原始 HTML。
 * 原始 HTML 会被当作纯文本处理（Compose 渲染本身也不执行 HTML）。
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<String>, val start: Int = 1) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/** 行内片段：同一段文字上的样式可以叠加（如粗体里嵌行内代码） */
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null
)

object MarkdownParser {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val FENCE = Regex("""^\s*(```|~~~)\s*([\w+#-]*)\s*$""")
    private val HR = Regex("""^\s{0,3}(?:-\s*){3,}$|^\s{0,3}(?:\*\s*){3,}$|^\s{0,3}(?:_\s*){3,}$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val TABLE_ROW = Regex("""^\s*\|(.+)\|\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")

    /**
     * 行内语法：命名分组便于区分类型；顺序必须「代码 → 图片 → 链接 → 自动链接 →
     * 粗体 → 删除线 → 斜体」，否则 ** 会被拆成两个 *、[x](y) 会先被强调规则误吃。
     */
    private val INLINE = Regex(
        """`(?<code>[^`]+)`""" +
            """|!\[(?<alt>[^\]]*)]\((?<imgUrl>[^)\s]+)\)""" +
            """|\[(?<label>[^\]]+)]\((?<linkUrl>[^)\s]+)\)""" +
            """|<(?<auto>(?:https?://|mailto:)[^\s<>]+)>""" +
            """|\*\*(?<bold>.+?)\*\*""" +
            """|__(?<boldAlt>.+?)__""" +
            """|~~(?<strike>.+?)~~""" +
            """|\*(?<italic>.+?)\*""" +
            """|(?<![\w])_(?<italicAlt>.+?)_(?![\w])"""
    )

    /** 只放行 http(s) 与 mailto，其它协议（javascript:、data: 等）一律丢弃 */
    fun sanitizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return if (Regex("""^(https?://|mailto:)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else null
    }

    fun parse(markdown: String): List<MarkdownBlock> {
        val source = markdown
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(HTML_COMMENT, "")
            .removePrefix("\uFEFF")
        if (source.isBlank()) return emptyList()

        val lines = source.split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }

        while (index < lines.size) {
            val line = lines[index]

            if (line.isBlank()) {
                flushParagraph()
                index += 1
                continue
            }

            val fence = FENCE.find(line)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2]
                val buffer = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith(marker)) {
                    buffer += lines[index]
                    index += 1
                }
                index += 1 // 跳过结束标记
                blocks += MarkdownBlock.Code(language, buffer.joinToString("\n"))
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                flushParagraph()
                blocks += MarkdownBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim()
                )
                index += 1
                continue
            }

            if (HR.matches(line)) {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                index += 1
                continue
            }

            if (TABLE_ROW.matches(line) && index + 1 < lines.size && TABLE_SEPARATOR.matches(lines[index + 1])) {
                flushParagraph()
                val header = splitTableRow(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && TABLE_ROW.matches(lines[index])) {
                    rows += splitTableRow(lines[index])
                    index += 1
                }
                blocks += MarkdownBlock.Table(header, rows)
                continue
            }

            if (QUOTE.matches(line)) {
                flushParagraph()
                val buffer = mutableListOf<String>()
                while (index < lines.size && QUOTE.matches(lines[index])) {
                    buffer += QUOTE.find(lines[index])?.groupValues?.get(1).orEmpty()
                    index += 1
                }
                blocks += MarkdownBlock.Quote(buffer.joinToString("\n").trim())
                continue
            }

            if (BULLET.matches(line)) {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && BULLET.matches(lines[index])) {
                    items += BULLET.find(lines[index])?.groupValues?.get(1).orEmpty()
                    index += 1
                }
                blocks += MarkdownBlock.BulletList(items)
                continue
            }

            val ordered = ORDERED.find(line)
            if (ordered != null) {
                flushParagraph()
                val items = mutableListOf<String>()
                var start = ordered.groupValues[1].toIntOrNull() ?: 1
                while (index < lines.size) {
                    val match = ORDERED.find(lines[index]) ?: break
                    if (items.isEmpty()) start = match.groupValues[1].toIntOrNull() ?: 1
                    items += match.groupValues[2]
                    index += 1
                }
                blocks += MarkdownBlock.OrderedList(items, start)
                continue
            }

            paragraph += line
            index += 1
        }

        flushParagraph()
        return blocks
    }

    private fun splitTableRow(line: String): List<String> {
        return line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }

    /**
     * 解析行内样式，返回带样式的片段列表。
     * @param depth 递归层数上限：避免恶意 / 畸形输入导致无限递归
     */
    fun parseInline(text: String, depth: Int = 0): List<MarkdownSpan> {
        if (text.isEmpty()) return emptyList()
        if (depth > 3) return listOf(MarkdownSpan(text))

        val spans = mutableListOf<MarkdownSpan>()
        var lastIndex = 0

        INLINE.findAll(text).forEach { match ->
            if (match.range.first > lastIndex) {
                spans += MarkdownSpan(text.substring(lastIndex, match.range.first))
            }
            val groups = match.groups
            when {
                groups["code"] != null -> spans += MarkdownSpan(groups["code"]!!.value, code = true)

                groups["alt"] != null -> {
                    val url = sanitizeUrl(groups["imgUrl"]?.value)
                    val label = groups["alt"]!!.value.ifBlank { url.orEmpty() }
                    spans += MarkdownSpan(label, linkUrl = url)
                }

                groups["label"] != null -> {
                    spans += MarkdownSpan(
                        groups["label"]!!.value,
                        linkUrl = sanitizeUrl(groups["linkUrl"]?.value)
                    )
                }

                groups["auto"] != null -> {
                    val url = groups["auto"]!!.value
                    spans += MarkdownSpan(url, linkUrl = sanitizeUrl(url))
                }

                groups["bold"] != null -> {
                    spans += parseInline(groups["bold"]!!.value, depth + 1).map { it.copy(bold = true) }
                }

                groups["boldAlt"] != null -> {
                    spans += parseInline(groups["boldAlt"]!!.value, depth + 1).map { it.copy(bold = true) }
                }

                groups["strike"] != null -> {
                    spans += parseInline(groups["strike"]!!.value, depth + 1).map { it.copy(strike = true) }
                }

                groups["italic"] != null -> {
                    spans += parseInline(groups["italic"]!!.value, depth + 1).map { it.copy(italic = true) }
                }

                groups["italicAlt"] != null -> {
                    spans += parseInline(groups["italicAlt"]!!.value, depth + 1).map { it.copy(italic = true) }
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            spans += MarkdownSpan(text.substring(lastIndex))
        }
        return spans
    }
}
