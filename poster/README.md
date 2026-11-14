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
```

页面上点「下载 PNG」即可拿到 2400 × 3200 的图片。

## 目录

```
poster/
├─ public/
│  ├─ BetterHRBUST.png             # 图标（复制自 landing/public）
│  ├─ island-expanded.jpg          # 上课提示 · 灵动岛展开态截图
│  ├─ island-capsule.jpg           # 上课提示 · 灵动岛折叠态截图
│  └─ cli-exams.png                # 命令行模式 · BetterHRBUSTCLI 终端截图
└─ src/
   ├─ App.vue                      # 工具条 + 预览缩放 + 导出逻辑
   ├─ posters/registry.ts          # 海报清单
   ├─ posters/Release140Poster.vue # v1.3.0 → v1.4.0 海报（内容硬编码）
   └─ assets/                      # Android 图标与下载二维码
```

## 新增一张海报

1. 在 `src/posters/` 下新建 `XxxPoster.vue`，根节点用固定宽高（如 `h-[1600px] w-[1200px]`），内容直接写在模板里。
2. 在 `src/posters/registry.ts` 的 `posters` 数组里追加一项（`id / name / meta / width / height / fileName / component`）。
3. 清单里超过一张时，预览区上方会自动出现海报切换按钮。

## 与 CI 的关系

本目录**不参与任何工作流**：`.github/workflows/` 下的 workflow 都带 `paths` 过滤，不包含 `poster/**`，因此改动本目录不会触发云端构建或部署。产物 `dist/`、`node_modules/` 已由根目录 `.gitignore` 忽略。
