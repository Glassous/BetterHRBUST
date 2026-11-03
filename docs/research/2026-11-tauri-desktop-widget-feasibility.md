# Tauri 桌面端加入桌面小组件可行性调研

> 调研日期：2026-11-03 ｜ 背景：Android 端已落地课表小组件（`android/.../widget/`，
> 4 种尺寸：周视图 / 今日宽版 / 今日窄版 / 通用），桌面端目前为单窗口 `main`
> （`desktop-tauri/src-tauri/src/lib.rs` 的 `create_window`）。本文评估在 Tauri 桌面端
> 引入"桌面小组件"的技术路线、复用面、代价与风险。
>
> 结论先行：**技术可行，且业务数据层几乎零改造；但"桌面小组件"在 Windows 上有三种
> 互不相通的含义，必须先定语义再动手**，否则会做出用户预期之外的东西。

## 一、结论（TL;DR）

| 路线 | 含义 | 结论 |
| --- | --- | --- |
| **B. 悬浮小窗**（推荐 MVP） | 无边框、透明、`skip_taskbar`、可选置顶的独立小窗，浮在桌面上 | ✅ 全部是 Tauri 官方能力，`docs.rs` 2.12.5 已确认 API 齐备；2~3 人日 |
| **A. 桌面层挂件** | 窗口 parent 到 Explorer 的 `WorkerW`，位于桌面图标之下/壁纸之上 | ⚠️ 可行但不是公开 API；社区插件或自研 ~150 行 Win32；+2 人日，风险中 |
| **C. Win11 小组件面板** | 系统任务栏 Widgets Board 里的卡片 | ❌ 不可行：Win32 应用必须**已打包（MSIX 包身份）**并实现 WinAppSDK 的 COM widget provider，仅 Win11 22H2+，与当前 NSIS/绿色版分发模型冲突 |
| **0. 主窗口紧凑模式** | 不新增窗口，把主窗口缩成小面板（复用同一 WebView） | ✅ 成本极低（~1 人日），可先用它验证"是否真需要常驻挂件" |

推荐路径：**先做路线 0 验证需求 → 上路线 B 做正式挂件 → 视反馈再评估路线 A**。
路线 C 建议直接排除，若产品预期就是"出现在系统小组件面板"，需要提前对齐（意味着改分发模型为 MSIX）。

三个**硬性前置条件**（不满足则小组件没有意义）：

1. **托盘 + 关闭到托盘**：Tauri 默认在最后一个窗口关闭时退出进程。用户点掉主窗口后进程若随之退出，
   小组件必然一起消失。需要 `tray-icon` 能力 + 拦截 `main` 的 `CloseRequested` 改为 `hide()`。
2. **小组件必须有独立前端入口**：现有 `web/src/App.vue` 挂载时会执行 `checkAuth` 与
   `ensureDailySync()`（每天首次打开触发全量联网同步）。若小组件复用同一入口，会每天在后台
   跑一遍全量教务同步，开销与失败提示都不可接受。
3. **窗口 label / capability / 导航守卫的第二套配置**：`capabilities/main.json` 当前只授权
   `windows: ["main"]`，导航守卫也只挂在主窗口的 `on_navigation` 上。

复用面（这是本项目做小组件最大的优势）：

| 已有能力 | 位置 | 小组件直接可用 |
| --- | --- | --- |
| 校历 / 教学周换算 | `shared/school-calendar.json`、`web/src/utils/schoolCalendar.js` | ✅ `teachingWeekOn()` 纯本地计算，无需联网 |
| 官方作息时间（大节起止） | `web/src/utils/periodTimes.js` | ✅ 含 `hasCombineSlotEnded()` 可直接判定"已下课" |
| 周次表达式匹配（单双周） | `web/src/utils/courseWeeks.js` | ✅ `isCourseActiveInWeek()` |
| 课程配色（黄金角色相） | `web/src/utils/courseColors.js` | ✅ `registerCourseColors()` / `getCourseColor()` |
| 课表缓存数据 | `better_hrbust_cache_timetable`（cells: day/sectionIndex/courseName/location/weeks） | ✅ 结构已足够渲染"今天/明天/本周" |
| 桌面端持久化 | `/__app/storage` 端点 + `web/src/services/storage.js` | ✅ 同源可见，只读即可 |
| Android 端小组件语义 | `TimetableWidgetModel.kt`（今日课程 / 剩余课程 / 第几周） | ✅ 逻辑可 1:1 平移为 JS 纯函数 |

## 二、三条路线的技术细节

### 2.1 路线 C（系统小组件面板）为什么不可行

微软官方文档（Widget provider package manifest）明确：**"For Win32 apps, only packaged apps are
currently supported"**——即应用必须拥有 MSIX 包身份，并在 `Package.appxmanifest` 中声明
`windows.widgetProvider` 扩展与进程外 COM server（`com:ExeServer` + `com:Class`），实现
`Microsoft.Windows.Widgets.Providers.IWidgetProvider`，通过 `WidgetManager` 推送内容；
运行时要求 Windows App SDK 1.2+，且仅 **Windows 11 22H2 (22621) 及以上**支持第三方 provider
（Windows 10 无此能力）。

与本项目的冲突点：

- 打包形态需从 NSIS `setup.exe`（currentUser 免管理员）改为 **MSIX**，还要维持绿色 zip 的
  "解压即用"体验——MSIX 与免安装天然矛盾；
- provider 需要常驻 COM 激活与包身份校验，等于在 Rust 里再包一层 C#/C++/WinRT 实现；
- 任务栏小组件面板本身可被组策略关闭、部分环境需登录微软账户，可用性不由我们控制；
- 只能"往系统面板里供卡片"，无法做桌面常驻挂件——即使做出来也不是用户说的"桌面小组件"。

**结论：不做。** 若产品侧有该预期，应以"需改分发模型 + 仅 Win11"为前置条件重新立项。

### 2.2 路线 B（悬浮小窗）：官方能力齐备

`docs.rs` 上 tauri 2.12.5（2026-10-21 发布）已确认以下 API：

| 需求 | API | 备注 |
| --- | --- | --- |
| 无边框 | `WebviewWindowBuilder::decorations(false)` | 与主窗口现有做法一致 |
| 透明背景 | `transparent(true)` | Windows 需同时 `decorations(false)`；**必须创建期设置**（`set_background_color` 在 Windows 窗口层忽略 alpha，Win8+ alpha≠0 一律按 255 处理） |
| 去掉阴影/白边 | `shadow(false)` | 官方注明：Windows 上无边框窗口若 `shadow(true)` 会加 1px 白边 / Win11 圆角 |
| 不占任务栏 | `skip_taskbar(true)` | Alt+Tab 是否隐藏取决于实现，需实测 |
| 置顶 | `always_on_top(true)` / `set_always_on_top` | 桌面被 Win+D 显示时会一起隐藏，属预期行为 |
| 点击穿透 | `set_ignore_cursor_events(true)` | 运行时方法（builder 无此项），配合"锁定"开关 |
| 贴边定位 | `Monitor::work_area() -> &PhysicalRect<i32, u32>` | 2.12 已提供，直接扣掉任务栏区域 |
| 毛玻璃 | `set_effects(WindowEffectsConfig)` | 需先 `transparent`；Mica/Acrylic 依赖 Win11，Win10 只能 blur 兜底 |
| 拖动 | 前端 `mousedown` → `startDragging()` | 与主窗口 `shim.js` 的 `desktopWindow.startDrag` 同路径 |

注意事项（官方 Known issues）：`WebviewWindowBuilder::new/from_config` 在 Windows 上若从
**同步 command 或事件处理器**里调用会死锁（WebView2 已知问题），必须在 `async` command 或独立线程里创建。
现有 `lib.rs` 已在 `tauri::async_runtime::spawn` 内创建窗口，天然满足——新增小组件窗口沿用同样方式即可。

### 2.3 路线 A（WorkerW 桌面层）

原理：向 `Progman` 发 `0x052C` 消息让 Explorer 生成 `WorkerW` 层，再 `SetParent(webview_hwnd, workerw)`，
窗口即位于**桌面图标之下、壁纸之上**，`Win+D` 不消失、不占任务栏、不参与窗口切换。
Wallpaper Engine / Lively 均为该做法，微软未公开文档化。

两种落地方式：

| 方式 | 说明 | 结论 |
| --- | --- | --- |
| `tauri-plugin-wallpaper`（社区，MIT） | 提供 `attach/detach`、Pin（拦截 `WM_WINDOWPOSCHANGING` 抗 Win+D）、Win11 圆角禁用、explorer 重启自动重挂、遮挡检测、以及**可交互**（把原始输入转发到 WebView2 的 `Chrome_WidgetWin_1`，滚轮用 DOM 事件绕开 Chromium 限制） | 能力强，但作者 README 自荐"超出 hobby 规模建议自行实现"；插件与 Tauri 锁定的 `windows` crate 版本强耦合（Tauri 2.11 → 4.0.x，主干要求 ≥2.12），升级 Tauri 容易断裂 |
| 自研（`windows` crate + 约 150 行） | 找 `WorkerW`、`SetParent`、监听 `TaskbarCreated` 重挂、禁用圆角 | 可控、无第三方维护风险；但**输入交互**是难点（WorkerW 子窗口收不到鼠标/键盘，需自行做原始输入转发） |

关键限制（务必写进产品预期）：

- 处于 WorkerW 层时**默认无法点击**（图标层在上面）。要做"可交互挂件"，必须做输入转发，
  且滚轮无法驱动原生滚动条（Chromium 会丢弃跨进程投递的滚轮消息）；
- 多显示器 / 混合 DPI 缩放 / 显示器热插拔的重排，插件与自研都需实测；
- 桌面层与"置顶悬浮"是互斥的两种用户体验，不能一个开关草率切换（切换会重建 parent 关系）。

## 三、推荐方案设计（路线 B）

### 3.1 Rust 侧：窗口创建通用化

把现有 `create_window(app, url)` 中的窗口选项参数化，新增 `create_widget_window`：

```rust
const WIDGET_W: f64 = 360.0;
const WIDGET_H: f64 = 300.0;
const WIDGET_MARGIN: f64 = 24.0;

fn create_widget_window(app: &tauri::AppHandle, base: &str) -> tauri::Result<()> {
    // base = "http://127.0.0.1:1950/"（端口可能顺延），与主窗口同源 → cookie / storage 端点直接复用
    let url = format!("{base}widget");
    let monitor = app.primary_monitor()?.or(app.available_monitors()?.into_iter().next());
    let (x, y) = widget_position(monitor.as_ref().map(|m| *m.work_area()), WIDGET_W, WIDGET_H);

    let parsed: tauri::Url = url.parse()?;
    let allowed_origin = format!("{}://{}", parsed.scheme(), parsed.authority());

    tauri::WebviewWindowBuilder::new(app, "widget", tauri::WebviewUrl::External(parsed))
        .title("BetterHRBUST 小组件")
        .inner_size(WIDGET_W, WIDGET_H)
        .position(x, y)          // 逻辑像素；首次启动按 work_area 右下角吸附
        .decorations(false)
        .transparent(true)       // 必须创建期开启（Windows 运行时改 alpha 无效）
        .shadow(false)           // 否则无边框窗口带 1px 白边
        .always_on_top(true)     // 桌面层方案（A）改为由 wallpaper attach 接管
        .skip_taskbar(true)
        .resizable(false)
        .focused(false)          // 创建时不抢主窗口焦点
        .visible(false)          // 复用主窗口的"加载完成再显示"策略，避免白闪
        .initialization_script(SHIM)   // 复用 shim.js：前端据此判定桌面环境
        .on_navigation(move |nav| { /* 复用 navigation_allowed 同一套守卫 */ })
        .build()?;
    Ok(())
}

/// 纯函数：右下角吸附 + 越界钳制。可像 navigation_allowed 一样直接单元测试。
fn widget_position(work: Option<PhysicalRect<i32, u32>>, w: f64, h: f64) -> (f64, f64) { /* ... */ }
```

设计要点：

- **位置持久化放在 Rust 侧**：监听 `WindowEvent::Moved` 写入 `app_data_dir/widget.json`，
  而不是让前端调 `set_position`。好处是 capability 不需要 `core:window:allow-set-position`，
  攻击面不扩大。
- **显示器拔除回落**：启动时校验已保存坐标是否落在任一 `available_monitors()` 的 work_area 内，
  不在则回落到主屏右下角。
- **单实例回调**：现在只对 `main` 做 `unminimize/show/set_focus`。若允许 `main` 被销毁，
  需在回调里重建；更稳的做法是 `main` 永不销毁（关闭 → `hide()`），小组件窗口独立开关。

### 3.2 权限：新增 `capabilities/widget.json`（最小集）

现有 `main.json` 的 `windows: ["main"]` 不覆盖新窗口。新增一份，`remote.urls` 与主窗口相同
（10 个生产端口 + 2 个 dev 端口；远程 URL 白名单是构建期静态值，增删端口需同步两处）：

```json
{
  "identifier": "widget",
  "description": "桌面小组件窗口：仅拖拽与事件，无窗口控制权限",
  "windows": ["widget"],
  "remote": { "urls": ["http://127.0.0.1:1950", "...", "http://localhost:5173"] },
  "permissions": ["core:window:allow-start-dragging", "core:event:default"]
}
```

小组件**不需要** `minimize / toggle-maximize / close / is-maximized`。`core:event:default` 用于
"点课程 → 主窗口跳到课表页"的事件通道。

### 3.3 前端：独立入口，避免触发全量同步

两个可选做法，推荐 A：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A. 入口内分支** | `web/src/main.js` 在 `initStorage()` 后按 `location.pathname` 分支：`/widget` → `import('./widget/entry.js')`，否则 → `import('./boot.js')` | 改动最小（不碰 vite 配置），动态 import 保证 App.vue 及其 14 个 view 不进小组件执行路径 |
| B. Vite 多页 | `rollupOptions.input = { main: 'index.html', widget: 'widget.html' }`，小组件独立 HTML 入口 | 产物体积最优（可少加载 362KB 的 `boot-*.js`），但需同步 `server.rs` 的 SPA 回退与 rust-embed 资产清单 |

两条都需要 `server.rs` 的静态托管支持——现状已满足：`static_response` 对无扩展名路径回退
`index.html`（`/widget` → `index.html`），因此方案 A **零后端改动**。

状态处理：

- 小组件窗口是独立 WebView，`storage.js` 会各自水合一次 `/__app/storage`（全量 JSON）。
  小组件**只读**业务缓存，写入仅限自己的键 `better_hrbust_widget_layout`，
  避免与主窗口的业务写入相互覆盖。
- 跨窗口无失效通知：主窗口同步完成后 `emit("widget:sync")`，小组件 `listen` 后重读存储；
  再加 60s 轻量轮询兜底（纯本地读取，不联网）。
- 点击课程 / "打开完整课表"：小组件 `emit("widget:navigate", { tab: "timetable" })`，
  主窗口 `listen` 后 `navigateTo(tab)`（`activeTab` 目前只在内存中，不是 URL 路由，所以不能用链接跳转）。
- 小组件内**不应** `import boot.js`：那会执行 `installDesktopExternalLinks()`、挂载 App、
  触发 `maybeAutoCheck()` 与 `ensureDailySync()`。
- 小组件只展示缓存，不发起教务请求；无数据/未登录时给出与 Android 一致的动作型文案
  （"打开应用登录" / "打开课表同步数据" / "当前处于假期"）。数据可能过期，必须显示"最后同步时间"。

### 3.4 渲染与外观

- 复用 `periodTimes.js` 的 `COMBINE_SLOT_TIMES` 计算"当前节 / 下一节 / 距下课"，
  复用 `courseColors.js` 的 `--course-h` 色相变量，视觉与主应用一致；
- 透明窗口内建议用 `rounded-xl` + 半透明背景 + `backdrop-blur`，由 CSS 完成，
  不依赖 `set_effects`（Mica/Acrylic 仅 Win11 且需额外处理，可作增强项）；
- 拖拽：整体用 `startDragging()`，但交互元素（按钮、滚动区）需 `stopPropagation`，
  否则点击会变成拖拽——主窗口 `DesktopTitleBar.vue` 已有同类处理可直接参考。

### 3.5 体积与内存代价（需在目标机实测复核）

| 指标 | 现状（README 实测 1.0.0） | 加入小组件后预估 |
| --- | --- | --- |
| 安装包 | 2.6MB（NSIS） | +0.1~0.3MB（复用同一份 vendor 与图标，仅新增入口与小 chunk） |
| 主进程 RSS | 15.8MB | 基本不变（窗口逻辑在既有进程内） |
| WebView2 渲染进程 | 主窗口一套 | **每多一个窗口多一个 renderer 进程**，典型私有工作集增量 30~60MB |
| 冷启动 | — | 小组件窗口 `visible(false)` 后异步创建，主窗口首屏不变 |

内存是这条路线的主要代价，也是建议"单窗口多形态（今日 / 本周 / 考试倒计时，内部切换）"
而不是"每个挂件一个窗口"的原因。若用户关闭小组件，应 `destroy()` 而非 `hide()`，以释放 renderer
（重建约 200~500ms）。

## 四、与 Android 端小组件的能力对齐

| Android（已有） | 桌面端对应 | 说明 |
| --- | --- | --- |
| 周视图（`TimetableWeekWidget`） | 尺寸 L（如 520×340），整周网格 | 数据同源，布局重写 |
| 今日宽版（`TimetableDayWidgets` wide） | 尺寸 M（360×300），今日剩余课程 + 进度 | **MVP 主形态** |
| 今日窄版（narrow） | 尺寸 S（220×160），下一节课 + 倒计时 | 信息密度最低，实现最简单 |
| 点击直达课表页 | `widget:navigate` 事件 → 主窗口切 tab | Android 用 `EXTRA_OPEN_TAB`，桌面用 Tauri event |
| 30 分钟系统周期刷新 | 前端 60s 本地重算 + 主窗口同步后事件 | 桌面无需系统调度，进程内定时即可 |
| 只读离线缓存、不联网 | 同 | 复用 `/__app/storage` |

首版建议只做 S/M 两档 + 一个"本周"切换，避免一次引入四种布局。

## 五、风险与缓解

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 用户说的"桌面小组件"其实是 Win11 系统面板 | 中 | 立项前确认语义；明确不可行原因（MSIX + 仅 Win11） |
| 关主窗口后进程退出，挂件消失 | 高 | 必须同批交付托盘 + 关闭到托盘；`main` 改为 `hide()` |
| 小组件入口误挂载全量 App，触发每日全量同步 | 高 | 独立入口分支；`boot.js` 与 `widget/entry.js` 严格分离；入口处加断言测试 |
| 透明 + 无边框在 WebView2 上的白闪/白边 | 低 | `shadow(false)` + `visible(false)` 待 `PageLoad::Finished` 再 show；透明只在创建期设置 |
| 双 WebView2 窗口内存增长 | 中 | 单窗口多形态；隐藏超时 `destroy()`；在 README 的体积/内存表中如实标注 |
| `skip_taskbar` 后 Alt+Tab 行为与预期不符 | 低 | 在 Win10/11 各测一次；必要时用 `WS_EX_TOOLWINDOW` 语义确认 |
| 多显示器 / 150% DPI 下位置漂移 | 中 | `work_area` + 逻辑坐标；拔显示器回落主屏；纯函数单测覆盖钳制逻辑 |
| 小组件展示过期数据引起误解 | 中 | 始终显示"最后同步时间"，无数据时给动作型文案 |
| 后续若转路线 A，WorkerW 非公开机制 | 中高 | 优先自研（150 行可控）；若用社区插件需 vendor + 锁 Tauri 小版本 |

## 六、工作量与实施路线

| 阶段 | 内容 | 估时 |
| --- | --- | --- |
| 0. 需求确认 | 明确是"悬浮小窗"还是"贴桌面"；确认可接受常驻内存代价与托盘常驻 | 0.5 人日 |
| 1. 外壳 | 托盘 + 关闭到托盘 + 单实例兼容；小组件窗口创建、位置持久化、导航守卫、capability | 1 人日 |
| 2. 前端入口 | `main.js` 分支 + `widget/entry.js` + `snapshot.js`（纯函数）+ 三档布局 | 1~1.5 人日 |
| 3. 联动与打磨 | 事件通道、隐藏/销毁策略、过渡动画、设置页开关（显示/置顶/尺寸/开机自启） | 0.5~1 人日 |
| 4. 测试与分发 | 纯函数单测、Rust 布局单测、Win10/11 + 多显示器 + 150% DPI 手工清单、体积/内存复测、README 更新 | 0.5 人日 |
| 可选 5. 桌面层 | WorkerW attach/detach（自研或插件）+ explorer 重启重挂 + 输入转发 | +2 人日 |

合计：路线 B 约 **3~4.5 人日**；加路线 A 约 **5~6.5 人日**（熟悉 Win32 的开发者）。

## 七、验收与测试

自动化（沿用项目现有习惯，不依赖 GUI）：

- Rust：`widget_position()` 的吸附/钳制/越界回落实例（与 `navigation_allowed` 同文件 `#[cfg(test)]`）；
  显示器拔除后的回落判定。
- 前端：`web/src/widget/snapshot.js` 用 `node --test`（仓库已有 `scripts/test-*.mjs` 惯例）验证
  "今天有课 / 今天已全部上完 / 明天有课 / 假期 / 未登录 / 缓存为空"六种快照。
- 回归：现有 `npm run test`（Rust 12 项集成测试）全绿，确认静态托管与 storage 端点未受影响。

手工清单：

- Win10 22H2 与 Win11（含 22H2+/24H2）各一轮；
- 150% 与 100% 缩放、双屏（主屏副屏互换、热插拔）；
- `Win+D`、Alt+Tab、任务栏自动隐藏、explorer 重启（仅路线 A 必须）；
- 关主窗口后托盘仍在、二次启动聚焦主窗口（单实例）；
- 小组件未登录 / 无缓存 / 缓存过期三种空状态文案。

## 八、待产品决策

1. "桌面小组件"的产品定义：悬浮置顶小窗（B）、贴在桌面图标下（A）、还是系统小组件面板（C，已判不可行）？
2. 是否接受**托盘常驻**这一交互改变（否则小组件无法在关窗后存活）？
3. 是否需要开机自启（`tauri-plugin-autostart`，会进一步抬高"装了个常驻程序"的感受）？
4. 小组件信息优先级：下一节课倒计时 / 今日剩余课程 / 本周课表 / 考试倒计时 / 成绩更新提醒。
5. 是否可接受整体内存增量（预估 +30~60MB，需实测）以换取常驻可见性。

## 九、参考

- Tauri `WebviewWindowBuilder`（2.12.5，2026-10-21）：<https://docs.rs/tauri/latest/tauri/webview/struct.WebviewWindowBuilder.html>
  —— `decorations/transparent/always_on_top/skip_taskbar/shadow/focused/position` 均存在；
  注明 Windows 上在同步 command 内创建窗口会死锁。
- Tauri `WebviewWindow` 运行时方法：<https://docs.rs/tauri/latest/tauri/webview/struct.WebviewWindow.html>
  —— `set_ignore_cursor_events`、`set_effects`（需 transparent）、`set_background_color`（Windows 忽略 alpha）、
  `set_skip_taskbar`（desktop only）。
- Tauri `Monitor`：<https://docs.rs/tauri/latest/tauri/window/struct.Monitor.html>
  —— `work_area() -> &PhysicalRect<i32, u32>`。
- Microsoft Learn《Widget provider package manifest XML format》：
  <https://learn.microsoft.com/en-us/windows/apps/develop/widgets/widget-provider-manifest>
  —— "For Win32 apps, only packaged apps are currently supported"。
- Microsoft Learn《Implement a widget provider in a win32 app (C++/WinRT)》：
  <https://learn.microsoft.com/en-us/windows/apps/develop/widgets/implement-widget-provider-win32>
  —— COM server + `windows.widgetProvider` 扩展 + WinAppSDK 1.2+。
- `tauri-plugin-wallpaper`（社区，MIT）：<https://github.com/meslzy/tauri-plugin-wallpaper>
  —— WorkerW attach/detach、抗 Win+D Pin、explorer 重启重挂、输入转发；与 Tauri 的 `windows` crate 版本强耦合。
- 本项目现状：`desktop-tauri/src-tauri/src/lib.rs`（窗口创建 / 导航守卫）、
  `desktop-tauri/src-tauri/src/server.rs`（SPA 回退）、`desktop-tauri/src-tauri/src/storage.rs`（存储端点）、
  `web/src/utils/{schoolCalendar,periodTimes,courseWeeks,courseColors}.js`、
  `android/app/src/main/java/com/glassous/betterhrbust/widget/`。
