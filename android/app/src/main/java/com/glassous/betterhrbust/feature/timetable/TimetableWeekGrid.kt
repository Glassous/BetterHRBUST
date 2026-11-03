@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.glassous.betterhrbust.feature.timetable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import java.time.LocalDate

/** 时间列宽度：仅展示节次序号与起止时间，保持窄而清爽，把宽度让给课程块。 */
private val TimeColumnWidth = 38.dp

/** 课程块四周留白，避免相邻块粘连成一片。 */
private val BlockGap = 5.dp

/** 课程块左右内边距（列宽较窄，留白需克制）。 */
private val BlockHorizontalPadding = 4.dp

private val HeaderHeight = 48.dp

/** 大节模式：有课行 / 空行高度。 */
private val CombineRowHeight = 96.dp
private val CombineEmptyRowHeight = 30.dp

/** 小节模式：行高为大节的一半，空行同样压缩。 */
private val BaseRowHeight = 48.dp
private val BaseEmptyRowHeight = 15.dp

/**
 * 网格行布局：行高与累计顶部偏移（空行压缩），时间列与课程块共用同一套几何信息。
 */
private class GridRows(
    val slots: List<TimeSlot>,
    val heights: List<Dp>,
    val tops: List<Dp>,
    val occupied: List<Boolean>,
    val totalHeight: Dp
)

@Composable
private fun rememberGridRows(mode: SectionMode, occupiedSections: Set<Int>): GridRows =
    remember(mode, occupiedSections) {
        val slots = slotsOf(mode)
        val occupiedRows = slots.map { slot ->
            if (mode == SectionMode.COMBINE) {
                slot.period in occupiedSections
            } else {
                sectionOfBasePeriod(slot.period) in occupiedSections
            }
        }
        val heights = occupiedRows.map { occupied ->
            when {
                mode == SectionMode.COMBINE -> if (occupied) CombineRowHeight else CombineEmptyRowHeight
                occupied -> BaseRowHeight
                else -> BaseEmptyRowHeight
            }
        }
        val tops = ArrayList<Dp>(heights.size)
        var cursor = 0.dp
        heights.forEach { height ->
            tops += cursor
            cursor += height
        }
        GridRows(
            slots = slots,
            heights = heights,
            tops = tops,
            occupied = occupiedRows,
            totalHeight = cursor
        )
    }

/** 课程块在日列中的纵向位置与高度（大节模式占一行，小节模式跨两行）。 */
private fun blockBounds(sectionIndex: Int, rows: GridRows, mode: SectionMode): Pair<Dp, Dp> {
    if (mode == SectionMode.COMBINE) {
        val index = (sectionIndex - 1).coerceIn(0, rows.slots.lastIndex)
        return rows.tops[index] to (rows.heights[index] - BlockGap)
    }
    val range = baseRowRangeOf(sectionIndex)
    val startIndex = (range.first - 1).coerceIn(0, rows.slots.lastIndex)
    val endIndex = (range.last - 1).coerceIn(0, rows.slots.lastIndex)
    val top = rows.tops[startIndex]
    val bottom = rows.tops[endIndex] + rows.heights[endIndex]
    return top to (bottom - top - BlockGap)
}

/**
 * 课表网格：左侧窄时间列 + 右侧日列（任意数量，日视图传单个日）。
 *
 * - 列宽自适应：时间列固定窄宽，其余宽度按日列数量等分，7 列在手机窄屏下同样铺满屏宽、无需横向滚动；
 * - 行高随「该小节是否有课」压缩，空行只占很矮的一条，整体更紧凑；
 * - 课程块在日列内按节次绝对定位（[Modifier.offset]），小节模式下大节占两行；
 * - 今天列高亮由调用方通过 [highlightToday] 决定（周视图在当前周时高亮，日视图不叠加背景）；
 * - 非当前教学周的课程统一走中性灰弱化（[CourseColorPalette.cardColors] 的 muted）。
 *
 * 纵向滚动由调用方提供（外层 `verticalScroll`）。
 *
 * @param days 需要展示的星期序号（1..7），日视图传单元素列表
 * @param page 当前页码，作为共享元素 key 的一部分（相邻页会同时组合，避免同 key 冲突）
 * @param highlightToday 是否以主色底 + 圆点标注「今天」所在列
 * @param dense 紧凑模式（周视图）：块内展示课程名 / 地点 / 教师
 * @param selectedDetail 当前展开详情的课程；与之相同的课表块退场，交给详情卡片（共享元素过渡）
 * @param sharedTransitionScope 页面级共享元素作用域，课程块与详情卡片在同一作用域内配对过渡
 */
@Composable
internal fun TimetableGrid(
    days: List<Int>,
    cells: List<TimetableCell>,
    selectedWeek: Int,
    weekStart: LocalDate?,
    page: Int,
    highlightToday: Boolean,
    sectionMode: SectionMode,
    isDark: Boolean,
    dense: Boolean,
    selectedDetail: TimetableCell?,
    sharedTransitionScope: SharedTransitionScope,
    onCourseClick: (TimetableCell) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = rememberGridRows(
        mode = sectionMode,
        occupiedSections = remember(cells) { cells.mapTo(HashSet()) { it.sectionIndex } }
    )
    // 星期 → 节次 → 该格课程，避免渲染时逐列过滤
    val cellsByDaySection = remember(cells) {
        cells.groupBy { it.day }.mapValues { (_, dayCells) -> dayCells.groupBy { it.sectionIndex } }
    }
    val todayDay = remember { LocalDate.now().dayOfWeek.value }

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
    // 今天列高亮走主题主色（普通色），不再使用绿色强调
    val todayAccent = MaterialTheme.colorScheme.primary
    val todayTint = todayAccent.copy(alpha = if (isDark) 0.12f else 0.08f)

    BoxWithConstraints(modifier = modifier) {
        // 除时间列外的宽度按日列等分：手机窄屏同样铺满屏宽，不做横向滚动
        val dayColumnWidth = (maxWidth - TimeColumnWidth) / days.size.coerceAtLeast(1)

        Column(modifier = Modifier.fillMaxWidth()) {
            // 表头：星期（今天列浅色底 + 圆点）
            Row(modifier = Modifier.height(HeaderHeight)) {
                Box(modifier = Modifier.width(TimeColumnWidth).fillMaxHeight())
                days.forEach { day ->
                    val isToday = highlightToday && day == todayDay
                    Box(
                        modifier = Modifier
                            .width(dayColumnWidth)
                            .fillMaxHeight()
                            .background(if (isToday) todayTint else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = DayNames[day - 1],
                                fontSize = 13.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                color = if (isToday) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = weekStart?.plusDays((day - 1).toLong())?.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) ?: "待同步",
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Row {
                // 时间列：节次序号 + 起止时间，空行压缩为一行序号
                Column(modifier = Modifier.width(TimeColumnWidth)) {
                    rows.slots.forEachIndexed { index, slot ->
                        val occupied = rows.occupied[index]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rows.heights[index]),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${slot.period}",
                                    fontSize = if (occupied) 14.sp else 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (occupied) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    // 压缩后的空行很矮，行高需同步收紧避免文字被裁切
                                    lineHeight = if (occupied) 16.sp else 11.sp
                                )
                                if (occupied) {
                                    Text(
                                        text = slot.start,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = slot.end,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                // 日列区：网格线、斑马纹与今天列底色统一由一次 drawBehind 绘制
                Box(
                    modifier = Modifier.drawBehind {
                        val dayWidth = dayColumnWidth.toPx()
                        val todayIndex = days.indexOf(todayDay)
                        if (highlightToday && todayIndex >= 0) {
                            drawRect(
                                color = todayTint,
                                topLeft = Offset(dayWidth * todayIndex, 0f),
                                size = Size(dayWidth, size.height)
                            )
                        }
                        rows.heights.forEachIndexed { index, height ->
                            val top = rows.tops[index].toPx()
                            if (index % 2 == 1) {
                                drawRect(
                                    color = bandColor,
                                    topLeft = Offset(0f, top),
                                    size = Size(size.width, height.toPx())
                                )
                            }
                        }
                        val stroke = 1.dp.toPx()
                        rows.tops.drop(1).forEach { top ->
                            val y = top.toPx()
                            drawLine(
                                color = dividerColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = stroke
                            )
                        }
                        for (column in 1 until days.size) {
                            val x = dayWidth * column
                            drawLine(
                                color = dividerColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = stroke
                            )
                        }
                    }
                ) {
                    Row {
                        days.forEach { day ->
                            Box(
                                modifier = Modifier
                                    .width(dayColumnWidth)
                                    .height(rows.totalHeight)
                            ) {
                                // 同一格可能排多门课（如不同周次），纵向等分展示
                                cellsByDaySection[day].orEmpty()
                                    .forEach { (section, courseCells) ->
                                        val (top, blockHeight) = blockBounds(section, rows, sectionMode)
                                        if (blockHeight <= 0.dp) return@forEach
                                        val stacked = courseCells.size > 1
                                        Column(
                                            modifier = Modifier
                                                .offset(y = top)
                                                .padding(horizontal = BlockGap / 2)
                                                .fillMaxWidth()
                                                .height(blockHeight),
                                            verticalArrangement = Arrangement.spacedBy(
                                                if (stacked) 3.dp else 0.dp
                                            )
                                        ) {
                                            courseCells.forEach { cell ->
                                                CourseBlock(
                                                    cell = cell,
                                                    page = page,
                                                    selectedWeek = selectedWeek,
                                                    isDark = isDark,
                                                    dense = dense,
                                                    // 被展开详情的课表块退场，交给详情卡片（共享元素过渡）
                                                    isSharedHidden = selectedDetail != null && selectedDetail.id == cell.id,
                                                    sharedTransitionScope = sharedTransitionScope,
                                                    // 同格并排多门课时高度减半，课程名减少行数并省略教师
                                                    nameMaxLines = when {
                                                        stacked -> 2
                                                        dense -> 3
                                                        else -> 2
                                                    },
                                                    showTeacher = !stacked,
                                                    onClick = { onCourseClick(cell) },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                )
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 课程块：色块 + 课程名 + 地点 + 教师（日视图另显示周次）。
 * 非当前教学周的课程走中性灰，并保留「非本周」提示。
 *
 * 每个块的背景、课程名、地点、教师都注册为共享元素：点击展开详情时该块退场，
 * 内容以共享元素过渡到详情卡片；关闭时反向过渡回来。
 */
@Composable
private fun CourseBlock(
    cell: TimetableCell,
    page: Int,
    selectedWeek: Int,
    isDark: Boolean,
    dense: Boolean,
    isSharedHidden: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    nameMaxLines: Int,
    showTeacher: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val key = remember(cell) {
        CourseColorPalette.keyOf(
            courseName = cell.courseName,
            courseSeq = cell.courseSeq,
            courseId = cell.courseId,
            id = cell.id
        )
    }
    val active = remember(cell, selectedWeek) {
        AcademicParsers.isCourseActiveInWeek(cell.weeks, selectedWeek)
    }
    val colors = remember(key, isDark, active) {
        CourseColorPalette.cardColors(key = key, dark = isDark, muted = !active)
    }
    val shape = RoundedCornerShape(10.dp)

    // 非本周的块要多带一行「非本周」标记，课程名相应少一行，避免整块内容溢出被裁切
    val nameLines = if (active) nameMaxLines else (nameMaxLines - 1).coerceAtLeast(1)


    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "coursePress")

    Box(modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        sharedTransitionScope.run {
            AnimatedVisibility(
                visible = !isSharedHidden,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    // 卡片必须铺满所在格子：块的背景色对整格负责，而不是只包住文字
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "bg")),
                            animatedVisibilityScope = this@AnimatedVisibility
                        )
                        .clip(shape)
                        .background(colors.container)
                        .border(1.dp, colors.border, shape)
                        .clickable(interactionSource = interactions, indication = null, onClick = onClick)
                        .padding(
                            horizontal = if (dense) BlockHorizontalPadding else 10.dp,
                            vertical = if (dense) 4.dp else 6.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = cell.courseName,
                        fontSize = if (dense) 10.sp else 13.sp,
                        lineHeight = if (dense) 13.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.content,
                        maxLines = nameLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "name")),
                            animatedVisibilityScope = this@AnimatedVisibility
                        )
                    )
                    Text(
                        text = cell.location.ifEmpty { "待定" },
                        fontSize = if (dense) 9.sp else 11.sp,
                        lineHeight = if (dense) 11.sp else 14.sp,
                        color = colors.content.copy(alpha = 0.85f),
                        maxLines = if (dense) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "location")),
                            animatedVisibilityScope = this@AnimatedVisibility
                        )
                    )
                    if (showTeacher) {
                        Text(
                            text = cell.teacher.ifEmpty { "—" },
                            fontSize = if (dense) 9.sp else 11.sp,
                            lineHeight = if (dense) 11.sp else 14.sp,
                            color = colors.content.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "teacher")),
                                animatedVisibilityScope = this@AnimatedVisibility
                            )
                        )
                    }
                    if (!dense && cell.weeks.isNotEmpty()) {
                        Text(
                            text = cell.weeks,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = colors.content.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!active) {
                        Text(
                            text = "非本周",
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.accent
                        )
                    }
                }
            }
        }
    }
}
