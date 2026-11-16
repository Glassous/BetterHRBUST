package com.glassous.betterhrbust.feature.scores

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.ScoreItem
import com.glassous.betterhrbust.core.model.ScoreResult
import com.glassous.betterhrbust.core.model.ScoreStats
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.PageHeaderTitle
import com.glassous.betterhrbust.core.ui.components.ResponsiveStatGrid
import com.glassous.betterhrbust.core.ui.components.SegmentedControl
import com.glassous.betterhrbust.core.ui.components.StatEntry
import com.glassous.betterhrbust.core.ui.isTabletDevice
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.AppMotion
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.ui.theme.pressClickable
import com.glassous.betterhrbust.core.util.GpaCalculator
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen(
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
    var scoreResult by remember { mutableStateOf<ScoreResult?>(null) }
    var cacheError by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var selectedSemester by remember { mutableStateOf("全部") } // 全部 或 "2024 春" 形式的学期标签
    var selectedProperty by remember { mutableStateOf("全部") } // 全部, 必修, 限选, 任选
    var selectedPassStatus by remember { mutableStateOf("全部") } // 全部, 仅及格, 未通过

    var selectedItem by remember { mutableStateOf<ScoreItem?>(null) }
    val navigator = rememberListDetailPaneScaffoldNavigator<ScoreItem>()
    // 手机：明细用 bottom sheet；平板保留双栏
    val isTablet = isTabletDevice()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                academicRepo.getScores(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            scoreResult = res.data
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

    val stats = remember(scoreResult) {
        scoreResult?.scores?.let { AcademicParsers.calculateGpaStats(it) }
    }

    // 数值刷新时滚动过渡，避免直接跳变
    val animatedGpa by animateFloatAsState(
        targetValue = (stats?.gpa ?: 0.0).toFloat(),
        animationSpec = tween(AppMotion.SmoothMillis),
        label = "gpa"
    )
    val animatedAvg by animateFloatAsState(
        targetValue = (stats?.weightedAvg ?: 0.0).toFloat(),
        animationSpec = tween(AppMotion.SmoothMillis),
        label = "avg"
    )
    val animatedEarned by animateFloatAsState(
        targetValue = (stats?.earnedCredits ?: 0.0).toFloat(),
        animationSpec = tween(AppMotion.SmoothMillis),
        label = "earnedCredits"
    )
    val animatedTotal by animateFloatAsState(
        targetValue = (stats?.totalCredits ?: 0.0).toFloat(),
        animationSpec = tween(AppMotion.SmoothMillis),
        label = "totalCredits"
    )
    val animatedFailed by animateIntAsState(
        targetValue = stats?.failedCount ?: 0,
        animationSpec = tween(AppMotion.SmoothMillis),
        label = "failedCount"
    )

    // 同一门课的重修/补考记录合并去重后展示
    val dedupedScores = remember(scoreResult) {
        scoreResult?.scores?.let { GpaCalculator.dedupeScores(it) } ?: emptyList()
    }
    // 键规则与 GpaCalculator.courseKey 保持一致（courseId 为空时回退 courseName）
    val recordCountMap = remember(dedupedScores) {
        dedupedScores.associate { it.item.courseId.ifEmpty { it.item.courseName } to it.recordCount }
    }

    // 学期筛选项：各学期按时间先后倒序（最新在前），排序口径与 Web 端 semesterSortKey 一致
    val semesterOptions = remember(dedupedScores) {
        dedupedScores.map { it.item }
            .distinctBy { GpaCalculator.semesterLabel(it.year, it.term) }
            .sortedByDescending { GpaCalculator.semesterSortKey(it.year, it.term) }
            .map { GpaCalculator.semesterLabel(it.year, it.term) }
    }

    // 数据变化（切换账号 / 重新同步）后，若所选学期已不存在则回退到"全部"
    LaunchedEffect(semesterOptions) {
        if (selectedSemester != "全部" && selectedSemester !in semesterOptions) {
            selectedSemester = "全部"
        }
    }

    val filteredScores = remember(dedupedScores, searchQuery, selectedSemester, selectedProperty, selectedPassStatus) {
        val all = dedupedScores.map { it.item }
        all.filter { item ->
            val matchQuery = searchQuery.isBlank() || item.courseName.contains(searchQuery, ignoreCase = true) || item.courseId.contains(searchQuery, ignoreCase = true)
            val matchSemester = selectedSemester == "全部" || GpaCalculator.semesterLabel(item.year, item.term) == selectedSemester
            val matchProp = selectedProperty == "全部" || item.property.contains(selectedProperty)
            val matchPass = when (selectedPassStatus) {
                "仅及格" -> item.passed
                "未通过" -> !item.passed
                else -> true
            }
            matchQuery && matchSemester && matchProp && matchPass
        }
    }

    BackHandler(enabled = isTablet && navigator.canNavigateBack()) {
        coroutineScope.launch {
            navigator.navigateBack()
        }
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        modifier = modifier.fillMaxSize(),
        listPane = {
            AppPullToRefreshBox(
                isRefreshing = isRefreshing || isSyncing,
                onRefresh = { refreshAll() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(
                        top = LocalTopContentInset.current + 32.dp,
                        bottom = LocalBottomContentInset.current + 24.dp
                    )
                ) {
                    item { PageHeaderTitle("成绩单", emphasized = true) }

                    // 学业概览：手机 2×2 / 平板 1×4 自适应
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppShapes.Hero,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(22.dp)) {
                                Text(
                                    text = "学业概览",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                ResponsiveStatGrid(
                                    entries = listOf(
                                        StatEntry(
                                            label = "GPA",
                                            value = String.format("%.2f", animatedGpa),
                                            valueColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        StatEntry(
                                            label = "均分",
                                            value = String.format("%.1f", animatedAvg),
                                            valueColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        StatEntry(
                                            label = "学分",
                                            value = "${trimCredit(animatedEarned)}/${trimCredit(animatedTotal)}"
                                        ),
                                        StatEntry(
                                            label = "挂科",
                                            value = "$animatedFailed",
                                            valueColor = if (animatedFailed > 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            }
                                        )
                                    )
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = GpaCalculator.EARNED_CREDITS_NOTE,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // 特色学业算法：学位证 / 推免 / 学业风险预警
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppShapes.Card,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "特色学业算法",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = GpaCalculator.STATS_SCOPE_NOTE,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                FeatureAlgorithmRow(
                                    title = "① 学位证算法",
                                    value = stats?.degree?.gpa?.let { String.format("%.2f", it) } ?: "--",
                                    qualified = stats?.degree?.qualified == true,
                                    detail = stats?.degree?.let {
                                        "门槛 ${it.threshold} · " +
                                            "E 类最高 1 门 + 剩余 A–E 类最高 1 门"
                                    } ?: "暂无数据"
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                FeatureAlgorithmRow(
                                    title = "② 推免资格自检",
                                    value = stats?.recommend?.let { "${it.retakeCount}/${it.retakeLimit}" } ?: "--",
                                    qualified = stats?.recommend?.retakeWithinLimit == true,
                                    statusText = stats?.recommend?.let { if (it.retakeWithinLimit) "良好" else "超过上限 / 需注意" } ?: "暂无数据",
                                    detail = stats?.recommend?.let {
                                        "补考 + 重修累计，上限 ${it.retakeLimit} 门 · " + if (it.courseCount == 0) "暂无必修成绩记录" else if (it.allPassed) "必修成绩全部合格" else "仍有未合格的必修成绩"
                                    } ?: "暂无数据"
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                FeatureAlgorithmRow(
                                    title = "③ 学业风险预警",
                                    value = stats?.risk?.label ?: "--",
                                    qualified = stats?.risk?.level == "none",
                                    detail = stats?.risk?.description ?: "暂无数据"
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                            }
                        }
                    }

                    // Search and Filters
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索课程名或课号...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = null)
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

                    // Semester Filter Chips（最新学期在前）
                    if (semesterOptions.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = selectedSemester == "全部",
                                        onClick = { selectedSemester = "全部" },
                                        label = { Text("全部学期") },
                                        shape = AppShapes.Pill
                                    )
                                }
                                items(semesterOptions) { label ->
                                    FilterChip(
                                        selected = selectedSemester == label,
                                        onClick = { selectedSemester = label },
                                        label = { Text(label) },
                                        shape = AppShapes.Pill
                                    )
                                }
                            }
                        }
                    }

                    // Segmented Row: Property（MD3E 胶囊分段控件，替代已淘汰的 SegmentedButton）
                    item {
                        val propOptions = listOf("全部", "必修", "限选", "任选")
                        SegmentedControl(
                            options = propOptions.map { it to it },
                            selected = selectedProperty,
                            onSelect = { selectedProperty = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Segmented Row: Pass Status
                    item {
                        val statusOptions = listOf("全部", "仅及格", "未通过")
                        SegmentedControl(
                            options = statusOptions.map { it to it },
                            selected = selectedPassStatus,
                            onSelect = { selectedPassStatus = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Results count
                    item {
                        AnimatedContent(
                            targetState = filteredScores.size,
                            transitionSpec = {
                                (fadeIn(tween(AppMotion.SmoothMillis)) +
                                    slideInVertically(tween(AppMotion.SmoothMillis)) { it / 2 })
                                    .togetherWith(fadeOut(tween(120)))
                            },
                            label = "resultCount"
                        ) { count ->
                            Text(
                                text = "$count 门课程",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (filteredScores.isEmpty()) {
                        item {
                            EmptyView(
                                title = "未找到成绩记录",
                                // 仅当本地确实没有成绩缓存时才提示刷新引导，避免筛选无结果时误报
                                description = if (dedupedScores.isEmpty() && cacheError.isNotEmpty()) {
                                    cacheError
                                } else {
                                    "请尝试更换筛选条件"
                                }
                            )
                        }
                    } else {
                        items(
                            items = filteredScores,
                            key = { it.courseId.ifEmpty { it.courseName } }
                        ) { item ->
                            val isSelected = selectedItem == item
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .pressClickable(pressedScale = 0.985f) {
                                        selectedItem = item
                                        if (isTablet) {
                                            coroutineScope.launch {
                                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item)
                                            }
                                        }
                                    },
                                shape = AppShapes.Card,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.courseName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            AnimatedVisibility(
                                                visible = !item.passed,
                                                enter = fadeIn(tween(AppMotion.SmoothMillis)) +
                                                    scaleIn(initialScale = 0.85f)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Surface(
                                                        shape = AppShapes.Pill,
                                                        color = MaterialTheme.colorScheme.errorContainer
                                                    ) {
                                                        Text(
                                                            text = "未通过",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            val recordCount =
                                                recordCountMap[item.courseId.ifEmpty { item.courseName }] ?: 1
                                            AnimatedVisibility(
                                                visible = recordCount > 1,
                                                enter = fadeIn(tween(AppMotion.SmoothMillis)) +
                                                    scaleIn(initialScale = 0.85f)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Surface(
                                                        shape = AppShapes.Pill,
                                                        color = MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Text(
                                                            text = "补考/重修已合并",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "${GpaCalculator.semesterLabel(item.year, item.term)} • ${item.property} • ${item.credit} 学分",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))
                                    // 仅不及格课程标红：60–69 分正常显示，避免把刚及格的成绩误读为风险
                                    Surface(
                                        shape = AppShapes.Pill,
                                        color = if (!item.passed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = item.score,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (!item.passed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        detailPane = {
            val item = selectedItem ?: navigator.currentDestination?.contentKey
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = LocalTopContentInset.current + 16.dp,
                        bottom = LocalBottomContentInset.current + 16.dp
                    ),
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                if (item != null) {
                    ScoreDetailContent(
                        item = item,
                        onClose = if (navigator.canNavigateBack()) {
                            { coroutineScope.launch { navigator.navigateBack() } }
                        } else null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "选择左侧课程查看详情",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    )

    // 手机端：课程明细使用 M3 bottom sheet（平板仍为双栏 detail pane）
    if (!isTablet) {
        selectedItem?.let { item ->
            ModalBottomSheet(
                onDismissRequest = { selectedItem = null },
                sheetState = sheetState,
                shape = AppShapes.Sheet,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                sheetMaxWidth = 640.dp
            ) {
                ScoreDetailContent(
                    item = item,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

/**
 * 成绩明细内容：手机端放入 bottom sheet，平板端放入双栏 detail pane。
 * 各分区错峰淡入，不使用分割线，靠分组卡片与间距表达层级。
 */
@Composable
private fun ScoreDetailContent(
    item: ScoreItem,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    var contentVisible by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) { contentVisible = true }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DetailSection(visible = contentVisible, index = 0) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "课程成绩明细",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${item.courseId} · ${item.courseSeq}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        DetailSection(visible = contentVisible, index = 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreMetricTile(
                    label = "总评分数",
                    value = item.score,
                    highlighted = true,
                    modifier = Modifier.weight(1f)
                )
                ScoreMetricTile(
                    label = "学分",
                    value = "${item.credit}",
                    modifier = Modifier.weight(1f)
                )
                ScoreMetricTile(
                    label = "学时",
                    value = "${item.hours}",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        DetailSection(visible = contentVisible, index = 2) {
            DetailGroup(title = "修读信息") {
                ScoreDetailRow("修读学年学期", "${item.year} 学年 第 ${item.term} 学期")
                ScoreDetailRow("选课属性", item.property)
                ScoreDetailRow("所属课组", item.courseGroup.ifEmpty { "无" })
                ScoreDetailRow("考试性质", item.examType)
            }
        }

        DetailSection(visible = contentVisible, index = 3) {
            DetailGroup(title = "成绩信息") {
                ScoreDetailRow(
                    label = "及格状态",
                    value = if (item.passed) "及格 (${item.passMark})" else "未通过 (${item.passMark})",
                    valueColor = if (item.passed) Color.Unspecified else MaterialTheme.colorScheme.error
                )
                ScoreDetailRow("备注", item.remark.ifEmpty { "无" })
            }
        }
    }
}

@Composable
private fun DetailSection(
    visible: Boolean,
    index: Int,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            tween(
                durationMillis = AppMotion.SmoothMillis,
                delayMillis = AppMotion.stagger(index)
            )
        ) + slideInVertically(
            tween(
                durationMillis = 320,
                delayMillis = AppMotion.stagger(index)
            )
        ) { it / 6 }
    ) {
        Column { content() }
    }
}

@Composable
private fun ScoreMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = AppShapes.InnerCard,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
    }
}

@Composable
private fun DetailGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = { content() }
            )
        }
    }
}

@Composable
private fun ScoreDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurface
            } else {
                valueColor
            }
        )
    }
}

/** 学分显示去掉无意义的 “.0” */
private fun trimCredit(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format("%.1f", value)

/** 特色算法条目：标题 + 关键指标 + 达标状态 + 说明 */
@Composable
private fun FeatureAlgorithmRow(
    title: String,
    value: String,
    qualified: Boolean,
    detail: String,
    statusText: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(
                text = statusText ?: if (qualified) "达标 / 符合" else "未达标 / 需注意",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
