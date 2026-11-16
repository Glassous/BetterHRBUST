package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 全应用统一的圆角与间距令牌。 */
object AppShapes {
    /** 页面级卡片 / 分组卡片。 */
    val Card = RoundedCornerShape(20.dp)

    /** 卡片内的次级容器、列表条目。 */
    val InnerCard = RoundedCornerShape(14.dp)

    /** ModalBottomSheet 顶部圆角（M3 规范 28dp）。 */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** 设置行 / 快捷入口的图标底板。 */
    val IconTile = RoundedCornerShape(12.dp)
}
