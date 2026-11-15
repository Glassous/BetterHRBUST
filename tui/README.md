# BetterHRBUST 终端界面

用 Go + [Charm](https://charm.land) 写的终端客户端：读桌面端已经同步好的本地缓存，不联网、不登录、不碰验证码。

```
┌ BetterHRBUST ──────────────────────────────────────────────────────────────┐
│  页面            姓名    张三                                               │
│ > 概览        -1 学号    2304060322                                         │
│   课程表      2  学院    计算机科学与技术学院                                │
│   成绩与 GPA  3  专业    软件工程 · 2024级 · 软件24-3                        │
│   考试日程    4  学期    2026 秋 · 第 11 教学周（共 19 周）                  │
│   培养方案    5                                                             │
│   教学公告    6  ▎学业指标（必修课口径）                                     │
│   学籍档案    7  平均学分绩点  2.40                                         │
│   课程名录    8  加权平均分    74.00                                        │
│   诊断        0  已获学分      3 / 5（60%）                                 │
│   设置        ,  挂科          1 门 / 2 学分                                │
│                  学位绩点      3.00（达标）                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 能力

界面按"卡片栅格 + 焦框"组织：每页由若干卡片组成，`tab` 在卡片间移动焦点，方向键在当前卡片内移动光标，`enter` 打开或聚焦下一层。这与前端「tab 换焦点、方向键在框内走」的心智一致。

| 页面 | 内容 |
|---|---|
| 概览 | 学籍 / 学期 / 学业指标 / 课程 / 考试 / 缓存 六张卡片；考勤、指标、缓存健康一屏可见 |
| 课程表 | 左侧周网格 + 右侧课程详情与当日时间轴；大节/小节切换、未安排课程、本周每天课量分布 |
| 成绩与 GPA | 指标带（含学期绩点走势）+ 课程列表 + 详情 + 分数分布 + 待关注清单 |
| 考试日程 | 列表 + 倒计时详情 + 按周分布，可调回看窗口 |
| 培养方案 | 总进度带 + 课组进度条列表 + 课组候选课程明细，支持筛选 |
| 教学公告 | 左侧标题列表 + 右侧正文常驻，按周浏览与关键字搜索 |
| 学籍档案 | 概览带 + 学籍信息 + 联系与证件（**固定打码**）+ 异动记录 |
| 课程名录 | 课程表 + 详情 |
| 诊断 | 健康摘要 + 检查结果 + 路径与文件 + 缓存键（凭证键只显示长度） |
| 设置 | 分组设置项 + 右侧说明与 AI 状态 |

此外还有一个 **AI 对话抽屉**：用自然语言提问，模型通过只读工具查询本地数据。

## 快速开始

**Windows 已装桌面端时无需构建**：安装包会把 `BetterHRBUSTTUI.exe` 装进安装目录，
而安装器已把该目录写入用户 PATH，重开终端后直接输入命令即可：

```powershell
BetterHRBUSTTUI
```

从源码构建（macOS / Linux，或想用最新代码时）：

```bash
# 从仓库根目录
cd tui
make build
./bin/BetterHRBUSTTUI
```

Windows 的 PowerShell 没有 make，直接构建：

```powershell
cd tui
go build -trimpath -ldflags "-s -w" -o bin\BetterHRBUSTTUI.exe ./cmd/betterhrbust-tui
```

前提：先用桌面端（或 Web 端）登录并同步过一次，`storage.json` 才会存在。

## 快捷键

| 键 | 作用 |
|---|---|
| `1`–`9` `0` `,` | 切换页面 |
| `tab` / `shift+tab` | 在当前页的卡片之间切换焦点 |
| `↑` `↓` `←` `→` | 在当前卡片内移动光标 |
| `ctrl+p` | 命令面板（模糊搜索页面与动作） |
| `ctrl+j` | 打开/关闭 AI 对话 |
| `ctrl+r` | 重新读取本地缓存 |
| `ctrl+t` | 切换主题（深色 → 浅色 → 16 色） |
| `?` | 快捷键帮助 |
| `esc` | 关闭浮层 / 关闭抽屉 / 返回上一页 |
| `q` / `ctrl+c` | 退出 |

页面内还有各自的按键（`enter` 打开、`/` 搜索、`f` 只看挂科……），按 `?` 可以看到当前页面的完整列表。

## 字形档位

三档字形，任意主题下都可切换：

| 档位 | 开关 | 字形 | 适用 |
|---|---|---|---|
| Unicode（默认） | — | `╭─ │ ▸ █ ░ ✓` | 绝大多数等宽字体 |
| ASCII | `--ascii` | `+- \| > # . OK` | 真正的 7 位终端、日志、限制环境 |
| Nerd Font | `--nerd-font` | Unicode + 私有区图标（`\uf073` 等） | 已安装 Nerd Font 的终端 |

也可写进配置文件：`ui.ascii` / `ui.nerd_font`，或在设置页里切换。

档位是**显式选择**而非自动探测：没有任何可靠办法判断终端装没装 Nerd Font，猜错的结果是满屏豆腐块。

## 非交互模式

`--json` 的输出结构与退出码与 `BetterHRBUSTCLI.exe` **完全一致**，因此在没有桌面端二进制的 Linux / macOS 上可以直接顶替：

```bash
BetterHRBUSTTUI --json status
BetterHRBUSTTUI --json summary --date 2026-11-13
BetterHRBUSTTUI --json scores --failed --property 必修
BetterHRBUSTTUI --json check --strict
```

| 退出码 | 含义 |
|---|---|
| 0 | 正常 |
| 1 | 参数或内部错误（含 `check --strict` 发现注意项） |
| 2 | 未登录且没有任何本地数据 → 先去图形界面登录 |
| 3 | 存储不可用，或已登录但缓存为空 → 去图形界面刷新 |

## AI 对话

**配置与图形界面共享**：桌面端（或 Web 端）启用 AI 后，终端直接读取
`%APPDATA%\edu.hrbust.betterhrbust\ai-config.json`（**只读**），在设置页填过一次
接口地址 / 模型 / API Key 就不必在两边各写一遍。桌面端未配置时，本程序仍使用自己的
`config.json`（见下文「数据与配置」）；两处都有配置时以**图形界面为准**。

默认关闭。在设置页（`,`）完成三步：

1. 填接口地址与模型名（OpenAI 兼容协议）
2. 填 API Key，或设置环境变量 `BETTERHRBUST_LLM_KEY`
3. 打开「AI 对话」并同意数据出网

可用的服务商示例：

| 服务 | base_url | 模型 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-flash` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Ollama（本地） | `http://127.0.0.1:11434/v1` | `qwen2.5:7b` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |

模型能调用的工具全部是只读查询：`query_summary`、`query_timetable`、`query_scores`、`query_exams`、`query_plan`、`query_notices`、`query_profile`、`query_courses`、`query_check`。它们与页面共用同一批函数，因此数字永远一致。

**隐私边界**：

- 只有提问与工具返回的查询结果会发送；证件号、手机号、邮箱、地址在离开进程前已经打码。
- 关闭「发送学籍」后，`query_profile` 不会出现在工具列表里，模型根本没有调用入口。
- 对话不落盘，`ctrl+n` 清空。
- 需要「AI 对话」与「允许数据出网」两个开关同时打开，打开时还会弹一次确认框。

## 数据与配置

| 内容 | 位置 |
|---|---|
| 教务缓存（只读） | `%APPDATA%\edu.hrbust.betterhrbust` · `~/Library/Application Support/edu.hrbust.betterhrbust` · `$XDG_DATA_HOME/edu.hrbust.betterhrbust` |
| 本程序配置 | `%APPDATA%\betterhrbust-tui\config.json` · `~/Library/Application Support/betterhrbust-tui/config.json` · `$XDG_CONFIG_HOME/betterhrbust-tui/config.json` |

覆盖方式：`--data-dir <路径>` / `BETTERHRBUST_DATA_DIR`，`BETTERHRBUST_TUI_CONFIG`（完整文件路径）。

配置文件权限 `0600`、目录 `0700`——里面可能存有 API Key。**它不会写进 `storage.json`**：那是图形界面拥有的缓存，终端界面只读。

## 开发

```bash
make build      # 构建到 ./bin
make test       # go test ./...
make check      # gofmt + go vet + go test
make snapshot   # 重新生成视图 golden
```

```
tui/
├── cmd/betterhrbust-tui/     入口、参数解析、--json
├── internal/model/           缓存结构 + 宽松反序列化
├── internal/store/           数据目录解析 + 只读快照 + 就绪状态
├── internal/academic/        学业统计、校历、格式化（纯函数）
├── internal/query/           命令载荷 + LLM 工具集
├── internal/llm/             OpenAI 兼容客户端 + SSE + 工具循环
├── internal/config/          本程序配置
└── internal/ui/              外壳、组件、主题、页面
```

三条硬规则：

1. `ui` 不 import `ui/screens`（注册表由 `cmd` 注入），否则循环依赖。
2. `academic` 不调用 `time.Now()`，所有时间由参数传入，测试才能复现。
3. 所有 Charm 类型只出现在 `ui/{app,components}`；`Screen.View()` 返回 `string`。

## 与他人协作时的一致性

学业算法的黄金向量在 [`shared/parity/`](../shared/parity/README.md)，由 Web / 桌面端 / 终端界面共读。改算法前先改那里的期望值。

## 许可

MIT
