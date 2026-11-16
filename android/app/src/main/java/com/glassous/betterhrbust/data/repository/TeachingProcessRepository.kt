package com.glassous.betterhrbust.data.repository

import android.content.Context
import com.glassous.betterhrbust.core.model.TeachingProcessData
import com.glassous.betterhrbust.core.parser.parseTeachingProcess

/**
 * 本科教学进程快照仓储。
 *
 * 数据随 APK 打包在 `assets/teaching-process.json`（教务处公开表，离线可用），
 * 首次读取后常驻内存，后续进入页面不再重复解析 200KB+ 的 JSON。
 */
class TeachingProcessRepository(private val context: Context) {

    @Volatile
    private var cached: TeachingProcessData? = null

    @Volatile
    private var loadFailed = false

    /** 读取快照；资源缺失或格式异常时返回 null，由页面展示空态。 */
    fun load(): TeachingProcessData? {
        cached?.let { return it }
        if (loadFailed) return null
        return synchronized(this) {
            cached ?: runCatching {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                parseTeachingProcess(text)
            }.getOrNull()?.also { cached = it } ?: run {
                loadFailed = true
                null
            }
        }
    }

    private companion object {
        const val ASSET_NAME = "teaching-process.json"
    }
}
