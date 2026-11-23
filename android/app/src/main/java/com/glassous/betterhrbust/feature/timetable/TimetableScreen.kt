@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.glassous.betterhrbust.feature.timetable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.CurrentCourseItem
import com.glassous.betterhrbust.core.model.CustomCourseEntry
import com.glassous.betterhrbust.core.model.RescheduleSourceMatch
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableOverridesPayload
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.model.UnarrangedCourse
import com.glassous.betterhrbust.core.notify.ClassReminderTicker
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.ui.theme.CourseCardColors
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.core.util.SchoolCalendar
import com.glassous.betterhrbust.core.util.TimetableOverrides
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import com.glassous.betterhrbust.widget.TimetableWidgetUpdater
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/** 调课弹窗状态。 */
private data class RescheduleDialogState(
    val sourceMatch: RescheduleSourceMatch,
    val existingId: String = "",
    val targetDay: Int = 1,
    val targetPeriod: Int = 1,
    val location: String = "",
    val teacher: String = "",
    val note: String = "",
    val customEntry: CustomCourseEntry? = null
)

/** 课表展示形态。 */
private enum class TimetableViewMode(val label: String) {
    WEEK("周"),
    DAY("日")
}

/**
 * 解析持久化的视图形态（[TimetableViewMode.name]，如 `WEEK` / `DAY`）。
 *
 * 大小写不敏感；缺失或未知值回落为周视图，保证旧数据 / 手工写入的异常值能够优雅降级。
 */
private fun viewModeOfStored(value: String?): TimetableViewMode =
    TimetableViewMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: TimetableViewMode.WEEK

/** 周视图列顺序（稳定实例，避免每次重组都新建列表）。 */
private val WeekDays = (1..7).toList()

@Composable
fun TimetableScreen(
    openRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val authRepo = remember { app.authRepository }
    val prefsManager = remember { app.preferencesManager }
    val syncManager = remember { app.syncManager }
    val coroutineScope = rememberCoroutineScope()
    // 使用应用内主题的明暗状态（而非系统主题），保证手动切换主题时课程配色同步
    val isDark = LocalDarkTheme.current

    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val isSyncing by syncManager.isSyncing.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    var timetableResult by remember { mutableStateOf<TimetableResult?>(null) }
    var currentCourses by remember { mutableStateOf<List<CurrentCourseItem>>(emptyList()) }
    var cacheError by remember { mutableStateOf("") }

    val studentId = prefs?.studentId.orEmpty()
    val timetableOverrides by remember(studentId) {
        academicRepo.observeTimetableOverrides(studentId)
    }.collectAsState(initial = TimetableOverridesPayload())

    val maxTeachingWeek = prefs?.semesterTeachingWeeks ?: 20
    val currentWeek = (prefs?.currentTeachingWeek ?: 1).coerceIn(1, maxTeachingWeek)
    var selectedWeek by remember { mutableStateOf(currentWeek) }
    var viewMode by remember { mutableStateOf(TimetableViewMode.WEEK) }
    var sectionMode by remember { mutableStateOf(SectionMode.COMBINE) }
    // 仅显示所选周次实际开设的课程（隐藏「非本周」课程）
    var onlyCurrentWeek by remember { mutableStateOf(false) }
    // 用户是否已在本页调整过「周 / 日」「大节 / 小节」：调整过之后，迟到的偏好值不再回写覆盖
    var displayModeTouched by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }

    var rescheduleMode by remember { mutableStateOf(false) }
    var rescheduleSource by remember { mutableStateOf<TimetableCell?>(null) }
    var editorDraft by remember { mutableStateOf<CustomCourseEntry?>(null) }
    var isEditingCustom by remember { mutableStateOf(false) }
    var rescheduleDialogState by remember { mutableStateOf<RescheduleDialogState?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun notifyMessage(msg: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    suspend fun syncWidgetsAndReminders() {
        TimetableWidgetUpdater.refreshAll(app)
        ClassReminderTicker.refreshAsync(app, forceReschedule = true)
    }

    // 恢复上次的「周 / 日」与「大节 / 小节」：偏好异步读到后套用一次（冷启动、旋转重建均生效），
    // 此后一律由本页切换结果写回，避免偏好流后续发射覆盖用户当前选择
    LaunchedEffect(prefs != null) {
        val loaded = prefs ?: return@LaunchedEffect
        if (displayModeTouched) return@LaunchedEffect
        displayModeTouched = true
        viewMode = viewModeOfStored(loaded.timetableViewMode)
        sectionMode = sectionModeOfStored(loaded.timetableSectionMode)
        onlyCurrentWeek = loaded.timetableOnlyCurrentWeek
    }

    val today = remember { LocalDate.now() }
    val todayDay = remember(today) { today.dayOfWeek.value } // 1..7
    var selectedDay by remember { mutableStateOf(todayDay) }

    // 详情卡片：打开后保留课程引用（含退场动画期间），入场/退场由 detailShown 驱动
    var selectedCourseDetail by remember { mutableStateOf<TimetableCell?>(null) }
    // 详情来源页码：共享元素 key 的一部分，用于与课表块配对
    var selectedDetailPage by remember { mutableStateOf(0) }
    var detailShown by remember { mutableStateOf(false) }

    val isDayView = viewMode == TimetableViewMode.DAY

    // 分页器：横向翻页的唯一手势载体。视图切换时按当前选中项重建，两种页码映射互不干扰。
    val pagerState = key(viewMode) {
        rememberPagerState(
            initialPage = pageOf(viewMode, selectedWeek, selectedDay, maxTeachingWeek),
            pageCount = { pageCountOf(viewMode, maxTeachingWeek) }
        )
    }

    /**
     * 显式导航：先更新选中状态，再驱动分页器滚动。
     *
     * 由点击直接发起（上一周 / 下一周 / 周选择 / 星期选择 / 回到今天），**不经过任何观察状态的
     * effect** —— 观察式写法会在翻页过程中被状态回写重启，从而取消进行中的动画（卡在半格）
     * 或在手势进行中抢断（滑动受阻）。
     */
    fun navigateTo(week: Int, day: Int) {
        selectedWeek = week
        selectedDay = day
        val target = pageOf(viewMode, week.coerceIn(1, maxTeachingWeek), day, maxTeachingWeek)
        coroutineScope.launch {
            // 远距离直接跳转避免长距离翻页闪烁，近邻页平滑滚动
            if (abs(target - pagerState.currentPage) > 4) {
                pagerState.scrollToPage(target)
            } else {
                pagerState.animateScrollToPage(target)
            }
        }
    }

    // 手势翻页 → 回写选中周次与星期（单向：只写状态，绝不反向驱动分页器，故不会干扰手势）
    LaunchedEffect(viewMode, pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val week = weekOfPage(viewMode, page)
            if (week != selectedWeek) selectedWeek = week
            if (isDayView) {
                val day = dayOfPage(page)
                if (day != selectedDay) selectedDay = day
            }
        }
    }

    // 课程配色注册：必须早于首帧取色完成，避免颜色按遭遇顺序分配导致跳动，故用 remember 而非 LaunchedEffect；
    // register 的返回值（已注册主键）在此无需使用，仅用于承载 remember 的产出（lambda 不得返回 Unit）
    remember(timetableResult, timetableOverrides) {
        val allCells = timetableResult?.cells.orEmpty() +
            timetableOverrides.customCourses.map { TimetableOverrides.customCourseToCell(it, SectionMode.COMBINE) }
        CourseColorPalette.register(
            allCells.map {
                CourseColorPalette.keyOf(it.courseName, it.courseSeq, it.courseId, it.id)
            }
        )
    }

    LaunchedEffect(selectedWeek, sectionMode) {
        rescheduleSource = null
    }

    /**
     * 打开课程详情卡片（共享元素从课表块过渡到卡片）。
     *
     * @param page 课程所在页码：共享元素 key 带页码，避免分页器预组合的相邻页出现同 key 课表块
     */
    fun openCourse(cell: TimetableCell, page: Int) {
        selectedCourseDetail = cell
        selectedDetailPage = page
        detailShown = true
    }

    /** 关闭课程详情卡片：只隐藏卡片，课程引用保留到退场动画结束。 */
    fun closeCourse() {
        detailShown = false
    }

    // 卡片展开期间拦截系统返回键
    BackHandler(enabled = detailShown) { closeCourse() }
    BackHandler(enabled = rescheduleMode && !detailShown) {
        rescheduleMode = false
        rescheduleSource = null
    }

    val quickCourseSuggestions = remember(timetableResult, currentCourses) {
        val seen = LinkedHashSet<String>()
        val list = mutableListOf<Pair<String, String>>()
        timetableResult?.unarranged.orEmpty().forEach {
            val name = it.courseName.trim()
            if (name.isNotEmpty() && seen.add(name)) list.add(name to it.teacher.trim())
        }
        currentCourses.forEach {
            val name = it.courseName.trim()
            if (name.isNotEmpty() && seen.add(name)) list.add(name to it.teacher.trim())
        }
        list
    }

    fun openQuickAdd(day: Int = selectedDay, period: Int = 1) {
        isEditingCustom = false
        editorDraft = CustomCourseEntry(
            id = "",
            category = "lab",
            courseName = "",
            week = selectedWeek,
            day = day,
            timeMode = "slot",
            slotType = if (sectionMode == SectionMode.BASE) "base" else "combine",
            slotPeriod = period,
            slotSpan = 1
        )
    }

    fun openQuickAddFromUnarranged(item: UnarrangedCourse) {
        isEditingCustom = false
        editorDraft = CustomCourseEntry(
            id = "",
            category = "lab",
            courseName = item.courseName,
            location = item.location,
            teacher = item.teacher,
            week = selectedWeek,
            day = selectedDay,
            timeMode = "slot",
            slotType = if (sectionMode == SectionMode.BASE) "base" else "combine",
            slotPeriod = 1,
            slotSpan = 1
        )
    }

    fun openEditCustom(cell: TimetableCell) {
        val existing = timetableOverrides.customCourses.firstOrNull { it.id == cell.id } ?: return
        isEditingCustom = true
        editorDraft = existing
    }

    fun openRescheduleDialog(source: TimetableCell, targetDay: Int, targetPeriod: Int) {
        if (source.isMovedOut) return
        val existingResched = timetableOverrides.reschedules.firstOrNull { it.id == source.rescheduleId }
        val customEntry = if (source.isCustomCourse) {
            timetableOverrides.customCourses.firstOrNull { it.id == source.id }
        } else null
        val match = TimetableOverrides.buildRescheduleSourceMatch(source, existingResched)
        if (!TimetableOverrides.isDifferentRescheduleTarget(match, targetDay, targetPeriod, sectionMode)) {
            notifyMessage("调整后的位置必须与原有位置不同")
            return
        }
        rescheduleDialogState = RescheduleDialogState(
            sourceMatch = match,
            existingId = existingResched?.id.orEmpty(),
            targetDay = targetDay,
            targetPeriod = targetPeriod,
            location = source.location.ifBlank { match.originLocation },
            teacher = source.teacher.ifBlank { match.originTeacher },
            note = source.note,
            customEntry = customEntry
        )
    }

    fun openRescheduleFromDetail(source: TimetableCell) {
        val defaultPeriod = if (sectionMode == SectionMode.BASE) {
            if (source.sectionIndex == 1) 3 else 1
        } else {
            if (source.sectionIndex == 1) 2 else 1
        }
        val existingResched = timetableOverrides.reschedules.firstOrNull { it.id == source.rescheduleId }
        val customEntry = if (source.isCustomCourse) {
            timetableOverrides.customCourses.firstOrNull { it.id == source.id }
        } else null
        val match = TimetableOverrides.buildRescheduleSourceMatch(source, existingResched)
        rescheduleDialogState = RescheduleDialogState(
            sourceMatch = match,
            existingId = existingResched?.id.orEmpty(),
            targetDay = source.day,
            targetPeriod = defaultPeriod,
            location = source.location.ifBlank { match.originLocation },
            teacher = source.teacher.ifBlank { match.originTeacher },
            note = source.note,
            customEntry = customEntry
        )
    }

    fun openMovedOutReschedule(cell: TimetableCell) {
        val existing = timetableOverrides.reschedules.firstOrNull { it.id == cell.rescheduleId } ?: return
        rescheduleDialogState = RescheduleDialogState(
            sourceMatch = existing.sourceMatch,
            existingId = existing.id,
            targetDay = existing.targetDay,
            targetPeriod = if (sectionMode == SectionMode.BASE) existing.targetBaseSection else existing.targetCombineSection,
            location = existing.location,
            teacher = existing.teacher,
            note = existing.note
        )
    }

    fun handleRescheduleCourseTap(cell: TimetableCell) {
        val currentSrc = rescheduleSource
        if (currentSrc == null) {
            if (!AcademicParsers.isCourseActiveInWeek(cell.weeks, selectedWeek)) {
                notifyMessage("非本周课程不可参与本周临时调课")
                return
            }
            rescheduleSource = cell
        } else if (currentSrc.id == cell.id && currentSrc.courseName == cell.courseName) {
            rescheduleSource = null
        } else {
            val targetPeriod = if (sectionMode == SectionMode.BASE) {
                baseRowRangeOf(cell.sectionIndex).first
            } else cell.sectionIndex
            openRescheduleDialog(currentSrc, cell.day, targetPeriod)
        }
    }

    // 教务当前周变化（同步完成后）跟随到新的当前周
    LaunchedEffect(prefs?.currentTeachingWeek) {
        val week = prefs?.currentTeachingWeek ?: return@LaunchedEffect
        if (week != selectedWeek) navigateTo(week, selectedDay)
    }

    LaunchedEffect(openRequest, prefs?.username) {
        if (openRequest == 0 || prefs == null) return@LaunchedEffect
        // 点击组件只定位到今天；绝不沿用上一次浏览留下的分页位置。
        selectedWeek = currentWeek
        selectedDay = todayDay
        pagerState.scrollToPage(pageOf(viewMode, currentWeek, todayDay, maxTeachingWeek))
        // 只有校历完全无法确定当前学期时才联网同步周次；
        // 已知学期的寒暑假（weekOn 返回 null 但 semesterOn 命中）保留初始定位，
        // 绝不用网络兜底把视图从假期末周拉回第 1 周。
        // getTeachingWeek 返回 Resource<Int>（L-12）：仅成功时定位，失败保持原位
        if (prefs?.teachingWeekOn(today) == null && SchoolCalendar.semesterOn(today) == null) {
            try {
                academicRepo.getTeachingWeek().collect { res ->
                    val week = (res as? Resource.Success)?.data ?: return@collect
                    if (week in 1..maxTeachingWeek) {
                        selectedWeek = week
                        pagerState.scrollToPage(pageOf(viewMode, week, todayDay, maxTeachingWeek))
                    }
                }
            } catch (switched: AccountSwitchedException) {
                // 账号已切换：本次周次定位作废，新账号的课表页会自行加载。
                // 业务信号必须在此消化——穿透 LaunchedEffect 会直接崩溃整个应用
            }
        }
    }

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                academicRepo.getTimetable(
                    currentPrefs.studentId,
                    currentPrefs.year,
                    currentPrefs.term,
                    cacheOnly = true
                ).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            timetableResult = res.data
                            cacheError = ""
                        }
                        is Resource.Error -> cacheError = res.message
                        else -> Unit
                    }
                }
                academicRepo.getCurrentCourses(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    if (res is Resource.Success) currentCourses = res.data
                }
            } catch (switched: AccountSwitchedException) {
                // 账号已切换：旧页面缓存读取作废，新页面会自行加载
            }
        }
    }

    /** 手动全量刷新：会话失效时要求重新登录 */
    fun refreshAll() {
        coroutineScope.launch {
            isRefreshing = true
            try {
                val outcome = syncManager.syncAll(manual = true)
                if (outcome.expired) {
                    authRepo.markSessionExpired(true)
                } else {
                    loadCache()
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(prefs?.studentId, prefs?.lastFullSyncDate) {
        if (prefs != null && prefs!!.studentId.isNotEmpty()) {
            loadCache()
        }
    }

    // 共享元素过渡作用域：以页面根容器承载（既提供根坐标，也承载过渡期间的浮层）
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val scope = this
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TimetableControlBar(
                    viewMode = viewMode,
                    onViewModeChange = { mode ->
                        displayModeTouched = true
                        viewMode = mode
                        coroutineScope.launch { prefsManager.setTimetableViewMode(mode.name) }
                    },
                    sectionMode = sectionMode,
                    onSectionModeChange = { mode ->
                        displayModeTouched = true
                        sectionMode = mode
                        coroutineScope.launch { prefsManager.setTimetableSectionMode(mode.name) }
                    },
                    onlyCurrentWeek = onlyCurrentWeek,
                    onToggleOnlyCurrentWeek = {
                        val next = !onlyCurrentWeek
                        onlyCurrentWeek = next
                        coroutineScope.launch { prefsManager.setTimetableOnlyCurrentWeek(next) }
                    },
                    rescheduleMode = rescheduleMode,
                    rescheduleSource = rescheduleSource,
                    onToggleRescheduleMode = {
                        if (rescheduleMode) {
                            rescheduleMode = false
                            rescheduleSource = null
                        } else {
                            closeCourse()
                            rescheduleSource = null
                            rescheduleMode = true
                        }
                    },
                    onClearRescheduleSource = { rescheduleSource = null },
                    onPickTargetFromBanner = {
                        rescheduleSource?.let { openRescheduleFromDetail(it) }
                    },
                    onQuickAdd = { openQuickAdd() },
                    selectedWeek = selectedWeek,
                    currentWeek = currentWeek,
                    onWeekStep = { step ->
                        navigateTo((selectedWeek + step).coerceIn(1, maxTeachingWeek), selectedDay)
                    },
                    onPickWeek = { showWeekPicker = true },
                    onBackToNow = {
                        // 周视图与日视图共用同一回归动作：选中周与选中日一并回到当下
                        navigateTo(currentWeek, todayDay)
                    },
                    selectedDay = selectedDay,
                    todayDay = todayDay,
                    onSelectDay = { day -> navigateTo(selectedWeek, day) }
                )

                AppPullToRefreshBox(
                    isRefreshing = isRefreshing || isSyncing,
                    onRefresh = { refreshAll() },
                    modifier = Modifier.weight(1f)
                ) {
                    val result = timetableResult
                    val hasAnyData = result != null && (
                        result.cells.isNotEmpty() ||
                            result.unarranged.isNotEmpty() ||
                            timetableOverrides.customCourses.isNotEmpty()
                        )
                    when {
                        result == null && (isRefreshing || isSyncing) -> LoadingView(message = "正在加载课表...")
                        result == null -> EmptyView(
                            title = "暂无课表数据",
                            description = cacheError.ifEmpty { "请下拉或使用「更多」页首行按钮手动刷新数据" }
                        )
                        !hasAnyData -> EmptyView(
                            title = "本学期暂无排课",
                            description = "教务系统尚未返回课程安排，可点击右上角添加实验或下拉重试"
                        )
                        else -> TimetableContent(
                            result = result,
                            overrides = timetableOverrides,
                            semesterWeeks = maxTeachingWeek,
                            referenceWeek = prefs?.currentWeek ?: 1,
                            referenceDate = prefs?.currentWeekReferenceDate.orEmpty(),
                            viewMode = viewMode,
                            pagerState = pagerState,
                            sectionMode = sectionMode,
                            onlyCurrentWeek = onlyCurrentWeek,
                            currentWeek = currentWeek,
                            isDark = isDark,
                            rescheduleMode = rescheduleMode,
                            rescheduleSource = rescheduleSource,
                            selectedDetail = selectedCourseDetail.takeIf { detailShown },
                            sharedTransitionScope = scope,
                            onCourseClick = { cell, page ->
                                if (rescheduleMode) handleRescheduleCourseTap(cell)
                                else openCourse(cell, page)
                            },
                            onQuickAdd = { day, period -> openQuickAdd(day, period) },
                            onRescheduleCellTap = { targetDay, targetPeriod ->
                                val src = rescheduleSource ?: return@TimetableContent
                                openRescheduleDialog(src, targetDay, targetPeriod)
                            },
                            onRescheduleDragDrop = { src, targetDay, targetPeriod ->
                                openRescheduleDialog(src, targetDay, targetPeriod)
                            },
                            onMovedOutClick = { cell -> openMovedOutReschedule(cell) },
                            onScheduleUnarranged = { item -> openQuickAddFromUnarranged(item) }
                        )
                    }
                }
            }

            // 课程详情：居中卡片 + 背景模糊 + 遮罩（非弹窗，直接绘制在页面之上）
            // 共享元素负责卡片的位置 / 尺寸变化，这里只做整体淡入淡出，避免两套缩放动画打架
            AnimatedVisibility(
                visible = detailShown,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 遮罩：命中测试止于本层，下方课表的下拉刷新 / 翻页 / 纵向滚动一并被阻断；
                    // 点击卡片以外任意位置关闭
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (isDark) 0.55f else 0.35f))
                            .dismissOnTap { closeCourse() }
                    )
                    val detail = selectedCourseDetail
                    if (detail != null) {
                        CourseDetailCard(
                            cell = detail,
                            page = selectedDetailPage,
                            selectedWeek = selectedWeek,
                            isDark = isDark,
                            sharedTransitionScope = scope,
                            animatedVisibilityScope = this@AnimatedVisibility,
                            onEditCustom = {
                                closeCourse()
                                openEditCustom(detail)
                            },
                            onDeleteCustom = {
                                closeCourse()
                                coroutineScope.launch {
                                    academicRepo.removeCustomCourse(studentId, detail.id)
                                    syncWidgetsAndReminders()
                                    notifyMessage("已从课表移除")
                                }
                            },
                            onReschedule = {
                                closeCourse()
                                openRescheduleFromDetail(detail)
                            },
                            onRestoreReschedule = {
                                closeCourse()
                                coroutineScope.launch {
                                    academicRepo.removeTimetableReschedule(studentId, detail.rescheduleId)
                                    syncWidgetsAndReminders()
                                    notifyMessage("已还原原排课")
                                }
                            },
                            onDismiss = { closeCourse() },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp, vertical = 32.dp)
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = LocalBottomContentInset.current + 12.dp)
            )
        }
    }

    if (showWeekPicker) {
        WeekPickerSheet(
            selectedWeek = selectedWeek,
            currentWeek = currentWeek,
            maxTeachingWeek = maxTeachingWeek,
            calendarKnown = prefs?.calendarWeekCountKnown == true,
            onSelect = { week ->
                showWeekPicker = false
                navigateTo(week, selectedDay)
            },
            onDismiss = { showWeekPicker = false }
        )
    }

    val currentEditor = editorDraft
    if (currentEditor != null) {
        CustomCourseEditorSheet(
            initialEntry = currentEditor,
            isEditing = isEditingCustom,
            maxTeachingWeek = maxTeachingWeek,
            quickSuggestions = quickCourseSuggestions,
            onSave = { saved ->
                editorDraft = null
                coroutineScope.launch {
                    academicRepo.upsertCustomCourse(studentId, saved)
                    syncWidgetsAndReminders()
                    if (saved.week != selectedWeek) {
                        navigateTo(saved.week, saved.day)
                    }
                    notifyMessage(if (isEditingCustom) "已更新课程信息" else "已添加到第 ${saved.week} 周课表")
                }
            },
            onDelete = { id ->
                editorDraft = null
                coroutineScope.launch {
                    academicRepo.removeCustomCourse(studentId, id)
                    syncWidgetsAndReminders()
                    notifyMessage("已从课表移除")
                }
            },
            onDismiss = { editorDraft = null }
        )
    }

    val currentReschedDialog = rescheduleDialogState
    if (currentReschedDialog != null) {
        RescheduleCourseSheet(
            week = selectedWeek,
            sectionMode = sectionMode,
            sourceMatch = currentReschedDialog.sourceMatch,
            existingId = currentReschedDialog.existingId,
            initialTargetDay = currentReschedDialog.targetDay,
            initialTargetPeriod = currentReschedDialog.targetPeriod,
            initialLocation = currentReschedDialog.location,
            initialTeacher = currentReschedDialog.teacher,
            initialNote = currentReschedDialog.note,
            onSave = { entry ->
                val customBase = currentReschedDialog.customEntry
                rescheduleDialogState = null
                rescheduleSource = null
                coroutineScope.launch {
                    if (customBase != null) {
                        val updatedCustom = customBase.copy(
                            day = entry.targetDay,
                            timeMode = "slot",
                            slotType = entry.targetSectionMode,
                            slotPeriod = if (entry.targetSectionMode == "base") entry.targetBaseSection else entry.targetCombineSection,
                            location = entry.location,
                            teacher = entry.teacher,
                            note = entry.note
                        )
                        academicRepo.upsertCustomCourse(studentId, updatedCustom)
                        syncWidgetsAndReminders()
                        notifyMessage("已调整实验/课程位置")
                    } else {
                        academicRepo.upsertTimetableReschedule(studentId, entry)
                        syncWidgetsAndReminders()
                        val targetPeriod = if (sectionMode == SectionMode.BASE) entry.targetBaseSection else entry.targetCombineSection
                        notifyMessage("第 ${entry.week} 周已调至 ${TimetableOverrides.formatDaySectionLabel(entry.targetDay, targetPeriod, sectionMode)}")
                    }
                }
            },
            onRestore = { id ->
                rescheduleDialogState = null
                rescheduleSource = null
                coroutineScope.launch {
                    academicRepo.removeTimetableReschedule(studentId, id)
                    syncWidgetsAndReminders()
                    notifyMessage("已还原原排课")
                }
            },
            onDismiss = { rescheduleDialogState = null }
        )
    }
}

/**
 * 顶部控制栏：视图 / 节次切换 + 快捷加课 / 调课模式 + 周选择 + 「回到本周 / 当前周」。
 */
@Composable
private fun TimetableControlBar(
    viewMode: TimetableViewMode,
    onViewModeChange: (TimetableViewMode) -> Unit,
    sectionMode: SectionMode,
    onSectionModeChange: (SectionMode) -> Unit,
    onlyCurrentWeek: Boolean,
    onToggleOnlyCurrentWeek: () -> Unit,
    rescheduleMode: Boolean,
    rescheduleSource: TimetableCell?,
    onToggleRescheduleMode: () -> Unit,
    onClearRescheduleSource: () -> Unit,
    onPickTargetFromBanner: () -> Unit,
    onQuickAdd: () -> Unit,
    selectedWeek: Int,
    currentWeek: Int,
    onWeekStep: (Int) -> Unit,
    onPickWeek: () -> Unit,
    onBackToNow: () -> Unit,
    selectedDay: Int,
    todayDay: Int,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = LocalTopContentInset.current)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentedToggle(
                    options = TimetableViewMode.entries.map { it to it.label },
                    selected = viewMode,
                    onSelect = onViewModeChange
                )
                SegmentedToggle(
                    options = SectionMode.entries.map { it to it.label },
                    selected = sectionMode,
                    onSelect = onSectionModeChange
                )
                Spacer(modifier = Modifier.weight(1f))
                OnlyCurrentWeekToggle(
                    onlyCurrentWeek = onlyCurrentWeek,
                    onToggle = onToggleOnlyCurrentWeek
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onWeekStep(-1) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "上一周",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(AppShapes.Pill)
                            .clickable(onClick = onPickWeek)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 $selectedWeek 周",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "选择教学周",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onWeekStep(1) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "下一周",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 快捷添加实验 / 课程
                    Surface(
                        shape = AppShapes.Pill,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .clip(AppShapes.Pill)
                            .clickable(onClick = onQuickAdd)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "添加实验或课程",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "加课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 调课模式开关
                    Surface(
                        shape = AppShapes.Pill,
                        color = if (rescheduleMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier
                            .clip(AppShapes.Pill)
                            .clickable(onClick = onToggleRescheduleMode)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = "调课模式",
                                modifier = Modifier.size(14.dp),
                                tint = if (rescheduleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (rescheduleMode) "退出" else "调课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (rescheduleMode) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (rescheduleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val isDayView = viewMode == TimetableViewMode.DAY
                    val atNow = selectedWeek == currentWeek && (!isDayView || selectedDay == todayDay)
                    if (!atNow) {
                        Surface(
                            shape = AppShapes.Pill,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clip(AppShapes.Pill)
                                .clickable(onClick = onBackToNow)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDayView) Icons.Default.Today else Icons.Default.Refresh,
                                    contentDescription = if (isDayView) "回到今天" else "回到本周",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (isDayView) "今天" else "本周",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // 调课模式引导条（支持点击选位与长按拖拽）
            AnimatedVisibility(
                visible = rescheduleMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    shape = AppShapes.InnerCard,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (rescheduleSource == null) {
                                "第 $selectedWeek 周调课：轻点选课或长按课程直接拖拽"
                            } else {
                                "已选「${rescheduleSource.courseName}」，轻点目标格或继续拖拽"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (rescheduleSource != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "选位置",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(AppShapes.Pill)
                                        .clickable(onClick = onPickTargetFromBanner)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Text(
                                    text = "重选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier
                                        .clip(AppShapes.Pill)
                                        .clickable(onClick = onClearRescheduleSource)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (viewMode == TimetableViewMode.DAY) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (day in 1..7) {
                        val isSelected = day == selectedDay
                        val isToday = day == todayDay
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(AppShapes.Pill)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectDay(day) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = DayShortNames[day - 1],
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (isToday) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            thickness = 0.5.dp
        )
    }
}

/** 轻量分段切换：MD3E 胶囊轨道 + 实心主色滑块。 */
@Composable
private fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(AppShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(AppShapes.Pill)
                    .clickable { onSelect(value) }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** 紧凑开关：仅显示所选周次实际开设的课程，隐藏「非本周」课程。 */
@Composable
private fun OnlyCurrentWeekToggle(
    onlyCurrentWeek: Boolean,
    onToggle: () -> Unit
) {
    val container = if (onlyCurrentWeek) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (onlyCurrentWeek) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(AppShapes.Pill)
            .background(container)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "仅看本周",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (onlyCurrentWeek) FontWeight.SemiBold else FontWeight.Normal,
            color = content
        )
    }
}

/**
 * 课表内容区：日/周视图共用同一网格实现，横向翻页交给 [HorizontalPager]。
 */
@Composable
private fun TimetableContent(
    result: TimetableResult,
    overrides: TimetableOverridesPayload,
    semesterWeeks: Int,
    referenceWeek: Int,
    referenceDate: String,
    viewMode: TimetableViewMode,
    pagerState: PagerState,
    sectionMode: SectionMode,
    onlyCurrentWeek: Boolean,
    currentWeek: Int,
    isDark: Boolean,
    rescheduleMode: Boolean,
    rescheduleSource: TimetableCell?,
    selectedDetail: TimetableCell?,
    sharedTransitionScope: SharedTransitionScope,
    onCourseClick: (TimetableCell, Int) -> Unit,
    onQuickAdd: (day: Int, period: Int) -> Unit,
    onRescheduleCellTap: (day: Int, period: Int) -> Unit,
    onRescheduleDragDrop: (source: TimetableCell, targetDay: Int, targetPeriod: Int) -> Unit,
    onMovedOutClick: (TimetableCell) -> Unit,
    onScheduleUnarranged: (UnarrangedCourse) -> Unit
) {
    val isDayView = viewMode == TimetableViewMode.DAY
    val blurRadius by animateDpAsState(
        targetValue = if (selectedDetail != null) 12.dp else 0.dp,
        animationSpec = tween(220),
        label = "gridBlur"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .blur(blurRadius)
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomContentInset.current + 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val pageWeek = weekOfPage(viewMode, page)
            val mergedCells = remember(result.cells, overrides, pageWeek, sectionMode) {
                TimetableOverrides.buildEffectiveTimetableCells(
                    rawCells = result.cells,
                    week = pageWeek,
                    sectionMode = sectionMode,
                    overrides = overrides
                )
            }
            val pageCells = remember(mergedCells, pageWeek, onlyCurrentWeek) {
                if (onlyCurrentWeek) {
                    mergedCells.filter { AcademicParsers.isCourseActiveInWeek(it.weeks, pageWeek) }
                } else {
                    mergedCells
                }
            }
            TimetableGrid(
                cells = pageCells,
                days = if (isDayView) listOf(dayOfPage(page)) else WeekDays,
                selectedWeek = pageWeek,
                weekStart = SchoolCalendar.semesterOn(LocalDate.now())?.start?.plusWeeks((pageWeek - 1).toLong())
                    ?: com.glassous.betterhrbust.core.util.TeachingWeek.mondayForWeek(
                        referenceWeek,
                        referenceDate,
                        pageWeek,
                        maxWeeks = semesterWeeks
                    ),
                page = page,
                highlightToday = !isDayView && pageWeek == currentWeek,
                sectionMode = sectionMode,
                isDark = isDark,
                dense = !isDayView,
                selectedDetail = selectedDetail,
                sharedTransitionScope = sharedTransitionScope,
                onCourseClick = { cell -> onCourseClick(cell, page) },
                rescheduleMode = rescheduleMode,
                rescheduleSource = rescheduleSource,
                isValidRescheduleTarget = { targetDay, targetPeriod ->
                    val src = rescheduleSource ?: return@TimetableGrid false
                    val existing = overrides.reschedules.firstOrNull { it.id == src.rescheduleId }
                    val match = TimetableOverrides.buildRescheduleSourceMatch(src, existing)
                    TimetableOverrides.isDifferentRescheduleTarget(match, targetDay, targetPeriod, sectionMode)
                },
                onQuickAdd = onQuickAdd,
                onRescheduleCellTap = onRescheduleCellTap,
                onRescheduleDragDrop = onRescheduleDragDrop,
                onMovedOutClick = onMovedOutClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isDayView) 12.dp else 0.dp)
            )
        }

        if (result.unarranged.isNotEmpty()) {
            UnarrangedList(
                courses = result.unarranged,
                onSchedule = onScheduleUnarranged
            )
        }
    }
}

/** 周视图页数 = 教学周数；日视图一页一天，跨周连续。 */
private fun pageCountOf(mode: TimetableViewMode, maxWeeks: Int): Int =
    if (mode == TimetableViewMode.DAY) maxWeeks * 7 else maxWeeks

/** 周次 + 星期 → 页码（0-based）。周视图按周分页，日视图按天连续分页。 */
private fun pageOf(mode: TimetableViewMode, week: Int, day: Int, maxWeeks: Int): Int {
    val weekIndex = (week - 1).coerceIn(0, maxWeeks - 1)
    return if (mode == TimetableViewMode.DAY) weekIndex * 7 + (day - 1).coerceIn(0, 6) else weekIndex
}

/** 页码 → 周次。 */
private fun weekOfPage(mode: TimetableViewMode, page: Int): Int =
    if (mode == TimetableViewMode.DAY) page / 7 + 1 else page + 1

/** 页码 → 星期（1..7，仅日视图有意义）。 */
private fun dayOfPage(page: Int): Int = page % 7 + 1

/** 未排课列表：扁平分隔行，支持一键排入课表。 */
@Composable
private fun UnarrangedList(
    courses: List<UnarrangedCourse>,
    onSchedule: (UnarrangedCourse) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "未排课 · ${courses.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        courses.forEach { course ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.courseName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = listOf(course.teacher, course.mergeClass, course.weeks)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(
                    onClick = { onSchedule(course) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("排入课表", fontSize = 12.sp)
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
        }
    }
}

/**
 * 共享元素键：课表块与详情卡片按「页码 + 排课 ID + 部位」配对。
 */
internal fun sharedKey(page: Int, cell: TimetableCell, part: String): String =
    "p$page-course-${cell.id}-$part"

private val DetailCardMinHeight = 360.dp
private val DetailCardCloseSize = 36.dp
private val DetailCardHorizontalPadding = 22.dp
private val DetailCardVerticalPadding = 20.dp

/**
 * 课程详情卡片：页面居中的定制卡片（非弹窗），与课表块做共享元素过渡。
 */
@Composable
private fun CourseDetailCard(
    cell: TimetableCell,
    page: Int,
    selectedWeek: Int,
    isDark: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onEditCustom: () -> Unit,
    onDeleteCustom: () -> Unit,
    onReschedule: () -> Unit,
    onRestoreReschedule: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorKey = remember(cell) {
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
    val colors: CourseCardColors = remember(colorKey, isDark, active) {
        CourseColorPalette.cardColors(key = colorKey, dark = isDark, muted = !active)
    }
    val shape = RoundedCornerShape(22.dp)
    val divider = colors.content.copy(alpha = 0.16f)

    with(sharedTransitionScope) {
        Column(
            modifier = modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "bg")),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .heightIn(min = DetailCardMinHeight)
                .shadow(elevation = 28.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(colors.container)
                .border(1.dp, colors.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DetailCardHorizontalPadding, vertical = DetailCardVerticalPadding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = cell.courseName,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.content,
                        modifier = Modifier
                            .weight(1f)
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "name")),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(DetailCardCloseSize)
                            .clip(CircleShape)
                            .background(colors.content.copy(alpha = 0.10f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = colors.content,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = cell.location.ifEmpty { "待定" },
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    color = colors.content.copy(alpha = 0.85f),
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "location")),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
                Text(
                    text = cell.teacher.ifEmpty { "—" },
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    color = colors.content.copy(alpha = 0.85f),
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "teacher")),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )

                if (!active) {
                    Text(text = "非本周课程", fontSize = 12.sp, color = colors.accent)
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = divider)
                Spacer(modifier = Modifier.height(4.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CardDetailRow("上课周次", cell.weeks, colors.content)
                    CardDetailRow(
                        label = if (cell.customTimeText.isNotEmpty()) "时间安排" else "节次安排",
                        value = if (cell.customTimeText.isNotEmpty()) {
                            "${cell.customTimeText}${if (cell.sectionLabel.isNotEmpty() && !cell.sectionLabel.startsWith("自定义")) " · ${cell.sectionLabel}" else ""}"
                        } else {
                            buildString {
                                if (cell.sectionLabel.isNotEmpty()) append(cell.sectionLabel)
                                CombineSlots.getOrNull(cell.sectionIndex - 1)?.let { slot ->
                                    if (isNotEmpty()) append(" · ")
                                    append("第 ${slot.period} 大节 ").append(slotRangeText(slot))
                                }
                            }
                        },
                        content = colors.content
                    )
                    if (!cell.isCustomCourse) {
                        CardDetailRow("学分", formatCredit(cell.credit), colors.content)
                    }
                    if (cell.isRescheduled && cell.originSummary.isNotEmpty()) {
                        CardDetailRow("原排课", cell.originSummary, colors.content)
                    }
                    if (cell.hoursType.isNotEmpty()) {
                        CardDetailRow("类型", cell.hoursType, colors.content)
                    }
                    if (cell.note.isNotEmpty()) {
                        CardDetailRow("备注", cell.note, colors.content)
                    }
                }
            }

            // 详情底部操作入口
            if (cell.isCustomCourse || cell.isRescheduled || active) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = divider)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when {
                            cell.isCustomCourse -> {
                                DetailActionChip(
                                    label = "编辑",
                                    icon = Icons.Default.Edit,
                                    contentColor = colors.content,
                                    onClick = onEditCustom
                                )
                                DetailActionChip(
                                    label = "删除",
                                    icon = Icons.Default.DeleteOutline,
                                    contentColor = MaterialTheme.colorScheme.error,
                                    onClick = onDeleteCustom
                                )
                            }
                            cell.isRescheduled -> {
                                DetailActionChip(
                                    label = "修改调课",
                                    icon = Icons.Default.SwapHoriz,
                                    contentColor = colors.content,
                                    onClick = onReschedule
                                )
                                DetailActionChip(
                                    label = "还原原排课",
                                    icon = Icons.Default.Refresh,
                                    contentColor = colors.content,
                                    onClick = onRestoreReschedule
                                )
                            }
                            active -> {
                                DetailActionChip(
                                    label = "本周调课",
                                    icon = Icons.Default.SwapHoriz,
                                    contentColor = colors.content,
                                    onClick = onReschedule
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(AppShapes.Pill)
            .background(contentColor.copy(alpha = 0.10f))
            .border(1.dp, contentColor.copy(alpha = 0.20f), AppShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
    }
}

/** 学分展示：整数不带小数尾巴；尚未同步到学分时显示破折号。 */
private fun formatCredit(credit: Double?): String {
    if (credit == null) return "—"
    val text = credit.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

/** 卡片内的信息行：标签 + 值，均使用课程色系保证在色块上可读。 */
@Composable
private fun CardDetailRow(label: String, value: String, content: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = content.copy(alpha = 0.55f),
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value.ifEmpty { "—" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 遮罩手势：点击卡片以外的任意位置即关闭。
 */
@Composable
private fun Modifier.dismissOnTap(onTap: () -> Unit): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(Unit) { detectTapGestures { currentOnTap() } }
}
