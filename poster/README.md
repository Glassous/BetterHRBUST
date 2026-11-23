# poster · BetterHRBUST 海报生成器

本地离线的小工具，用来排版 BetterHRBUST 的版本更新海报并导出 PNG。只做这一件事：**预览 + 下载**，不接后台、不发网络请求、不做在线编辑。

## 特性

- Vite 5 + Vue 3 + TypeScript + Tailwind CSS v4（`@tailwindcss/vite`），仅浅色模式
- 画布固定 1200 × 1600（竖屏 3:4），导出 2 倍图 2400 × 3200 PNG
- 海报内容硬编码在组件里，改文案直接改 `.vue`
- 字体只用系统字体栈，不请求外部字体，离线可用

## 使用

```bash
npm install
npm run dev      # http://localhost:5175
npm run build    # 类型检查 + 产出 dist/（相对路径，可离线打开）
npm run preview  # 预览构建产物
npm run capture  # 模拟数据 + 无头浏览器生成桌面端课表截图（写入 public/）
npm run export   # 无头浏览器点「下载 PNG」，2 倍图落到 out/
```

页面上点「下载 PNG」即可拿到 2400 × 3200 的图片。

## 目录

```
poster/
├─ public/
│  ├─ BetterHRBUST.png             # 图标（复制自 landing/public）
│  ├─ web-reschedule-mode.png      # 桌面端课表 · 调课模式（capture 生成）
│  ├─ web-reschedule-dialog.png    # 单周临时调课确认弹窗（capture 生成）
│  ├─ web-timetable.png            # 桌面端整周课表（capture 生成）
│  ├─ island-countdown.jpg         # Android 超级岛折叠态截图
│  └─ *.jpg / cli-exams.png        # 历史版本素材（当前海报未引用）
├─ scripts/
│  ├─ capture-web.mjs              # 模拟教务数据 + Playwright 截图
│  └─ export-png.mjs               # 无头浏览器导出 2 倍图
└─ src/
   ├─ App.vue                      # 工具条 + 预览缩放 + 导出逻辑
   ├─ posters/registry.ts          # 海报清单
   ├─ posters/Release160Poster.vue # v1.5.0 → v1.6.0 海报（内容硬编码）
   ├─ posters/PlatformQaPoster.vue # 平台答疑海报（MacOS / iOS / 鸿蒙 / 网页版）
   └─ assets/                      # Android 图标与下载二维码
```

## 截图脚本说明

`npm run capture` 会用一份写死的模拟教务数据（第 13 周、含调课与自定义课程）拉起 `web` 的 Vite dev server，
再用 `web/node_modules` 里的 `playwright-core` 截图。默认使用本机已安装的 Chromium / Chrome / Edge，
可用 `CAPTURE_BROWSER_PATH` 指定；端口与画幅可用 `CAPTURE_PORT` / `CAPTURE_WIDTH` / `CAPTURE_HEIGHT` 覆盖。

## 新增一张海报

1. 在 `src/posters/` 下新建 `XxxPoster.vue`，根节点用固定宽高（如 `h-[1600px] w-[1200px]`），内容直接写在模板里。
2. 在 `src/posters/registry.ts` 的 `posters` 数组里追加一项（`id / name / meta / width / height / fileName / component`）。
3. 清单里超过一张时，预览区上方会自动出现海报切换按钮。

## 与 CI 的关系

本目录**不参与任何工作流**：`.github/workflows/` 下的 workflow 都带 `paths` 过滤，不包含 `poster/**`，因此改动本目录不会触发云端构建或部署。产物 `dist/`、`node_modules/` 已由根目录 `.gitignore` 忽略。
