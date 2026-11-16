package com.glassous.betterhrbust.core.ui.theme

import androidx.compose.material3.Typography

/**
 * MD3E 字阶：直接采用 1.5.0 线的默认 15 档正文 + 15 档 Emphasized。
 *
 * 页面标题使用 `displaySmall` / `headlineMedium` 等大字级制造编辑式层级，
 * 正文与标签继续使用 `body*` / `label*`；Expressive 字阶由组件自身
 * （如 FlexibleTopAppBar、LoadingIndicator）按需取用。
 */
val Typography: Typography = androidx.compose.material3.Typography()
