# shared/parity

跨端一致性黄金向量。**Web（JS）、桌面端（Rust CLI）与终端界面（Go TUI）读同一批文件，断言同一组数值。**

任何一端改了学业算法却没同步这里的期望值，它自己的测试就会红。

## 为什么需要它

五端口径一致是本项目最核心的承诺：GPA、已获学分、学位绩点、推免资格、风险预警、教学周——同一个学生、同一份缓存，在任何一端都必须给出同一个数字。

算法已经被移植了三次（`web/src/services/academic/stats.js` → Rust → Go）。文档和注释都会过期，只有可执行的期望值不会。

## 文件结构

```json
{
  "schemaVersion": 1,
  "id": "academic-stats",
  "kind": "academicStats",
  "description": "这个文件验证什么",
  "reference": ["web/src/services/academic/stats.js"],
  "cases": [
    {
      "name": "用例名（中文，说明业务含义）",
      "clock": { "today": "2026-11-13", "minuteOfDay": 600 },
      "input": {},
      "expect": [{ "path": "degree.gpa", "equals": 3.07 }],
      "forbid": ["110101199001011234"]
    }
  ]
}
```

### 字段

| 字段 | 说明 |
|---|---|
| `schemaVersion` | 当前为 `1`。升级必须三端同一个 PR 完成 |
| `id` | 文件标识，缺省取文件名 |
| `kind` | 决定 `input` 怎么被消费，见下表 |
| `reference` | 口径来源文件，供排查时追溯 |
| `cases[].name` | 用例名，会出现在测试失败信息里 |
| `cases[].clock` | 时间基准。缺省用系统当前时间（应尽量避免） |
| `cases[].input` | 该 kind 的输入 |
| `cases[].expect` | 断言列表 |
| `cases[].forbid` | 结果序列化文本中**不得出现**的字符串，用于隐私断言 |

### 断言类型

按以下顺序生效，互斥：

| 写法 | 含义 |
|---|---|
| `{"absent": true}` | 字段必须不存在 |
| `{"null": true}` | 字段必须存在且为 `null` |
| `{"len": 3}` | 数组长度 / 对象键数 |
| `{"approx": 3.07}` | 数字近似（容差 1e-9） |
| `{"contains": "刷新"}` | 字符串包含 |
| `{"equals": ...}` | 深比较（数字按 1e-9 容差） |

`path` 支持 `a.b[0].c` 形式。

### kind

| kind | input | 说明 |
|---|---|---|
| `academicStats` | `{scores}` | 学业统计总览 |
| `degreeCourses` | `{scores}` | 学位课集合 |
| `dedupe` | `{scores}` | 去重合并，输出 `{record, isRetake, recordCount}` |
| `creditsProgress` | `{scores, groups, totalRequiredCredits?, requiredOnly?}` | 培养方案学分进度 |
| `courseWeeks` | `{expression, week?, maxWeek?}` | 周次判定与展开 |
| `teachingWeek` | `{today}` | 教学周换算 |
| `dayDate` | `{currentWeek, selectedWeek, day, today}` | 教学周日历标签 |
| `slotTimes` | `{period, minutesOfDay?}` | 大节时间与是否下课 |
| `examTime` | `{text}` | 考试时间解析 |
| `cellFields` | `{lines}` | 课表格子字段拆解 |
| `readiness` | `{storage, loadError?}` | 就绪状态与退出码 |
| `query` | `{storage \| fixture, command, options?, today?, minuteOfDay?}` | 命令输出 |
| `mask` | `{text, keepHead, keepTail}` | 打码 |

`fixtures/` 下的文件是完整的 `storage.json`（`map[string]string`），`query` 用例可用 `{"fixture": "fixtures/storage-full.json"}` 直接引用。

## 规则

1. **改算法先改 fixtures。** 先在这里写清期望值，再去改代码。
2. **`schemaVersion` 升级必须三端同 PR。** 否则老版本的端会静默跳过新字段。
3. **未知 kind 必须跳过而不是失败。** 这样一端可以先于其他端接入新用例。
4. **不要在这里放真实学生数据。** 夹具全部是构造的，证件号 `1101…1234` 是示例号。
5. **`forbid` 是隐私断言，不是可选项。** 只要某条用例涉及学籍、凭证或地址，就必须写。

## 各端如何消费

- Go：`tui/internal/query/parity_test.go`（执行全部 kind）
- Rust：待接入，`desktop-tauri/src-tauri/src/cli` 的单元测试已含同一批黄金数值
- Web：待接入，`web/scripts/test-academic-corrections.mjs` 已含同一批断言

## 已知的有意分歧

| 项 | 说明 |
|---|---|
| `isProfessionalElectiveGroup` | Web 与 Android 用正则 `专业(?:方向)?(?:选修\|限选)`；Rust 用「同时包含专业与选修/限选」的宽松判断，会把「专业基础限选」误判为专业选修。Go 采用前者的严格正则。 |
| 单条坏记录 | Rust / JS 用整表反序列化，任一条脏数据会让整张表清空；Go 逐条解析，只丢坏记录。 |
| `summary.profile` | Rust 的 `summary --json` 输出未打码学籍；Go 一律打码。这是需要修正的泄露面，不应被复制。 |
