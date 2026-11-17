package com.glassous.betterhrbust.core.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import com.glassous.betterhrbust.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 岛通知需要展示的文案（集中一处，避免各层各拼一套）。
 *
 * 布局依据《小米超级岛摘要态设计规范》：
 * - 大岛以摄像头为界分 A 区（左）/ B 区（右），A 区图标与正文大字二选一；
 * - 文本组件只有「前置小字 / 大字 / 后置小字」三级，字号由系统固定，不能传参，
 *   因此需要强调的内容一律放进 `title`（唯一的大字槽位）。
 */
data class IslandNotificationText(
    /** 通知标题 / 焦点通知卡片主文案，如「12 分钟后上课」 */
    val title: String,
    /** 焦点通知卡片次要文本（第二行，完整教室），如「西-新B406（直播）」 */
    val content: String,
    /** 状态栏 / 息屏短文案，如「数据结构与算法 · 12 分钟后上课」 */
    val ticker: String,
    /** 息屏文案，如「数据结构与算法 西-新B406」 */
    val aodTitle: String,
    /** 大岛 A 区（左）大字：倒计时数字，如「12」 */
    val countdownNumber: String,
    /** 大岛 A 区（左）后置小字：单位，如「分」 */
    val countdownUnit: String,
    /** 大岛 B 区（右）大字：精简教室，如「西-新B406」 */
    val room: String,
    /** 焦点通知默认消失时间（分钟） */
    val timeoutMinutes: Int,
    /** 岛自动消失时间（秒） */
    val islandTimeoutSeconds: Int,
    /** 是否为本节课提醒首次出现（首次展开一次，后续整分更新静默刷新） */
    val firstFloat: Boolean = false
)

/**
 * 小米超级岛 / 焦点通知的扩展参数构建。
 *
 * 官方规范：在普通通知的 extras 中附加 `miui.focus.param`（JSON 字符串，顶层固定
 * `{"param_v2": {...}}`）；岛屿图片来自 `miui.focus.pics`，操作按钮来自 `miui.focus.actions`，
 * JSON 里用同名的 key 引用。字段取值以《小米超级岛模板库》为准，这里集中一处便于后续调整。
 *
 * 任何字段拼装失败都不应影响通知本身：调用方对异常兜底，退化为普通通知。
 */
object HyperIslandParams {
    const val PARAM_KEY = "miui.focus.param"
    const val PICS_KEY = "miui.focus.pics"
    const val ACTIONS_KEY = "miui.focus.actions"

    /** 岛屿图片 key（`miui.focus.pics` 中的键名，供小岛折叠态引用） */
    const val PIC_KEY_LOGO = "miui.focus.pic_logo"

    /** 岛内按钮 key（`miui.focus.actions` 中的键名） */
    const val ACTION_KEY_TIMETABLE = "miui.focus.action_timetable"

    /** 场景标识，用于系统侧统计；同一应用保持一致即可 */
    private const val BUSINESS = "schedule"

    /** 主文案强调色，取应用主色系中较醒目的一档 */
    private const val HIGHLIGHT_COLOR = "#006EFF"

    private val PAREN_SUFFIX_REGEX = Regex("""\s*(?:（[^）]*）|\([^)]*\))\s*""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    /**
     * 精简超级岛胶囊右侧展示的教室文案：
     * - 空串或默认占位「地点待公布」统一收敛为「待公布」；
     * - 去除全角 / 半角括号备注（如「西-新B406（直播）」→「西-新B406」），展开卡片仍展示完整教室；
     * - 若剥离后为空串（整段内容均在括号内），回退为去除首尾空白的原文。
     */
    fun compactRoomForIsland(rawLocation: String): String {
        val trimmed = rawLocation.trim()
        if (trimmed.isEmpty() || trimmed == "地点待公布") return "待公布"
        val stripped = trimmed.replace(PAREN_SUFFIX_REGEX, " ").trim().replace(MULTI_SPACE_REGEX, " ")
        return stripped.ifEmpty { trimmed }
    }

    fun build(text: IslandNotificationText): String = buildJsonObject {
        put("param_v2", buildJsonObject {
            put("protocol", 1)
            put("business", BUSINESS)
            // 持续性通知：同 id 反复更新时保持「岛」形态，而不是每次都当作新通知
            put("updatable", true)
            // 仅在本节课提醒首次出现时自动展开一次；后续按分钟更新置 false，避免反复打断用户
            put("islandFirstFloat", text.firstFloat)
            put("enableFloat", false)
            // 被取消后同 id 再次发送仍然展示（下一节课复用同一条通知 id）
            put("reopen", "reopen")
            // 置 false：权限判定已在上层完成（焦点通知 或 实况通知任一放行才发送）。
            // 若这里为 true，焦点通知权限关闭时系统会把整条通知过滤掉，
            // 连带吞掉本该由「实况通知」通道呈现的内容。
            put("filterWhenNoPermission", false)
            put("timeout", text.timeoutMinutes)
            put("ticker", text.ticker)
            put("aodTitle", text.aodTitle)
            put("baseInfo", buildJsonObject {
                put("type", 1)
                put("title", text.title)
                if (text.content.isNotBlank()) put("content", text.content)
                put("colorTitle", HIGHLIGHT_COLOR)
            })
            put("hintInfo", buildJsonObject {
                put("type", 1)
                put("title", "查看课表")
                put("actionInfo", buildJsonObject { put("action", ACTION_KEY_TIMETABLE) })
            })
            put("param_island", buildJsonObject {
                // 1 = 以信息展示为主（本节课提醒没有高频操作）
                put("islandProperty", 1)
                // 2 = 高优先级，保证摘要态与展开态都能正常渲染
                put("islandPriority", 2)
                put("islandTimeout", text.islandTimeoutSeconds)
                put("highlightColor", HIGHLIGHT_COLOR)
                put("bigIslandArea", buildJsonObject {
                    // A 区（摄像头左侧）：纯文本倒计时（遵循「图标或正文大字二选一」，不再附带应用图标）
                    put("imageTextInfoLeft", buildJsonObject {
                        put("type", 1)
                        put("textInfo", buildJsonObject {
                            put("title", text.countdownNumber)
                            if (text.countdownUnit.isNotBlank()) put("content", text.countdownUnit)
                            put("showHighlightColor", true)
                            put("narrowFont", true)
                        })
                    })
                    // B 区（摄像头右侧）：图文组件2 的文字槽位，放精简后的教室名称
                    put("imageTextInfoRight", buildJsonObject {
                        put("type", 2)
                        put("textInfo", buildJsonObject {
                            put("title", text.room)
                            put("showHighlightColor", true)
                        })
                    })
                })
                // 小岛（多岛并存折叠态）只支持图片组件：引用 pics 中的应用图标
                put("smallIslandArea", buildJsonObject {
                    put("picInfo", appIconPic())
                })
                put("shareData", buildJsonObject {
                    put("title", text.title)
                    put("content", text.content)
                    put("shareContent", "${text.title} ${text.content}")
                })
            })
        })
    }.toString()

    private fun appIconPic() = buildJsonObject {
        put("type", 1)
        put("pic", PIC_KEY_LOGO)
    }

    /**
     * 组装需要挂到通知上的 extras：岛参数 + 图片 + 操作按钮。
     */
    fun extras(
        context: Context,
        paramJson: String,
        contentIntent: PendingIntent
    ): Bundle = Bundle().apply {
        putString(PARAM_KEY, paramJson)
        putBundle(ACTIONS_KEY, Bundle().apply {
            putParcelable(
                ACTION_KEY_TIMETABLE,
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_class),
                    "查看课表",
                    contentIntent
                ).build()
            )
        })
        putBundle(PICS_KEY, Bundle().apply {
            putParcelable(PIC_KEY_LOGO, Icon.createWithResource(context, R.mipmap.ic_launcher))
        })
    }
}
