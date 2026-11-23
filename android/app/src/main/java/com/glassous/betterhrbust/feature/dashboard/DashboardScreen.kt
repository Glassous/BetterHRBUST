package com.glassous.betterhrbust.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.*
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.GpaCalculationHelp
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.PageHeaderTitle
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.ui.theme.pressClickable
import com.glassous.betterhrbust.core.util.GpaCalculator
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch
import java.time.LocalDate

data class QuickNavGridItem(
    val title: String,
    val icon: ImageVector,
    val route: Any
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val authRepo = remember { app.authRepository }
    val prefsManager = remember { app.preferencesManager }
    val syncManager = remember { app.syncManager }
    val coroutineScope = rememberCoroutineScope()

    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val isSyncing by syncManager.isSyncing.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    var profile by remember { mutableStateOf<PersonalInfo?>(null) }
    var timetable by remember { mutableStateOf<TimetableResult?>(null) }
    var scoreResult by remember { mutableStateOf<ScoreResult?>(null) }
    var exams by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var plan by remember { mutableStateOf<CurriculumPlanResult?>(null) }

    /**
     * 读取本地缓存（离线只读，不联网）
     * 教务数据仅在登录成功、每天首次打开与手动刷新三种情况下获取
     */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                if (currentPrefs.username.isNotEmpty()) {
                    academicRepo.getPersonalInfo(currentPrefs.username, cacheOnly = true).collect { res ->
                        if (res is Resource.Success) profile = res.data
                    }
                }
                if (currentPrefs.studentId.isNotEmpty()) {
                    academicRepo.getTimetable(
                        currentPrefs.studentId,
                        currentPrefs.year,
                        currentPrefs.term,
                        cacheOnly = true
                    ).collect { res ->
                        if (res is Resource.Success) timetable = res.data
                    }
                    academicRepo.getScores(currentPrefs.studentId, cacheOnly = true).collect { res ->
                        if (res is Resource.Success) scoreResult = res.data
                    }
                    academicRepo.getExams(currentPrefs.studentId, cacheOnly = true).collect { res ->
                        if (res is Resource.Success) exams = res.data
                    }
                    academicRepo.getCurriculumPlan(currentPrefs.studentId, cacheOnly = true).collect { res ->
                        if (res is Resource.Success) plan = res.data
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

    val stats = remember(scoreResult) {
        scoreResult?.scores?.let { AcademicParsers.calculateGpaStats(it) }
    }


    // Today's courses
    val clock by produceState(initialValue = java.time.LocalDateTime.now()) {
        while (true) { value = java.time.LocalDateTime.now(); kotlinx.coroutines.delay(30_000) }
    }
    val today = clock.toLocalDate()
    val todayDayOfWeek = remember(today) { today.dayOfWeek.value } // 1=Monday .. 7=Sunday
    val currentWeek = prefs?.currentTeachingWeek ?: 1

    val studentId = prefs?.studentId.orEmpty()
    val timetableOverrides by remember(studentId) {
        academicRepo.observeTimetableOverrides(studentId)
    }.collectAsState(initial = TimetableOverridesPayload())

    val todayCourses = remember(timetable, timetableOverrides, todayDayOfWeek, currentWeek, clock) {
        if (prefs?.teachingWeekOn(today) == null) emptyList()
        else {
            val effective = com.glassous.betterhrbust.core.util.TimetableOverrides.buildEffectiveTimetableCells(
                rawCells = timetable?.cells.orEmpty(),
                week = currentWeek,
                sectionMode = com.glassous.betterhrbust.feature.timetable.SectionMode.COMBINE,
                overrides = timetableOverrides
            )
            effective
                .filter { cell ->
                    !cell.isMovedOut &&
                        cell.day == todayDayOfWeek &&
                        AcademicParsers.isCourseActiveInWeek(cell.weeks, currentWeek) &&
                        !com.glassous.betterhrbust.core.util.CourseSchedule.hasEnded(cell, clock.toLocalTime())
                }
                .sortedBy { cell ->
                    com.glassous.betterhrbust.core.util.TimetableOverrides.effectiveTimeRangeOf(cell)?.first
                        ?: java.time.LocalTime.of(cell.sectionIndex.coerceIn(1, 23), 0)
                }
        }
    }

    // 记忆键含 today：驻留跨天后倒计时按新日期重算，而非沿用进入页面当天的旧值
    val upcomingExam = remember(exams, today) {
        exams.firstOrNull { it.isUpcoming && (it.countdownDays ?: 0) >= 0 }
    }

    val quickNavItems = listOf(
        QuickNavGridItem("课程表", Icons.Default.CalendarMonth, com.glassous.betterhrbust.navigation.TimetableRoute),
        QuickNavGridItem("成绩单", Icons.Default.Assessment, com.glassous.betterhrbust.navigation.ScoresRoute),
        QuickNavGridItem("考试日程", Icons.Default.DateRange, com.glassous.betterhrbust.navigation.ExamsRoute),
        QuickNavGridItem("培养方案", Icons.Default.Bookmark, com.glassous.betterhrbust.navigation.ProgramRoute),
        QuickNavGridItem("空教室", Icons.Default.MeetingRoom, com.glassous.betterhrbust.navigation.ClassroomsRoute),
        QuickNavGridItem("我的课程名录", Icons.Default.Search, com.glassous.betterhrbust.navigation.CoursesRoute),
        QuickNavGridItem("学籍档案", Icons.Default.Badge, com.glassous.betterhrbust.navigation.ProfileRoute),
        QuickNavGridItem("教学进程", Icons.Default.EventNote, com.glassous.betterhrbust.navigation.TeachingProcessRoute)
    )

    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    top = LocalTopContentInset.current + 32.dp,
                    bottom = LocalBottomContentInset.current + 24.dp
                )
            ) {
            // 页面标题（作为滚动内容，可穿透状态栏）
            item { PageHeaderTitle("概览", emphasized = true) }

            // 关键统计卡片：MD3E hero 容器（28dp 圆角 + secondaryContainer），低饱和容器承载大字号数值
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Hero,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .pressClickable(pressedScale = 0.98f) {
                                    onNavigate(com.glassous.betterhrbust.navigation.ScoresRoute)
                                }
                        ) {
                            Text(
                                text = stats?.gpa?.let { String.format("%.2f", it) } ?: "--",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "GPA",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .pressClickable(pressedScale = 0.98f) {
                                    onNavigate(com.glassous.betterhrbust.navigation.TimetableRoute)
                                }
                        ) {
                            Text(
                                text = "${todayCourses.size} 门",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "今日剩余课程",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }

            // 成绩卡片：点击跳转至成绩页，移除副标题与底部小字，数值不显示红色
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressClickable {
                            onNavigate(com.glassous.betterhrbust.navigation.ScoresRoute)
                        },
                    shape = AppShapes.Card,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "成绩",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 计算说明入口：卡片右上角问号（点击只弹说明，不透传到卡片的跳转）
                                GpaCalculationHelp(
                                    iconOnly = true,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "查看成绩",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MiniStatColumn(
                                label = "学位绩点",
                                value = stats?.degree?.gpa?.let { String.format("%.2f", it) } ?: "--"
                            )
                            MiniStatColumn(
                                label = "补考/重修",
                                value = stats?.retakeCount?.toString() ?: "--"
                            )
                            MiniStatColumn(
                                label = "挂科学分",
                                value = stats?.risk?.failedCredits?.let { trimNumber(it) } ?: "--"
                            )

                        }
                    }
                }
            }

            // Upcoming Exam Card (if any)
            if (upcomingExam != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressClickable {
                                onNavigate(com.glassous.betterhrbust.navigation.ExamsRoute)
                            },
                        shape = AppShapes.Hero,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "近期考试：${upcomingExam.courseName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${upcomingExam.time} | ${upcomingExam.location}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Surface(
                                shape = AppShapes.Pill,
                                color = MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    text = "还有 ${upcomingExam.countdownDays} 天",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Today's Courses Section
            item {
                Text(
                    text = "今日课程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (todayCourses.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "今日已无待上课程", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                items(todayCourses.size) { index ->
                    val course = todayCourses[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressClickable {
                                onNavigate(com.glassous.betterhrbust.navigation.TimetableRoute)
                            },
                        shape = AppShapes.Card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = AppShapes.Pill,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${course.sectionIndex}",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = course.courseName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (course.isCustomCourse) {
                                        Surface(
                                            shape = AppShapes.Pill,
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = if (course.customCategory == "lab") "实验" else "自定",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else if (course.isRescheduled) {
                                        Surface(
                                            shape = AppShapes.Pill,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "调课",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = buildString {
                                        append(course.location.ifBlank { "待定" })
                                        if (course.teacher.isNotBlank()) append(" • ${course.teacher}")
                                        if (course.customTimeText.isNotBlank()) append(" • ${course.customTimeText}")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = AppShapes.Pill,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = course.customTimeText.ifBlank { course.sectionLabel },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Nav Grid Section
            item {
                Text(
                    text = "功能导航",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                        for (row in quickNavItems.chunked(4)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (item in row) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .pressClickable(pressedScale = 0.94f) {
                                                onNavigate(item.route)
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Surface(
                                            shape = AppShapes.Pill,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = item.icon,
                                                    contentDescription = item.title,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
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

/** 去除学分等数值末尾多余的 .0，保持紧凑展示 */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

/** 概览页指标迷你列（统一使用主题色，不再显示红色） */
@Composable
private fun MiniStatColumn(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

