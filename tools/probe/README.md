# 哈理工教务在线 · 接口探测工具

一套**零依赖**的 Node 工具，用于登录哈尔滨理工大学教务在线
（URP 架构）并批量探测其接口，把原始响应落盘成可供离线分析的样本集与接口清单。

配套产物：

- 接口文档 → [`docs/api/`](../../docs/api/)
- 接口定义目录 → [`catalog.mjs`](./catalog.mjs)

---

## 目录

- [1. 它能做什么](#1-它能做什么)
- [2. 环境要求](#2-环境要求)
- [3. 快速开始](#3-快速开始)
- [4. 工作原理](#4-工作原理)
- [5. 输出产物](#5-输出产物)
- [6. 命令行参考](#6-命令行参考)
- [7. 网页控制台 API](#7-网页控制台-api)
- [8. 维护接口目录](#8-维护接口目录)
- [9. 与接口文档的关系](#9-与接口文档的关系)
- [10. 安全与隐私](#10-安全与隐私)
- [11. 常见问题](#11-常见问题)
- [12. 目录结构](#12-目录结构)
- [13. 实测结论摘要](#13-实测结论摘要)

---

## 1. 它能做什么

| 能力 | 说明 |
| --- | --- |
| **登录** | 图形验证码 + Acegi 表单认证；支持网页端（推荐）与命令行两种方式 |
| **模块调度发现** | 提取菜单里的 `moduleId`，跟随 `accessModule.do` 的 302 揭示真实功能页地址 |
| **门户页多层抓取** | 递归跟进 `<frame>` / `<iframe>`，解析 frame、表单、按钮、内联 JS 里的地址 |
| **多轮递进探测** | 每轮结束后从已落盘样本里再挖新地址，直到收敛（实测第 2 轮才收敛） |
| **正确解码** | 按响应头 charset 解码（教务系统**不同模块编码不同**），GBK 仅作兜底 |
| **样本结构分析** | 自动解析每个页面的表头、表单字段、按钮行为，产出可读报告 |
| **参数收割** | 从响应中提取真实参数（`cid`、`userid`、`scoreid`、加密 `studentId`），带参补测「缺参数」的接口 |
| **产物脱敏** | 落盘前屏蔽学号、姓名、证件号、手机号、加密串等隐私数据（默认开启） |
| **离线重分析** | `--analyze-only` 不联网即可重新分析已有样本 |
| **目录保护** | 拒绝把输出目录指向 `docs/`，探测永远不会改动接口文档 |

**设计原则**：不做任何猜测。所有路径都来自真实响应，接口目录里的 `confidence`
字段如实标注哪些是实测过的、哪些还需确认。

## 2. 环境要求

- **Node.js 18 或更高**
  依赖内置 `fetch`、`AbortSignal.timeout`、以及**完整 ICU** 的 `TextDecoder('gbk')`。
  若使用 small-icu 构建的 Node，脚本会告警并回退 UTF-8（中文可能乱码）。
- **校园网或学校 VPN**
  用浏览器能打开 <http://jwzx.hrbust.edu.cn/academic/> 即可。
- 无需 `npm install`，无第三方依赖。

```bash
node --version   # 应 >= v18
```

## 3. 快速开始

### 方式一：网页控制台（推荐）

不想在终端里输密码、也不想把验证码存到本地？用这个：

```bash
node tools/probe/server.mjs --open
```

浏览器打开 `http://127.0.0.1:7788/`，然后：

1. 输入学号、密码，以及**页面上直接显示的验证码**（点击图片可就地刷新）；
2. 点「登录」——**会话 Cookie 由本地服务托管**，之后所有请求都在服务端发起；
3. 按需勾选模块，调整并发数与探测轮数，点「开始探测」；
4. 实时查看每个接口的状态、**实际编码**、大小、耗时与结论
   （有数据 / 路径不存在 / 错误页 / 会话失效）；
5. 登录后会**自动抓取门户页并做结构化分析**，页面上直接列出扫到的接口地址；
6. 点任意一行的「预览」查看响应（优先打开已解码的 UTF-8 副本）。

> **为什么要这样做**：登录态接口需要「验证码 + 真实账号」，而验证码是图片，
> 只能由人来看。网页端让你在浏览器里完成登录，服务端持有 Cookie 后所有探测
> 都在服务端发起——既不需要终端交互，也完全避开了浏览器跨域限制。

服务默认只监听 `127.0.0.1`，不对局域网暴露。

### 方式二：命令行

```bash
# 全量探测（交互式输入验证码）
node tools/probe/probe.mjs --user 你的学号

# 只探测成绩相关接口
node tools/probe/probe.mjs --only score.*

# 复用已登录会话重跑（网页端登录过也能用同一份会话）
node tools/probe/probe.mjs --skip-login --only timetable.*

# 查看内置接口目录（不联网）
node tools/probe/probe.mjs --list

# 只打印探测计划，不发请求
node tools/probe/probe.mjs --dry-run

# 离线重分析已有样本
node tools/probe/probe.mjs --analyze-only
```

### 方式三：空教室定向探测

「教室时间占用」是级联表单，且按教室 / 按周是两个不同接口，批量探测覆盖不到。
用这个脚本按真实参数把整条链路抓一遍：

```bash
# 复用网页端登录的会话（output/session.json）
node tools/probe/classroom-probe.mjs

# 指定教学区 / 教学楼 / 周次 / 星期
node tools/probe/classroom-probe.mjs --aid 353 --building 1688 --week 10 --day 1

# 多抓几间教室，并打印详细请求日志
node tools/probe/classroom-probe.mjs --rooms 3 --verbose
```

产物落在 `output/classroom/`：`01-query.html` … `05-room-*.html` 样本、
`report.md`（每张表的行列规模与前 3 行）与 `structure.json`（结构化结果）。
参数语义：`--week` 是第几周（`whichweek`），`--day` 是星期几（`week`）。

## 4. 工作原理

### 4.1 探测流程

```
① 连通性检测        访问 /academic/，确认能通、取得 JSESSIONID
② 登录              验证码 → checkCaptcha.do → j_acegi_security_check
③ 门户页多层抓取    沿 <frame>/<iframe> 递归，解析结构并落盘
④ 接口发现          菜单扫描 + moduleId 跟随 accessModule.do（302 揭示真实地址）
⑤ 多轮递进探测      第 1 轮探入口，第 2+ 轮从样本里挖新地址，直到收敛
⑥ 样本结构分析      解析每个样本的表头/表单/按钮，产出 analysis.md
⑦ 落盘              样本、解码副本、清单、报告
```

### 4.2 模块调度（本系统最关键的结构特征）

菜单**不直接暴露功能页地址**，只给出一串模块 ID：

```
GET /academic/accessModule.do?moduleId=210&groupId=&randomString=<yyyyMMddHHmmss+6位随机>
  → 302 → /academic/manager/studyschedule/help.htm
```

`accessModule.do` 是**模块调度器**，服务端按当前用户权限把 `moduleId`
解析并重定向到真实功能页。工具会提取菜单里的全部 `moduleId` 并逐个跟随。

> 这是必需的：实测中按通用 URP 命名猜的 **35 个路径全部 404**。
> 例如考试安排的真实地址是 `student/exam/index.jsdo`，
> 而不是 `manager/exam/examArrange.do`。

### 4.3 多轮递进

每个功能页自身还会引用更深的接口（表单 action、按钮 onclick、导出链接），
所以每轮结束后会从**已落盘的样本**里再挖一轮新地址：

```
第 1 轮：内置目录 + 菜单 + 门户页 + moduleId 调度   →  实测 55 个
第 2 轮：从第 1 轮样本里挖出的新地址                →  实测 +25 个
第 3 轮：无新发现，收敛
```

用 `--depth <n>` 控制轮数（默认 2，实测 2 轮即收敛）。

> ⚠️ 相对地址必须**相对页面自身所在目录**解析。例如
> `calendar/calendarViewList.do` 里的 `findSchoolCalendarDetailByDate.do`
> 真实地址是 `calendar/findSchoolCalendarDetailByDate.do`，
> 当成相对基址会 404。工具已实现正确的路径归一化。

### 4.4 编码处理

教务系统**不同模块编码不同**，必须按响应头 charset 解码：

| 接口 | charset |
| --- | --- |
| `manager/score/studentOwnScore.do` | **UTF-8** |
| `listLeft.do` / `currcourse.jsdo` / `top.jsp` | GBK 系 |
| `main.jsp` | `ISO-8859-1`（正文为纯 ASCII） |

一律按 GBK 解码会让成绩页中文变成 `涓汉鎴愬哗鏌ヨ`，且关键字匹配全部失败。

解码顺序：**响应头 charset → HTML meta 嗅探 → GBK 兜底**；
若解码后出现大量替换字符 `U+FFFD`，自动用 GBK 重试一次。

### 4.5 参数收割

目录里有一批接口（课程详情、教师信息、教学报告、培养方案等）
**必须带真实参数才能返回数据**，而参数值只存在于运行期的响应里：

```
course_detail.jsdo?cid=248686                        ← 本学期课程列表的链接里
showTeacherInfoItem.do?userid=113679                 ← 同上
schoolTeachingReportIndexStudent.do?scoreid=233727590 ← 同上
scheduleJump.jsp?...&studentId=<加密串>              ← 教学计划入口页里
```

探测时工具会从每个响应中提取这些「接口 + 参数」组合，在下一轮
**带着真实参数补测**。这解释了为什么单轮探测会误判这些接口不可用：
不带参数请求得到的是「提示信息」错误页，看起来像无权限或路径错误。

两条链式规则值得注意：

- `scheduleJump.jsp` 本身**只是个跳转页**（几百字节的
  `location.href=...`），没有数据，但它的跳转目标才是真正的
  培养方案页面（数百 KB）；
- 教学计划的 `studentId` 是**加密串**，必须原样回传，不能自己拼。

> ⚠️ 收割发生在**脱敏之前**：加密串是敏感值，落盘后只剩占位符，
> 从文件里收割只会拿到 `[加密串已脱敏]`。

## 5. 输出产物

```
tools/probe/output/
├── manifest.json     # 机器可读清单（含每个接口的实测状态、编码、脱敏统计）
├── manifest.md       # 人工可读汇总（含 404 清单与错误页清单）
├── analysis.md       # 样本结构分析报告（每个页面的表头/表单/按钮）
├── analysis.json     # 同上，结构化版本
├── session.json      # 会话 Cookie 与登录学号（凭证文件；目录内含自忽略 .gitignore）
├── samples/          # 响应样本（已脱敏，UTF-8；二进制内容替换为说明文件）
├── decoded/          # 同 samples/（保留目录以兼容分析器）
└── portal/           # 登录后门户页的多层框架抓取与分析
    ├── index-new.utf8.html              # 一行一标签重排的源码
    ├── index-new.analysis.json          # 结构化分析结果
    ├── index-new.analysis.md            # 可读报告
    └── index-new.inline-scripts.js      # 内联脚本全文
```

**关于 `samples/` 与 `decoded/`**：默认（脱敏开启）两者内容相同，都是
脱敏后的 UTF-8 文本。这是因为脱敏必须「解码 → 替换 → 重新编码」，
而 Node 没有 GBK 编码器；原始编码记录在 `manifest.json` 的 `charset` 字段。
用 `--no-redact` 关闭脱敏后，`samples/` 恢复为服务器原始字节（含 GBK），
`decoded/` 则是按正确字符集解码的 UTF-8 副本。

## 6. 命令行参考

### 探测脚本 `probe.mjs`

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--user <学号>` | — | 教务账号，也可用环境变量 `HRBUST_USER` |
| `--pass <密码>` | — | 教务密码，也可用环境变量 `HRBUST_PASS` |
| `--captcha <验证码>` | — | 直接提供验证码，省略则交互输入 |
| `--base <URL>` | `http://jwzx.hrbust.edu.cn/academic/` | 教务系统基址 |
| `--only <keys>` | 全部 | 仅探测指定接口，逗号分隔，支持前缀通配（`score.*`） |
| `--out <目录>` | `tools/probe/output` | 样本输出目录 |
| `--timeout <ms>` | `20000` | 单请求超时 |
| `--concurrency <n>` | `1` | 并发数。**默认串行，避免给教务系统压力** |
| `--depth <n>` | `2` | 探测轮数，每轮从上一轮样本里递进挖新地址 |
| `--skip-login` | — | 复用 `output/session.json` 中的会话 |
| `--no-portal` | — | 不抓取登录后门户页 |
| `--no-discover` | — | 关闭菜单自动发现，仅探测内置目录 |
| `--no-open` | — | 不自动打开验证码图片 |
| `--allow-mutating` | 关闭 | 允许探测会改变数据的接口（选课/退课等） |
| `--analyze-only` | — | 不联网，仅重新分析已有样本 |
| `--clean` | — | 探测前清空 `samples/` `decoded/` `portal/`，避免多次运行的文件累积（**不会删除会话文件**） |
| `--no-redact` | — | **关闭产物脱敏**（默认开启）。关闭后产物含真实隐私数据，请勿分享或提交 |
| `--list` | — | 仅打印内置接口目录 |
| `--dry-run` | — | 仅打印探测计划，不发请求 |
| `--verbose` | — | 打印每个请求的详细日志 |
| `--config <文件>` | — | 从 JSON 配置文件读取默认值（见 `config.example.json`） |
| `-h, --help` | — | 显示帮助 |

### 服务 `server.mjs`

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-p, --port <端口>` | `7788` | 监听端口 |
| `--host <地址>` | `127.0.0.1` | 监听地址（仅本机可访问） |
| `--out <目录>` | `tools/probe/output` | 样本输出目录 |
| `--base <URL>` | 同上 | 教务系统基址 |
| `--timeout <ms>` | `20000` | 单请求超时 |
| `--open` | — | 启动后自动打开浏览器 |

## 7. 网页控制台 API

全部仅本机可访问（默认绑定 `127.0.0.1`）：

| 路径 | 说明 |
| --- | --- |
| `GET /` | 探测控制台页面 |
| `GET /api/status` | 服务与会话状态 |
| `GET /api/captcha` | 代理获取验证码图片（Cookie 留在服务端） |
| `POST /api/login` | 提交登录（学号 / 密码 / 验证码） |
| `POST /api/logout` | 退出登录 |
| `GET /api/catalog` | 内置接口目录 |
| `GET /api/probe/stream` | **SSE** 实时探测进度 |
| `GET /api/manifest` | 探测清单 JSON |
| `GET /api/report` | 汇总报告 Markdown |
| `GET /api/sample?file=` | 查看响应样本（优先打开解码副本） |
| `GET /api/portal?file=` | 查看门户页 / 样本分析产物（**强制纯文本返回**，不在控制台源上执行页面脚本） |

SSE 事件类型：`stage`（阶段）、`log`、`progress`（单条结果）、`round`（轮次）、
`portal`（门户页分析）、`analysis`（样本分析）、`discovered`、`done`、`error`。

## 8. 维护接口目录

`catalog.mjs` 是**唯一的接口定义来源**，文档与探测都由它驱动。

```js
{
  key: 'score.byTerm',                        // 唯一标识：模块.用途
  name: '按学期成绩',                          // 中文名称
  module: 'score',                            // 所属模块（见 MODULES）
  path: 'manager/score/studentOwnScore.do',   // 相对 /academic/ 的路径
  method: 'POST',                             // GET | POST
  params: { year: '{year}', term: '{term}' }, // 支持 {studentId}/{year}/{term}/{timestamp}/{randomString}
  need: ['year', 'term'],                     // 运行时必须的上下文
  markers: ['课程', '成绩'],                   // 判定「有数据」的关键字
  parser: 'parseScoreTable',                  // 建议的前端解析器名
  confidence: 'verified',                     // verified | likely | guess
  mutating: false,                            // 是否改变教务数据
  note: '实测：HTTP 200，55554 字节，UTF-8。真实表头为……'
}
```

**可靠度约定**

| 值 | 含义 |
| --- | --- |
| `verified` | 实测 HTTP 200 且有响应体、命中业务关键字 |
| `likely` | 路径存在但未取到数据（需参数或无权限） |
| `guess` | 推测路径，尚未探测 |

**其它字段**：`public`（免登录）、`binary`（二进制响应）、`skipInBatch`（不参与批量探测）。

添加新接口后：

```bash
node tools/probe/probe.mjs --only 你的新key   # 单独验证
```

## 9. 与接口文档的关系

接口文档位于 [`docs/api/`](../../docs/api/)，采用**手工撰写**，
面向开发者阅读（说明请求方式、参数、响应结构与实现建议）。

本工具与文档的分工：

| 关注点 | 归属 |
| --- | --- |
| 接口路径、参数、编码、请求方式 | 两者都有，需保持一致 |
| 响应结构、字段含义、解析要点 | `docs/api/` 详述 |
| 可用性验证、响应样本、结构分析 | 本工具产出 |

**维护约定**：修改 `catalog.mjs` 的接口定义后，请同步更新 `docs/api/`
下对应的模块文档，避免两者不一致。

`catalog.mjs` 的 `note` 字段建议记录实测事实（响应大小、真实表头、
踩过的坑），这些内容在写文档时可以直接复用。

## 10. 安全与隐私

### 10.1 产物脱敏（默认开启）

所有落盘内容在写入前都会经过脱敏，规则分两类：

| 类型 | 覆盖内容 | 说明 |
| --- | --- | --- |
| **已知值替换** | 登录学号、教务内部 ID、真实姓名、页面中的加密串 | 运行期逐步收集（加密串与姓名来自真实响应），结束时统一重写一遍 |
| **模式规则** | 证件号码、手机号、电子邮箱、`userid=`/`studentId=` 参数值、键值表中的敏感字段 | 格式固定，直接识别 |

被屏蔽后的占位符形如 `[学号已脱敏]`、`[内部ID已脱敏]`、`[姓名已脱敏]`、
`[加密串已脱敏]`。参数名会保留（如 `userid=[加密串已脱敏]`），方便对照接口定义。

脱敏会记录到 `manifest.json` 的 `redaction` 字段：

```json
"redaction": {
  "enabled": true,
  "replacements": 484,
  "byType": { "studentId": 212, "token": 170, "username": 12, "name": 45, "sensitiveField": 45 }
}
```

**两点实现细节需要知道：**

1. **脱敏模式下 `samples/` 与 `decoded/` 统一为 UTF-8。**
   脱敏需要「解码 → 替换 → 重新编码」，而 Node 没有 GBK 编码器，
   因此无法把替换后的文本再编码回 GBK。原始编码仍记录在
   `manifest.json` 的 `charset` 字段，需要字节级核对时以它为准。
2. **图片不落盘。** 学生照片、验证码等二进制内容无法脱敏，
   只会写入一个说明文件（含类型、大小、哈希前 16 位），并标记 `binarySkipped`。

用 `--no-redact` 可关闭脱敏，恢复「原始字节 + 解码副本」的行为——
**仅在确认数据可安全存放时使用**。

### 10.2 输出目录保护

探测会执行清空目录等破坏性操作，因此**拒绝把输出目录指向 `docs/`**
（接口文档目录）。指向时直接报错退出。

### 10.3 其它约定

- 账号密码仅用于本次登录，**不会写入任何文件**；`session.json` 只保存 Cookie
  与登录学号（复用会话时据此脱敏样本中的学号）。该文件属于凭证，请勿分享。
- 输出目录（含默认 `output/` 与 `--out` 自定义目录）运行时会自动放置
  `*\n!.gitignore` 自忽略文件；但若把 `--out` 指到仓库外或其他位置，
  产物是否被忽略以你本机 git 配置为准，分享前请自行确认。
- 选课、退课、保存学籍等**会改变教务数据**的接口默认不探测，需显式 `--allow-mutating`。
- 即使已脱敏，也不要把 `output/` 公开分享——文件中仍包含你的选课记录、
  课表安排等个人信息，只是不含能直接定位身份的字段。
- 脚本全程串行请求（`--concurrency` 默认 1），对教务系统的压力与正常浏览相当。
- 网页控制台默认只监听 `127.0.0.1`，不要改成 `0.0.0.0` 暴露到局域网。

## 11. 常见问题

**Q：提示「无法访问教务系统」**

确认已连接校园网或学校 VPN；用浏览器打开 `http://jwzx.hrbust.edu.cn/academic/` 验证。
若浏览器能打开但脚本不能，检查是否有系统代理拦截（校园站点通常需要直连）。

**Q：提示「当前 Node 未提供 GBK 解码器」**

当前 Node 是 small-icu 构建。改用 Node 官方发行版，或安装完整 ICU 数据。

**Q：某个接口返回 404**

内置目录里 `confidence` 为 `guess` 的路径是推测值，不同教务版本路径可能不同。
实际使用时以 `manifest.json` 的实测结果为准；工具的自动发现会从菜单与样本里
提取真实路径，以 `discovered.*` / `expanded.*` 为 key 出现在清单中。

**Q：看到「会话可能已过期」提示**

重新运行（不加 `--skip-login`）重新登录即可。
注意单条接口回落登录页不代表会话失效——工具只在**成规模**回落时才告警。

**Q：网页端登录成功，命令行能复用吗？**

可以。两者共用同一份 `output/session.json`：
网页端登录后可直接 `node tools/probe/probe.mjs --skip-login`，反之亦然。

**Q：想在不联网的情况下重新分析已有样本**

```bash
node tools/probe/probe.mjs --analyze-only
```

**Q：改了 `catalog.mjs` 之后要做什么？**

同步更新 `docs/api/` 下对应的模块文档，保持接口定义与文档一致
（见 [§9](#9-与接口文档的关系)）。

## 12. 目录结构

```
tools/probe/
├── probe.mjs            # 命令行探测入口
├── classroom-probe.mjs  # 空教室（教室时间占用）级联定向探测
├── server.mjs           # 网页探测控制台服务（本地 HTTP + SSE）
├── catalog.mjs          # 接口目录（接口定义 + 实测记录）
├── config.example.json  # 配置示例
├── public/
│   └── index.html       # 控制台页面（零依赖，原生 JS）
├── lib/
│   ├── http.mjs         # 请求封装：Cookie Jar / 按 charset 解码 / 重定向 / 超时
│   ├── auth.mjs         # 验证码、登录、会话校验与持久化
│   ├── discover.mjs     # 菜单链接与 moduleId 发现、accessModule 跟随
│   ├── portal.mjs       # 门户页分析：frame / 表单 / 按钮 / 内联 JS 地址提取
│   ├── analyzer.mjs     # 样本批量分析、相对路径解析、候选地址提取
│   ├── harvest.mjs      # 参数收割：从响应中提取真实参数，带参补测缺参接口
│   ├── redact.mjs       # 产物脱敏：已知值替换 + 模式规则
│   ├── dumper.mjs       # 响应落盘、清单生成、输出目录保护
│   ├── runner.mjs       # 探测编排核心（CLI 与网页端共用）
│   ├── text.mjs         # 文本处理小工具
│   └── cli.mjs          # 参数解析与终端交互
└── output/              # 运行产物（已 gitignore，内容已脱敏）
```

## 13. 实测结论摘要

基于 2026-10-08 的完整探测（80 个接口，67 个 HTTP 200，48 个拿到业务数据）：

| 结论 | 说明 |
| --- | --- |
| **编码不是全站统一的** | 成绩模块 UTF-8，课表/菜单 GBK，`top.jsp` gb2312。必须按响应头解码。 |
| **路径猜不出来** | 按通用 URP 命名猜的 35 个路径**全部 404**。真实地址靠 moduleId 调度发现。 |
| **会话失效不能看单条接口** | `index.jsp` 必然 302 回登录页，但同一会话下其他接口全部正常。 |
| **学生上下文不是学号** | `currcourse.jsdo` 的 `studentid` 是教务内部 ID，与登录学号完全不同，不能混用。 |
| **URP 错误页会返回 200** | 标题固定为「提示信息」。`class="error"` **不是**错误页标志（正常表单用它做必填占位）。 |
| **验证码其实是 JPEG** | 响应头 `image/jpeg`，之前硬存成 `.png` 会导致系统看图工具打不开。 |
| **门户页是 frameset 套娃** | `index_new.jsp` → `frameset_index.jsp` → 4 个 frame，必须递归跟进。 |
| **注释里的代码不能当真实结构** | 考试页 `<th>考试方式</th>` 实际被 `<!--...-->` 注释掉了。 |
| **DWR 无业务价值** | 只暴露 `messagePush.onPageLoad()` 与 `pushStart.sendMessageAuto()`，都是消息推送。 |

完整结论见 [`docs/api/00-overview.md`](../../docs/api/00-overview.md)。

---

## 关于 agent-browser

本工具**不使用** `agent-browser`：在当前环境下它拒绝一切纯 HTTP 导航
（`net::ERR_BLOCKED_BY_CLIENT`，对照组 `http://neverssl.com` 同样被拦截，HTTPS 正常），
而教务系统仅提供 HTTP。因此校验改用本工具直接抓取服务器原始响应，证据强度更高。
若确需真实浏览器核对，可自行在浏览器中打开教务站点。
