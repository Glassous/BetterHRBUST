// 全站统一的真实跳转地址（来自仓库 README）。
// 注意：发版时需同步替换 WINDOWS_SETUP / WINDOWS_PORTABLE / ANDROID_APK / DESKTOP_ALL 中的版本号（1.5.0）。
export const GITHUB_REPO = 'https://github.com/Glassous/BetterHRBUST'
export const RELEASES = `${GITHUB_REPO}/releases`

// 桌面端全平台整合包：解压后同时得到 Linux（deb / rpm）与 Windows（安装版 / 免安装版）
export const DESKTOP_ALL = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-desktop-all.zip`
export const WINDOWS_SETUP = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-windows-setup.exe`
export const WINDOWS_PORTABLE = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-windows-portable.zip`
export const LINUX_DEB = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-linux-amd64.deb`
export const LINUX_RPM = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-linux-x86_64.rpm`
export const ANDROID_APK = `${RELEASES}/latest/download/BetterHRBUST-1.5.0-android.apk`

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
