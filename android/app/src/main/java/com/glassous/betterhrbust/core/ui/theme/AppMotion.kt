package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 全应用统一动效令牌：时长、弹簧与错峰延迟。
 *
 * 交互反馈（按压 / 选中 / 回弹）用 MD3E 风格的弹簧（轻微过冲后稳定），
 * 页面与内容入场用短时长缓动，避免同类动效在各页面快慢不一。
 */
object AppMotion {
    /** 整块内容 / 面板滑入时长（毫秒）。 */
    const val ScreenMillis = 360

    /** 内容淡入淡出 / 颜色切换时长（毫秒）。 */
    const val SmoothMillis = 280

    /** 按压、选中、回弹：带明显过冲的 Expressive 弹簧。 */
    val springSnappy = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)

    /** 大面积容器的展开 / 收起：比 [springSnappy] 更柔和。 */
    val springGentle = spring<Float>(dampingRatio = 0.78f, stiffness = 320f)

    /** 错峰入场延迟（毫秒），最多 200ms。 */
    fun stagger(index: Int): Int = (index * 40).coerceAtMost(200)
}

/**
 * 可点击 + 按下微缩的通用组合，替代单纯的 [clickable]。
 *
 * 按下 0.975 缩放、松手弹性回弹，并保留原有 ripple 指示；
 * 用在卡片、快捷入口、设置行等大面积可点区域，提供更跟手的触感。
 */
fun Modifier.pressClickable(
    pressedScale: Float = 0.975f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = AppMotion.springSnappy,
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick
        )
}
