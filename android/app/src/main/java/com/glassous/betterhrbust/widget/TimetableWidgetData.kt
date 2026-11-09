package com.glassous.betterhrbust.widget

import androidx.compose.ui.graphics.Color
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import com.glassous.betterhrbust.feature.timetable.CombineSlots
import com.glassous.betterhrbust.feature.timetable.MaxTeachingWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

/**
 * 课程块配色（明 / 暗两套）。
 *
 * 小部件需跟随系统深浅色：Glance 的明暗色值由桌面端在渲染时择一
 * （API 31+ 走 RemoteViews 的 night / notNight 色），
 * 因此这里一次产出两套色值，系统切换深色模式时无需重新组合小部件。
 */
internal data class WidgetCardColors(
    val containerDay: Color,
    val containerNight: Color,
    val contentDay: Color,
    val contentNight: Color,
    val accentDay: Color,
    val accentNight: Color,
    val subContentDay: Color,
    val subContentNight: Color
)

/** 单个课程格的渲染素材：课程本体 + 已按当前教学周判定的「本周是否有效」与配色。 */
internal data class WidgetCell(
    val course: TimetableCell,
    /** 该课程在当前教学周是否开课（false 时以中性灰弱化并标注「非本周」） */
    val activeInWeek: Boolean,
    val colors: WidgetCardColors
)

/**
 * 周视图的一行（一个大节）。
 *
 * 与 App 内一致：6 个大节全部保行，[occupied] 为 false 的行（本周该大节无课）
 * 在渲染时按「无课行」收窄。
 */
internal data class WidgetWeekRow(
    val section: Int,
    val cellsByDay: Map<Int, List<WidgetCell>>
) {
    /** 本周该大节是否排有课程 */
    val occupied: Boolean get() = cellsByDay.values.any { it.isNotEmpty() }
}

/**
 * 日视图的一行（一个大节）。
 *
 * 与周视图一致：6 个大节全部保行，[occupied] 为 false 的行（今天该大节无课）收窄显示。
 */
internal data class WidgetDaySlot(
    val section: Int,
    val cells: List<WidgetCell>
) {
    /** 今天该大节是否排有课程 */
    val occupied: Boolean get() = cells.isNotEmpty()
}

/**
 * 三个小部件共享的一次刷新快照。
 *
 * 数据只来自 App 已持久化的本地缓存（Room 课表 + DataStore 当前周），
 * 不联网、不写入，保证与 App 内课表完全一致且离线可用。
 */
internal data class TimetableWidgetData(
    /** 是否已登录（存在学号）。未登录时只展示占位提示 */
    val hasSession: Boolean,
    /** 本地是否已有课表数据（无数据时提示用户打开应用同步，而非「本周无课」） */
    val hasTimetable: Boolean,
    /** 当前教学周 */
    val currentWeek: Int,
    /** 今天星期序号（1..7） */
    val todayDay: Int,
    /** 周视图行（按大节升序，6 个大节全部保行，无课行由渲染层收窄） */
    val weekRows: List<WidgetWeekRow>,
    /** 日视图行（按大节升序，6 个大节全部保行，无课行由渲染层收窄） */
    val todayRows: List<WidgetDaySlot>,
    val todayDate: LocalDate = LocalDate.now(),
    val calendarMessage: String = "",
    /** 本次组合时刻（页脚展示「更新时刻」，与「双日课表」页脚一致） */
    val updatedAt: LocalTime = LocalTime.now(),
    /** 今天已上完的大节数 / 今天（本周有效）的课程数 */
    val todayDone: Int = 0,
    val todayTotal: Int = 0
) {
    /** 本周是否有任何课程 */
    val hasWeekCourses: Boolean get() = weekRows.any { it.occupied }

    /**
     * 周视图要渲染的大节行：整行为空（本周该节次没有课）的行直接隐藏。
     *
     * 与 App 内课表一致地保留节次位置信息（序号贴在行首），只是不再为空格子占白。
     */
    val visibleWeekRows: List<WidgetWeekRow> get() = weekRows.filter { it.occupied }

    /**
     * 周视图要渲染的星期列（1=周一 … 7=周日，升序）：整列为空（本周该星期没有课）的列直接隐藏。
     *
     * 列被隐藏后剩余列按权重均分宽度，因此课程块的宽度会自动变大，可读性更好。
     */
    val visibleWeekDays: List<Int>
        get() = (1..7).filter { day -> weekRows.any { it.cellsByDay[day].orEmpty().isNotEmpty() } }

    /** 今天是否有课程 */
    val hasTodayCourses: Boolean get() = todayRows.any { it.occupied }

    companion object {
        fun empty(todayDay: Int) = TimetableWidgetData(
            hasSession = false,
            hasTimetable = false,
            currentWeek = 1,
            todayDay = todayDay,
            weekRows = emptyList(),
            todayRows = emptyList()
        )
    }
}

/**
 * 小部件数据加载器：读取本地缓存 → 解码课表 → 计算派生态与配色。
 *
 * 任何一步失败（未登录 / 无缓存 / JSON 解析异常）都回退为空数据，避免小部件崩溃。
 */
internal object TimetableWidgetLoader {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): TimetableWidgetData {
        val app = BetterHrbustApp.instance
        val todayDay = LocalDate.now().dayOfWeek.value

        // 与双日组件共享已核验的账号快照；错误身份缓存不进入原有组件。
        val snapshot = WidgetDataSource.load(app) ?: return TimetableWidgetData.empty(todayDay)
        if (snapshot.message == "当前处于假期") return TimetableWidgetData.empty(todayDay).copy(
            hasSession=true, hasTimetable=true, todayDate=snapshot.date,
            currentWeek=snapshot.prefs.semesterTeachingWeeks, calendarMessage=snapshot.message)
        if (snapshot.message.isNotEmpty()) return TimetableWidgetData.empty(todayDay)
        val prefs = snapshot.prefs

        val timetable = runCatching {
            app.database.timetableDao().getTimetable(prefs.studentId).firstOrNull()?.json
                ?.let { AcademicParsers.normalizeTimetableCells(json.decodeFromString<TimetableResult>(it)) }
        }.getOrNull() ?: TimetableResult()

        val week = (snapshot.week ?: return TimetableWidgetData.empty(todayDay)).coerceIn(1, prefs.semesterTeachingWeeks)
        // CourseColorPalette 约定仅主线程访问（与 App 内取色共用同一张槽位表），故在 Main 上取色
        val styles = withContext(Dispatchers.Main) { resolveStyles(timetable.cells, week) }

        val current = app.preferencesManager.preferencesFlow.firstOrNull()
        if (current?.username != prefs.username || current.studentId != prefs.studentId || current.lastLoginAt != prefs.lastLoginAt)
            return TimetableWidgetData.empty(todayDay)
        val todayRows = buildTodayRows(timetable.cells, styles, todayDay)
        val now = snapshot.time
        val todayCells = todayRows.flatMap { it.cells }.filter { it.activeInWeek }
        return TimetableWidgetData(
            hasSession = true,
            hasTimetable = timetable.cells.isNotEmpty() || timetable.unarranged.isNotEmpty(),
            currentWeek = week,
            todayDay = todayDay,
            weekRows = buildWeekRows(timetable.cells, styles),
            todayDate = snapshot.date,
            todayRows = todayRows,
            updatedAt = now,
            todayDone = todayCells.count { cell -> finishedBy(cell.course.sectionIndex, now) },
            todayTotal = todayCells.size
        )
    }

    /**
     * 周视图行：6 个大节全部保行（与 App 内网格一致，便于对齐节次位置），
     * 同一大节内按星期归组，同一格可能有多门课（周次不同的课程）。
     */
    private fun buildWeekRows(
        cells: List<TimetableCell>,
        styles: Map<String, WidgetCell>
    ): List<WidgetWeekRow> {
        val bySection = cells
            .mapNotNull { cell -> styles[cell.id] }
            .groupBy { it.course.sectionIndex }
        return CombineSlots.map { slot ->
            WidgetWeekRow(
                section = slot.period,
                cellsByDay = bySection[slot.period].orEmpty().groupBy { it.course.day }
            )
        }
    }

    /**
     * 日视图行：6 个大节全部保行，仅保留今天（[todayDay]）的课程，
     * 同一大节内按节次顺序排列（同一格可能有多门课，如周次不同的课程）。
     */
    private fun buildTodayRows(
        cells: List<TimetableCell>,
        styles: Map<String, WidgetCell>,
        todayDay: Int
    ): List<WidgetDaySlot> {
        val bySection = cells
            .filter { it.day == todayDay }
            .mapNotNull { cell -> styles[cell.id] }
            .sortedBy { it.course.sectionIndex }
            .groupBy { it.course.sectionIndex }
        return CombineSlots.map { slot ->
            WidgetDaySlot(section = slot.period, cells = bySection[slot.period].orEmpty())
        }
    }

    /** 逐个课程格解析配色；非本周有效的课程走中性灰（与 App 内一致）。 */
    private fun resolveStyles(
        cells: List<TimetableCell>,
        currentWeek: Int
    ): Map<String, WidgetCell> {
        if (cells.isEmpty()) return emptyMap()
        CourseColorPalette.register(cells.map(::colorKeyOf))
        return cells.associate { cell ->
            val key = colorKeyOf(cell)
            val active = AcademicParsers.isCourseActiveInWeek(cell.weeks, currentWeek)
            val light = CourseColorPalette.cardColors(key = key, dark = false, muted = !active)
            val dark = CourseColorPalette.cardColors(key = key, dark = true, muted = !active)
            cell.id to WidgetCell(
                course = cell,
                activeInWeek = active,
                colors = WidgetCardColors(
                    containerDay = light.container,
                    containerNight = dark.container,
                    contentDay = light.content,
                    contentNight = dark.content,
                    accentDay = light.accent,
                    accentNight = dark.accent,
                    // 与 App 内课程卡片一致：地点 / 教师用 0.85 透明度的同色系文字
                    subContentDay = light.content.copy(alpha = 0.85f),
                    subContentNight = dark.content.copy(alpha = 0.85f)
                )
            )
        }
    }
}

/** 该大节的课是否已经下课（页脚「今天已上 x/y 节」用）。 */
private fun finishedBy(section: Int, now: LocalTime): Boolean {
    val slot = CombineSlots.getOrNull(section - 1) ?: return false
    return !now.isBefore(LocalTime.parse(slot.end))
}

/** 课程配色主键（与 App 内课表色块 / 详情卡片同一口径）。 */
internal fun colorKeyOf(cell: TimetableCell): String = CourseColorPalette.keyOf(
    courseName = cell.courseName,
    courseSeq = cell.courseSeq,
    courseId = cell.courseId,
    id = cell.id
)
