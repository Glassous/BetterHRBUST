# BetterHRBUST

自制的现代化哈尔滨理工大学教务在线（URP）网页客户端。

哈理工教务在线（`http://jwzx.hrbust.edu.cn/academic/`）基于清华教育在线 / 优慕课 URP 架构，为早期 JSP 应用：缺乏公开 API 与官方文档、网页编码不统一（GBK / UTF-8 混用）、真实功能路径深藏于模块调度器后。

**BetterHRBUST** 致力于将其重塑为现代、优雅、跨平台且具备离线体验的教务工作台：100% 真实教务直连，零模拟假数据，并提供完整的逆向接口文档与自动化探测工具。

```
BetterHRBUST/
├── docs/api/        # 接口逆向文档（13 篇，手写整理，详细踩坑记录）
├── tools/probe/     # 接口探测与逆向工具（零依赖 Node.js CLI + 网页控制台）
└── web/             # 现代化 Web 客户端（Vue 3 + Vite + Tailwind CSS）
```

---

## 🌟 核心特性

- **100% 真实教务直连**：不使用任何模拟数据，数据全部直连哈理工教务在线系统，保障准确性。
- **独立现代化登录**：简洁高效的单列登录面板，支持验证码实时刷新、学号记住与会话快速切换。
- **离线持久化与断网保活**：教务 Session 失效或断网时，自动无缝加载本地持久化缓存，顶栏清晰标识离线状态并支持一键重新认证。
- **概览工作台**：
  - 今日课程智能展示（课表大节加粗呈现，空闲状态智能提示）
  - 近期考试速览（智能时间过滤：未来考试与过去 7 天内考试）
  - 学业核心指标（已获学分 / 修读总学分、平均学分绩点 GPA、挂科门数）
- **智能课程表**：支持按周次切换、当前周高亮、单双周过滤、节次时段分布与课程详情。
- **成绩与 GPA 分析**：历年成绩汇总、按学期/课程性质筛选、不及格标记与 GPA 计算。
- **培养方案与学分进度**：与教务培养方案深度对齐，直观展示各课组学分要求与已获进度。
- **考试日程与倒计时**：清晰掌握考场、座号、时间及考试倒计时。
- **空教室检索**：按校区、教学楼、周次推算空闲自习教室。
- **学籍档案与全校课程**：学籍关键信息一览，全校开课名录便捷检索。

---

## 🚀 快速上手

### 1. 网页客户端 (`web/`)

运行现代 Web 界面：

```bash
# 进入前端目录
cd web

# 安装依赖
npm install

# 启动开发服务器（已配置教务在线反向代理）
npm run dev
```

启动后访问提示的本地地址（如 `http://localhost:5173/`）。

> [!TIP]
> 哈理工教务在线运行于校园网环境。在校外访问时，请确保已连接校园 VPN 或校园 WebVPN。

构建生产版本：

```bash
npm run build
```

---

### 2. 油猴脚本客户端（Tampermonkey）

无需本地启动 dev server：可一键将整个客户端打包为**单文件油猴脚本**，安装后直接访问教务在线（`http://jwzx.hrbust.edu.cn/`）即自动接管旧版 JSP 页面。所有请求与原版教务系统完全同源（Cookie 会话、验证码、GBK 解码行为一致），无任何中转服务。

```bash
cd web
npm ci
npm run build:userscript   # 产物: web/dist-userscript/better-hrbust.user.js（单文件，样式与图片全内联）
```

安装与使用说明见 [`web/USERSCRIPT.md`](./web/USERSCRIPT.md)。合并后 GitHub Actions 会自动构建并发布到 `dist` 分支，普通用户可直接从下面的 raw 地址一键安装（油猴凭 `@updateURL` 自动检查更新）：

```
https://raw.githubusercontent.com/Glassous/BetterHRBUST/dist/better-hrbust.user.js
```

> [!TIP]
> 油猴版与 Web 版共用同一套业务代码（client / api / parser / views），**零改动**。
> 选课等写操作请通过油猴菜单「查看原版教务系统」回到官方页面办理。

---

### 3. 接口探测工具 (`tools/probe/`)

教务系统改版或需验证底层接口时，可直接运行逆向探测工具：

```bash
# 方式一：网页控制台（推荐，零依赖，浏览器内登录）
node tools/probe/server.mjs --open

# 方式二：命令行探测
node tools/probe/probe.mjs --user 你的学号
```

**探测工具亮点**：
- **模块调度揭示**：自动跟随 `accessModule.do` 302 重定向揭示真实功能路径。
- **参数收割补测**：自动从响应中提取 `studentId`、`cid` 等关键标识进行缺参补测。
- **自动隐私脱敏**：落盘前自动对学号、姓名、证件号、手机号等关键个人隐私进行星号脱敏。
- **零外部依赖**：仅需 Node.js ≥ 18。

---

## 📚 接口逆向文档 (`docs/api/`)

在深入代码前，推荐先阅读手写接口文档中心 [`docs/api/README.md`](./docs/api/README.md) 与全局总览 [`00-overview.md`](./docs/api/00-overview.md)：

| 文档 | 对应业务 | 关键接口与说明 |
| --- | --- | --- |
| [00-overview.md](./docs/api/00-overview.md) | 全局总览 | 架构、编码异构、会话失效机制、内部 ID 机制 |
| [01-auth.md](./docs/api/01-auth.md) | 认证与会话 | `getCaptcha.do` 验证码拉取、`j_acegi_security_check` 登录认证 |
| [02-score.md](./docs/api/02-score.md) | 成绩与 GPA | `studentOwnScore.do` 历年成绩、GPA 及排名 |
| [03-timetable.md](./docs/api/03-timetable.md) | 课程表 | `showTimetable.do` 学期课表、周次与单双周 |
| [04-program.md](./docs/api/04-program.md) | 培养方案 | `programTree.do` 课组层级、学分要求与毕业审核 |
| [05-exam.md](./docs/api/05-exam.md) | 考试日程 | `studentQueryAllExam.do` 考场安排、座号与时间 |
| [06-classroom.md](./docs/api/06-classroom.md) | 空教室查询 | `roomschedule*.jsdo` 教学楼自习教室空闲占用推算 |
| [07-calendar.md](./docs/api/07-calendar.md) | 校历与周次 | `viewCalendarInfo.do` 当前教学周、真实开学日期 |
| [08-profile.md](./docs/api/08-profile.md) | 学籍档案 | `showPersonalInfo.do` 个人学籍与学籍异动记录 |
| [09-notice.md](./docs/api/09-notice.md) | 教学公告 | `calendarViewList.do` 教务在线通知列表 |
| [10-course.md](./docs/api/10-course.md) | 全校课程名录 | `courseSearch.do` 课程库检索 |
| [11-selection.md](./docs/api/11-selection.md) | 选课相关 | 选课批次与选课状态提示（查询类） |
| [12-portal.md](./docs/api/12-portal.md) | 门户与上下文 | `studentContext.do`、`menu.do` 上下文与菜单树 |

---

## 🔒 隐私、安全与合规声明

1. **隐私安全至上**：
   - 网页客户端采用纯前端直连与本地缓存架构，所有会话凭证（Cookie）及个人信息**仅保存在用户本地浏览器内**，绝不收集或上传到任何第三方服务器。
   - 探测工具默认启用自动脱敏逻辑，测试数据落盘前自动屏蔽敏感隐私字段。
2. **只读保护原则**：
   - 本项目核心目标为**改善信息查询与日程体验**。
   - 任何涉及修改教务数据的高风险写操作（如选课、退课、修改学籍等）均不在客户端内提供，请登录官方教务系统原站办理。
3. **友好访问原则**：
   - 接口请求均采用防抖与按需加载策略，配合本地持久化缓存，有效减少对学校教务服务器的不必要请求与访问负担。

---

## 🛠️ 技术栈

- **框架**：Vue 3 (Composition API / `<script setup>`)
- **构建工具**：Vite 5
- **样式方案**：Tailwind CSS v4
- **工具链**：Node.js 原生 ES Modules
