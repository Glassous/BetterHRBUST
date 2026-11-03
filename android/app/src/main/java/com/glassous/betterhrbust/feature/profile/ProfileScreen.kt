package com.glassous.betterhrbust.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.PersonalInfo
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
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
    var profile by remember { mutableStateOf<PersonalInfo?>(null) }

    /** 读取本地缓存（离线只读，不联网） */
    fun loadCache() {
        val currentPrefs = prefs ?: return
        coroutineScope.launch {
            try {
                academicRepo.getPersonalInfo(currentPrefs.username, cacheOnly = true).collect { res ->
                    if (res is Resource.Success) {
                        profile = res.data
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

    LaunchedEffect(prefs?.username, prefs?.lastFullSyncDate) {
        if (prefs != null && prefs!!.username.isNotEmpty()) {
            loadCache()
        }
    }

    // 固定顶栏：标题与返回按钮不随内容滚动，列表滚起来后顶栏底色转为不透明
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing || isSyncing,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize()
        ) {
            val p = profile
            if (p == null && (isRefreshing || isSyncing)) {
                LoadingView(message = "正在加载学籍信息...")
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
                    // Profile Header Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = p?.realName?.ifEmpty { "哈理工同学" } ?: "哈理工同学",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = p?.status ?: "在籍",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "学号: ${p?.studentNumber?.ifEmpty { prefs?.username } ?: prefs?.username ?: "未知"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Academic Info Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "学业学籍信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                HorizontalDivider()
                                ProfileField("所在院系", p?.college ?: "")
                                ProfileField("专业名称", p?.major ?: "")
                                if (!p?.direction.isNullOrBlank()) {
                                    ProfileField("专业方向", p.direction)
                                }
                                ProfileField("行政班级", p?.className ?: "")
                                ProfileField("入学年级", p?.grade ?: "")
                                ProfileField("学生类别", p?.studentType ?: "本科生")
                            }
                        }
                    }

                    // Contact Info Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "个人联络资料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                HorizontalDivider()
                                ProfileField("联系电话", p?.phone ?: "未填")
                                ProfileField("电子邮箱", p?.email ?: "未填")
                                ProfileField("通讯地址", p?.address ?: "未填")
                                ProfileField("邮政编码", p?.postalCode ?: "未填")
                            }
                        }
                    }

                    // Status changes
                    val changes = p?.changes ?: emptyList()
                    if (changes.isNotEmpty()) {
                        item {
                            Text(text = "学籍异动记录 (${changes.size} 条)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        items(changes) { ch ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = ch.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(text = ch.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    if (ch.reason.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "原因: ${ch.reason}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (ch.remark.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = "备注: ${ch.remark}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FixedPageTopBar(title = "学籍档案", onBack = onBack)
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(text = value.ifEmpty { "未登记" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
