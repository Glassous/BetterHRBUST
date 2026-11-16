package com.glassous.betterhrbust.feature.exams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.ExamItem
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.PageHeaderTitle
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(
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
    var examList by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var cacheError by remember { mutableStateOf("") }

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                academicRepo.getExams(currentPrefs.studentId, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            examList = res.data
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

    val upcomingExams = remember(examList) {
        examList.filter { (it.countdownDays ?: 0) >= 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (examList.isEmpty() && (isRefreshing || isSyncing)) {
                LoadingView(message = "正在获取考试安排...")
            } else if (examList.isEmpty()) {
                EmptyView(
                    title = "暂无考试安排",
                    description = cacheError.ifEmpty { "教务系统目前未公布您的考试日程" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(
                        top = LocalTopContentInset.current + 32.dp,
                        bottom = LocalBottomContentInset.current + 24.dp
                    )
                ) {
                    // 页面标题（作为滚动内容，可穿透状态栏）
                    item { PageHeaderTitle("考试日程", emphasized = true) }

                    // Carousel of upcoming exams if any
                    if (upcomingExams.isNotEmpty()) {
                        item {
                            Text(
                                text = "近期考试倒计时",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val carouselState = rememberCarouselState { upcomingExams.size }
                            HorizontalMultiBrowseCarousel(
                                state = carouselState,
                                preferredItemWidth = 280.dp,
                                itemSpacing = 12.dp,
                                modifier = Modifier.fillMaxWidth().height(168.dp)
                            ) { index ->
                                val exam = upcomingExams[index]
                                Card(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = AppShapes.Hero,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp).fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = exam.courseName,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                // 允许折行完整显示科目名，超长时省略号收尾，避免硬截断
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = AppShapes.Pill,
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = "还有 ${exam.countdownDays} 天",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                                )
                                            }
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = exam.time,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = exam.location,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // All exams title
                    item {
                        Text(
                            text = "全部考试 (${examList.size} 门)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Exam Cards List
                    items(examList) { exam ->
                        val isFinished = (exam.countdownDays ?: 0) < 0
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppShapes.Card,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFinished) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = exam.courseName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = AppShapes.Pill,
                                        color = when {
                                            isFinished -> MaterialTheme.colorScheme.surfaceContainerHighest
                                            (exam.countdownDays ?: 0) <= 3 -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.secondaryContainer
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                isFinished -> "已结束"
                                                exam.countdownDays != null -> "还有 ${exam.countdownDays} 天"
                                                else -> exam.property
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                isFinished -> MaterialTheme.colorScheme.onSurfaceVariant
                                                (exam.countdownDays ?: 0) <= 3 -> MaterialTheme.colorScheme.onErrorContainer
                                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = exam.time, style = MaterialTheme.typography.bodyMedium)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = exam.location, style = MaterialTheme.typography.bodyMedium)
                                }

                                if (exam.property.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "${exam.property} · ${exam.courseId}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
