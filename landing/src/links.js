// 全站统一的真实跳转地址（来自仓库 README）。
// 注意：发版时需同步替换 WINDOWS_SETUP / WINDOWS_PORTABLE / ANDROID_APK 中的版本号（1.3.0）。
export const GITHUB_REPO = 'https://github.com/Glassous/BetterHRBUST'
export const RELEASES = `${GITHUB_REPO}/releases`

export const WINDOWS_SETUP = `${RELEASES}/latest/download/BetterHRBUST-1.3.0-windows-setup.exe`
export const WINDOWS_PORTABLE = `${RELEASES}/latest/download/BetterHRBUST-1.3.0-windows-portable.zip`
export const ANDROID_APK = `${RELEASES}/latest/download/BetterHRBUST-1.3.0-android.apk`

export const USERSCRIPT =
  'https://raw.githubusercontent.com/Glassous/BetterHRBUST/dist/better-hrbust.user.js'

// 六端品牌图标（顺序与「随处可用」卡片一致），供 Hero「查看全部版本」按钮的堆叠图标使用
export const PLATFORM_LOGOS = [
  '/Tampermonkey_logo.svg',
  '/Windows_logo.svg',
  '/Android_logo.svg',
  '/Linux_logo.svg',
  '/Apple_logo.svg',
  '/App_Store_logo.svg'
]
