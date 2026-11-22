package com.glassous.betterhrbust.feature.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.CurrentCourseItem
import com.glassous.betterhrbust.core.model.ScoreResult
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.util.GpaCalculator
import com.glassous.betterhrbust.core.util.MyCourseCatalog
import com.glassous.betterhrbust.core.util.MyCourseFilter
import com.glassous.betterhrbust.core.util.MyCourseItem
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(
    onBack: () -> Unit,
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

    var currentCourses by remember { mutableStateOf<List<CurrentCourseItem>>(emptyList()) }
    var currentCoursesLoaded by remember { mutableStateOf(false) }
    var scoreResult by remember { mutableStateOf<ScoreResult?>(null) }
    var cacheError by remember { mutableStateOf("") }
    var syncError by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(MyCourseFilter.ALL) }

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        if (currentPrefs.studentId.isEmpty()) return
        coroutineScope.launch {
            try {
                academicRepo.getCurrentCourses(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            currentCourses = res.data
                            currentCoursesLoaded = true
                            syncError = ""
                        }
                        is Resource.Error -> {
                            currentCourses = emptyList()
                            currentCoursesLoaded = false
                        }
                        else -> Unit
                    }
                }
                academicRepo.getScores(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            scoreResult = res.data
                            cacheError = ""
                        }
                        is Resource.Error -> {
                            scoreResult = null
                            cacheError = res.message
                        }
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
            syncError = ""
            try {
                val outcome = syncManager.syncAll(manual = true)
                if (outcome.expired) {
                    authRepo.markSessionExpired(true)
                } else if (!outcome.skipped) {
                    if (!outcome.success && outcome.message.isNotBlank()) {
                        syncError = outcome.message
                    }
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

    var wasSyncing by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncing) {
        if (wasSyncing && !isSyncing && prefs?.studentId?.isNotEmpty() == true) {
            loadCache()
        }
        wasSyncing = isSyncing
    }

    val allCourses = remember(currentCourses, scoreResult) {
        MyCourseCatalog.build(
            currentCourses = currentCourses,
            scores = scoreResult?.scores.orEmpty()
        )
    }

    val filteredCourses = remember(allCourses, searchQuery, selectedFilter) {
        MyCourseCatalog.filter(allCourses, searchQuery, selectedFilter)
    }

    val currentCount = remember(allCourses) { allCourses.count { it.isCurrent } }
    val requiredCount = remember(allCourses) { allCourses.count { GpaCalculator.isRequired(it.property) } }
    val electiveCount = remember(allCourses) {
        allCourses.count { !GpaCalculator.isRequired(it.property) && it.property.contains("选") }
    }

    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (allCourses.isEmpty() && (isRefreshing || isSyncing)) {
                LoadingView(
                    message = "正在加载我的课程名录...",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = pageTopBarTopPadding())
                )
            } else if (allCourses.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = pageTopBarTopPadding(),
                            bottom = LocalBottomContentInset.current + 24.dp,
                            start = 16.dp,
                            end = 16.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyView(
                        title = "暂无课程名录数据",
                        description = syncError.ifEmpty { cacheError }.ifEmpty { "请下拉刷新或点击下方按钮同步本学期与历史课程" },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { refreshAll() },
                        shape = AppShapes.Pill
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("立即同步")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        top = pageTopBarTopPadding(),
                        bottom = LocalBottomContentInset.current + 24.dp
                    )
                ) {
                    if (!currentCoursesLoaded) {
                        item {
                            Surface(
                                shape = AppShapes.Card,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "本学期在修课程尚未缓存，同步后可补齐",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { refreshAll() }) {
                                        Text("同步")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("检索课程名称、课程号、教师或课组...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .registerInputField(),
                            shape = AppTextFieldShape
                        )
                    }

                    item {
                        val counts = mapOf(
                            MyCourseFilter.ALL to allCourses.size,
                            MyCourseFilter.CURRENT to currentCount,
                            MyCourseFilter.REQUIRED to requiredCount,
                            MyCourseFilter.ELECTIVE to electiveCount
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(MyCourseFilter.entries) { filter ->
                                val selected = selectedFilter == filter
                                val count = counts[filter] ?: 0
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedFilter = filter },
                                    label = { Text("${filter.label} ($count)") },
                                    shape = AppShapes.Pill
                                )
                            }
                        }
                    }

                    if (filteredCourses.isEmpty()) {
                        item {
                            EmptyView(
                                title = "未找到匹配的课程",
                                description = "请尝试调整检索关键词或筛选条件",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                            )
                        }
                    } else {
                        items(
                            items = filteredCourses,
                            key = { "${it.isCurrent}_${it.id}_${it.name}" }
                        ) { course ->
                            MyCourseCard(course = course)
                        }
                    }
                }
            }
        }
        FixedPageTopBar(title = "我的课程名录", onBack = onBack)
    }
}

@Composable
private fun MyCourseCard(course: MyCourseItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = course.id.ifEmpty { "暂无课号" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (course.isCurrent) {
                        Surface(
                            shape = AppShapes.Pill,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "本学期",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    } else if (course.passed != null) {
                        val badgeLabel = when {
                            course.pending -> "待出分"
                            course.passed -> "已通过"
                            else -> "未通过"
                        }
                        Surface(
                            shape = AppShapes.Pill,
                            color = when {
                                course.pending -> MaterialTheme.colorScheme.surfaceContainerHighest
                                course.passed -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    course.pending -> MaterialTheme.colorScheme.onSurfaceVariant
                                    course.passed -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Surface(
                        shape = AppShapes.Pill,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = course.property,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = course.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))
            val metaParts = buildList {
                if (course.semester.isNotEmpty()) add(course.semester)
                if (course.group.isNotEmpty()) add(course.group)
                add("${trimCredits(course.credit)} 学分")
                if (course.hours > 0) add("${course.hours} 学时")
            }
            Text(
                text = metaParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val hasFooter = course.isCurrent || course.latestScore.isNotEmpty()
            if (hasFooter) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Spacer(modifier = Modifier.height(10.dp))

                if (course.isCurrent) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "任课教师：${course.teacher.ifEmpty { "未提供教师信息" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (course.examWay.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = course.examWay,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (course.timeAndPlace.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = course.timeAndPlace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (course.latestScore.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = course.examType.ifEmpty { "正常考试" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "总评：${course.latestScore}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun trimCredits(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
