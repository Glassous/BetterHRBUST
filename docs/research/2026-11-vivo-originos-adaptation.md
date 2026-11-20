# vivo OriginOS 原子岛 / 原子组件适配调研

调研时间：2026-11-20
适用端：`android/`（Kotlin + Jetpack Compose，minSdk 30 / targetSdk 36 / compileSdk 37）
真机：vivo V2415A / OriginOS 16.0（Android 16 / API 36）；原子组件与通知形态已实测，原子岛确认不可用

## 结论摘要

| 能力 | 能否接入 | 结论 |
| --- | --- | --- |
| 原子岛（灵动岛 / 灵动胶囊） | **否** | vivo 私有协议 + 白名单准入，要求应用已在 vivo 应用商店上架，并走邮件申请、需求与 UI 评审、权限开通；场景枚举里没有「上课 / 课表」。本项目未上架，**不做正式接入**，通知继续走标准 `Notification`，由系统按能力降级。 |
| 原子组件（桌面小组件） | **有条件** | 本质是标准 `AppWidget` 加 3 个 `meta-data`。尺寸、12 小时刷新、浅深色预览都能做；但 `vivo_widget` 声明只有通过 vivo 原子组件平台审核后才应启用，否则 OriginOS 会把组件从「应用挂件」列表移出，而「原子组件」库又不收录未审核组件。本项目保留适配与待启用的清单块。 |

一句话：**原子组件是公开可做的；原子岛不是「写法问题」，是准入问题。**

---

## 一、原子岛

### 1.1 它不是 Android 16 的 Live Updates

Android 16 提供了标准的「实况通知 / Live Updates」：

```kotlin
builder.setRequestPromotedOngoing(true)
builder.setShortCriticalText("12 分钟")
// 配合 setUsesChronometer(true) / setChronometerCountDown(true)
```

vivo 的原子岛是**另一套私有实现**，走 `notification.superx.*` extras，和 AOSP 的提升机制不互通。两者不能互相替代：

- 标准 API 在 vivo 上会被系统忽略，`NotificationManager.canPostPromotedNotifications()` 返回 `false`；
- 私有 extras 在没有准入的情况下会被系统清洗，读回通知时连字段都不存在。

### 1.2 客户端本地接口（官方文档）

应用进程活跃时，把参数写进 `Notification.extras` 后正常 `notify()`：

```java
Bundle b = new Bundle();
b.putInt("notification.superx.operation", 0);      // 0 创建 / 1 更新 / 2 结束
b.putBoolean("notification.superx.showNotify", true); // 原子通知失败时是否回落普通通知
b.putInt("notification.superx.template", 1);       // 大卡模板
b.putParcelable("notification.superx.clickResp", pendingIntent);
b.putString("notification.superx.scene", "HEALTH_REGISTER");

Bundle baseInfo = new Bundle();
baseInfo.putParcelable("notification.superx.baseInfos.icon", icon);
baseInfo.putCharSequence("notification.superx.baseInfos.title", title);
baseInfo.putCharSequence("notification.superx.baseInfos.content", content);
b.putBundle("notification.superx.baseInfos", baseInfo);
```

字段分组：

| 字段 | 作用 |
| --- | --- |
| `notification.superx.operation` | 0 创建、1 更新、2 结束 |
| `notification.superx.showNotify` | 原子通知展示失败时是否回落到普通通知 |
| `notification.superx.template` | 大卡模板：1 强调信息、2 进度可视化、4 基础 |
| `notification.superx.scene` | 场景枚举，决定系统用哪套模板与话术 |
| `notification.superx.baseInfos` | 图标、标题、正文 |
| `notification.superx.capsule` | 普通胶囊：state / content / icon |
| `notification.superx.infos` | 模板数据（进度、左右信息、图片等） |
| `notification.superx.shortInfos` | 大卡展开后的短信息 |
| `notification.superx.island` | 原子岛胶囊：左模板 `leftTemplate`、右模板 `rightTemplate`（1–6）及 `leftInfo` / `rightInfo` |
| `notification.superx.clickResp` | 点击跳转的 `PendingIntent` |
| `notification.superx.keepDuration` | 结束后存档秒数，最长 1 小时 |
| `notification.superx.changeRecord` | 更新序号，小于当前值的更新会被丢弃 |

远程推送侧对应 `AndroidVivoLiveMessage`，字段结构一致，只是以 JSON 传递。本项目没有接入 vivo 推送，故不涉及。

### 1.3 展示形态

- **普通胶囊**：不支持原子岛的机型、上下折叠屏外屏，显示在状态栏；
- **原子岛胶囊**：支持原子岛的机型，围绕前摄挖孔显示，左侧 2 种模板（图片 / 图片+文本），右侧 5 种（图片 / 图片+文本 / 文本 / 文本+图片 / 胶囊文本）；
- **大卡**：通知中心、轻尚锁屏、悬浮大卡；分强调信息、进度可视化、左右布局、基础、导航 5 类；
- **小卡**：熄屏、桌面组件、上下折外屏锁屏；
- **Origin 锁屏样式**：Origin 锁屏。

### 1.4 场景枚举（关键限制）

官方枚举：

```
MOVIE / HEALTH_REGISTER / TAXI / TAKEOUT / DELIEVERY /
NAVIGATION / CAR_STATE / METTING / TRAIN / FLIGHT
```

没有「上课 / 课表 / 校园」类目。原子通知的产品定位是「用户主动触发、有明确起止、实时关注」的进程型事件（外卖、打车、导航、航班）。「课前 20 分钟提醒」更接近**定时提醒**，不是原子通知定义的持续进程，即使走完准入，场景评审也存在被拒的风险。

### 1.5 官方接入前置（硬门槛）

摘自《原子通知接入指导》：

1. 应用**已在 vivo 应用商店上架**；
2. 发邮件到 vivo 指定邮箱申请接入权限，等待原子通知团队审核准入（**7–10 个工作日**）；
3. 完成需求定义与 UI 设计；
4. 双方评审并定稿；
5. vivo 开通权限后开发，双方联调测试；
6. 上线后**按机型逐步放量**；
7. 定期体验巡检，重大更新互相公告。

邮件必须包含：应用名称、包名、联系电话/微信、接入的具体服务场景、需求定义文档、服务示意图。缺一无法通过准入。

### 1.6 运行限制

- 同一信息不要同时发原子通知和普通通知；
- 单个活动刷新频率最高 10 秒/次，超出丢弃；
- 超过 2 小时不更新，系统清除；
- 单个活动最长显示 8 小时；
- 用户手动删除通知中心/锁屏的原子通知后，本次不再显示；
- 应用在前台落地页时，状态栏胶囊临时隐藏；
- 应用进程被杀后，系统默认**不清除**原子通知，需要业务方自己结束。

### 1.7 第三方实测证据

开源项目 `island-live` 在 vivo V2458A（OriginOS，Android 16）上的实测（2026-09-14）：

- `canPostPromotedNotifications() = false`，标准 API 不提升；
- 通知 extras 里 `android.requestPromotedOngoing`、`android.shortCriticalText`、`ProgressStyle` 全部设上，仍不上岛；
- 按官方字段名直接塞 `notification.superx.*`，读回通知时字段被系统剥离；
- `cmd appops get` 只有 `SYSTEM_ALERT_WINDOW: default`，没有提升相关开关，Shizuku 改 appops 也绕不过；
- 同一台机器上别的 App 能上岛，说明是**白名单放行**，不是系统不支持。

结论与官方文档一致：**没有准入就没有原子岛。**

### 1.8 本项目决定

**不写原子岛代码路径。** 理由：

1. 未在 vivo 应用商店上架，准入第一步就不满足；
2. 场景枚举没有教育/课表类目，需求评审大概率不通过；
3. 私有 extras 会被系统剥离，写了也是死代码；
4. 标准 API 在 vivo 上不放行，无法用「兜底」方式呈现。

保留现状：上课提示仍是标准常驻通知（`setUsesChronometer` + `setChronometerCountDown` + `setOngoing`），在 vivo 上呈现为普通通知栏通知，用户仍能看到倒计时。等未来具备上架与准入条件，再按 `notification.superx.*` 接入，改动点集中在 `core/notify/ClassIslandNotifier.kt`。

### 1.9 真机验证方法（确认上述结论）

```bash
adb shell getprop ro.vivo.os.version
adb shell getprop ro.build.version.release
adb logcat -s HyperIsland:D
# 调试包内开启「模拟上课提示（调试）」，日志会打印：
#   设备支持超级岛=false，协议版本=0
#   上课提示已下发，呈现方式：普通通知

adb shell dumpsys notification --noredact | grep -A40 course_reminder
# 检查 android.requestPromotedOngoing / android.shortCriticalText 是否存在，
# 以及是否出现 notification.superx.*（正常情况下不会出现）
```

> 注：本机 vivo 会抑制第三方 `Log.d`，实测 `logcat -s HyperIsland:D` 为空。此时以 `dumpsys notification` 与状态栏 / 通知中心视觉为准。

实测结果（2026-11-20，vivo V2415A / OriginOS 16.0 / Android 16）：

- 模拟上课提示已下发：`dumpsys notification` 可见 `id=20261109`、`channel=course_reminder`、`category=event`、`flags=ONGOING_EVENT|ONLY_ALERT_ONCE`；
- extras 只有标准字段（title / text / bigText / chronometer）与小米 `miui.focus.*`，**没有** `android.requestPromotedOngoing`、`android.shortCriticalText`，也**没有** `notification.superx.*`；
- `cmd appops get com.glassous.betterhrbust.debug` 无 promoted 相关 op；`dumpsys notification` 全局 **0** 处 `promot`；
- 通知中心显示为普通卡片「N 分钟后上课 / 教1-302」，状态栏没有围绕摄像头的胶囊；
- 结论与外部实测一致：vivo 不消费 Android 16 的标准提升字段，原子岛只对白名单合作方开放。

预期：`呈现方式：普通通知`，状态栏无胶囊，通知中心有普通常驻通知。

---

## 二、原子组件

### 2.1 本质

vivo 原子组件 = 标准 `AppWidget` + 3 个 `meta-data`。不需要引入 vivo SDK，不需要改渲染框架；Glance 和传统 `RemoteViews` 都能用。

### 2.2 Manifest 字段

```xml
<receiver android:name=".widget.TimetableWeekWidgetReceiver" ...>
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider"
        android:resource="@xml/timetable_week_widget_info" />

    <meta-data android:name="vivo_widget" android:value="true" />
    <meta-data android:name="vivoWidgetVersion" android:value="1" />
    <meta-data android:name="vivo.widget.description"
        android:resource="@string/widget_week_vivo_description" />
</receiver>
```

- `vivo_widget`：原子组件标识，固定 `true`；
- `vivoWidgetVersion`：**正整数，从 1 开始，只能升不能降**；每个组件单独维护，组件内容变化时 +1；
- `vivo.widget.description`：组件库详情页里、组件名称下方、预览图上方的描述语，不宜过长。

> **重要：未通过 vivo 原子组件平台审核时，不要声明 `vivo_widget=true`。**
>
> 真机实测（vivo V2415A / OriginOS 6）：声明后，三个 Glance 组件会从桌面「应用挂件」列表消失，该应用只剩「双日课表」1 个；去掉声明后恢复为 4 个。卸载重装、重启桌面后结果一致，不是缓存问题。
>
> 也就是说，`vivo_widget` 不是「让组件更好看」的开关，而是把组件**转交原子组件库**的开关；原子组件库只收录通过 vivo 平台审核的组件，未审核的应用会落入「原子组件库没有、普通挂件列表也不显示」的空档。

### 2.3 网格尺寸

| 规格 | targetCellWidth × Height | 建议 minWidth | minWidth 范围 | 建议 minHeight | minHeight 范围 |
| --- | --- | --- | --- | --- | --- |
| 2×2 | 2 × 2 | 120dp | (80, 160] | 120dp | (80, 160] |
| 4×2 | 4 × 2 | 280dp | (240, 320] | 120dp | (80, 160] |
| 4×4 | 4 × 4 | 280dp | (240, 320] | 280dp | (240, 320] |

现有三个组件是 4×4、2×4、4×4。2×4 按上表转置处理：`minWidth=120dp`、`minHeight=280dp`。

> 2026-11-20 调整：窄版日视图改为 **2×3（120×190dp）**，不在 vivo 的 2×2 / 4×2 / 4×4 三档推荐网格内。因为未声明 `vivo_widget`，它按普通 AppWidget 使用，不受原子组件网格审核约束；若未来要作为原子组件提交，需要改回三档之一。

### 2.4 刷新

官方允许三种：

1. 应用主动刷新；
2. 用户主动触发刷新；
3. 按 `updatePeriodMillis` 间隔刷新，**要求间隔在 12 小时以上**。

因此 `updatePeriodMillis=1800000`（30 分钟）不符合原子组件规范，需要改为 `43200000`（12 小时）。本项目的小组件另有更及时的刷新源：数据变化、跨天/改时间/时区/开机广播、`AlarmManager` 在课程边界精确刷新，12 小时只是兜底，不影响实际体验。

> 配套修正：原来的课程边界闹钟只在「双日课表」存在时排程，只有 Glance 组件时不会排程。本次把排程移到 `TimetableWidgetUpdater.refreshAll`，由 `TimetableGlanceReceiver` 在组件添加 / 移除时维护，并让闹钟同时刷新传统组件与 Glance 组件，避免放宽到 12 小时后出现「只有原子组件的用户不会准点刷新」。

### 2.5 深色模式

- 组件本身跟随系统深浅色（Glance 的 `ColorProvider` 已做到）；
- 预览图需要**浅色与深色两套，文件名保持一致**，分别放在 `drawable-nodpi/` 与 `drawable-night-nodpi/`；
- 组件库会分别展示两套预览。

### 2.6 气泡快捷菜单（可选）

长按组件弹出的气泡菜单，最多 4 个，`meta-data` 键必须是 `shortcutinfo0` ~ `shortcutinfo3`，值格式：

```
action/packagename/classname/extravalue
```

示例：

```xml
<meta-data android:name="shortcutinfo0"
    android:value="android.intent.action.MAIN/com.glassous.betterhrbust/com.glassous.betterhrbust.MainActivity/fromwidget" />
```

- 气泡里的标题和图标来自目标 Activity 的 `label` 与 `icon`；
- `extravalue` 通过 `intent.getStringExtra("value")` 读取；
- 目标 Activity 必须始终可用，否则点击无响应。

本项目未使用：组件整块点击已经打开课表页，没有第二个有意义的高频动作。

### 2.7 跳转组件库详情页

需要权限：

```xml
<uses-permission android:name="com.bbk.launcher2.permission.JUMP_ORIGIN" />
```

显式 Intent：

```java
Intent intent = new Intent();
intent.setPackage("com.bbk.launcher2");
intent.setData(Uri.parse("vivo://com.bbk.launcher2/origin?pkg=xxx&classname=xxx&comType=0&locType=1"));
startActivity(intent);
```

- `comType=0`：展示该应用下所有组件；
- `locType=1`：跳转方式；
- 需要 OriginOS 版本支持（`ro.vivo.os.version >= 14.0`），平板不支持。

本项目未加入应用内入口，避免新增一个「只在部分 ROM 生效」的按钮。

### 2.8 一键添加到桌面

`AppWidgetManager.requestPinAppWidget()`。vivo 侧要求**原子组件已提交平台审核并上架**，否则调用无效果（不会报错）。本项目未上架，故不使用。

### 2.9 其他硬性注意点

- `resizeMode` 必须为 `none` 或删除声明；
- 根布局用 `match_parent`，不要写固定宽高；
- 背景不要用一张图片，避免切换布局时拉伸；
- 组件内禁止左右/上下滑动；
- 不要复用 `RemoteViews`，每次更新重新构建；
- 不要通过 `RemoteViews` 传 Bitmap（单次或累计 >100KB 视为大数据）；
- 文件、数据库、字体加载等耗时操作必须放子线程；
- 动效只能用 `layoutAnimation` 补间动画，时长建议 200–800ms。

### 2.10 本项目落地范围

适配三个固定尺寸的 Glance 组件：

| 组件 | 类 | 规格 | 处理 |
| --- | --- | --- | --- |
| 课表 · 周视图 | `TimetableWeekWidget` | 4×4 | 280dp、12h、浅深预览；vivo 标识待审核后启用 |
| 课表 · 日视图（窄） | `TimetableDayNarrowWidget` | 2×3 | 120×190dp、12h、浅深预览；今日剩余课程紧凑列表，无节次数字列；vivo 标识待审核后启用 |
| 课表 · 日视图（宽） | `TimetableDayWideWidget` | 4×4 | 280dp、12h、浅深预览；vivo 标识待审核后启用 |

「双日课表」（`TimetableWidgetProvider`）**不改**：它支持拖动缩放（`resizeMode="horizontal|vertical"`），与原子组件「固定尺寸」的要求冲突；强行加 `vivo_widget` 会导致组件库审核不通过或行为异常。它继续作为普通 AppWidget 提供。

**最终落地方式**：三个 Glance 组件的尺寸、12 小时周期、浅深预览与刷新链路保留；`vivo_widget` / `vivoWidgetVersion` / `vivo.widget.description` 以注释块形式留在 Manifest 中，等通过 vivo 原子组件平台审核后再取消注释。未审核时声明会让组件在 OriginOS 上无处可添加（见 2.2 实测）。

---

## 三、真机测试

### 3.1 环境

- JDK 21（已有）
- Android SDK：`platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0`
- 构建：`ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- 安装：`adb install -r app/build/outputs/apk/debug/BetterHRBUST-1.5.0-android-debug.apk`

构建验证（2026-11-20，本机）：

- Debug APK 已产出：`android/app/build/outputs/apk/debug/BetterHRBUST-1.5.0-android-debug.apk`，包名 `com.glassous.betterhrbust.debug`；
- `aapt2` 核对：4×4 为 280dp、2×4 为 120×280dp，`updatePeriodMillis=43200000`，`resizeMode=none`，`previewImage` 指向新缩略图；vivo meta-data 以注释块保留，最终 APK 未打入；
- 缩略图：`drawable-nodpi` 与 `drawable-night-nodpi` 两套均已打入 APK；
- `./gradlew :app:testDebugUnitTest` 通过。

真机验证（2026-11-20，vivo V2415A / OriginOS 16.0 / Android 16）：

- 未声明 `vivo_widget` 时，桌面「应用挂件」中 `BetterHRBUST Debug` 显示 **4** 个组件（双日课表 + 3 个 Glance）；
- 声明 `vivo_widget` 时，同一入口只剩「双日课表」**1** 个；卸载重装、重启桌面后结果一致；
- 原子组件库搜索 `BetterHRBUST` 无结果（该库只收录已审核组件）；
- 周视图 4×4 已添加到桌面，浅色/深色都正常渲染，空态「暂无课表数据 / 点击打开应用同步」与圆角无裁切；
- 窄版 2×3 已添加到桌面，系统注册为 120×190dp；深/浅色正常，标题、页脚完整，课已结束时显示「今天的课已结束」；
- 组件库底部「应用挂件」入口可正常添加，底部预览卡显示生成的课程示意图（深色缩略图与当前系统主题一致）。

### 3.2 原子组件验证清单

> 实测（2026-11-20）：普通「应用挂件」列表可见 4 个组件；「原子组件」库搜索不到未审核应用。以下步骤以已通过 vivo 原子组件平台审核为前提。

1. 安装后长按桌面 → 原子组件 / 小部件 → 找到 BetterHRBUST；
2. 确认三个组件是否出现在原子组件库，描述语是否为 `vivo.widget.description`；
3. 分别添加 4×4 周视图、2×4 日视图、4×4 日视图，确认占格与内容不被裁切；
4. 切换深色模式（`adb shell cmd uimode night yes|no`），确认组件与预览图都跟随；
5. 跨天或改时间后确认自动刷新；
6. 读取组件注册信息：

```bash
adb shell dumpsys appwidget | grep -i -A20 betterhrbust
adb shell cmd package dump com.glassous.betterhrbust.debug | grep -A8 TimetableWeekWidgetReceiver
```

### 3.3 原子岛能力验证清单

> 实测已完成（2026-11-20），结果见 1.9：普通通知、无胶囊、无 promoted/superx 字段。

1. 打开调试包的「模拟上课提示（调试）」；
2. `adb logcat -s HyperIsland:D` 查看呈现档位；
3. 观察状态栏、锁屏、通知中心是否有胶囊/大卡；
4. `adb shell dumpsys notification --noredact` 核对 extras；
5. 截图留证（`adb exec-out screencap -p > vivo-island.png`）。

预期结果与「1.8 本项目决定」一致：普通通知，无原子岛。

---

## 四、参考

- vivo《原子通知接入指导》：https://dev.vivo.com.cn/documentCenter/doc/894
- vivo《原子通知规范》：https://dev.vivo.com.cn/documentCenter/doc/895
- vivo《原子组件适配》：https://dev.vivo.com.cn/documentCenter/doc/845
- vivo HIG《原子通知》：https://developers.vivo.com/hig/cb551badb01c4811be7e330888c6f32a
- vivo HIG《原子组件》：https://developers.vivo.com/hig/d2131768ec0d4787ad136bf2bde1622f
- 第三方实测记录：https://github.com/DpVoliin/island-live/blob/main/docs/oem-notes.md
- 阿里云 EMAS《vivo 原子岛推送指南》：https://help.aliyun.com/document_detail/3030718.html
