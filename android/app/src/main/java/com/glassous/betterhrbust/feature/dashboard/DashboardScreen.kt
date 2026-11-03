package com.glassous.betterhrbust.feature.dashboard

import androidx.compose.foundation.clickable
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

    val todayCourses = remember(timetable, todayDayOfWeek, currentWeek, clock) {
        timetable?.cells?.filter { cell ->
            prefs?.teachingWeekOn(today) != null && cell.day == todayDayOfWeek && AcademicParsers.isCourseActiveInWeek(cell.weeks, currentWeek) && !com.glassous.betterhrbust.core.util.CourseSchedule.hasEnded(cell.sectionIndex, clock.toLocalTime())
        }?.sortedBy { it.sectionIndex } ?: emptyList()
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
        QuickNavGridItem("通知与设置", Icons.Default.Settings, com.glassous.betterhrbust.navigation.SettingsRoute)
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

            // 关键统计卡片：显示 GPA 与 今日课程安排（n节）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigate(com.glassous.betterhrbust.navigation.ScoresRoute) }
                        ) {
                            Text(
                                text = stats?.gpa?.let { String.format("%.2f", it) } ?: "--",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "GPA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        VerticalDivider(modifier = Modifier.height(40.dp))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigate(com.glassous.betterhrbust.navigation.TimetableRoute) }
                        ) {
                            Text(
                                text = "${todayCourses.size} 门",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "今日剩余课程",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
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
                        .clickable { onNavigate(com.glassous.betterhrbust.navigation.ScoresRoute) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "成绩",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 计算说明入口：卡片右上角问号（点击只弹说明，不透传到卡片的跳转）
                                GpaCalculationHelp(
                                    iconOnly = true,
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "查看成绩",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
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
                            .clickable { onNavigate(com.glassous.betterhrbust.navigation.ExamsRoute) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "近期考试：${upcomingExam.courseName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${upcomingExam.time} | ${upcomingExam.location}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    text = "还有 ${upcomingExam.countdownDays} 天",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (todayCourses.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                            Text(text = "今日已无待上课程", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                items(todayCourses.size) { index ->
                    val course = todayCourses[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(com.glassous.betterhrbust.navigation.TimetableRoute) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${course.sectionIndex}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.courseName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${course.location} • ${course.teacher}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = course.sectionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Quick Nav Grid Section
            item {
                Text(
                    text = "功能导航",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        for (row in quickNavItems.chunked(4)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (item in row) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onNavigate(item.route) }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = item.icon,
                                                    contentDescription = item.title,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelSmall,
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

