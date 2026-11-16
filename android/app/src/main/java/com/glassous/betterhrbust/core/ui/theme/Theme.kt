package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前应用内是否处于深色主题。
 *
 * 应用支持独立于系统主题的深浅色切换，需要感知明暗的组件（如课表课程配色）
 * 应读取该值而不是 isSystemInDarkTheme()，否则在应用内主题与系统主题不一致时配色错乱。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用主题：Material 3 Expressive。
 *
 * - 配色：品牌 indigo 派生的 MD3E 色板（不参与壁纸动态取色，保证与 Web 端观感一致）；
 * - 动效：`MotionScheme.expressive()`，组件动画统一走弹簧，位置/尺寸用 Spatial、颜色/透明度用 Effects；
 * - 字体：MD3E 默认 15 档正文 + 15 档 Emphasized 字阶；
 * - 形状：8 档圆角令牌（含 M3E 新增的 largeIncreased / extraLargeIncreased / extraExtraLarge）。
 */
@Composable
fun BetterHRBUSTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = betterHrbustColorScheme(darkTheme),
            motionScheme = MotionScheme.expressive(),
            typography = Typography,
            shapes = ThemeShapes,
            content = content
        )
    }
}
