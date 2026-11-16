package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * MD3E 的 8 档圆角令牌（M3 的 5 档 + Expressive 新增 3 档）。
 *
 * 组件优先取 `MaterialTheme.shapes.*`；只有跨页面复用的自定义容器才引用
 * [AppShapes]，避免各处硬编码圆角导致形状语言不统一。
 */
val ThemeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp)
)

/** 全应用统一的圆角令牌（收敛到 MD3E 形状刻度）。 */
object AppShapes {
    /** 页面级卡片 / 分组卡片：largeIncreased（20dp）。 */
    val Card = RoundedCornerShape(20.dp)

    /** 卡片内的次级容器、列表条目：medium（12dp）。 */
    val InnerCard = RoundedCornerShape(12.dp)

    /** Hero / 统计大卡：extraLarge（28dp），承担页面的形状强调。 */
    val Hero = RoundedCornerShape(28.dp)

    /** ModalBottomSheet 顶部圆角（M3 规范 28dp）。 */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** 设置行 / 快捷入口的图标底板：small（8dp）。 */
    val IconTile = RoundedCornerShape(12.dp)

    /** 胶囊：按钮、分段控件、徽标。 */
    val Pill: Shape = CircleShape
}
