# BetterHRBUST 发布页

Vue 3 + Vite 静态发布页。提供功能介绍、Android/Windows/油猴下载、使用指南、主题切换与移动端布局，不使用个人教务截图或数据。

## 本地开发与构建

在 landing 目录运行 npm ci、npm run dev。npm run build 生成 dist，npm run preview 可预览构建结果。图片和脚本使用相对路径，支持 GitHub Pages 的 /BetterHRBUST/ 子路径。

页面读取原仓库 GitHub Releases 的最新公开版本与附件地址。API 不可用或尚无匹配附件时，按钮仍能打开原仓库的正式发行页，不猜测版本化文件名。新版源码功能需对应版本发布后才在正式下载包中可用。

展示顺序为概览、01 课表、02 GPA 分析、03 资料查找、04 手机端。前四块使用静态 CSS 组件，直接复用客户端的 UiCard、Icon、学业统计函数和课程配色，布局依据真实客户端页面精简；不提供演示切换或编辑交互。姓名、学号和成绩为明确标注的示例，资料条目来自公开目录。第 04 项保留原有设备示意。所有组件随页面主题自动切换，不使用用户个人截图。
## 自动部署

`.github/workflows/deploy-landing.yml` 负责介绍页的自动构建与部署：

- **触发**：向 `main` 推送 `landing/**`（或该工作流文件本身）的改动；也可在 Actions 页面手动触发（`workflow_dispatch`）。
- **构建**：全程在 GitHub 云端运行器（`ubuntu-latest`，Node 22）执行 `npm ci && npm run build`，**不在服务器上构建**。
- **部署**：`rsync -avz --delete` 将 `landing/dist/` 内容全量同步到服务器
  `/opt/1panel/www/sites/betterhrbust/index/dist`（`--delete` 会清理历史哈希产物，注意该目录应专用于本页面）。

需要在仓库 **Settings → Secrets and variables → Actions → Secrets** 中配置：

| Secret | 必填 | 说明 |
| --- | --- | --- |
| `SERVER_HOST` | 是 | 服务器地址（域名或 IP） |
| `SERVER_USER` | 是 | SSH 登录用户，需对目标目录有写权限 |
| `SERVER_SSH_KEY` | 是 | SSH 私钥全文（对应公钥需已加入服务器 `~/.ssh/authorized_keys`） |
| `SERVER_PORT` | 否 | SSH 端口，默认 `22` |

要求服务器已安装 `rsync`（1Panel 环境通常已内置），且目标目录的父级 `index/` 已存在。

同步参数使用 `--omit-dir-times --no-owner --no-group`：1Panel 站点目录通常属 root 或其他用户，非属主无法写目录时间戳/属主属性，忽略它们可避免 `rsync: failed to set times ... Operation not permitted`（退出码 23）。若希望完整保留属性，可在服务器上把目录交给部署用户：`chown -R <SERVER_USER> /opt/1panel/www/sites/betterhrbust/index/dist`（注意 1Panel 可能会重置该权限）。

## 说明

动效复用已有 GSAP 依赖：首屏文字依次入场，低对比度背景文字轮换，几何图形轻微漂浮；功能区与下载卡片随滚动进入，精细指针设备支持轻微背景视差。遵循 prefers-reduced-motion，减少动态效果时所有内容直接呈现。浏览器回归同时检查正常动效和减少动态效果模式。

发布页的 `ClientDemo.vue` 只负责缩放独立预览框，预览框中直接使用 `web/` 的 AppLayout、DashboardView、TimetableView、ScoreView、ResourcesView、EvaluationView 与实际 CSS。示例数据与时间仅在预览路由内注入，教务请求被限定为本地展示响应，不会提交真实教学评价。

多端区域的 `public/screenshots/desktop-*.png` 由 `scripts/capture-clients.cjs` 从实际客户端视图截图；`android-*.png` 来自 Android 独立调试包的 `NativePreviewTest`，为模拟器实际画面。更新界面后需重新截图两套主题，不用手绘占位界面。贡献者链接进入 GitHub 完整贡献列表，MIT 链接进入 Open Source Initiative 的 MIT 许可证介绍页。
