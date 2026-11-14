package com.glassous.betterhrbust.feature.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.NoticeItem
import com.glassous.betterhrbust.core.notify.ClassReminderTicker
import com.glassous.betterhrbust.core.notify.HyperIslandSupport
import com.glassous.betterhrbust.core.notify.isDebuggable
import com.glassous.betterhrbust.core.model.UpdateState
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.components.AppPullToRefreshBox
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.PageHeaderTitle
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesSettingsScreen(
    onEvaluation: () -> Unit = {},
    onResources: () -> Unit = {},
    onFeedback: () -> Unit = {},
    onLogout: () -> Unit,
    onReLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val authRepo = remember { app.authRepository }
    val prefsManager = remember { app.preferencesManager }
    val database = remember { app.database }
    val syncManager = remember { app.syncManager }
    val updateRepo = remember { app.updateRepository }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val updateState by updateRepo.state.collectAsState()
    val isCheckingUpdate = updateState is UpdateState.Checking

    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val islandReminderEnabled = prefs?.classIslandReminder ?: true
    // 调试包额外提供「模拟 15 分钟后上课」入口：没有真实课表或未进入提醒窗口时也能验证通知样式
    val debugBuild = remember { isDebuggable(context) }
    var mockReminderEnabled by remember { mutableStateOf(ClassReminderTicker.isMockEnabled()) }
    var pendingNotificationAction by remember { mutableStateOf<NotificationAction?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        coroutineScope.launch {
            if (!granted) {
                // 被拒绝时开关回退为关闭，避免「开关开着但实际不会提醒」的假象
                if (action == NotificationAction.EnableReminder) {
                    prefsManager.setClassIslandReminder(false)
                }
                Toast.makeText(context, "未授予通知权限，上课提醒未开启", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (action) {
                NotificationAction.EnableReminder -> {
                    prefsManager.setClassIslandReminder(true)
                    HyperIslandSupport.invalidate()
                }

                NotificationAction.MockReminder -> {
                    mockReminderEnabled = ClassReminderTicker.toggleMock(context)
                }

                null -> Unit
            }
        }
    }
    val isSyncing by syncManager.isSyncing.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<List<NoticeItem>>(emptyList()) }
    var selectedNotice by remember { mutableStateOf<NoticeItem?>(null) }
    var syncMessage by remember { mutableStateOf("") }
    var cacheError by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    /**
     * 读取本地缓存（离线只读，不联网）
     * 教务数据仅在登录成功、每天首次打开与手动刷新三种情况下获取
     */
    fun loadCache() {
        coroutineScope.launch {
            try {
                academicRepo.getNotices(forceRefresh = false, cacheOnly = true).collect { res ->
                    when (res) {
                        is Resource.Success -> {
                            notices = res.data
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

    /**
     * 手动全量刷新：会话失效时要求重新登录。
     *
     * [fromPullDown] 为 true（下拉手势触发）时才驱动下拉刷新指示器；
     * 顶部按钮触发时只显示按钮自身的 loading，避免出现"没有下拉却在转圈"的动画。
     */
    fun refreshAll(fromPullDown: Boolean = false) {
        coroutineScope.launch {
            if (fromPullDown) isRefreshing = true
            try {
                val outcome = syncManager.syncAll(manual = true)
                syncMessage = outcome.message
                if (outcome.expired) {
                    authRepo.markSessionExpired(true)
                } else {
                    loadCache()
                }
            } finally {
                if (fromPullDown) isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCache()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AppPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshAll(fromPullDown = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    top = LocalTopContentInset.current + 32.dp,
                    bottom = LocalBottomContentInset.current + 32.dp
                )
            ) {
                // 第一行：页面标题 + 手动刷新按钮（登录后教务数据不再自动获取）
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            PageHeaderTitle("更多", modifier = Modifier.weight(1f), emphasized = true)
                            FilledTonalIconButton(
                                onClick = { refreshAll() },
                                enabled = !isSyncing
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "手动刷新教务数据"
                                    )
                                }
                            }
                        }
                        if (syncMessage.isNotEmpty()) {
                            Text(
                                text = syncMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onEvaluation) { Text("教学评价助手") }
                        OutlinedButton(onClick = onResources) { Text("资料查找") }
                    }
                }
                // Section: Academic Notices
                item {
                    Text(
                        text = "教务通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (notices.isEmpty() && (isRefreshing || isSyncing)) {
                    item {
                        LoadingView(message = "正在加载教务通知...")
                    }
                } else if (notices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = cacheError.ifEmpty { "暂无新的教务公告" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(notices) { notice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedNotice = notice },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = notice.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    // 教务源页面不含发布日期（date 为空时隐藏，不伪造）
                                    if (notice.date.isNotBlank()) {
                                        Text(
                                            text = notice.date,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }

                // Section: 外观（主题模式单独成组，与数据清理分离）
                item {
                    Text(
                        text = "外观",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "深浅色主题",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val currentMode = when (prefs?.darkTheme) {
                                true -> "深色"
                                false -> "浅色"
                                null -> "系统"
                            }
                            val modes = listOf("系统", "浅色", "深色")
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                modes.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = currentMode == mode,
                                        onClick = {
                                            coroutineScope.launch {
                                                val saveVal = when (mode) {
                                                    "深色" -> "dark"
                                                    "浅色" -> "light"
                                                    else -> "system"
                                                }
                                                prefsManager.setDarkTheme(saveVal)
                                            }
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                                    ) {
                                        Text(mode, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // Section: 提醒（全设备可见：小米上岛、Android 16 实况通知、其余为普通通知）
                item {
                    Text(
                        text = "提醒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "上课提示",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "课前 20 分钟提醒课程与教室，上课后自动收起",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = islandReminderEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            val needPermission =
                                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.POST_NOTIFICATIONS
                                                    ) != PackageManager.PERMISSION_GRANTED
                                            if (needPermission) {
                                                // 未授权先申请，授权结果回到 launcher 里落库
                                                pendingNotificationAction = NotificationAction.EnableReminder
                                                notificationPermissionLauncher.launch(
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                )
                                            } else {
                                                coroutineScope.launch {
                                                    prefsManager.setClassIslandReminder(true)
                                                    HyperIslandSupport.invalidate()
                                                }
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                prefsManager.setClassIslandReminder(false)
                                            }
                                        }
                                    }
                                )
                            }
                            // 调试构建专用：模拟「15 分钟后上课」，不依赖真实课表与提醒窗口
                            if (debugBuild) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                SettingsActionRow(
                                    icon = Icons.Default.BugReport,
                                    title = if (mockReminderEnabled) "关闭模拟上课提示" else "模拟上课提示（调试）",
                                    subtitle = if (mockReminderEnabled) {
                                        "已开启：按「15 分钟后上课」生成模拟通知，到点自动收起；点击关闭"
                                    } else {
                                        "生成一条「15 分钟后上课」的模拟通知，用于验证文案与呈现形态（仅调试包）"
                                    },
                                    onClick = {
                                        if (mockReminderEnabled) {
                                            mockReminderEnabled = ClassReminderTicker.toggleMock(context)
                                        } else {
                                            val needPermission =
                                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.POST_NOTIFICATIONS
                                                    ) != PackageManager.PERMISSION_GRANTED
                                            if (needPermission) {
                                                pendingNotificationAction = NotificationAction.MockReminder
                                                notificationPermissionLauncher.launch(
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                )
                                            } else {
                                                mockReminderEnabled = ClassReminderTicker.toggleMock(context)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Section: 账号与数据（意见反馈 / 清理缓存 / 重新登录 / 退出登录 合并为一组）
                item {
                    Text(
                        text = "账号与数据",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column {
                            SettingsActionRow(
                                icon = Icons.Default.DeleteOutline,
                                title = "清理离线缓存",
                                subtitle = "清除本地缓存的教务数据",
                                onClick = { showClearCacheDialog = true }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            // 课表小部件的准点刷新需要系统的精确闹钟权限，与缓存清理同属「本机能力」，合并在同一卡片里
                            SettingsActionRow(
                                icon = Icons.Default.Alarm,
                                title = "课表组件准点更新",
                                subtitle = "允许系统闹钟后，在课程结束和午夜准点刷新；未允许时系统更新可能延后",
                                onClick = {
                                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                android.net.Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SettingsActionRow(
                                icon = Icons.Outlined.Feedback,
                                title = "意见反馈",
                                subtitle = "提交使用中遇到的问题、建议或数据偏差",
                                onClick = onFeedback
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SettingsActionRow(
                                icon = Icons.Default.Refresh,
                                title = "重新登录",
                                subtitle = "重新认证教务会话",
                                onClick = onReLogin
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SettingsActionRow(
                                icon = Icons.AutoMirrored.Filled.Logout,
                                title = "退出登录",
                                subtitle = "清除会话并返回登录页",
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { showLogoutDialog = true }
                            )
                        }
                    }
                }

                // Section: 关于与更新（数据源为 GitHub Release：仅提示 + 跳转下载页，不做应用内安装）
                item {
                    Text(
                        text = "关于与更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    val available = updateState as? UpdateState.Available
                    val errorState = updateState as? UpdateState.Error

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "版本更新",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "当前版本 v${updateRepo.currentVersion}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            updateRepo.check()
                                            // 手动检测到新版本时直接弹出说明
                                            if (updateRepo.state.value is UpdateState.Available) {
                                                showUpdateDialog = true
                                            }
                                        }
                                    },
                                    enabled = !isCheckingUpdate
                                ) {
                                    when {
                                        isCheckingUpdate -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )

                                        updateState is UpdateState.UpToDate -> {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("已是最新")
                                        }

                                        else -> Text("检查更新")
                                    }
                                }
                            }

                            // 仅在检测失败时提示（成功/最新状态由按钮自身表达，不再额外占一行）
                            if (errorState != null && !isCheckingUpdate) {
                                Text(
                                    text = errorState.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            // 发现新版本：页面内只保留入口，说明统一在弹窗中展示
                            if (available != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "发现新版本 v${available.latestVersion}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "查看 Markdown 更新说明",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Button(
                                            onClick = { showUpdateDialog = true },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("查看更新", fontSize = 13.sp)
                                        }
                                    }
                                }

                                if (showUpdateDialog) {
                                    UpdateDialog(
                                        currentVersion = available.currentVersion,
                                        latestVersion = available.latestVersion,
                                        publishedDate = available.publishedDate,
                                        notes = available.notes,
                                        onDownload = { openReleasePage(context, available.releaseUrl) },
                                        onDismiss = { showUpdateDialog = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Modal Bottom Sheet for notice detail
        if (selectedNotice != null) {
            val n = selectedNotice!!
            ModalBottomSheet(
                onDismissRequest = { selectedNotice = null },
                sheetState = sheetState,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(text = n.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (n.date.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "发布日期: ${n.date}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = n.content, style = MaterialTheme.typography.bodyMedium, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { selectedNotice = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("关闭")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Clear Cache Confirmation Dialog
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("确认清理缓存？") },
                text = { Text("清理后需联网重新获取数据。") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            // 清理范围与文案承诺一致：当前账号的 6 张表全部清除
                            // （课表/成绩/考试/培养方案按 studentId，档案按学号，公告无账号键整表清）
                            val snapshot = prefs
                            if (snapshot != null && snapshot.studentId.isNotEmpty()) {
                                database.timetableDao().clear(snapshot.studentId)
                                database.scoreDao().clear(snapshot.studentId)
                                database.examDao().clear(snapshot.studentId)
                                database.curriculumDao().clear(snapshot.studentId)
                            }
                            if (snapshot != null && snapshot.username.isNotEmpty()) {
                                database.profileDao().clear(snapshot.username)
                            }
                            database.noticeDao().clearAll()
                            showClearCacheDialog = false
                        }
                    }) {
                        Text("确认清理", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // Logout Confirmation Dialog
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("退出登录") },
                text = { Text("将清除会话并返回登录页。") },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        coroutineScope.launch {
                            authRepo.logout().collect {
                                onLogout()
                            }
                        }
                    }) {
                        Text("确定退出", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

/**
 * 设置项行：图标 + 标题 + 说明 + 箭头。
 * 采用低调的列表样式（无填充色按钮），替代原先的大号红色按钮。
 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 通知权限申请的前置动作：授权回调后据此决定「落库开关」还是「启动模拟提醒」。 */
private enum class NotificationAction { EnableReminder, MockReminder }

/**
 * 打开 Release 页面（交系统浏览器 / GitHub 客户端处理）。
 * 无可用处理应用时给出轻提示，避免直接崩溃。
 */
private fun openReleasePage(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可打开该链接的应用", Toast.LENGTH_SHORT).show()
    }
}
