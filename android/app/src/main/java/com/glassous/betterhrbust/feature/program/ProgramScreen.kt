package com.glassous.betterhrbust.feature.program

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.CreditCategory
import com.glassous.betterhrbust.core.model.CreditsProgress
import com.glassous.betterhrbust.core.model.CurriculumGroup
import com.glassous.betterhrbust.core.model.CurriculumPlanResult
import com.glassous.betterhrbust.core.model.PersonalInfo
import com.glassous.betterhrbust.core.model.PlanCourseDetail
import com.glassous.betterhrbust.core.model.ScoreResult
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.util.GpaCalculator
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(
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
    var planResult by remember { mutableStateOf<CurriculumPlanResult?>(null) }
    var scoreResult by remember { mutableStateOf<ScoreResult?>(null) }
    var profile by remember { mutableStateOf<PersonalInfo?>(null) }
    var cacheError by remember { mutableStateOf("") }
    var syncError by remember { mutableStateOf("") }

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                academicRepo.getCurriculumPlan(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            planResult = res.data
                            cacheError = ""
                            syncError = ""
                        }
                        is Resource.Error -> {
                            planResult = null
                            cacheError = res.message
                        }
                        else -> Unit
                    }
                }
                academicRepo.getScores(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    if (res is Resource.Success) scoreResult = res.data
                }
                if (currentPrefs.username.isNotEmpty()) {
                    academicRepo.getPersonalInfo(currentPrefs.username, cacheOnly = true).collect { res ->
                        if (res is Resource.Success) profile = res.data
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

    // 任意后台/手动同步完成时自动重读缓存，避免 lastFullSyncDate 未变化时页面不刷新
    var wasSyncing by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncing) {
        if (wasSyncing && !isSyncing && prefs?.studentId?.isNotEmpty() == true) {
            loadCache()
        }
        wasSyncing = isSyncing
    }

    val groups = planResult?.groups ?: emptyList()

    // 与概览页共用同一口径：即使成绩尚未同步，也根据培养方案展示总要求学分
    val creditsProgress: CreditsProgress? = remember(scoreResult, planResult) {
        if (planResult == null && scoreResult == null) null
        else GpaCalculator.computeCreditsProgress(
            scores = scoreResult?.scores.orEmpty(),
            groups = groups,
            planTotalCredits = planResult?.totalRequiredCredits
        )
    }

    val earnedByCategory: Map<String, CreditCategory> = remember(creditsProgress) {
        creditsProgress?.categories?.associateBy { it.name } ?: emptyMap()
    }
    val groupNameSet = remember(groups) { groups.map { it.name }.toSet() }
    val extraCategories = remember(creditsProgress, groupNameSet) {
        creditsProgress?.categories?.filter { it.name !in groupNameSet } ?: emptyList()
    }

    val passedKeys = remember(scoreResult) {
        val codes = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        scoreResult?.scores
            ?.let { GpaCalculator.dedupeScores(it) }
            ?.forEach { deduped ->
                val item = deduped.item
                if (item.passed && !item.pending) {
                    if (item.courseId.isNotBlank()) codes.add(item.courseId.trim())
                    if (item.courseName.isNotBlank()) names.add(item.courseName.trim())
                }
            }
        codes to names
    }

    // 固定顶栏：标题与返回按钮不随内容滚动；空态保持可滚动以便下拉刷新生效
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (groups.isEmpty() && (isRefreshing || isSyncing)) {
                LoadingView(
                    message = "正在获取培养方案...",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = pageTopBarTopPadding())
                )
            } else if (groups.isEmpty()) {
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
                        title = "暂无培养方案数据",
                        description = syncError.ifEmpty { cacheError }.ifEmpty { "请下拉刷新或点击下方按钮同步培养方案" },
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(
                        top = pageTopBarTopPadding(),
                        bottom = LocalBottomContentInset.current + 24.dp
                    )
                ) {
                    // Summary Card
                    item {
                        val earnedText = trimCredits(creditsProgress?.earnedTotal ?: 0.0)
                        val requiredText = creditsProgress?.requiredTotal?.takeIf { it > 0 }
                            ?.let { "${trimCredits(it)} 学分" } ?: "待同步"
                        val majorSummary = remember(profile) {
                            val p = profile ?: return@remember ""
                            val grade = p.grade.trim().replace(Regex("(?:\\s*级)+$"), "")
                                .takeIf { it.isNotEmpty() }?.let { "${it}级" } ?: ""
                            listOfNotNull(
                                p.college.takeIf { it.isNotBlank() },
                                p.major.takeIf { it.isNotBlank() }
                            ).joinToString(" · ") + if (grade.isNotEmpty()) " ($grade)" else ""
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppShapes.Hero,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(22.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "毕业学分进度",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "$earnedText / $requiredText",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Text(
                                        text = "${creditsProgress?.completionPercent ?: 0}%",
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (majorSummary.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = majorSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                // MD3E 波浪进度：把「学分完成度」做成页面的一个高光时刻
                                LinearWavyProgressIndicator(
                                    progress = {
                                        ((creditsProgress?.completionPercent ?: 0) / 100f)
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = GpaCalculator.EARNED_CREDITS_NOTE,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "课组要求（${groups.size}）",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    items(groups, key = { it.id.ifBlank { it.name } }) { group ->
                        CurriculumGroupCard(
                            group = group,
                            earned = earnedByCategory[group.name],
                            passedKeys = passedKeys
                        )
                    }

                    if (extraCategories.isNotEmpty()) {
                        items(extraCategories, key = { "extra_${it.name}" }) { extra ->
                            ExtraCategoryCard(category = extra)
                        }
                    }
                }
            }
        }
        FixedPageTopBar(title = "培养方案", onBack = onBack)
    }
}

@Composable
fun CurriculumGroupCard(
    group: CurriculumGroup,
    earned: CreditCategory? = null,
    passedKeys: Pair<Set<String>, Set<String>> = emptySet<String>() to emptySet()
) {
    var expanded by remember { mutableStateOf(false) }

    val earnedCredits = earned?.earned ?: 0.0
    val requiredCredits = earned?.required ?: group.requiredCredits
    val qualified = requiredCredits > 0 && earnedCredits >= requiredCredits
    val statusText = when {
        qualified -> "已达标"
        requiredCredits > 0 -> "修读中"
        else -> "未设要求"
    }
    val progress = if (requiredCredits > 0) {
        (earnedCredits / requiredCredits).toFloat().coerceIn(0f, 1f)
    } else {
        if (earnedCredits > 0) 1f else 0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = group.courses.isNotEmpty()) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = AppShapes.Pill,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = group.property,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = AppShapes.Pill,
                            color = if (qualified) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (qualified) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val courseReqText = if (GpaCalculator.isProfessionalElectiveGroup(group) && group.courses.isNotEmpty()) {
                        "${group.courses.size} 选 4"
                    } else {
                        "${GpaCalculator.planGroupRequiredCourses(group)} 门"
                    }
                    Text(
                        text = "要求 ${trimCredits(requiredCredits)} 学分 · $courseReqText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (group.courses.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 已获 / 要求 学分对照（与概览页同口径）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "学分进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${trimCredits(earnedCredits)} / ${trimCredits(requiredCredits)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            if (GpaCalculator.EARNED_CREDITS_REQUIRED_ONLY &&
                (GpaCalculator.resolveProperty(group.property) == "limited" ||
                    GpaCalculator.resolveProperty(group.property) == "elective")
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "选修类课程学分不计入已获得学分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (group.courses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) "收起课组内课程" else "查看课组内课程（${group.courses.size} 门）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(visible = expanded && group.courses.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (c in group.courses) {
                        val isPassed = isCoursePassed(c, passedKeys)
                        Surface(
                            shape = AppShapes.InnerCard,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = c.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val metaParts = buildList {
                                        if (c.code.isNotBlank()) add(c.code)
                                        if (c.property.isNotBlank()) add(c.property)
                                        if (c.hours > 0) add("${c.hours} 学时")
                                    }
                                    Text(
                                        text = metaParts.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${trimCredits(c.credit)} 学分",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = AppShapes.Pill,
                                        color = if (isPassed) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        }
                                    ) {
                                        Text(
                                            text = if (isPassed) "已通过" else "未修/未过",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isPassed) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

@Composable
private fun ExtraCategoryCard(category: CreditCategory) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = category.property.ifBlank { "任选" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${trimCredits(category.earned)} 学分",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun isCoursePassed(
    course: PlanCourseDetail,
    passedKeys: Pair<Set<String>, Set<String>>
): Boolean {
    val (codes, names) = passedKeys
    if (course.code.isNotBlank() && course.code.trim() in codes) return true
    if (course.name.isNotBlank() && course.name.trim() in names) return true
    return false
}

private fun trimCredits(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
