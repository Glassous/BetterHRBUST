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
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
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

/** 窄版标题两行（今天 · 周X / 第 N 周）的行高。 */
private val DayNarrowTitleHeight = 18.dp
private val DayNarrowWeekHeight = 14.dp

/** 标题与网格之间的间隔。 */
private val DayHeaderGap = 6.dp

/** 内容面板的内边距（窄版更紧凑，把宽度让给课程块）。 */
private val DayNarrowPanelPadding = 3.dp
private val DayWidePanelPadding = 6.dp

/** 面板最小高度：保证 6 个大节仍能铺开。 */
private val DayPanelMinHeight = 60.dp

/** 时间列宽度（窄版更紧凑）。 */
private val DayNarrowTimeColumnWidth = 22.dp
private val DayWideTimeColumnWidth = 32.dp

/** 课程格四周留白。 */
private val DayCellInset = 1.dp

/** 同一大节多门课时的块间距。 */
private val DayStackGap = 2.dp

/** 页脚更新时刻格式（与「双日课表」页脚一致）。 */
private val DayFooterTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * 日视图课表小部件（窄版，桌面占位 2 列 × 4 行）。
 *
 * 与 App 内日视图一致：时间列 + 今天列（共 2 列），6 个大节全部保行，
 * 今天无课的大节收窄显示；固定展示今天，非本周有效的课程以中性灰弱化并标注「非本周」。
 */
class TimetableDayNarrowWidget : GlanceAppWidget() {

    /** 固定尺寸小部件：按宿主给出的精确尺寸组合，行高据此计算。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = TimetableWidgetLoader.load()
        val strings = WidgetStrings(context)
        val action = openTimetableAction(context)
        provideContent {
            DayWidgetContent(data = data, strings = strings, action = action, detailed = false)
        }
    }
}

/** 日视图小部件（窄版）的系统入口（Manifest 中声明）。 */
class TimetableDayNarrowWidgetReceiver : GlanceAppWidgetReceiver() {
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
            DayWidgetContent(data = data, strings = strings, action = action, detailed = true)
        }
    }
}

/** 日视图小部件（宽版）的系统入口（Manifest 中声明）。 */
class TimetableDayWideWidgetReceiver : GlanceAppWidgetReceiver() {
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
    action: Action,
    detailed: Boolean
) {
    // Glance 在组合期始终提供小部件实际尺寸（见 GlanceRemoteViews / SizeBox）
    val size = LocalSize.current
    WidgetRoot(action = action, padding = widgetRootPadding(size)) {
        when {
            // 未登录 / 本地无课表：提示打开应用同步（与「今天没有课」区分开）
            !data.hasSession || !data.hasTimetable ->
                WidgetEmptyState(strings.noSessionTitle, strings.noSessionHint)
            !data.hasTodayCourses -> WidgetEmptyState(strings.dayEmpty)
            else -> DayGrid(data = data, strings = strings, size = size, detailed = detailed)
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
    size: DpSize,
    detailed: Boolean
) {
    val contentHeight = (size.height - widgetRootPadding(size) * 2).coerceAtLeast(40.dp)
    val headerHeight = if (detailed) {
        WidgetHeaderHeight
    } else {
        DayNarrowTitleHeight + DayNarrowWeekHeight
    }
    val panelPadding = if (detailed) DayWidePanelPadding else DayNarrowPanelPadding
    val panelHeight = (contentHeight - headerHeight - DayHeaderGap - WidgetFooterHeight - WidgetFooterGap)
        .coerceAtLeast(DayPanelMinHeight)
    val gridHeight = (panelHeight - panelPadding * 2).coerceAtLeast(20.dp)
    val emptyRowCount = data.todayRows.count { !it.occupied }
    val occupiedRowCount = data.todayRows.size - emptyRowCount
    val (occupiedRowHeight, emptyRowHeight) = allocateRowHeights(gridHeight, occupiedRowCount, emptyRowCount)
    val timeColumnWidth = if (detailed) DayWideTimeColumnWidth else DayNarrowTimeColumnWidth

    DayHeader(
        data = data,
        strings = strings,
        detailed = detailed,
        headerHeight = headerHeight
    )
    WidgetGap(DayHeaderGap)

    // 内容面板：与「双日课表」的面板同款（中性底色，不对「今天」做高亮），大节保行铺满面板内部
    WidgetPanel(
        height = panelHeight,
        color = WidgetPalette.Panel,
        padding = panelPadding
    ) {
        data.todayRows.forEach { slot ->
            DaySlotRow(
                slot = slot,
                strings = strings,
                rowHeight = if (slot.occupied) occupiedRowHeight else emptyRowHeight,
                timeColumnWidth = timeColumnWidth,
                detailed = detailed
            )
        }
    }

    WidgetGap(WidgetFooterGap)
    WidgetFooter(
        updatedAt = data.updatedAt.format(DayFooterTimeFormat),
        progress = if (detailed) {
            strings.doneProgress(data.todayDone, data.todayTotal)
        } else {
            strings.doneProgressShort(data.todayDone, data.todayTotal)
        }
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
    timeColumnWidth: Dp,
    detailed: Boolean
) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(rowHeight)) {
        // 时间列：大节序号；宽版在行高允许时追加起止时间
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
                    if (detailed && rowHeight >= WidgetTimeRangeMinRowHeight) {
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
                                detailed = detailed,
                                stacked = shown.size > 1,
                                reserveTagLine = !cell.activeInWeek
                            ),
                            detailed = detailed,
                            modifier = GlanceModifier.fillMaxWidth().height(blockHeight)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日视图标题。
 *
 * 宽版一行放下「今天 · 周三」与「第 N 周」；窄版仅 2 列宽，改用两行避免标题被截断。
 */
@Composable
private fun DayHeader(
    data: TimetableWidgetData,
    strings: WidgetStrings,
    detailed: Boolean,
    headerHeight: Dp
) {
    val title = data.todayDate.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) + " " + DayNames[data.todayDay - 1]
    val week = data.calendarMessage.ifEmpty { strings.weekTitle(data.currentWeek) }
    if (detailed) {
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
    } else {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = GlanceModifier.fillMaxWidth().height(DayNarrowTitleHeight),
                style = widgetText(WidgetPalette.OnSurface, 12, FontWeight.Bold),
                maxLines = 1
            )
            Text(
                text = week,
                modifier = GlanceModifier.fillMaxWidth().height(DayNarrowWeekHeight),
                style = widgetText(WidgetPalette.OnSurfaceVariant, 9),
                maxLines = 1
            )
        }
    }
}
