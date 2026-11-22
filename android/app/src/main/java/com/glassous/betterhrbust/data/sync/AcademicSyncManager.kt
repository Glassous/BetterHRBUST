package com.glassous.betterhrbust.data.sync

import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.data.repository.AcademicRepository
import com.glassous.betterhrbust.data.repository.AuthRepository
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.last
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 教务数据全量同步管理器
 *
 * 策略（与用户约定一致）：
 * 1. 登录成功后一次性全量拉取（档案 / 课表 / 成绩 / 考试 / 培养方案 / 公告）并写入 Room 持久化；
 * 2. 之后各页面仅读本地缓存（`cacheOnly = true`），不再自动联网；
 * 3. 仅在【每天首次打开】与【用户手动刷新】两种情况下触网；
 * 4. 手动刷新时若会话已失效，则要求用户重新登录（由 AuthRepository 的节流判定决定是否弹提示）。
 */
class AcademicSyncManager(
    private val academicRepo: AcademicRepository,
    private val prefs: UserPreferencesManager,
    private val authRepo: AuthRepository
) {

    companion object {
        internal fun wasFullyRefreshed(results: List<Any>): Boolean = results.none {
            it is Resource.Error || (it is Resource.Success<*> && it.isOfflineCache)
        }
    }

    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    /** 同步结果 */
    data class SyncOutcome(
        val success: Boolean,
        /** 会话失效（需要重新登录） */
        val expired: Boolean,
        val message: String,
        /** true = 本轮未执行（已有另一轮同步进行中），ensureDailySync 据此如实返回 */
        val skipped: Boolean = false
    )

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _lastSyncMessage = MutableStateFlow("")
    val lastSyncMessage: StateFlow<String> = _lastSyncMessage

    private fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** 今天是否尚未做过全量同步 */
    suspend fun needsDailySync(): Boolean {
        val snapshot = prefs.preferencesFlow.firstOrNull() ?: return false
        if (snapshot.studentId.isEmpty()) return false
        return snapshot.lastFullSyncDate != todayKey()
    }

    /**
     * 每天首次打开应用时自动全量同步
     * @return 是否真正执行了同步（另一轮同步进行中时返回 false，与注释契约一致）
     */
    suspend fun ensureDailySync(): Boolean {
        // 切号时先等待旧轮次退出，再重查新账号是否需要同步，避免 tryLock 忙碌后漏同步。
        syncMutex.withLock { }
        if (!needsDailySync()) return false
        val outcome = syncAll(manual = false)
        return !outcome.skipped
    }

    /**
     * 执行一次全量同步（各端点并发）
     * @param manual 是否由用户手动刷新触发（失效时无条件要求重新登录）
     */
    suspend fun syncAll(manual: Boolean = false): SyncOutcome {
        if (!syncMutex.tryLock()) {
            return SyncOutcome(success = false, expired = false, message = "正在同步中，请稍候", skipped = true)
        }

        try {
            // 手动刷新：随后的会话失效需要无条件要求重新登录
            if (manual) authRepo.notifyManualRefreshIntent()

            val snapshot = prefs.preferencesFlow.firstOrNull()
                ?: return SyncOutcome(success = false, expired = false, message = "无法读取本地登录信息")

            if (snapshot.studentId.isEmpty()) {
                authRepo.markSessionExpired(true)
                return SyncOutcome(success = false, expired = true, message = "当前未登录，请重新登录后再刷新数据")
            }

            _isSyncing.value = true
            _lastSyncMessage.value = ""

            return try {
                val studentId = snapshot.studentId
                val results = coroutineScope {
                    val tasks = mutableListOf(
                        async { academicRepo.getCurrentCourses(studentId, forceRefresh = true).last() },
                        async { academicRepo.getScores(studentId, forceRefresh = true).last() },
                        async { academicRepo.getCurriculumPlan(studentId, forceRefresh = true).last() },
                        async { academicRepo.getExams(studentId, forceRefresh = true).last() },
                        async { academicRepo.getNotices(forceRefresh = true).last() },
                        async { academicRepo.getTeachingWeek().last() }
                    )
                    if (snapshot.username.isNotEmpty()) {
                        tasks.add(async { academicRepo.getPersonalInfo(snapshot.username, forceRefresh = true).last() })
                    }
                    if (snapshot.year.isNotEmpty() && snapshot.term.isNotEmpty()) {
                        tasks.add(
                            async {
                                academicRepo.getTimetable(
                                    studentId = studentId,
                                    year = snapshot.year,
                                    term = snapshot.term,
                                    forceRefresh = true
                                ).last()
                            }
                        )
                    }
                    tasks.awaitAll()
                }

                val current = prefs.preferencesFlow.firstOrNull()
                if (current?.studentId != studentId || current?.username != snapshot.username || current?.lastLoginAt != snapshot.lastLoginAt) {
                    return SyncOutcome(success = false, expired = false, message = "账号已切换，旧同步结果已丢弃")
                }
                if (authRepo.isSessionExpired.value) {
                    SyncOutcome(success = false, expired = true, message = "登录状态已失效，请重新登录后再刷新数据")
                } else {
                    val failed = results.filterIsInstance<Resource.Error>()
                    val cached = results.filterIsInstance<Resource.Success<*>>().count { it.isOfflineCache }
                    val complete = wasFullyRefreshed(results)
                    if (complete) prefs.setLastFullSyncDate(todayKey(), expected = snapshot)
                    val message = when {
                        complete -> "数据已全部同步"
                        failed.isNotEmpty() -> "部分数据同步失败（${failed.size} 项）：${failed.first().message}"
                        else -> "${cached} 项未取得新数据，继续显示本地缓存，请稍后重试"
                    }
                    _lastSyncMessage.value = message
                    SyncOutcome(success = complete, expired = false, message = message)
                }
            } catch (switched: com.glassous.betterhrbust.data.repository.AccountSwitchedException) {
                // 账号切换不是异常场景：旧同步结果作废，静默返回（不再用
                // CancellationException 承载该信号——那会取消兄弟任务并穿透本 catch）
                SyncOutcome(success = false, expired = false, message = "账号已切换，旧同步结果已丢弃")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "同步异常：${e.message ?: "未知错误"}"
                _lastSyncMessage.value = message
                SyncOutcome(success = false, expired = authRepo.isSessionExpired.value, message = message)
            }
        } finally {
            _isSyncing.value = false
            syncMutex.unlock()
        }
    }
}
