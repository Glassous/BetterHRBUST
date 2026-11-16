package com.glassous.betterhrbust.core.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.core.ui.theme.AppMotion

/** 二级页面返回（滑出）时收起的圆角半径。 */
val SecondaryPageExitCorner = 20.dp

/**
 * 二级页面容器：返回（含系统预测性返回手势）时保持整页底色不透明，并把页面裁成圆角卡片。
 *
 * 需要直接放在 `NavHost` 的 `composable { }` 内使用——该作用域是 [AnimatedContentScope]，
 * 从中可读取转场进度：预测性返回拖动时导航组件会 seek 同一条 Transition，
 * 因此圆角与位移始终和手指进度同步；手势取消回弹时圆角也随之收回。
 *
 * 不在这里包一层不透明底色的话，页面滑出时其下方（正在返回的首页）会直接透出，
 * 视觉上像是"这页的背景消失了"；同时去掉 pop 转场的淡出，页面才是一整块卡片滑走。
 */
@Composable
fun AnimatedContentScope.SecondaryPageSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 0 = 展开（静止 / 进入），1 = 已退出；只在返回方向上产生圆角。
    val exitProgress by transition.animateFloat(
        transitionSpec = { tween(AppMotion.ScreenMillis) },
        label = "secondaryPageExitProgress"
    ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }

    val cornerPx = with(LocalDensity.current) { SecondaryPageExitCorner.toPx() } * exitProgress
    val cornerShape = if (cornerPx > 0f) RoundedCornerShape(cornerPx) else RectangleShape

    Box(
        modifier = modifier
            .fillMaxSize()
            // 先建 layer 再铺底色：底色与内容一起被裁进圆角，
            // 页面滑出过程中保持不透明，只有四角露出下层页面。
            .graphicsLayer {
                shape = cornerShape
                clip = cornerPx > 0f
            }
            .background(MaterialTheme.colorScheme.background)
    ) {
        content()
    }
}
