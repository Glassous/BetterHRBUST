package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * ---------------------------------------------------------------------------
 * 配色与 Web 端（web/src，Tailwind zinc + 自定义底色）保持一致。
 *
 * Web 端没有品牌主色：界面全部由 zinc 中性色阶构成（主按钮 = zinc-800 反白、
 * 卡片 = 白 / #1c1e24、页面底 = #f6f7f9 / #14161a、边框 = zinc-200 / zinc-800）。
 * 此处把同一套色值映射到 Material 3 的语义色角色，使 Compose 界面与 Web 观感统一。
 * ---------------------------------------------------------------------------
 */

// ---------------- Tailwind zinc 色阶（与 Web 端 zinc-* 工具类一一对应） ----------------

val Zinc50 = Color(0xFFFAFAFA)
val Zinc100 = Color(0xFFF4F4F5)
val Zinc200 = Color(0xFFE4E4E7)
val Zinc300 = Color(0xFFD4D4D8)
val Zinc400 = Color(0xFFA1A1AA)
val Zinc500 = Color(0xFF71717A)
val Zinc600 = Color(0xFF52525B)
val Zinc700 = Color(0xFF3F3F46)
val Zinc800 = Color(0xFF27272A)
val Zinc900 = Color(0xFF18181B)
val Zinc950 = Color(0xFF09090B)

// ---------------- 页面底色 / 表面 ----------------

/** 页面底色：Web `bg-[#f6f7f9]`。 */
val AppBackgroundLight = Color(0xFFF6F7F9)

/** 页面底色：Web `dark:bg-[#14161a]`。 */
val AppBackgroundDark = Color(0xFF14161A)

/** 卡片 / 弹层表面：Web `bg-white` / `dark:bg-[#1c1e24]`。 */
val AppSurfaceLight = Color(0xFFFFFFFF)
val AppSurfaceDark = Color(0xFF1C1E24)

// ---------------- 语义色（与 Web 端同色号） ----------------

/** danger / rose 系列（Web `rose-*`）。 */
val Rose100 = Color(0xFFFFE4E6)
val Rose200 = Color(0xFFFECDD3)
val Rose400 = Color(0xFFFB7185)
val Rose500 = Color(0xFFF43F5E)
val Rose600 = Color(0xFFE11D48)
val Rose800 = Color(0xFF9F1239)
val Rose950 = Color(0xFF4C0519)

/** warning / amber 系列（Web `amber-*`）。 */
val Amber400 = Color(0xFFFBBF24)
val Amber500 = Color(0xFFF59E0B)
val Amber600 = Color(0xFFD97706)
val Amber700 = Color(0xFFB45309)
val Amber950 = Color(0xFF451A03)

/** success / emerald 系列（Web `emerald-*`）。 */
val Emerald500 = Color(0xFF10B981)

/**
 * 明 / 暗两套「警告提示」配色（登录失效横幅等）。
 *
 * 对齐 Web 端 AppHeader 的会话提示：amber-500/10 半透明底 + amber-500/30 描边 + amber-700 字
 * （深色为 amber-950 压暗底 + amber-400 字）。横幅是浮层，故把半透明色预算成不透明色值，
 * 避免下层内容透过横幅影响文字可读性。
 */
data class WarningBannerColors(
    val container: Color,
    val border: Color,
    val content: Color,
    val accent: Color
)

fun warningBannerColors(dark: Boolean): WarningBannerColors = if (dark) {
    WarningBannerColors(
        container = Color(0xFF2B1E0F),
        border = Color(0xFF6E4A15),
        content = Amber400,
        accent = Amber500
    )
} else {
    WarningBannerColors(
        container = Color(0xFFF7EEE2),
        border = Color(0xFFEFD9AE),
        content = Amber700,
        accent = Amber500
    )
}

// ---------------- Material 3 配色方案 ----------------

private val LightColorScheme = lightColorScheme(
    // 主色即「zinc 反白」：Web 的 primary 按钮是 bg-zinc-800 / 文字 zinc-100
    primary = Zinc800,
    onPrimary = Zinc50,
    primaryContainer = Zinc200,
    onPrimaryContainer = Zinc900,
    inversePrimary = Zinc300,
    secondary = Zinc600,
    onSecondary = Zinc50,
    secondaryContainer = Zinc200,
    onSecondaryContainer = Zinc900,
    tertiary = Zinc700,
    onTertiary = Zinc50,
    tertiaryContainer = Zinc100,
    onTertiaryContainer = Zinc900,
    background = AppBackgroundLight,
    onBackground = Zinc900,
    surface = AppSurfaceLight,
    onSurface = Zinc900,
    surfaceVariant = Zinc100,
    onSurfaceVariant = Zinc600,
    surfaceTint = Zinc800,
    inverseSurface = Zinc900,
    inverseOnSurface = Zinc50,
    error = Rose600,
    onError = Color.White,
    errorContainer = Rose100,
    onErrorContainer = Rose800,
    outline = Zinc400,
    outlineVariant = Zinc200,
    scrim = Color.Black,
    surfaceBright = AppSurfaceLight,
    surfaceDim = Color(0xFFE7E8EB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFCFD),
    // 卡片统一取白色（Web `bg-white/95`）
    surfaceContainer = AppSurfaceLight,
    // 卡片内部的次级块（Web `bg-zinc-50/50`）
    surfaceContainerHigh = Zinc100,
    // 分段控件底槽等（需比页面底色更深一档才可辨）
    surfaceContainerHighest = Color(0xFFE9EAED)
)

private val DarkColorScheme = darkColorScheme(
    primary = Zinc200,
    onPrimary = Zinc900,
    primaryContainer = Zinc700,
    onPrimaryContainer = Zinc100,
    inversePrimary = Zinc800,
    secondary = Zinc400,
    onSecondary = Zinc900,
    secondaryContainer = Zinc700,
    onSecondaryContainer = Zinc100,
    tertiary = Zinc300,
    onTertiary = Zinc900,
    tertiaryContainer = Zinc800,
    onTertiaryContainer = Zinc100,
    background = AppBackgroundDark,
    onBackground = Zinc100,
    surface = AppSurfaceDark,
    onSurface = Zinc100,
    surfaceVariant = Zinc800,
    onSurfaceVariant = Zinc400,
    surfaceTint = Zinc200,
    inverseSurface = Zinc100,
    inverseOnSurface = AppSurfaceDark,
    error = Rose400,
    onError = Rose950,
    errorContainer = Rose950,
    onErrorContainer = Rose200,
    outline = Zinc500,
    outlineVariant = Zinc800,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2E36),
    surfaceDim = Color(0xFF101216),
    surfaceContainerLowest = Color(0xFF101216),
    surfaceContainerLow = Color(0xFF191B21),
    surfaceContainer = AppSurfaceDark,
    surfaceContainerHigh = Color(0xFF23262D),
    surfaceContainerHighest = Color(0xFF2A2E36)
)

/** 应用当前使用的 Material 3 配色方案（与 Web 端同源的 zinc 中性色板）。 */
internal fun betterHrbustColorScheme(darkTheme: Boolean) =
    if (darkTheme) DarkColorScheme else LightColorScheme

/**
 * 中性灰课程配色：非当前教学周的课程统一走该语义色（对齐 Web 的 COURSE_MUTED）。
 * 当前教学周的课程配色由 [CourseColorPalette] 的黄金角 HSL 算法动态生成。
 */
val CourseMutedColor = CourseMutedColors(
    backgroundLight = Zinc100,
    textLight = Zinc500,
    backgroundDark = Zinc800,
    textDark = Zinc400
)

data class CourseMutedColors(
    val backgroundLight: Color,
    val textLight: Color,
    val backgroundDark: Color,
    val textDark: Color
)
