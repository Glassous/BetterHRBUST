package com.glassous.betterhrbust

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.glassous.betterhrbust.core.database.AppDatabase
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.network.AcademicHttpClient
import com.glassous.betterhrbust.data.repository.AcademicRepository
import com.glassous.betterhrbust.data.repository.AuthRepository
import com.glassous.betterhrbust.data.repository.FeedbackRepository
import com.glassous.betterhrbust.data.repository.UpdateRepository
import com.glassous.betterhrbust.data.sync.AcademicSyncManager

class BetterHrbustApp : Application() {
    private val widgetScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)


    lateinit var database: AppDatabase
        private set

    lateinit var preferencesManager: UserPreferencesManager
        private set

    lateinit var httpClient: AcademicHttpClient
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var academicRepository: AcademicRepository
        private set

    lateinit var syncManager: AcademicSyncManager
        private set

    lateinit var updateRepository: UpdateRepository
        private set

    lateinit var feedbackRepository: FeedbackRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        preferencesManager = UserPreferencesManager(this)
        httpClient = AcademicHttpClient()
        authRepository = AuthRepository(httpClient, preferencesManager, database)
        httpClient.onSessionExpired = {
            authRepository.markSessionExpired(true)
        }
        academicRepository = AcademicRepository(httpClient, database, preferencesManager)
        syncManager = AcademicSyncManager(academicRepository, preferencesManager, authRepository)
        // 登录、退出、同步和教学周变动都刷新桌面；仅观察本地存储，不额外联网。
        // 外层只取身份键（studentId + username）且去重：DataStore 任意键写入
        // （主题/导航坞/更新检查等）不再触发内层重订阅——内层 combine 收集时
        // 即发射当前值，会造成全部小部件无差别重建。真正的数据变化由内层的
        // Room 表 flow 自行驱动。
        widgetScope.launch {
            preferencesManager.preferencesFlow
                .map { it.studentId to it.username }
                .distinctUntilChanged()
                .collectLatest { (studentId, username) ->
                    kotlinx.coroutines.flow.combine(
                        database.timetableDao().getTimetable(studentId),
                        database.profileDao().getProfile(username)
                    ) { timetable, profile -> timetable to profile }.collect {
                        refreshWidgets()
                    }
                }
        }
        updateRepository = UpdateRepository(
            currentVersion = resolveAppVersion(),
            prefs = preferencesManager
        )
        feedbackRepository = FeedbackRepository()
        // 上课提醒（小米超级岛）：非超级岛设备内部直接短路，不建通知通道也不轮询
        com.glassous.betterhrbust.core.notify.ClassReminderTicker.start(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 主进程存活时，系统主题/字号变化立即重算组件，而非复用旧密度布局。
        widgetScope.launch { refreshWidgets() }
    }

    private suspend fun refreshWidgets() {
        try {
            com.glassous.betterhrbust.widget.TimetableWidgetProvider.refreshAll(this)
            com.glassous.betterhrbust.widget.TimetableWidgetUpdater.refreshAll(this)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 后续本地数据/系统更新会重试，不影响应用启动。
        }
    }

    /** 读取当前应用版本号（versionName）；异常时回退为占位版本 */
    private fun resolveAppVersion(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
    }.getOrDefault("1.0")

    companion object {
        lateinit var instance: BetterHrbustApp
            private set
    }
}
