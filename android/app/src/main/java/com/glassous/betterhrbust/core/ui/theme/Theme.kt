package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
 * 应用主题：固定使用与 Web 端一致的 zinc 中性色板，不参与系统动态取色（Material You）。
 *
 * 之所以关闭动态取色：Web 端没有品牌色 / 壁纸取色概念，桌面端与网页端的观感必须一致，
 * 壁纸取色会让同一份界面在不同机型上变成不同颜色。
 */
@Composable
fun BetterHRBUSTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = betterHrbustColorScheme(darkTheme),
            typography = Typography,
            content = content
        )
    }
}
