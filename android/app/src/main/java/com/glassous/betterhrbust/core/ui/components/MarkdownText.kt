package com.glassous.betterhrbust.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.core.parser.MarkdownBlock
import com.glassous.betterhrbust.core.parser.MarkdownParser
import com.glassous.betterhrbust.core.parser.MarkdownSpan

/**
 * Markdown 渲染（Compose，零依赖）
 *
 * 与 Web 端更新说明使用同一套语法范围；解析在 [MarkdownParser]，
 * 这里只负责把解析结果画出来。链接可点击（交给系统浏览器 / 默认处理）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    InlineMarkdown(
                        text = block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                is MarkdownBlock.Paragraph -> InlineMarkdown(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )

                is MarkdownBlock.BulletList -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(14.dp)
                            )
                            InlineMarkdown(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MarkdownBlock.OrderedList -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${block.start + index}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(22.dp)
                            )
                            InlineMarkdown(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 18.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    InlineMarkdown(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MarkdownBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }

                is MarkdownBlock.Table -> MarkdownTable(block)

                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            table.header.forEach { cell ->
                TableCell(
                    text = cell,
                    weight = 1f,
                    borderColor = borderColor,
                    header = true
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = borderColor)
            Row(modifier = Modifier.fillMaxWidth()) {
                table.header.indices.forEach { index ->
                    TableCell(
                        text = row.getOrElse(index) { "" },
                        weight = 1f,
                        borderColor = borderColor,
                        header = false
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    borderColor: Color,
    header: Boolean
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .border(0.25.dp, borderColor)
            .background(
                if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        InlineMarkdown(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 行内样式文本：粗体 / 斜体 / 删除线 / 行内代码 / 可点击链接 */
@Composable
private fun InlineMarkdown(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(text, linkColor, codeBackground, codeColor) {
        buildMarkdownAnnotated(text, linkColor, codeBackground, codeColor)
    }
    Text(
        text = annotated,
        style = style.copy(
            color = if (color == Color.Unspecified) style.color else color,
            fontWeight = fontWeight ?: style.fontWeight,
            lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else style.lineHeight
        ),
        modifier = modifier
    )
}

private fun buildMarkdownAnnotated(
    text: String,
    linkColor: Color,
    codeBackground: Color,
    codeColor: Color
): AnnotatedString = buildAnnotatedString {
    appendSpans(MarkdownParser.parseInline(text), linkColor, codeBackground, codeColor)
}

private fun AnnotatedString.Builder.appendSpans(
    spans: List<MarkdownSpan>,
    linkColor: Color,
    codeBackground: Color,
    codeColor: Color
) {
    spans.forEach { span ->
        val url = span.linkUrl
        val decoration = when {
            span.strike && url != null -> TextDecoration.combine(
                listOf(TextDecoration.LineThrough, TextDecoration.Underline)
            )
            span.strike -> TextDecoration.LineThrough
            url != null -> TextDecoration.Underline
            else -> null
        }
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = decoration,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            fontSize = if (span.code) 12.sp else TextUnit.Unspecified,
            color = when {
                url != null -> linkColor
                span.code -> codeColor
                else -> Color.Unspecified
            },
            background = if (span.code) codeBackground else Color.Unspecified
        )
        if (url != null) {
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(style = style)
                )
            ) {
                append(span.text)
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
