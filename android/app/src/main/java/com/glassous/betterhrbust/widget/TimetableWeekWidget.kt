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
import com.glassous.betterhrbust.feature.timetable.DayNames
import com.glassous.betterhrbust.feature.timetable.DayShortNames

/** 标题行右侧「今天 · 周X」占宽。 */
private val WeekTitleTailWidth = 76.dp

/** 标题行与星期表头、星期表头与课程行之间的间隔。 */
private val WeekHeaderGap = 4.dp
private val WeekBodyGap = 2.dp

/** 课程格四周留白（避免相邻色块粘连）。 */
private val WeekCellInset = 1.dp

/** 同格多门课时的块间距。 */
private val WeekStackGap = 2.dp

/** 内容面板内边距 / 最小高度（与「双日课表」面板一致的圆角纸片）。 */
private val WeekPanelPadding = 4.dp
private val WeekPanelMinHeight = 80.dp

/** 页脚更新时刻格式（与「双日课表」页脚一致）。 */
private val WeekFooterTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * 周视图课表小部件（桌面占位 4 列 × 4 行）。
 *
 * 固定展示当前教学周，并自动隐藏空行 / 空列（小部件面积有限，优先显示真实课程）：
 * - 本周该大节没有任何课程的行、以及本周该星期没有任何课程的列都不渲染，
 *   剩余行列均分面板高度 / 宽度，课程块因此更宽、行高更充裕；节次序号仍贴在行首保留位置感知；
 * - 不做高亮：所有列共用同一套中性配色（「今天」只体现在顶部日期文字里，不染底色、不加粗）；
 * - 非本周有效的课程以中性灰弱化并标注「非本周」。
 *
 * 布局说明：小部件内所有元素都使用显式 dp 尺寸（依据 [LocalSize] 计算），
 * 不使用 `fillMaxHeight` / `Spacer` —— 在 RemoteViews 的嵌套 LinearLayout 中，
 * MATCH_PARENT 高度的子视图会取满整块高度、并把后续内容挤出可视区域；
 * 横向列宽则统一交给 `defaultWeight` 由宿主按实际宽度精确分配，避免按尺寸推算导致的溢出。
 */
class TimetableWeekWidget : GlanceAppWidget() {

    /** 固定尺寸小部件：按宿主给出的精确尺寸组合，行高据此计算。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = TimetableWidgetLoader.load()
        val strings = WidgetStrings(context)
        val action = openTimetableAction(context)
        provideContent { WeekWidgetContent(data = data, strings = strings, action = action) }
    }
}

/** 周视图小部件的系统入口（Manifest 中声明）。 */
class TimetableWeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimetableWeekWidget()
}

@Composable
private fun WeekWidgetContent(data: TimetableWidgetData, strings: WidgetStrings, action: Action) {
    // Glance 在组合期始终提供小部件实际尺寸（见 GlanceRemoteViews / SizeBox）
    val size = LocalSize.current
    WidgetRoot(action = action, padding = widgetRootPadding(size)) {
        when {
            // 未登录 / 本地无课表：提示打开应用同步（与「本周无课」区分开）
            !data.hasSession || !data.hasTimetable ->
                WidgetEmptyState(strings.noSessionTitle, strings.noSessionHint)
            !data.hasWeekCourses -> WidgetEmptyState(strings.weekEmpty)
            else -> WeekGrid(data = data, strings = strings, size = size)
        }
    }
}

/**
 * 周网格：标题 + 内容面板（星期表头 + 6 个大节行）+ 页脚。
 *
 * 外观与「双日课表」一致：纸面底板 + 18dp 圆角面板 + 底部「更新时刻 / 今天已上 x/y 节」。
 * 列宽全部由 `defaultWeight` 分配（时间列固定窄宽），因此无论宿主机型 / 密度如何，
 * 表格宽度都恰好等于面板宽度，不会右溢出。
 */
@Composable
private fun WeekGrid(data: TimetableWidgetData, strings: WidgetStrings, size: DpSize) {
    val contentHeight = (size.height - widgetRootPadding(size) * 2).coerceAtLeast(40.dp)
    val panelHeight = (contentHeight - WidgetHeaderHeight - WeekHeaderGap - WidgetFooterHeight - WidgetFooterGap)
        .coerceAtLeast(WeekPanelMinHeight)
    val gridHeight = (panelHeight - WeekPanelPadding * 2 - WidgetWeekdayHeight - WeekBodyGap)
        .coerceAtLeast(20.dp)

    // 隐藏空行 / 空列：只渲染本周有课的大节与星期，剩余高度在可见行之间均分，精确铺满面板
    val rows = data.visibleWeekRows
    val days = data.visibleWeekDays
    val rowHeight = (gridHeight / rows.size.coerceAtLeast(1)).coerceAtLeast(WidgetRowLabelMinHeight)

    // 标题：教学周 + 今天
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(WidgetHeaderHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = data.calendarMessage.ifEmpty { strings.weekTitle(data.currentWeek) },
            modifier = GlanceModifier.defaultWeight(),
            style = widgetText(WidgetPalette.OnSurface, 12, FontWeight.Bold),
            maxLines = 1
        )
        Text(
            text = data.todayDate.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) + " " + DayNames[data.todayDay - 1],
            modifier = GlanceModifier.width(WeekTitleTailWidth),
            style = widgetText(WidgetPalette.OnSurfaceVariant, 9, align = TextAlign.End),
            maxLines = 1
        )
    }

    WidgetGap(WeekHeaderGap)

    // 内容面板：与「双日课表」的面板同款（18dp 圆角纸片），内部铺星期表头 + 可见大节行
    WidgetPanel(
        height = panelHeight,
        color = WidgetPalette.Panel,
        padding = WeekPanelPadding
    ) {
        WeekWeekdayHeader(data = data, days = days)
        WidgetGap(WeekBodyGap)
        rows.forEach { row ->
            WeekSlotRow(
                row = row,
                strings = strings,
                rowHeight = rowHeight,
                days = days
            )
        }
    }

    WidgetGap(WidgetFooterGap)
    WidgetFooter(
        updatedAt = data.updatedAt.format(WeekFooterTimeFormat),
        progress = strings.doneProgress(data.todayDone, data.todayTotal)
    )
}

/** 星期表头：只渲染 [days]（本周有课的星期），所有列同一套中性配色（不区分今天）。 */
@Composable
private fun WeekWeekdayHeader(data: TimetableWidgetData, days: List<Int>) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(WidgetWeekdayHeight)) {
        Box(modifier = GlanceModifier.width(WidgetTimeColumnWidth).height(WidgetWeekdayHeight)) {}
        for (day in days) {
            Box(
                modifier = GlanceModifier.defaultWeight().height(WidgetWeekdayHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = DayShortNames[day - 1] + "\n" + data.todayDate.minusDays((data.todayDay - 1).toLong()).plusDays((day - 1).toLong()).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")),
                    style = widgetText(WidgetPalette.OnSurfaceVariant, 9),
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * 周视图的一行（一个大节）：时间列 + 可见的星期列。
 *
 * 行高由调用方按可用高度算好；同一格可能排多门课（不同周次），纵向等分展示前两门。
 */
@Composable
private fun WeekSlotRow(
    row: WidgetWeekRow,
    strings: WidgetStrings,
    rowHeight: Dp,
    days: List<Int>
) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(rowHeight)) {
        Box(
            modifier = GlanceModifier.width(WidgetTimeColumnWidth).height(rowHeight),
            contentAlignment = Alignment.Center
        ) {
            if (rowHeight >= WidgetRowLabelMinHeight) {
                Text(
                    text = "${row.section}",
                    style = widgetText(WidgetPalette.OnSurface, 9, FontWeight.Bold),
                    maxLines = 1
                )
            }
        }
        for (day in days) {
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(rowHeight)
                    .padding(WeekCellInset)
            ) {
                val cells = row.cellsByDay[day].orEmpty()
                if (cells.isNotEmpty()) {
                    val shown = cells.take(2)
                    val blockHeight = if (shown.size > 1) {
                        ((rowHeight - WeekStackGap) / 2).coerceAtLeast(8.dp)
                    } else {
                        rowHeight
                    }
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        shown.forEachIndexed { index, cell ->
                            if (index > 0) WidgetGap(WeekStackGap)
                            GridCourseBlock(
                                cell = cell,
                                strings = strings,
                                layout = blockTextLayout(
                                    blockHeight = blockHeight,
                                    detailed = false,
                                    stacked = shown.size > 1,
                                    reserveTagLine = !cell.activeInWeek
                                ),
                                detailed = false,
                                modifier = GlanceModifier.fillMaxWidth().height(blockHeight)
                            )
                        }
                    }
                }
            }
        }
    }
}
