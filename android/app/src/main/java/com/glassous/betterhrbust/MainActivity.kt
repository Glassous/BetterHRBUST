package com.glassous.betterhrbust

import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.glassous.betterhrbust.core.model.AuthState
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.clearFocusOnTapOutside
import com.glassous.betterhrbust.core.ui.components.NavigationDock
import com.glassous.betterhrbust.core.ui.components.NavigationDockDestination
import com.glassous.betterhrbust.core.ui.components.navigationDockInset
import com.glassous.betterhrbust.core.ui.components.navigationDockStartPadding
import com.glassous.betterhrbust.core.ui.components.rememberNavigationDockCollapseConnection
import com.glassous.betterhrbust.core.ui.components.rememberNavigationDockCollapseState
import com.glassous.betterhrbust.core.ui.components.SecondaryPageSurface
import com.glassous.betterhrbust.core.ui.isTabletDevice
import com.glassous.betterhrbust.core.ui.theme.BetterHRBUSTTheme
import com.glassous.betterhrbust.core.ui.theme.AppMotion
import com.glassous.betterhrbust.feature.auth.AuthScreen
import com.glassous.betterhrbust.feature.auth.ReLoginOverlay
import com.glassous.betterhrbust.feature.auth.SessionExpiredBanner
import com.glassous.betterhrbust.feature.classrooms.ClassroomsScreen
import com.glassous.betterhrbust.feature.courses.CoursesScreen
import com.glassous.betterhrbust.feature.dashboard.DashboardScreen
import com.glassous.betterhrbust.feature.exams.ExamsScreen
import com.glassous.betterhrbust.feature.evaluation.EvaluationScreen
import com.glassous.betterhrbust.feature.feedback.FeedbackScreen
import com.glassous.betterhrbust.feature.resources.ResourcesScreen
import com.glassous.betterhrbust.feature.profile.ProfileScreen
import com.glassous.betterhrbust.feature.program.ProgramScreen
import com.glassous.betterhrbust.feature.scores.ScoresScreen
import com.glassous.betterhrbust.feature.settings.NoticesSettingsScreen
import com.glassous.betterhrbust.feature.teachingprocess.TeachingProcessScreen
import com.glassous.betterhrbust.feature.timetable.TimetableScreen
import com.glassous.betterhrbust.navigation.*
import com.glassous.betterhrbust.widget.TimetableWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.glassous.betterhrbust.widget.TimetableWidgetProvider.refreshAll(this@MainActivity)
        }
    }

    /** 桌面小部件点击请求打开的一级 Tab 索引（null = 无待处理请求） */
    private val pendingTab = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingTab.value = intent?.requestedTab()

        setContent {
            val app = remember { BetterHrbustApp.instance }
            val requestedTab by pendingTab.collectAsState()
            val prefs by app.preferencesManager.preferencesFlow.collectAsState(initial = null)
            // 会话状态初始为 null（尚未从 DataStore 读出）：
            // 若直接给 Unauthenticated 作为初值，会先渲染登录页再跳到首页，出现"一闪而过的登录页"
            val authState by app.authRepository.authState.collectAsState(initial = null)
            val isSessionExpired by app.authRepository.isSessionExpired.collectAsState()
            val shouldPromptReLogin by app.authRepository.shouldPromptReLogin.collectAsState()
            val sessionPromptDismissed by app.authRepository.sessionPromptDismissed.collectAsState()

            // 上课提示依赖通知权限：只在「已登录 + 开关开启 + 未授权」时申请一次，
            // 全设备通用（小米呈现为超级岛，Android 16 为实况通知，其余为普通通知）。
            val context = LocalContext.current
            var notificationPermissionAsked by remember { mutableStateOf(false) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(prefs?.classIslandReminder, authState) {
                val snapshot = prefs ?: return@LaunchedEffect
                if (notificationPermissionAsked) return@LaunchedEffect
                if (!snapshot.classIslandReminder) return@LaunchedEffect
                if (authState !is AuthState.Authenticated) return@LaunchedEffect
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) return@LaunchedEffect
                notificationPermissionAsked = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            val isSystemDark = isSystemInDarkTheme()
            val useDarkTheme = prefs?.darkTheme ?: isSystemDark

            // 系统栏图标明暗跟随应用内主题（支持与系统主题不一致的手动切换）
            val window = this@MainActivity.window
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useDarkTheme
                    isAppearanceLightNavigationBars = !useDarkTheme
                }
            }

            // 固定使用与 Web 端一致的中性色板，不启用系统动态取色（Material You）
            BetterHRBUSTTheme(darkTheme = useDarkTheme) {
                val resolvedAuthState = authState
                if (resolvedAuthState == null || prefs == null) {
                    // 首帧占位：仅渲染与主题一致的底色，等会话状态与偏好就绪后再决定落地页
                    // （偏好需先到位，导航坞才能按持久化的折叠态首帧落位，避免可见的二次跳动）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                } else {
                    // 只在「账号」变化时重建主界面：
                    // 同一账号的重新登录（会话恢复）不重建，否则会把用户从二级页面
                    // （如「更多 → 意见反馈」）踢回概览页，丢失页面状态。
                    // 重新登录后的数据同步由 MainAppScaffold 内的会话恢复逻辑单独负责。
                    key((resolvedAuthState as? AuthState.Authenticated)?.studentId) {
                    MainAppScaffold(
                        authState = resolvedAuthState,
                        isSessionExpired = isSessionExpired,
                        shouldPromptReLogin = shouldPromptReLogin,
                        sessionPromptDismissed = sessionPromptDismissed,
                        dockCollapsed = prefs?.navigationDockCollapsed ?: false,
                        pendingTab = requestedTab,
                        onPendingTabHandled = ::consumePendingTab
                    )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab.value = intent.requestedTab()
    }

    /** 消费一次性跳转请求：同时移除意图 extra，避免配置变更重建后再次跳转 */
    private fun consumePendingTab() {
        intent?.removeExtra(EXTRA_OPEN_TAB)
        intent?.removeExtra(com.glassous.betterhrbust.widget.TimetableWidgetProvider.OPEN_TIMETABLE)
        pendingTab.value = null
    }

    /** 解析小部件携带的目标 Tab；extra 缺失或越界一律忽略 */
    private fun Intent.requestedTab(): Int? = getIntExtra(EXTRA_OPEN_TAB, -1)
        .takeIf { hasExtra(EXTRA_OPEN_TAB) && it in TopLevelDestination.entries.indices }
        ?: if (getBooleanExtra(com.glassous.betterhrbust.widget.TimetableWidgetProvider.OPEN_TIMETABLE, false)) TAB_TIMETABLE else null

    companion object {
        /** 小部件点击时携带的一级 Tab 索引（见 [pendingTab]） */
        const val EXTRA_OPEN_TAB = "com.glassous.betterhrbust.extra.OPEN_TAB"

        /** 一级 Tab：课表（与 [TopLevelDestination] 顺序保持一致） */
        val TAB_TIMETABLE: Int = TopLevelDestination.TIMETABLE.ordinal
    }
}

@Composable
fun MainAppScaffold(
    authState: AuthState,
    isSessionExpired: Boolean,
    shouldPromptReLogin: Boolean = isSessionExpired,
    sessionPromptDismissed: Boolean = false,
    /** 持久化的导航坞折叠态（用户上次手动展开 / 折叠的结果） */
    dockCollapsed: Boolean = false,
    /** 桌面小部件点击请求打开的一级 Tab（null = 无请求） */
    pendingTab: Int? = null,
    /** 消费小部件跳转请求（跳转后调用，避免重复触发） */
    onPendingTabHandled: () -> Unit = {}
) {
    // NavHost 会保留路由内容；用 State 转发新意图，避免闭包只保留第一次的跳转值。
    val latestRequestedTab by rememberUpdatedState(pendingTab)
    val authRepo = remember { BetterHrbustApp.instance.authRepository }
    val syncManager = remember { BetterHrbustApp.instance.syncManager }
    val updateRepo = remember { BetterHrbustApp.instance.updateRepository }
    val prefsManager = remember { BetterHrbustApp.instance.preferencesManager }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var showReLogin by remember { mutableStateOf(false) }
    // 重新登录覆盖层：打开滑入、关闭滑出；返回交给系统默认处理
    var reLoginRendered by remember { mutableStateOf(false) }
    val reLoginSlide = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }
    var lastAuthenticated by remember { mutableStateOf(false) }

    val isAuthScreen = currentDestination?.hasRoute(AuthRoute::class) == true
    val isMainScreen = currentDestination?.hasRoute(MainRoute::class) == true ||
        currentDestination?.hasRoute(DashboardRoute::class) == true

    val startDestination: Any = if (authState is AuthState.Authenticated) {
        MainRoute
    } else {
        AuthRoute
    }

    // 版本更新检测：启动静默检查一次（按天节流，失败静默，不阻塞主流程）
    LaunchedEffect(Unit) {
        updateRepo.checkIfNeeded()
    }

    // 数据策略：用户主动登录成功 → 立即全量同步；
    // 冷启动已登录 → 仅"每天首次打开"自动同步一次；其余一律只读本地缓存
    LaunchedEffect(authState) {
        val authenticated = authState is AuthState.Authenticated
        if (!initialized) {
            initialized = true
            lastAuthenticated = authenticated
            if (authenticated) {
                syncManager.ensureDailySync()
            }
        } else if (authenticated && !lastAuthenticated) {
            // 未登录 → 已登录：用户主动登录成功
            lastAuthenticated = true
            syncManager.syncAll()
        } else {
            lastAuthenticated = authenticated
        }
    }

    // 会话失效后重新登录成功（isSessionExpired true → false）：主界面不再重建，
    // 这里补一次全量同步，保证「重新登录后恢复在线同步」，
    // 同时用户停留在原页面（如正在填写的意见反馈页）。
    var previousSessionExpired by remember { mutableStateOf(isSessionExpired) }
    LaunchedEffect(isSessionExpired) {
        if (previousSessionExpired && !isSessionExpired && authState is AuthState.Authenticated) {
            syncManager.syncAll()
        }
        previousSessionExpired = isSessionExpired
    }

    // 桌面小部件刷新：仅在「同步结束」与「会话被清理（退出登录）」两个时点各刷新一次，
    // 其余更新交给系统周期（30 分钟）与日期 / 时区 / 开机广播兜底
    val isSyncing by syncManager.isSyncing.collectAsState()
    val scaffoldPrefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val studentId = scaffoldPrefs?.studentId.orEmpty()
    var previousSyncing by remember { mutableStateOf<Boolean?>(null) }
    var previousStudentId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isSyncing, studentId) {
        val syncFinished = previousSyncing == true && !isSyncing
        val sessionCleared = !previousStudentId.isNullOrEmpty() && studentId.isEmpty()
        if (syncFinished || sessionCleared) TimetableWidgetUpdater.refreshAsync()
        previousSyncing = isSyncing
        previousStudentId = studentId
    }

    // 小部件跳转请求：仅在已登录时生效，未登录时丢弃（避免登录后突然跳到课表页）
    LaunchedEffect(authState, pendingTab) {
        if (pendingTab != null && authState !is AuthState.Authenticated) onPendingTabHandled()
    }

    // 会话失效提示按一周节流；仅在应提示且用户未忽略时展示顶部横幅
    val showSessionBanner = !isAuthScreen && shouldPromptReLogin && !sessionPromptDismissed

    // 系统栏安全间距（dp）
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 顶部让位：会话过期横幅为悬浮提示，不影响内容布局，内容始终按状态栏让位
    val topContentInset = statusBarInset
    // 平板端导航坞竖排在左侧（见 [MainPagerScreen]），底部无需再为导航坞让位
    val isTablet = isTabletDevice()
    // 底部让位：主界面为底部导航坞让位，其它二级页面、平板端直接让位给系统导航条
    val bottomContentInset = if (isMainScreen && !isTablet) {
        navigationDockInset()
    } else {
        navigationBarInset
    }

    CompositionLocalProvider(
        LocalTopContentInset provides topContentInset,
        LocalBottomContentInset provides bottomContentInset,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // 全应用统一：点击任意非输入框区域（空白 / 按钮 / 顶部标题等）清除输入焦点、收起键盘
                .clearFocusOnTapOutside()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 重新登录覆盖层渲染 / 滑出期间冻结下层输入，避免触摸穿透
                    .then(if (reLoginRendered) Modifier.blockPointerInput() else Modifier)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = {
                        slideInHorizontally(tween(AppMotion.ScreenMillis)) { it } +
                            fadeIn(tween(AppMotion.ScreenMillis))
                    },
                    exitTransition = {
                        slideOutHorizontally(tween(AppMotion.ScreenMillis)) { -it / 4 } +
                            fadeOut(tween(AppMotion.ScreenMillis))
                    },
                    // 返回：二级页面整块滑走，全程不淡出——底色保持不透明，
                    // 圆角（见 SecondaryPageSurface）在滑出时露出下层首页。
                    popEnterTransition = {
                        slideInHorizontally(tween(AppMotion.ScreenMillis)) { -it / 4 }
                    },
                    popExitTransition = {
                        slideOutHorizontally(tween(AppMotion.ScreenMillis)) { it }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    composable<AuthRoute> {
                        AuthScreen(
                            onLoginSuccess = {
                                navController.navigate(MainRoute) {
                                    popUpTo(AuthRoute) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<MainRoute> {
                        MainPagerScreen(
                            navController = navController,
                            dockCollapsed = dockCollapsed,
                            pendingTab = latestRequestedTab,
                            onPendingTabHandled = onPendingTabHandled,
                            onLogout = {
                                navController.navigate(AuthRoute) {
                                    popUpTo(MainRoute) { inclusive = true }
                                }
                            },
                            onReLogin = {
                                navController.navigate(AuthRoute) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    // 兼容旧的 DashboardRoute 目标
                    composable<DashboardRoute> {
                        MainPagerScreen(
                            navController = navController,
                            dockCollapsed = dockCollapsed,
                            pendingTab = latestRequestedTab,
                            onPendingTabHandled = onPendingTabHandled,
                            onLogout = {
                                navController.navigate(AuthRoute) {
                                    popUpTo(DashboardRoute) { inclusive = true }
                                }
                            },
                            onReLogin = {
                                navController.navigate(AuthRoute) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    // 二级页面：作为 NavHost 目的地承载，
                    // 返回手势由 Navigation 组件接入系统原生预测性返回动画；
                    // 统一套 SecondaryPageSurface，返回时整页不透明滑出并带圆角
                    // 本科教学进程（入口：首页「功能导航」宫格）
                    composable<TeachingProcessRoute> {
                        SecondaryPageSurface {
                            TeachingProcessScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<ProgramRoute> {
                        SecondaryPageSurface {
                            ProgramScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<ClassroomsRoute> {
                        SecondaryPageSurface {
                            ClassroomsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<CoursesRoute> {
                        SecondaryPageSurface {
                            CoursesScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<ProfileRoute> {
                        SecondaryPageSurface {
                            ProfileScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<EvaluationRoute> {
                        SecondaryPageSurface {
                            EvaluationScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<ResourcesRoute> {
                        SecondaryPageSurface {
                            ResourcesScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    composable<FeedbackRoute> {
                        SecondaryPageSurface {
                            FeedbackScreen(
                                onBack = { navController.popBackStack() },
                                onGoLogin = { showReLogin = true }
                            )
                        }
                    }
                }
            }

            // 会话已过期提示：悬浮在内容之上（不参与布局测量），出现 / 收起时其它区域不位移；
            // 按声明顺序位于内容之上、重新登录覆盖层之下
            AnimatedVisibility(
                visible = showSessionBanner,
                enter = slideInVertically(animationSpec = tween(AppMotion.SmoothMillis)) { -it } +
                    fadeIn(animationSpec = tween(AppMotion.SmoothMillis)),
                exit = slideOutVertically(animationSpec = tween(AppMotion.SmoothMillis)) { -it } +
                    fadeOut(animationSpec = tween(AppMotion.SmoothMillis)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            ) {
                SessionExpiredBanner(
                    onReLoginClick = { showReLogin = true },
                    onDismiss = { authRepo.dismissSessionPrompt() }
                )
            }

            // 重新登录覆盖层：打开滑入、关闭滑出
            if (reLoginRendered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = reLoginSlide.value * size.width }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    CompositionLocalProvider(
                        // 覆盖层上方无横幅，顶部让位固定为状态栏；底部让位固定为系统导航条
                        LocalTopContentInset provides statusBarInset,
                        LocalBottomContentInset provides navigationBarInset
                    ) {
                        ReLoginOverlay(onDismiss = { showReLogin = false })
                    }
                }
            }
        }
    }

    // 覆盖层开合动画：打开时滑入，关闭时滑出后再卸载内容
    LaunchedEffect(showReLogin) {
        if (showReLogin) {
            reLoginRendered = true
            reLoginSlide.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
        } else if (reLoginRendered) {
            reLoginSlide.animateTo(1f, tween(ReLoginTransitionMillis))
            reLoginRendered = false
        }
    }

    // 返回键 / 手势返回：交给系统默认返回处理，覆盖层只负责滑出动画
    BackHandler(enabled = showReLogin) {
        showReLogin = false
    }
}

/** 重新登录覆盖层的滑入 / 滑出时长（毫秒）。 */
private const val ReLoginTransitionMillis = 320

/**
 * 承载 5 个一级导航 Tab 的主页面容器：
 * - 使用 [HorizontalPager] 承载，提供平滑左右滑动切换动画，[beyondViewportPageCount] 保活所有页面状态；
 * - [userScrollEnabled] 设为 false，禁止手势直接翻页，仅由导航坞驱动；
 * - 手机端：底部悬浮 [NavigationDock]，并在容器挂载滚动折叠监听（标签随页面滑动折叠）；
 * - 平板端：导航坞竖排悬浮在左侧，页面内容整体左让位（让位宽度随折叠动画收缩）；
 *   坞内水平滑动可手动展开 / 折叠，主区域的上下滚动不影响它；
 * - 折叠态由 [dockCollapsed] 持久化：用户手动展开 / 折叠后保存，冷启动沿用，
 *   且在此之前不再被自动行为改变；
 * - 二级页面由外层 NavHost 承载，转场与系统原生预测性返回交给导航组件处理。
 */
@Composable
fun MainPagerScreen(
    navController: NavController,
    onLogout: () -> Unit,
    onReLogin: () -> Unit,
    /** 持久化的导航坞折叠态，作为首帧状态（不播放动画） */
    dockCollapsed: Boolean = false,
    /** 桌面小部件请求打开的一级 Tab（null = 无请求）；冷启动时直接作为首帧页，避免先闪一下概览页 */
    pendingTab: Int? = null,
    /** 消费小部件跳转请求（切页后调用） */
    onPendingTabHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val preferencesManager = remember { BetterHrbustApp.instance.preferencesManager }
    val initialPage = (pendingTab ?: 0).coerceIn(0, TopLevelDestination.entries.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { TopLevelDestination.entries.size }
    )
    var tabIndex by rememberSaveable { mutableStateOf(initialPage) }
    var timetableOpenRequest by remember { mutableStateOf(0) }
    val currentTab = tabIndex

    // 平板端：导航坞竖排在左侧，页面内容让位其宽度 + 左侧安全距离（见 pagerModifier）
    val isTablet = isTabletDevice()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling && page != tabIndex) tabIndex = page
            }
    }

    // 小部件跳转请求（应用已在运行时经 onNewIntent 送达）：直接切到目标一级 Tab
    LaunchedEffect(pendingTab) {
        val target = pendingTab ?: return@LaunchedEffect
        val index = target.coerceIn(0, TopLevelDestination.entries.lastIndex)
        if (index == MainActivity.TAB_TIMETABLE) timetableOpenRequest++
        tabIndex = index
        pagerState.scrollToPage(index)
        onPendingTabHandled()
    }

    // 首帧落位到持久化的折叠态：用户手动展开 / 折叠后写回偏好，冷启动沿用
    val dockCollapseState = rememberNavigationDockCollapseState(initiallyCollapsed = dockCollapsed)
    val dockCollapseConnection = rememberNavigationDockCollapseConnection(dockCollapseState)

    val dockDestinations = remember {
        TopLevelDestination.entries.map {
            NavigationDockDestination(glyph = it.selectedIcon, label = it.label)
        }
    }

    // 非「概览」标签时，系统返回键平滑回到「概览」
    BackHandler(enabled = currentTab != 0) {
        tabIndex = 0
        coroutineScope.launch { pagerState.animateScrollToPage(0) }
    }

    val onDashboardNavigate: (Any) -> Unit = { route ->
        val targetTab = when (route) {
            is DashboardRoute, DashboardRoute -> 0
            is TimetableRoute, TimetableRoute -> 1
            is ScoresRoute, ScoresRoute -> 2
            is ExamsRoute, ExamsRoute -> 3
            is SettingsRoute, SettingsRoute -> 4
            else -> -1
        }
        if (targetTab != -1) {
            tabIndex = targetTab
            coroutineScope.launch { pagerState.animateScrollToPage(targetTab) }
        } else {
            navController.navigate(route)
        }
    }

    val containerModifier = if (isTablet) {
        // 竖排导航坞不随页面滚动折叠：主区域上下滑动不转发滚动量
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxSize()
            .nestedScroll(dockCollapseConnection)
    }

    val pagerModifier = Modifier
        .fillMaxSize()
        // 平板端：内容左让位随折叠进度收缩，折叠后页面可用宽度随之增大
        .navigationDockStartPadding(dockCollapseState)

    Box(modifier = containerModifier) {
        HorizontalPager(
            state = pagerState,
            modifier = pagerModifier,
            beyondViewportPageCount = 4,
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> DashboardScreen(onNavigate = onDashboardNavigate)
                1 -> TimetableScreen(openRequest = timetableOpenRequest)
                2 -> ScoresScreen()
                3 -> ExamsScreen()
                4 -> NoticesSettingsScreen(
                    onEvaluation = { navController.navigate(EvaluationRoute) },
                    onResources = { navController.navigate(ResourcesRoute) },
                    onFeedback = { navController.navigate(FeedbackRoute) },
                    onLogout = onLogout,
                    onReLogin = onReLogin
                )
            }
        }

        NavigationDock(
            destinations = dockDestinations,
            selectedIndex = currentTab,
            onSelected = { index ->
                tabIndex = index
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
            collapseState = dockCollapseState,
            vertical = isTablet,
            // 仅平板：手动展开 / 折叠后持久化，并停止被自动行为改变（直到下次冷启动）；
            // 手机端手动折叠保持原有"临时"语义，仍可被页面滚动重新展开
            onManualCollapseChange = if (isTablet) {
                { collapsed: Boolean ->
                    dockCollapseState.markUserControlled()
                    coroutineScope.launch {
                        preferencesManager.setNavigationDockCollapsed(collapsed)
                    }
                }
            } else {
                null
            },
            modifier = Modifier.align(
                if (isTablet) Alignment.CenterStart else Alignment.BottomCenter
            )
        )
    }
}

/**
 * 屏蔽该节点及其子节点的手势：在 Initial 阶段吞掉所有指针事件。
 *
 * 用于重新登录覆盖层渲染期间冻结下层主页，避免触摸穿透。
 */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
