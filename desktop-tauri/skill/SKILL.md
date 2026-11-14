---
name: betterhrbust
description: 通过本机 BetterHRBUSTCLI.exe 查询哈尔滨理工大学教务本地缓存（课程表、成绩与 GPA、考试日程、培养方案学分进度、教学公告、学籍档案、本学期课程名录）。当用户询问自己的课表、某天/某周的课、成绩、绩点、挂科、考试安排、毕业学分进度，或要求把教务数据整理成表格与摘要时使用。只读取本机缓存，不联网、不需要账号密码与验证码。
---

# BetterHRBUST 教务数据查询

用本机已安装的 `BetterHRBUSTCLI.exe` 读取桌面端缓存的教务数据。命令只读本地文件，不联网、不登录、不修改任何数据。

## 何时使用

- 用户询问自己的课程表、今天/某天的课、某周的课、下学期开课
- 用户询问成绩、某门课成绩、平均学分绩点（GPA）、已获学分、挂科门数、学位/推免资格
- 用户询问考试时间、考场、倒计时
- 用户询问培养方案学分完成度、某个课组的毕业进度
- 用户询问教学公告、学籍信息、本学期课程名录
- 需要排查"为什么查不到数据 / 数据是不是旧的"

## 何时不要使用

- 需要选课、退课、提交教学评价、修改学籍等写操作：命令行不提供，也不应尝试，引导用户到图形界面或教务原站办理
- 需要"最新"数据但缓存过旧：命令行不联网，引导用户在图形界面点击「刷新」
- 用户尚未登录且无本地数据：不要尝试登录（需要图片验证码），引导用户打开图形界面登录

## 调用方式

优先直接执行短命令（安装时已把安装目录写入用户 PATH）：

```powershell
BetterHRBUSTCLI status
```

若提示找不到命令（终端未重启或便携版），按顺序尝试：

```powershell
where.exe BetterHRBUSTCLI
# 便携版：直接执行所在目录下的可执行文件
.\BetterHRBUSTCLI.exe status
```

安装目录通常在 `%LOCALAPPDATA%` 下，也可用 `BetterHRBUSTCLI doctor` 查看可执行文件与数据文件路径。

## 命令

| 命令 | 用途 |
| --- | --- |
| `status` | 登录状态、本地数据条数、上次同步时间 |
| `summary [--date YYYY-MM-DD]` | 概览：身份、当日课程、近期考试、学业指标；`--date` 把基准日挪到指定日期 |
| `timetable [--week N] [--day N] [--today] [--date YYYY-MM-DD] [--base] [--course 关键字]` | 课程表；`--today` 今日剩余课程，`--date` 指定某一天，`--base` 小节课表 |
| `scores [--year 2025] [--term 秋] [--course 关键字] [--property 必修\|限选\|任选\|其他] [--failed] [--merged]` | 成绩列表 + 必修课口径 GPA / 学分 / 挂科统计；`--merged` 输出去重合并后的课程清单 |
| `exams [--all] [--days N] [--course 关键字]` | 考试日程；默认近期（未来 + 过去 7 天），`--days N` 调整窗口，`--all` 全部 |
| `plan [--group 关键字]` | 培养方案各课组学分完成度；`--group` 只看名称匹配的课组 |
| `notices [--week N] [--query 关键字] [--limit N]` | 教学公告；可按关键字过滤、限制条数 |
| `profile` | 学籍档案（证件号、手机号默认打码） |
| `courses [--query 关键字] [--limit N]` | 本学期课程名录 |
| `doctor` | 数据文件、路径、可读性与缓存键诊断 |
| `check [--strict]` | 错误检查：数据完整性、会话状态、同步失败项、数据新鲜度、成绩可读性 |
| `skill [--save <路径>]` | 输出或保存本 SKILL 文件 |

通用选项：`--json`（机器可读输出，推荐脚本与 AI 使用）、`--data-dir <路径>`（指定数据目录）。

说明：正常查询命令**不会**在结果后面夹带报错信息（历史同步失败等只在 `check` 中显示），因此不要因为看不到报错就认为数据一定完整；需要确认时执行 `check`。

## 输出与解析约定

- 默认输出是给人看的中文表格，宽度按 CJK 显示对齐；解析请一律使用 `--json`。
- `--json` 成功结构：`{ "command", "ok": true, "generatedAt", "loggedIn", "offline", "account", "lastSyncAt", "lastSyncText", "data" }`。
- `--json` 失败结构：`{ "command", "ok": false, "error": { "code", "message" } }`。
- 字段名与图形界面一致（camelCase），缺失数据返回空数组 / `null`，不会省略键。
- 统计口径与图形界面完全相同：GPA、已获学分、挂科门数只统计必修课，重修补考合并去重；学位绩点使用"学业课 + E 类最高一门 + 其余 A–E 类最高一门"。不要把 `scores` 原始记录自行重新汇总后当成官方绩点。
- `scores --json` 的 `data.stats` 始终按**全部成绩**统计；`data.scores` 是筛选后的原始记录（同一门课可能多条），`data.courses` 是去重合并后的课程清单（含 `isRetake` / `recordCount`）。
- `check --json` 的 `data.checks[]` 每项含 `id / level(ok|warn|fail) / title / detail / hint`，`data.summary` 给出三类计数。

## 退出码（重要）

| 退出码 | 含义 | 应如何处理 |
| --- | --- | --- |
| 0 | 正常 | 直接使用输出 |
| 1 | 参数或内部错误（含 `check --strict` 发现注意项） | 检查命令拼写与选项，必要时执行 `help`；门禁场景看 `check` 输出 |
| 2 | 未登录且没有任何本地数据 | 告知用户：登录需要图片验证码，请先打开 BetterHRBUST 图形界面登录一次，再回到终端查询。不要尝试其他登录方式 |
| 3 | 存储不可用，或已登录但缓存为空（`check` 有 fail 项时同样是 3） | 告知用户：请打开图形界面并在顶栏点击「刷新」同步数据；若仍失败，执行 `check` / `doctor` 并反馈其输出 |

退出码 2 / 3 时不要编造数据，也不要回退到猜测或示例数据。

## 只读与隐私约束

- 禁止尝试登录、伪造缓存、写回数据；命令本身也不提供任何写操作。
- 不要输出、转述、复述 `better_hrbust_saved_credential` 等凭证内容（命令行不会打印，也不要通过其他方式读取）。
- `profile` 输出中的证件号与手机号已打码，保持原样转述，不要尝试还原。
- 数据可能是一段时间之前的缓存：转述时说明 `lastSyncText`（上次同步时间），尤其在用户问"最新""现在"时。
- 用户处于离线缓存状态（`offline: true`）时，说明"这是上次登录时的缓存，会话已失效"，并建议需要更新时到图形界面刷新。

## 典型工作流

1. **确认可用性**：`BetterHRBUSTCLI status --json`，看 `loggedIn` / `hasData` / `lastSyncAt`；结果异常或用户怀疑数据缺失时执行 `BetterHRBUSTCLI check --json`。
2. **取数据**：按用户问题选择命令，统一加 `--json`；能用参数缩小范围时优先使用（如 `--date`、`--course`、`--property`、`--failed`、`--group`、`--days`），避免把上百条记录整段贴给用户。
3. **组织回答**：把 `data` 转成用户需要的表格或摘要，附上"数据同步时间"，必要时附统计口径说明。
4. **需要更多细节**：`scores --json` 含每门课的学分、属性、绩点与是否补考重修，可自行做筛选（如"哪几门挂科""某学期绩点"），但汇总结论以命令给出的 `stats` 为准。

## 示例

```powershell
# 今天还有什么课
BetterHRBUSTCLI timetable --today --json

# 指定某天有什么课（自动换算教学周与星期）
BetterHRBUSTCLI timetable --date 2026-11-13 --json

# 某门课的全部成绩记录，以及合并后的最终结论
BetterHRBUSTCLI scores --course 线性代数 --json
BetterHRBUSTCLI scores --course 线性代数 --merged --json

# 只看挂科
BetterHRBUSTCLI scores --failed --json

# 本学期成绩与 GPA
BetterHRBUSTCLI scores --json

# 近期考试（自定义窗口）
BetterHRBUSTCLI exams --days 30 --json

# 毕业学分还差多少
BetterHRBUSTCLI plan --json

# 排查为什么查不到数据
BetterHRBUSTCLI check --json

# 把 SKILL 安装到自己的 AI 工具技能目录
BetterHRBUSTCLI skill --save .\.codebuddy\skills
```
