package com.glassous.betterhrbust.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.glassous.betterhrbust.feature.timetable.CombineSlots
import com.glassous.betterhrbust.feature.timetable.DayNames

/** 标题右侧「第 N 周」占宽（宽版一行布局用）。 */
private val DayTitleTailWidth = 70.dp

/** 窄版（2×3）标题、页脚与列表间距。 */
private val DayNarrowHeaderHeight = 16.dp
private val DayNarrowFooterHeight = 14.dp
private val DayNarrowRowGap = 3.dp
private val DayNarrowRowMaxHeight = 46.dp
private val DayNarrowWeekWidth = 36.dp

/** 标题与网格之间的间隔。 */
private val DayHeaderGap = 6.dp

/** 内容面板的内边距。 */
private val DayWidePanelPadding = 6.dp

/** 面板最小高度：保证 6 个大节仍能铺开。 */
private val DayPanelMinHeight = 60.dp

/** 时间列宽度。 */
private val DayWideTimeColumnWidth = 32.dp

/** 课程格四周留白。 */
private val DayCellInset = 1.dp

/** 同一大节多门课时的块间距。 */
private val DayStackGap = 2.dp

/** 页脚更新时刻格式（与「双日课表」页脚一致）。 */
private val DayFooterTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * 日视图课表小部件（窄版，桌面占位 2 列 × 3 行）。
 *
 * 2×3 空间有限，不再套用「时间列 + 6 个大节」的网格，改为紧凑的今日剩余课程列表：
 * 不显示节次序号，课程块占满整行宽度，最多 3 条，避免数字列挤压课程名。
 */
class TimetableDayNarrowWidget : GlanceAppWidget() {

    /** 固定尺寸小部件：按宿主给出的精确尺寸组合，行高据此计算。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = TimetableWidgetLoader.load()
        val strings = WidgetStrings(context)
        val action = openTimetableAction(context)
        provideContent {
            NarrowDayContent(data = data, strings = strings, action = action)
        }
    }
}

/** 日视图小部件（窄版）的系统入口（Manifest 中声明）。 */
class TimetableDayNarrowWidgetReceiver : TimetableGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimetableDayNarrowWidget()
}

/**
 * 日视图课表小部件（宽版，桌面占位 4 列 × 4 行）。
 *
 * 与窄版共用同一套网格，仅课程块信息量不同：宽版在行高允许时展示上课地点与教师。
 */
class TimetableDayWideWidget : GlanceAppWidget() {

    /** 固定尺寸小部件：按宿主给出的精确尺寸组合，行高据此计算。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = TimetableWidgetLoader.load()
        val strings = WidgetStrings(context)
        val action = openTimetableAction(context)
        provideContent {
            DayWidgetContent(data = data, strings = strings, action = action)
        }
    }
}

/** 日视图小部件（宽版）的系统入口（Manifest 中声明）。 */
class TimetableDayWideWidgetReceiver : TimetableGlanceReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimetableDayWideWidget()
}

/**
 * 日视图内容：窄版与宽版共用同一份「今日课程」数据，仅信息量不同（[detailed]）。
 *
 * 与周视图一致：全部使用显式 dp 尺寸（依据 [LocalSize] 计算），
 * 不使用 `fillMaxHeight` / `Spacer`；行高按可用高度精确分配，铺满整个小部件。
 */
@Composable
private fun DayWidgetContent(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    action: Action
) {
    // Glance 在组合期始终提供小部件实际尺寸（见 GlanceRemoteViews / SizeBox）
    val size = LocalSize.current
    WidgetRoot(action = action, padding = widgetRootPadding(size)) {
        when {
            // 未登录 / 本地无课表：提示打开应用同步（与「今天没有课」区分开）
            !data.hasSession || !data.hasTimetable ->
                WidgetEmptyState(strings.noSessionTitle, strings.noSessionHint)
            !data.hasTodayCourses -> WidgetEmptyState(strings.dayEmpty)
            else -> DayGrid(data = data, strings = strings, size = size)
        }
    }
}

/**
 * 日视图网格：标题 + 内容面板（时间列 + 今天列，6 个大节全部保行）+ 页脚。
 *
 * 外观与「双日课表」一致：纸面底板 + 18dp 圆角中性面板 + 底部「更新时刻 / 今天已上 x/y 节」；
 * 面板不染「今天」的高亮色，与周视图共用同一套中性配色。
 * 行高由 [allocateRowHeights] 按面板内部高度精确分配（有课行高、无课行收窄），因此网格铺满面板。
 */
@Composable
private fun DayGrid(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    size: DpSize
) {
    val contentHeight = (size.height - widgetRootPadding(size) * 2).coerceAtLeast(40.dp)
    val headerHeight = WidgetHeaderHeight
    val panelHeight = (contentHeight - headerHeight - DayHeaderGap - WidgetFooterHeight - WidgetFooterGap)
        .coerceAtLeast(DayPanelMinHeight)
    val gridHeight = (panelHeight - DayWidePanelPadding * 2).coerceAtLeast(20.dp)
    val emptyRowCount = data.todayRows.count { !it.occupied }
    val occupiedRowCount = data.todayRows.size - emptyRowCount
    val (occupiedRowHeight, emptyRowHeight) = allocateRowHeights(gridHeight, occupiedRowCount, emptyRowCount)

    DayHeader(
        data = data,
        strings = strings,
        headerHeight = headerHeight
    )
    WidgetGap(DayHeaderGap)

    // 内容面板：与「双日课表」的面板同款（中性底色，不对「今天」做高亮），大节保行铺满面板内部
    WidgetPanel(
        height = panelHeight,
        color = WidgetPalette.Panel,
        padding = DayWidePanelPadding
    ) {
        data.todayRows.forEach { slot ->
            DaySlotRow(
                slot = slot,
                strings = strings,
                rowHeight = if (slot.occupied) occupiedRowHeight else emptyRowHeight,
                timeColumnWidth = DayWideTimeColumnWidth
            )
        }
    }

    WidgetGap(WidgetFooterGap)
    WidgetFooter(
        updatedAt = data.updatedAt.format(DayFooterTimeFormat),
        progress = strings.doneProgress(data.todayDone, data.todayTotal)
    )
}

/**
 * 日视图的一行：时间列（大节序号 / 宽版追加起止时间）+ 今天列（课程块）。
 *
 * 行高由调用方按可用高度算好，因此行内不做任何高度填充。
 */
@Composable
private fun DaySlotRow(
    slot: WidgetDaySlot,
    strings: WidgetStrings,
    rowHeight: Dp,
    timeColumnWidth: Dp
) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(rowHeight)) {
        // 时间列：大节序号；行高允许时追加起止时间
        Box(
            modifier = GlanceModifier.width(timeColumnWidth).height(rowHeight),
            contentAlignment = Alignment.Center
        ) {
            if (rowHeight >= WidgetRowLabelMinHeight) {
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    Text(
                        text = "${slot.section}",
                        style = widgetText(WidgetPalette.OnSurface, 9, FontWeight.Bold),
                        maxLines = 1
                    )
                    if (rowHeight >= WidgetTimeRangeMinRowHeight) {
                        CombineSlots.getOrNull(slot.section - 1)?.let { timeSlot ->
                            Text(
                                text = timeSlot.start,
                                style = widgetText(WidgetPalette.OnSurfaceVariant, 8),
                                maxLines = 1
                            )
                            Text(
                                text = timeSlot.end,
                                style = widgetText(WidgetPalette.OnSurfaceVariant, 8),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 今天列：同一大节可能有多门课（不同周次），纵向等分展示前两门
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .height(rowHeight)
                .padding(DayCellInset)
        ) {
            val shown = slot.cells.take(2)
            if (shown.isNotEmpty()) {
                val blockHeight = if (shown.size > 1) {
                    ((rowHeight - DayStackGap) / 2).coerceAtLeast(8.dp)
                } else {
                    rowHeight
                }
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    shown.forEachIndexed { index, cell ->
                        if (index > 0) WidgetGap(DayStackGap)
                        GridCourseBlock(
                            cell = cell,
                            strings = strings,
                            layout = blockTextLayout(
                                blockHeight = blockHeight,
                                detailed = true,
                                stacked = shown.size > 1,
                                reserveTagLine = !cell.activeInWeek
                            ),
                            detailed = true,
                            modifier = GlanceModifier.fillMaxWidth().height(blockHeight)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日视图（宽版）标题：一行放下「MM/dd 周X」与「第 N 周」。
 */
@Composable
private fun DayHeader(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    headerHeight: Dp
) {
    val title = data.todayDate.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) + " " + DayNames[data.todayDay - 1]
    val week = data.calendarMessage.ifEmpty { strings.weekTitle(data.currentWeek) }
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(headerHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = GlanceModifier.defaultWeight(),
            style = widgetText(WidgetPalette.OnSurface, 12, FontWeight.Bold),
            maxLines = 1
        )
        Text(
            text = week,
            modifier = GlanceModifier.width(DayTitleTailWidth),
            style = widgetText(WidgetPalette.OnSurfaceVariant, 9, align = TextAlign.End),
            maxLines = 1
        )
    }
}

// ================= 窄版（2×3）=================

/**
 * 窄版（2×3）内容：标题 + 今日剩余课程紧凑列表 + 页脚。
 *
 * 与宽版刻意采用不同的信息结构：不显示节次序号，课程名占满整行；
 * 每条课程只保留「课程名 + 开始时间/地点」两行，最多 3 条。
 */
@Composable
private fun NarrowDayContent(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    action: Action
) {
    val size = LocalSize.current
    WidgetRoot(action = action, padding = widgetRootPadding(size)) {
        when {
            !data.hasSession || !data.hasTimetable ->
                WidgetEmptyState(strings.noSessionTitle, strings.noSessionHint)
            !data.hasTodayCourses -> WidgetEmptyState(strings.dayEmpty)
            else -> NarrowDayList(data = data, strings = strings, size = size)
        }
    }
}

@Composable
private fun NarrowDayList(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    size: DpSize
) {
    val contentHeight = (size.height - widgetRootPadding(size) * 2).coerceAtLeast(40.dp)
    val listHeight = (contentHeight - DayNarrowHeaderHeight - DayNarrowFooterHeight - DayHeaderGap * 2)
        .coerceAtLeast(24.dp)

    // 只保留今天尚未结束的课程；紧凑布局最多展示 3 条，总数由页脚 x/y 体现
    val remaining = data.todayRows.filter { slot ->
        val end = CombineSlots.getOrNull(slot.section - 1)?.end
        slot.occupied && end != null && java.time.LocalTime.parse(end) > data.updatedAt
    }
    val shown = remaining.take(3)
    val rowHeight = if (shown.isEmpty()) 0.dp else (listHeight / shown.size).coerceAtMost(DayNarrowRowMaxHeight)

    // 标题：日期 + 星期；右侧教学周
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(DayNarrowHeaderHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = data.todayDate.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) + " " + DayNames[data.todayDay - 1],
            modifier = GlanceModifier.defaultWeight(),
            style = widgetText(WidgetPalette.OnSurface, 11, FontWeight.Bold),
            maxLines = 1
        )
        Text(
            text = data.calendarMessage.ifEmpty { strings.weekTitle(data.currentWeek) },
            modifier = GlanceModifier.width(DayNarrowWeekWidth),
            style = widgetText(WidgetPalette.OnSurfaceVariant, 8, align = TextAlign.End),
            maxLines = 1
        )
    }
    WidgetGap(DayHeaderGap)

    if (shown.isEmpty()) {
        // 今天的课都已结束：保留标题与页脚，中间给出明确状态
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(listHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = strings.dayFinished,
                style = widgetText(WidgetPalette.OnSurfaceVariant, 10),
                maxLines = 1
            )
        }
    } else {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            shown.forEachIndexed { index, slot ->
                if (index > 0) WidgetGap(DayNarrowRowGap)
                val cell = slot.cells.firstOrNull { it.activeInWeek } ?: slot.cells.first()
                NarrowCourseRow(cell = cell, section = slot.section, rowHeight = rowHeight)
            }
        }
    }

    WidgetGap(DayHeaderGap)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(DayNarrowFooterHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = data.updatedAt.format(DayFooterTimeFormat),
            modifier = GlanceModifier.defaultWeight(),
            style = widgetText(WidgetPalette.OnSurfaceVariant, 8),
            maxLines = 1
        )
        Text(
            text = strings.doneProgressShort(data.todayDone, data.todayTotal),
            style = widgetText(WidgetPalette.OnSurface, 9, FontWeight.Medium),
            maxLines = 1
        )
    }
}

/**
 * 窄版课程条：课程名一行，开始时间 + 地点一行；左侧不再保留节次数字列。
 *
 * 同一条只取当前教学周有效的那门课；若没有有效课程，则取第一门并沿用其弱化配色。
 */
@Composable
private fun NarrowCourseRow(
    cell: WidgetCell,
    section: Int,
    rowHeight: Dp
) {
    val start = CombineSlots.getOrNull(section - 1)?.start
    val sub = buildList {
        start?.let { add(it) }
        add(cell.course.location.ifBlank { "地点待定" })
        if (!cell.activeInWeek) add("非本周")
    }.joinToString(" · ")

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(rowHeight)
            .cornerRadius(8.dp)
            .background(cell.colors.containerColor())
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = cell.course.courseName,
            style = widgetText(cell.colors.contentColor(), 10, FontWeight.Medium),
            maxLines = 1
        )
        Text(
            text = sub,
            style = widgetText(cell.colors.subContentColor(), 8),
            maxLines = 1
        )
    }
}
