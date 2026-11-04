package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.parser.MarkdownBlock
import com.glassous.betterhrbust.core.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本更新说明的 Markdown 解析：与 Web 端 web/scripts/test-markdown.mjs 覆盖同一批语法 */
class MarkdownParserTest {

    @Test
    fun headingsAndParagraphsAreSplit() {
        val blocks = MarkdownParser.parse("# 一级\n\n正文第一行\n正文第二行")
        assertEquals(2, blocks.size)

        val heading = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("一级", heading.text)

        val paragraph = blocks[1] as MarkdownBlock.Paragraph
        assertTrue(paragraph.text.contains("\n"))
    }

    @Test
    fun listsGroupConsecutiveItems() {
        val bullet = MarkdownParser.parse("- 第一项\n- 第二项").single() as MarkdownBlock.BulletList
        assertEquals(listOf("第一项", "第二项"), bullet.items)

        val ordered = MarkdownParser.parse("3. 第三\n4. 第四").single() as MarkdownBlock.OrderedList
        assertEquals(3, ordered.start)
        assertEquals(listOf("第三", "第四"), ordered.items)
    }

    @Test
    fun codeFenceKeepsRawContent() {
        val block = MarkdownParser.parse("```kotlin\nval a = 1 < 2\n```").single() as MarkdownBlock.Code
        assertEquals("kotlin", block.language)
        assertEquals("val a = 1 < 2", block.code)
    }

    @Test
    fun tableIsParsed() {
        val block = MarkdownParser.parse("| 端 | 版本 |\n| --- | --- |\n| Android | 1.3.1 |")
            .single() as MarkdownBlock.Table
        assertEquals(listOf("端", "版本"), block.header)
        assertEquals(listOf("Android", "1.3.1"), block.rows.single())
    }

    @Test
    fun quoteAndDividerAreRecognised() {
        val blocks = MarkdownParser.parse("> 注意事项\n\n---")
        assertEquals(2, blocks.size)
        assertEquals("注意事项", (blocks[0] as MarkdownBlock.Quote).text)
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }

    @Test
    fun inlineStylesAreParsed() {
        val spans = MarkdownParser.parseInline("**粗体** 与 `code` 与 *斜体*")

        assertTrue(spans.any { it.bold && it.text == "粗体" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.italic && it.text == "斜体" })
    }

    @Test
    fun inlineCodeKeepsAsterisks() {
        val spans = MarkdownParser.parseInline("`a*b*c`")
        assertEquals(1, spans.size)
        assertTrue(spans.single().code)
        assertEquals("a*b*c", spans.single().text)
    }

    @Test
    fun linksKeepSafeUrlsOnly() {
        val safe = MarkdownParser.parseInline("[发布页](https://github.com/Glassous/BetterHRBUST/releases)")
        assertEquals("https://github.com/Glassous/BetterHRBUST/releases", safe.first().linkUrl)

        val unsafe = MarkdownParser.parseInline("[点我](javascript:alert(1))")
        assertEquals("点我", unsafe.first().text)
        assertEquals(null, unsafe.first().linkUrl)
    }
}
