@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.glassous.betterhrbust.feature.timetable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.model.UnarrangedCourse
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.theme.CourseCardColors
import com.glassous.betterhrbust.core.ui.theme.CourseColorPalette
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.core.util.SchoolCalendar
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

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
    var cacheError by remember { mutableStateOf("") }

    val maxTeachingWeek = prefs?.semesterTeachingWeeks ?: 20
    val currentWeek = (prefs?.currentTeachingWeek ?: 1).coerceIn(1, maxTeachingWeek)
    var selectedWeek by remember { mutableStateOf(currentWeek) }
    var viewMode by remember { mutableStateOf(TimetableViewMode.WEEK) }
    var sectionMode by remember { mutableStateOf(SectionMode.COMBINE) }
    // 用户是否已在本页调整过「周 / 日」「大节 / 小节」：调整过之后，迟到的偏好值不再回写覆盖
    var displayModeTouched by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }

    // 恢复上次的「周 / 日」与「大节 / 小节」：偏好异步读到后套用一次（冷启动、旋转重建均生效），
    // 此后一律由本页切换结果写回，避免偏好流后续发射覆盖用户当前选择
    LaunchedEffect(prefs != null) {
        val loaded = prefs ?: return@LaunchedEffect
        if (displayModeTouched) return@LaunchedEffect
        displayModeTouched = true
        viewMode = viewModeOfStored(loaded.timetableViewMode)
        sectionMode = sectionModeOfStored(loaded.timetableSectionMode)
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
    remember(timetableResult) {
        CourseColorPalette.register(
            timetableResult?.cells.orEmpty().map {
                CourseColorPalette.keyOf(it.courseName, it.courseSeq, it.courseId, it.id)
            }
        )
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
                when {
                    result == null && (isRefreshing || isSyncing) -> LoadingView(message = "正在加载课表...")
                    result == null -> EmptyView(
                        title = "暂无课表数据",
                        description = cacheError.ifEmpty { "请下拉或使用「更多」页首行按钮手动刷新数据" }
                    )
                    result.cells.isEmpty() && result.unarranged.isEmpty() -> EmptyView(
                        title = "本学期暂无排课",
                        description = "教务系统尚未返回课程安排，稍后可下拉刷新重试"
                    )
                    else -> TimetableContent(
                        result = result,
                        semesterWeeks = maxTeachingWeek,
                        referenceWeek = prefs?.currentWeek ?: 1,
                        referenceDate = prefs?.currentWeekReferenceDate.orEmpty(),
                        viewMode = viewMode,
                        pagerState = pagerState,
                        sectionMode = sectionMode,
                        currentWeek = currentWeek,
                        isDark = isDark,
                        selectedDetail = selectedCourseDetail.takeIf { detailShown },
                        sharedTransitionScope = scope,
                        onCourseClick = { cell, page -> openCourse(cell, page) }
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
                        onDismiss = { closeCourse() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp, vertical = 32.dp)
                    )
                }
            }
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
}

/**
 * 顶部控制栏：视图 / 节次切换 + 周选择 + 「回到本周 / 当前周」（日视图为「回到今天 / 今天」）。
 *
 * 扁平单层，不带阴影与卡片包裹；日视图额外展示星期选择，周视图以「今天列」高亮代替，
 * 两种视图共用同一套周次选择与回归逻辑。
 */
@Composable
private fun TimetableControlBar(
    viewMode: TimetableViewMode,
    onViewModeChange: (TimetableViewMode) -> Unit,
    sectionMode: SectionMode,
    onSectionModeChange: (SectionMode) -> Unit,
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
                horizontalArrangement = Arrangement.SpaceBetween,
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
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onPickWeek)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 $selectedWeek 周",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "选择教学周",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
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

                val isDayView = viewMode == TimetableViewMode.DAY
                // 周视图回归目标是「本周」，日视图回归目标是「今天」（含当前周与今天所在日）
                val atNow = selectedWeek == currentWeek && (!isDayView || selectedDay == todayDay)
                // 中性主题色：当前周 / 今天标识与「回到…」按钮同款，不再使用绿色强调
                val chipColor = MaterialTheme.colorScheme.surfaceContainerHigh
                val chipContent = MaterialTheme.colorScheme.onSurfaceVariant
                if (atNow) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(chipColor)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(chipContent)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isDayView) "今天" else "当前周",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = chipContent
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(chipColor)
                            .clickable(onClick = onBackToNow)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDayView) Icons.Default.Today else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = chipContent
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isDayView) "回到今天" else "回到本周",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = chipContent
                        )
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
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectDay(day) }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = DayShortNames[day - 1],
                                fontSize = 13.sp,
                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
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

/** 轻量分段切换：扁平、小尺寸，无按钮边框，仅以滑块底色区分选中项。 */
@Composable
private fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(2.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(value) }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 课表内容区：日/周视图共用同一网格实现，横向翻页交给 [HorizontalPager] ——
 * 拖拽过程中页面实时跟随手指，松手按位移与速度吸附到相邻页；纵向滚动由外层容器提供。
 *
 * 本组件是纯粹的「渲染层 + 手势源」：分页状态由调用方持有并驱动，手势翻页只回写选中项，
 * 自身不观察任何状态、不发起任何滚动，因此拖拽期间不可能被 effect 抢断。
 *
 * 每个页面的行高都基于全量课程计算（与当前页无关），保证翻页过程中网格高度恒定、不跳动。
 *
 * @param selectedDetail 当前展开详情的课程（null 表示卡片已关闭）；打开期间课表整体模糊
 */
@Composable
private fun TimetableContent(
    result: TimetableResult,
    semesterWeeks: Int,
    referenceWeek: Int,
    referenceDate: String,
    viewMode: TimetableViewMode,
    pagerState: PagerState,
    sectionMode: SectionMode,
    currentWeek: Int,
    isDark: Boolean,
    selectedDetail: TimetableCell?,
    sharedTransitionScope: SharedTransitionScope,
    onCourseClick: (TimetableCell, Int) -> Unit
) {
    val isDayView = viewMode == TimetableViewMode.DAY
    // 打开详情卡片时对课表做实时高斯模糊（Android 12+ 生效，低版本仅显示遮罩）
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
            // 预组合左右邻页：拖动第一帧就有内容，避免"拖不动/顿一下"的受阻感
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val pageWeek = weekOfPage(viewMode, page)
            TimetableGrid(
                // 行高等几何信息由全量课程决定（与页无关），翻页时网格高度恒定
                cells = result.cells,
                days = if (isDayView) listOf(dayOfPage(page)) else WeekDays,
                selectedWeek = pageWeek,
                weekStart = com.glassous.betterhrbust.core.util.SchoolCalendar.semesterOn(LocalDate.now())?.start?.plusWeeks((pageWeek-1).toLong()) ?: com.glassous.betterhrbust.core.util.TeachingWeek.mondayForWeek(referenceWeek, referenceDate, pageWeek, maxWeeks = semesterWeeks),
                page = page,
                highlightToday = !isDayView && pageWeek == currentWeek,
                sectionMode = sectionMode,
                isDark = isDark,
                dense = !isDayView,
                selectedDetail = selectedDetail,
                sharedTransitionScope = sharedTransitionScope,
                onCourseClick = { cell -> onCourseClick(cell, page) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isDayView) 12.dp else 0.dp)
            )
        }

        if (result.unarranged.isNotEmpty()) {
            UnarrangedList(courses = result.unarranged)
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

/** 未排课列表：扁平分隔行，不使用卡片包裹。 */
@Composable
private fun UnarrangedList(courses: List<UnarrangedCourse>) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.courseName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = listOf(course.teacher, course.mergeClass)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = course.weeks,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
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
 *
 * 页码参与配对，是因为分页器会预组合相邻页，同一课程可能同时出现在多页上；
 * 带上页码可保证任意时刻同一 key 只有唯一的课表块。
 */
internal fun sharedKey(page: Int, cell: TimetableCell, part: String): String =
    "p$page-course-${cell.id}-$part"

/** 详情卡片几何：最小高度撑起卡片、右上角圆形关闭按钮尺寸、内容内边距。 */
private val DetailCardMinHeight = 360.dp
private val DetailCardCloseSize = 36.dp
private val DetailCardHorizontalPadding = 22.dp
private val DetailCardVerticalPadding = 20.dp

/**
 * 课程详情卡片：页面居中的定制卡片（非弹窗），与课表块做共享元素过渡。
 *
 * 参与过渡：背景容器、课程名、上课地点、任课教师；周次 / 节次 / 学时随卡片淡入。
 * 卡片有最小高度，信息少时也不显扁平；关闭入口是右上角的正圆形叉号（不占用底部空间）。
 *
 * @param page 课程来源页码，与课表块配对使用（见 [sharedKey]）
 */
@Composable
private fun CourseDetailCard(
    cell: TimetableCell,
    page: Int,
    selectedWeek: Int,
    isDark: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
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
            // 与课表块共享同一个容器边界：展开时由格子长成卡片，关闭时缩回去
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = sharedKey(page, cell, "bg")),
                animatedVisibilityScope = animatedVisibilityScope
            )
            // 卡片至少占这么高：信息少时也不会显得扁平，多余空间由内容均分
            .heightIn(min = DetailCardMinHeight)
            .shadow(elevation = 28.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(colors.container)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = DetailCardHorizontalPadding, vertical = DetailCardVerticalPadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 标题行：课程名 + 右上角正圆形关闭按钮（不再占用卡片底部空间）
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
                label = "节次安排",
                value = buildString {
                    if (cell.sectionLabel.isNotEmpty()) append(cell.sectionLabel)
                    CombineSlots.getOrNull(cell.sectionIndex - 1)?.let { slot ->
                        if (isNotEmpty()) append(" · ")
                        append("第 ${slot.period} 大节 ").append(slotRangeText(slot))
                    }
                },
                content = colors.content
            )
            if (cell.hoursType.isNotEmpty()) CardDetailRow("学时类型", cell.hoursType, colors.content)
        }
    }
    }
}

/** 卡片内的信息行：标签 + 值，均使用课程色系保证在色块上可读。 */
@Composable
private fun CardDetailRow(label: String, value: String, content: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
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
 *
 * 命中测试在命中的同级节点处即停止，因此这层遮罩天然拦截下发课表的下拉刷新 / 翻页 / 纵向滚动；
 * 只在 Main 阶段处理点击（不做 Initial 消费），卡片内部的按钮才能正常收到按压。
 */
@Composable
private fun Modifier.dismissOnTap(onTap: () -> Unit): Modifier {
    // 手势协程只启动一次，回调始终取最新引用
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(Unit) { detectTapGestures { currentOnTap() } }
}
