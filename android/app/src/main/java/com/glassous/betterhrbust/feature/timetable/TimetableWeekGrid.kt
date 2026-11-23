@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.glassous.betterhrbust.feature.timetable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import java.time.LocalDate
import kotlin.math.roundToInt

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
    rescheduleMode: Boolean = false,
    rescheduleSource: TimetableCell? = null,
    isValidRescheduleTarget: (Int, Int) -> Boolean = { _, _ -> false },
    onQuickAdd: (day: Int, period: Int) -> Unit = { _, _ -> },
    onRescheduleCellTap: (day: Int, period: Int) -> Unit = { _, _ -> },
    onRescheduleDragDrop: (source: TimetableCell, targetDay: Int, targetPeriod: Int) -> Unit = { _, _, _ -> },
    onMovedOutClick: (TimetableCell) -> Unit = {},
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
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var draggedCell by remember { mutableStateOf<TimetableCell?>(null) }
    var dragFingerInGrid by remember { mutableStateOf<Offset?>(null) }
    var dragCardTopLeft by remember { mutableStateOf(Offset.Zero) }
    var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var dragBlockHeight by remember { mutableStateOf(80.dp) }
    var dragHoverTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var armedQuickAddCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(armedQuickAddCell, rescheduleMode) {
        if (rescheduleMode) {
            armedQuickAddCell = null
        } else if (armedQuickAddCell != null) {
            kotlinx.coroutines.delay(3000L)
            armedQuickAddCell = null
        }
    }

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
    // 今天列高亮走主题主色（普通色），不再使用绿色强调
    val todayAccent = MaterialTheme.colorScheme.primary
    val todayTint = todayAccent.copy(alpha = if (isDark) 0.12f else 0.08f)

    BoxWithConstraints(modifier = modifier) {
        // 除时间列外的宽度按日列等分：手机窄屏同样铺满屏宽，不做横向滚动
        val dayColumnWidth = (maxWidth - TimeColumnWidth) / days.size.coerceAtLeast(1)
        val dayWidthPx = with(density) { dayColumnWidth.toPx() }
        val rowBoundsPx = remember(rows, density) {
            with(density) {
                rows.slots.indices.map { i ->
                    val top = rows.tops[i].toPx()
                    top to (top + rows.heights[i].toPx())
                }
            }
        }

        fun hitTestTarget(posInGrid: Offset): Pair<Int, Int>? {
            if (posInGrid.x < 0f || posInGrid.y < 0f || dayWidthPx <= 0f) return null
            val colIdx = (posInGrid.x / dayWidthPx).toInt()
            if (colIdx !in days.indices) return null
            val rowIdx = rowBoundsPx.indexOfFirst { (top, bottom) -> posInGrid.y in top..bottom }
            if (rowIdx < 0) return null
            return days[colIdx] to rows.slots[rowIdx].period
        }

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
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isToday) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = weekStart?.plusDays((day - 1).toLong())?.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) ?: "待同步",
                                style = MaterialTheme.typography.labelSmall,
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
                                    style = if (occupied) {
                                        MaterialTheme.typography.titleSmall
                                    } else {
                                        MaterialTheme.typography.labelSmall
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = if (occupied) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    // 压缩后的空行很矮，行高需同步收紧避免文字被裁切
                                    lineHeight = if (occupied) 16.sp else 11.sp
                                )
                                if (occupied) {
                                    Text(
                                        text = slot.start,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = slot.end,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 日列区：网格线、斑马纹与今天列底色统一由一次 drawBehind 绘制
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { gridCoordinates = it }
                        .drawBehind {
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
                                // 底层网格槽位：正常模式下空白槽可点击「+」快捷加课；调课模式或拖拽中高亮目标槽
                                rows.slots.forEachIndexed { rowIdx, slot ->
                                    val rowTop = rows.tops[rowIdx]
                                    val rowHeight = rows.heights[rowIdx]
                                    val slotSection = if (sectionMode == SectionMode.BASE) {
                                        sectionOfBasePeriod(slot.period)
                                    } else {
                                        slot.period
                                    }
                                    val slotHasCourses = cellsByDaySection[day]?.get(slotSection)?.isNotEmpty() == true
                                    val isDragHover = dragHoverTarget?.first == day && dragHoverTarget?.second == slot.period
                                    val isTapTargetActive = rescheduleMode && rescheduleSource != null
                                    val slotValidForDrag = draggedCell?.let { src ->
                                        val match = com.glassous.betterhrbust.core.util.TimetableOverrides.buildRescheduleSourceMatch(src)
                                        com.glassous.betterhrbust.core.util.TimetableOverrides.isDifferentRescheduleTarget(
                                            match,
                                            day,
                                            slot.period,
                                            sectionMode
                                        )
                                    } ?: false
                                    val slotValidForTap = isTapTargetActive && isValidRescheduleTarget(day, slot.period)
                                    val isQuickAddArmed = !rescheduleMode && !slotHasCourses && armedQuickAddCell == (day to slot.period)

                                    val highlightColor = when {
                                        isDragHover && slotValidForDrag -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                        isDragHover && !slotValidForDrag -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                        isTapTargetActive && slotValidForTap && !slotHasCourses -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                        isQuickAddArmed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                                        else -> Color.Transparent
                                    }
                                    val borderColor = when {
                                        isDragHover && slotValidForDrag -> MaterialTheme.colorScheme.primary
                                        isDragHover && !slotValidForDrag -> MaterialTheme.colorScheme.error
                                        isTapTargetActive && slotValidForTap && !slotHasCourses -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        isQuickAddArmed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else -> Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .offset(y = rowTop)
                                            .fillMaxWidth()
                                            .height(rowHeight)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(highlightColor)
                                            .then(
                                                if (borderColor != Color.Transparent) {
                                                    Modifier.border(
                                                        width = if (isDragHover) 1.5.dp else 1.dp,
                                                        color = borderColor,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                } else Modifier
                                            )
                                            .clickable(enabled = !rescheduleMode || isTapTargetActive) {
                                                if (isTapTargetActive) {
                                                    onRescheduleCellTap(day, slot.period)
                                                } else if (!rescheduleMode) {
                                                    if (armedQuickAddCell == (day to slot.period)) {
                                                        armedQuickAddCell = null
                                                        onQuickAdd(day, slot.period)
                                                    } else {
                                                        armedQuickAddCell = day to slot.period
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isDragHover) {
                                            Text(
                                                text = if (slotValidForDrag) "松手调至此处" else "原位置",
                                                fontSize = if (dense) 9.sp else 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (slotValidForDrag) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        } else if (isQuickAddArmed) {
                                            if (dense) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "点击添加实验或课程",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Add,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = "添加实验 / 课程",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

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
                                                val isSourceSelected = rescheduleMode && rescheduleSource?.id == cell.id && rescheduleSource.courseName == cell.courseName
                                                val isBeingDragged = draggedCell?.id == cell.id && draggedCell?.courseName == cell.courseName
                                                val activeThisWeek = AcademicParsers.isCourseActiveInWeek(cell.weeks, selectedWeek)

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
                                                    rescheduleMode = rescheduleMode,
                                                    selectedForReschedule = isSourceSelected,
                                                    isBeingDragged = isBeingDragged,
                                                    onClick = {
                                                        if (cell.isMovedOut) {
                                                            if (rescheduleMode && rescheduleSource != null) {
                                                                val targetPeriod = if (sectionMode == SectionMode.BASE) {
                                                                    baseRowRangeOf(cell.sectionIndex).first
                                                                } else cell.sectionIndex
                                                                onRescheduleCellTap(day, targetPeriod)
                                                            } else {
                                                                onMovedOutClick(cell)
                                                            }
                                                        } else {
                                                            onCourseClick(cell)
                                                        }
                                                    },
                                                    onDragEnabled = rescheduleMode && activeThisWeek && !cell.isMovedOut,
                                                    onDragStart = { cardCoords, localOffset ->
                                                        val gridCoords = gridCoordinates ?: return@CourseBlock
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        val topLeft = gridCoords.localPositionOf(cardCoords, Offset.Zero)
                                                        draggedCell = cell
                                                        dragCardTopLeft = topLeft
                                                        dragGrabOffset = localOffset
                                                        dragBlockHeight = if (stacked) (blockHeight / courseCells.size).coerceAtLeast(48.dp) else blockHeight
                                                        val finger = topLeft + localOffset
                                                        dragFingerInGrid = finger
                                                        dragHoverTarget = hitTestTarget(finger)
                                                    },
                                                    onDrag = { dragAmount ->
                                                        val nextFinger = (dragFingerInGrid ?: return@CourseBlock) + dragAmount
                                                        dragFingerInGrid = nextFinger
                                                        dragHoverTarget = hitTestTarget(nextFinger)
                                                    },
                                                    onDragEnd = {
                                                        val src = draggedCell
                                                        val target = dragHoverTarget
                                                        draggedCell = null
                                                        dragFingerInGrid = null
                                                        dragHoverTarget = null
                                                        if (src != null && target != null) {
                                                            onRescheduleDragDrop(src, target.first, target.second)
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        draggedCell = null
                                                        dragFingerInGrid = null
                                                        dragHoverTarget = null
                                                    },
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

                    // 拖拽调课时的随指悬浮预览卡
                    val floatingCell = draggedCell
                    val finger = dragFingerInGrid
                    if (floatingCell != null && finger != null) {
                        val previewPos = finger - dragGrabOffset
                        val key = CourseColorPalette.keyOf(
                            floatingCell.courseName,
                            floatingCell.courseSeq,
                            floatingCell.courseId,
                            floatingCell.id
                        )
                        val colors = CourseColorPalette.cardColors(key = key, dark = isDark, muted = false)
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            modifier = Modifier
                                .zIndex(20f)
                                .offset { IntOffset(previewPos.x.roundToInt(), previewPos.y.roundToInt()) }
                                .width(dayColumnWidth - BlockGap)
                                .height(dragBlockHeight)
                                .graphicsLayer {
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    alpha = 0.94f
                                }
                                .shadow(14.dp, shape)
                                .clip(shape)
                                .background(colors.container)
                                .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                .padding(horizontal = if (dense) BlockHorizontalPadding else 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = floatingCell.courseName,
                                fontSize = if (dense) 10.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.content,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = floatingCell.location.ifEmpty { "待定" },
                                fontSize = if (dense) 9.sp else 11.sp,
                                color = colors.content.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
    rescheduleMode: Boolean,
    selectedForReschedule: Boolean,
    isBeingDragged: Boolean,
    onClick: () -> Unit,
    onDragEnabled: Boolean,
    onDragStart: (LayoutCoordinates, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (cell.isMovedOut) {
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f))
                .drawBehind {
                    drawRoundRect(
                        color = outlineColor,
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )
                    )
                }
                .clickable(onClick = onClick)
                .padding(horizontal = if (dense) BlockHorizontalPadding else 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "已调出",
                fontSize = if (dense) 8.sp else 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = cell.courseName,
                fontSize = if (dense) 9.sp else 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "→ ${cell.movedToLabel}",
                fontSize = if (dense) 8.sp else 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

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
    val shape = RoundedCornerShape(12.dp)

    // 非本周的块要多带一行「非本周」标记，课程名相应少一行，避免整块内容溢出被裁切
    val hasBadge = cell.isCustomCourse || cell.isRescheduled
    val nameLines = when {
        !active -> (nameMaxLines - 1).coerceAtLeast(1)
        hasBadge && dense -> (nameMaxLines - 1).coerceAtLeast(1)
        else -> nameMaxLines
    }

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "coursePress")
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { cardCoords = it }
            .alpha(if (isBeingDragged) 0.32f else if (rescheduleMode && !active) 0.45f else 1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
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
                        .border(
                            width = if (selectedForReschedule) 2.dp else 1.dp,
                            color = if (selectedForReschedule) MaterialTheme.colorScheme.primary else colors.border,
                            shape = shape
                        )
                        .pointerInput(onDragEnabled, cell.id, cell.courseName) {
                            if (!onDragEnabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val coords = cardCoords ?: return@detectDragGesturesAfterLongPress
                                    onDragStart(coords, offset)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel
                            )
                        }
                        .clickable(interactionSource = interactions, indication = null, onClick = onClick)
                        .padding(
                            horizontal = if (dense) BlockHorizontalPadding else 10.dp,
                            vertical = if (dense) 4.dp else 6.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (hasBadge) {
                        val badgeLabel = when {
                            cell.customCategory == "lab" -> "实验"
                            cell.isCustomCourse -> "自定"
                            cell.isRescheduled -> "调课"
                            else -> ""
                        }
                        if (badgeLabel.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = AppShapes.Pill,
                                    color = colors.content.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        fontSize = if (dense) 8.sp else 10.sp,
                                        lineHeight = if (dense) 10.sp else 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.content,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                if (cell.customTimeText.isNotEmpty() && !dense) {
                                    Text(
                                        text = cell.customTimeText,
                                        fontSize = 10.sp,
                                        color = colors.content.copy(alpha = 0.8f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
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
                        text = if (dense && cell.customTimeText.isNotEmpty()) {
                            "${cell.customTimeText} · ${cell.location.ifEmpty { "待定" }}"
                        } else {
                            cell.location.ifEmpty { "待定" }
                        },
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
