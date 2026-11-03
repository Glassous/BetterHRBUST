package com.glassous.betterhrbust.feature.program

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.CreditCategory
import com.glassous.betterhrbust.core.model.CurriculumGroup
import com.glassous.betterhrbust.core.model.CreditsProgress
import com.glassous.betterhrbust.core.model.CurriculumPlanResult
import com.glassous.betterhrbust.core.model.ScoreResult
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
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
    var cacheError by remember { mutableStateOf("") }

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
                        }
                        is Resource.Error -> cacheError = res.message
                        else -> Unit
                    }
                }
                academicRepo.getScores(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    if (res is Resource.Success) scoreResult = res.data
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

    val groups = planResult?.groups ?: emptyList()

    // 与概览页共用同一口径：必修课去重后的已获学分，保证两页数据完全一致
    val creditsProgress: CreditsProgress? = remember(scoreResult, planResult) {
        scoreResult?.scores?.let { scores ->
            GpaCalculator.computeCreditsProgress(scores, groups, planTotalCredits = planResult?.totalRequiredCredits)
        }
    }

    val earnedByCategory: Map<String, CreditCategory> = remember(creditsProgress) {
        creditsProgress?.categories?.associateBy { it.name } ?: emptyMap()
    }

    // 固定顶栏：标题与返回按钮不随内容滚动，列表滚起来后顶栏底色转为不透明；
    // 加载 / 空态同样盖在顶栏之下，避免打开后无处退出
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (groups.isEmpty() && (isRefreshing || isSyncing)) {
                LoadingView(message = "正在获取培养方案...")
            } else if (groups.isEmpty()) {
                EmptyView(
                    title = "暂无培养方案数据",
                    description = cacheError.ifEmpty { "请下拉刷新加载方案" }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(
                        top = pageTopBarTopPadding(),
                        bottom = LocalBottomContentInset.current + 24.dp
                    )
                ) {

                    // Summary Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "毕业学分进度",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${creditsProgress?.earnedTotal ?: 0.0} / " +
                                                creditsProgress?.requiredTotal?.takeIf { it > 0 }?.let { "$it 学分" } ?: "待同步",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = "${creditsProgress?.completionPercent ?: 0}%",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        ((creditsProgress?.completionPercent ?: 0) / 100f)
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = GpaCalculator.EARNED_CREDITS_NOTE,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "课组要求（${groups.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(groups) { group ->
                        CurriculumGroupCard(
                            group = group,
                            earned = earnedByCategory[group.name]
                        )
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
    earned: CreditCategory? = null
) {
    var expanded by remember { mutableStateOf(false) }

    val earnedCredits = earned?.earned ?: 0.0
    val requiredCredits = earned?.required ?: group.requiredCredits
    val qualified = requiredCredits > 0 && earnedCredits >= requiredCredits
    val progress = if (requiredCredits > 0) {
        (earnedCredits / requiredCredits).toFloat().coerceIn(0f, 1f)
    } else {
        if (earnedCredits > 0) 1f else 0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = group.property,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "要求 $requiredCredits 学分 · " + if (GpaCalculator.isProfessionalElectiveGroup(group) && group.courses.isNotEmpty()) "${group.courses.size} 选 4" else "${GpaCalculator.planGroupRequiredCourses(group)} 门",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 已获 / 要求 学分对照（与概览页同口径）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "学分进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = "$earnedCredits / $requiredCredits",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )

            if (GpaCalculator.resolveProperty(group.property) == "limited" ||
                GpaCalculator.resolveProperty(group.property) == "elective"
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "选修课学分不计入已获得学分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            AnimatedVisibility(visible = expanded && group.courses.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    Text(
                        text = "课程（${group.courses.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    for (c in group.courses) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(text = "课号: ${c.code} • ${c.property}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Text(text = "${c.credit} 学分", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
