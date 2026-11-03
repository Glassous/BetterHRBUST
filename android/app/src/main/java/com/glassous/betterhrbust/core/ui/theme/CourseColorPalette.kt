package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * 课程卡片的一套颜色（底 / 字 / 边 / 强调点），明暗主题各一套。
 */
data class CourseCardColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val accent: Color
)

/**
 * 课程动态配色（黄金角 HSL），与 Web 端 `web/src/utils/courseColors.js` 完全对齐。
 *
 * - 以「课程名称」为主键（缺失时回退课序号 → 课程 ID → 排课 ID），去除空白后作为稳定标识；
 * - 模块级注册表为每门课分配独立色相槽位，色相 = round(slot × 137.508) % 360，
 *   槽位间隔最大化、互不重复 —— 有多少门课就有多少种颜色，不受固定色板数量限制；
 * - [register] 按课程主键码点排序批量注册，保证配色与数据源顺序无关，跨页面、跨重启稳定；
 * - 非当前教学周的课程由调用方以 `muted = true` 取色，统一走中性灰弱化。
 *
 * 使用方应在课表数据变化时（`remember(timetableResult)` 内）先调用 [register]，
 * 再逐块调用 [cardColors]，避免首帧按遭遇顺序分配槽位导致颜色跳动。
 */
object CourseColorPalette {

    private const val GOLDEN_ANGLE = 137.508f

    /** 主键缺失时的兜底色相（Web 同为 220 的中性蓝）。 */
    private const val FALLBACK_HUE = 220

    /** 课程主键 → 色相槽位序号（一旦分配即稳定不变）。主界面与组件共享，访问串行化。 */
    private val slotRegistry = LinkedHashMap<String, Int>()

    /** 提取课程主键：优先课程名，依次回退课序号 / 课程 ID / 排课 ID，并去除所有空白。 */
    fun keyOf(
        courseName: String? = null,
        courseSeq: String? = null,
        courseId: String? = null,
        id: String? = null
    ): String {
        val raw = sequenceOf(courseName, courseSeq, courseId, id)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
        return normalize(raw)
    }

    /**
     * 批量注册课程配色（在课表数据加载完成后调用）。
     *
     * 按课程主键码点顺序注册新键，使颜色分配与数据源顺序无关；已注册的课程不受影响，
     * 可安全重复调用。
     *
     * @return 本次涉及的规范化主键（升序）。注册需在组合期完成（`remember(timetableResult)`），
     *   而 `remember` 的 lambda 不允许返回 Unit，故由本方法产出该值供调用方承载。
     */
    @Synchronized
    fun register(keys: Collection<String>): List<String> {
        val newKeys = keys.asSequence()
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .toSortedSet()
        newKeys.forEach { key ->
            if (!slotRegistry.containsKey(key)) {
                slotRegistry[key] = slotRegistry.size
            }
        }
        return newKeys.toList()
    }

    /** 取课程色相（0..359）。主键为空返回兜底色相，未注册的主键按首次遭遇顺序补分配。 */
    @Synchronized
    fun hueOf(key: String): Int {
        val normalized = normalize(key)
        if (normalized.isEmpty()) return FALLBACK_HUE
        val slot = slotRegistry.getOrPut(normalized) { slotRegistry.size }
        // 取整后可能得到 360，回绕到 0 以保证色相始终落在 [0, 360)
        return ((slot * GOLDEN_ANGLE) % 360f).roundToInt() % 360
    }

    /**
     * 取课程卡片配色。
     *
     * @param muted 非当前教学周课程传 true，返回中性灰样式（Web 的 COURSE_MUTED 等价物）
     */
    @Synchronized
    fun cardColors(key: String, dark: Boolean, muted: Boolean = false): CourseCardColors {
        if (muted) return mutedColors(dark)
        val hue = hueOf(key).toFloat()
        return if (dark) {
            // hsl(H 35% 20%) / hsl(H 55% 88%) / hsl(H 45% 60% / .35) / 强调点 hsl(H 70% 64%)
            CourseCardColors(
                container = Color.hsl(hue, 0.35f, 0.20f),
                content = Color.hsl(hue, 0.55f, 0.88f),
                border = Color.hsl(hue, 0.45f, 0.60f, 0.35f),
                accent = Color.hsl(hue, 0.70f, 0.64f)
            )
        } else {
            // hsl(H 70% 92%) / hsl(H 45% 26%) / hsl(H 60% 80%) / 强调点 hsl(H 65% 55%)
            CourseCardColors(
                container = Color.hsl(hue, 0.70f, 0.92f),
                content = Color.hsl(hue, 0.45f, 0.26f),
                border = Color.hsl(hue, 0.60f, 0.80f, 0.9f),
                accent = Color.hsl(hue, 0.65f, 0.55f)
            )
        }
    }

    private fun mutedColors(dark: Boolean) = CourseCardColors(
        container = if (dark) CourseMutedColor.backgroundDark else CourseMutedColor.backgroundLight,
        content = if (dark) CourseMutedColor.textDark else CourseMutedColor.textLight,
        // 边框 / 强调点直接沿用 Web 的 zinc-800 / zinc-200 与 zinc-500 / zinc-400
        border = if (dark) Zinc800 else Zinc200,
        accent = if (dark) Zinc500 else Zinc400
    )

    private fun normalize(value: String): String = value.replace(Regex("""\s+"""), "").trim()
}
