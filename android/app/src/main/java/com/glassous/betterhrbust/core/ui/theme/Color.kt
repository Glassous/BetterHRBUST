package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * ---------------------------------------------------------------------------
 * Material 3 Expressive 配色。
 *
 * 色板由品牌主色 #6366F1（与官网 / landing 的 indigo 品牌色一致）经
 * Material Color Utilities 的 VIBRANT 方案派生：保留品牌蓝紫色相，
 * 同时把亮度与色度拉开到 MD3E 需要的对比层级。
 * 所有色值均为 HCT 计算结果，勿手工微调（会破坏角色间的对比关系）。
 * ---------------------------------------------------------------------------
 */

// ---------------- 中性色阶（桌面小部件仍沿用，保持与旧版组件一致） ----------------

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

/** 页面底色（浅色）。 */
val AppBackgroundLight = Color(0xFFFCF8FF)

/** 页面底色（深色）。 */
val AppBackgroundDark = Color(0xFF12121C)

/** 卡片 / 弹层表面。 */
val AppSurfaceLight = Color(0xFFFFFFFF)
val AppSurfaceDark = Color(0xFF1F1F29)

// ---------------- 语义色（品牌色板配套） ----------------

/** danger / rose 系列。 */
val Rose100 = Color(0xFFFFDAD6)
val Rose200 = Color(0xFFFECDD3)
val Rose400 = Color(0xFFFB7185)
val Rose500 = Color(0xFFF43F5E)
val Rose600 = Color(0xFFBA1A1A)
val Rose800 = Color(0xFF93000A)
val Rose950 = Color(0xFF4C0519)

/** warning / amber 系列。 */
val Amber400 = Color(0xFFFFB95E)
val Amber500 = Color(0xFFF0A03C)
val Amber600 = Color(0xFFD97706)
val Amber700 = Color(0xFF8A5100)
val Amber950 = Color(0xFF2E2115)

/** success / emerald 系列。 */
val Emerald500 = Color(0xFF10B981)

/**
 * 明 / 暗两套「警告提示」配色（登录失效横幅等）。
 *
 * 语义上仍是警示（琥珀），但容器 / 描边取 MD3E 的柔和容器层级，
 * 与新的蓝紫主色共存时不会显得突兀。
 */
data class WarningBannerColors(
    val container: Color,
    val border: Color,
    val content: Color,
    val accent: Color
)

fun warningBannerColors(dark: Boolean): WarningBannerColors = if (dark) {
    WarningBannerColors(
        container = Color(0xFF2E2115),
        border = Color(0xFF6B4A1C),
        content = Amber400,
        accent = Amber500
    )
} else {
    WarningBannerColors(
        container = Color(0xFFFFF4E5),
        border = Color(0xFFF0C48A),
        content = Amber700,
        accent = Amber600
    )
}

// ---------------- Material 3 配色方案 ----------------

private val LightColorScheme = lightColorScheme(
    // 主色：低饱和的雾蓝靛（不再使用高饱和电光蓝，避免大面积刺眼）
    primary = Color(0xFF4C5B7E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4F5),
    onPrimaryContainer = Color(0xFF1B2539),
    inversePrimary = Color(0xFFB6C4E3),
    primaryFixed = Color(0xFFDDE4F5),
    primaryFixedDim = Color(0xFFB6C4E3),
    onPrimaryFixed = Color(0xFF101B2F),
    onPrimaryFixedVariant = Color(0xFF35456A),
    secondary = Color(0xFF63597C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF4B4263),
    secondaryFixed = Color(0xFFE9DDFF),
    secondaryFixedDim = Color(0xFFCDC0E9),
    onSecondaryFixed = Color(0xFF1F1635),
    onSecondaryFixedVariant = Color(0xFF4B4263),
    tertiary = Color(0xFF705382),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DAFF),
    onTertiaryContainer = Color(0xFF573C69),
    tertiaryFixed = Color(0xFFF3DAFF),
    tertiaryFixedDim = Color(0xFFDCBAF0),
    onTertiaryFixed = Color(0xFF290F3B),
    onTertiaryFixedVariant = Color(0xFF573C69),
    background = AppBackgroundLight,
    onBackground = Color(0xFF1B1B25),
    surface = AppBackgroundLight,
    onSurface = Color(0xFF1B1B25),
    surfaceVariant = Color(0xFFE3E0F2),
    onSurfaceVariant = Color(0xFF464554),
    surfaceTint = Color(0xFF4C5B7E),
    inverseSurface = Color(0xFF302F3A),
    inverseOnSurface = Color(0xFFF2EFFE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF767585),
    outlineVariant = Color(0xFFC7C5D6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCF8FF),
    surfaceDim = Color(0xFFDBD8E7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FF),
    surfaceContainer = Color(0xFFEFECFB),
    surfaceContainerHigh = Color(0xFFE9E6F5),
    surfaceContainerHighest = Color(0xFFE3E1EF)
)

private val DarkColorScheme = darkColorScheme(
    // 主色：低饱和雾蓝靛（深色下同样保持柔和，不再出现亮蓝大色块）
    primary = Color(0xFFB6C4E3),
    onPrimary = Color(0xFF1D2B45),
    primaryContainer = Color(0xFF354564),
    onPrimaryContainer = Color(0xFFDDE4F5),
    inversePrimary = Color(0xFF4C5B7E),
    primaryFixed = Color(0xFFDDE4F5),
    primaryFixedDim = Color(0xFFB6C4E3),
    onPrimaryFixed = Color(0xFF101B2F),
    onPrimaryFixedVariant = Color(0xFF35456A),
    secondary = Color(0xFFCDC0E9),
    onSecondary = Color(0xFF342B4B),
    secondaryContainer = Color(0xFF4B4263),
    onSecondaryContainer = Color(0xFFE9DDFF),
    secondaryFixed = Color(0xFFE9DDFF),
    secondaryFixedDim = Color(0xFFCDC0E9),
    onSecondaryFixed = Color(0xFF1F1635),
    onSecondaryFixedVariant = Color(0xFF4B4263),
    tertiary = Color(0xFFDCBAF0),
    onTertiary = Color(0xFF3F2551),
    tertiaryContainer = Color(0xFF573C69),
    onTertiaryContainer = Color(0xFFF3DAFF),
    tertiaryFixed = Color(0xFFF3DAFF),
    tertiaryFixedDim = Color(0xFFDCBAF0),
    onTertiaryFixed = Color(0xFF290F3B),
    onTertiaryFixedVariant = Color(0xFF573C69),
    background = AppBackgroundDark,
    onBackground = Color(0xFFE3E1EF),
    surface = AppBackgroundDark,
    onSurface = Color(0xFFE3E1EF),
    surfaceVariant = Color(0xFF464554),
    onSurfaceVariant = Color(0xFFC7C5D6),
    surfaceTint = Color(0xFFB6C4E3),
    inverseSurface = Color(0xFFE3E1EF),
    inverseOnSurface = Color(0xFF302F3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF908F9F),
    outlineVariant = Color(0xFF464554),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383843),
    surfaceDim = Color(0xFF12121C),
    surfaceContainerLowest = Color(0xFF0D0D17),
    surfaceContainerLow = Color(0xFF1B1B25),
    surfaceContainer = Color(0xFF1F1F29),
    surfaceContainerHigh = Color(0xFF292934),
    surfaceContainerHighest = Color(0xFF34343F)
)

/** 应用当前使用的 Material 3 Expressive 配色方案。 */
internal fun betterHrbustColorScheme(darkTheme: Boolean) =
    if (darkTheme) DarkColorScheme else LightColorScheme

/**
 * 中性灰课程配色：非当前教学周的课程统一走该语义色（对齐 Web 的 COURSE_MUTED）。
 * 当前教学周的课程配色由 [CourseColorPalette] 的黄金角 HSL 算法动态生成。
 */
val CourseMutedColor = CourseMutedColors(
    backgroundLight = Color(0xFFEFECFB),
    textLight = Color(0xFF767585),
    backgroundDark = Color(0xFF292934),
    textDark = Color(0xFFC7C5D6)
)

data class CourseMutedColors(
    val backgroundLight: Color,
    val textLight: Color,
    val backgroundDark: Color,
    val textDark: Color
)
