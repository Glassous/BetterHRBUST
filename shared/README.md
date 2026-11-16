# 教务处公开资料目录

`resources.json` 收录教务处「资料下载」栏目下的全部分页标题、日期、原文及附件链接。网页构建时内联目录，Android 从 `assets/resources.json` 加载，搜索不需要连接教务。

从仓库根目录运行 `python tools/crawl-resources.py` 更新目录；脚本使用 Python 标准库，不需要额外依赖。每个分类的解析条数必须与学校分页统计一致，且取得全部栏目后才保存。逐门读取公开原文并解析附件地址，间隔 150ms，不登录、不下载附件；外部学院页面只保留学校目录给出的原文地址。

生成文件同步写入 `shared/resources.json` 与 `android/app/src/main/assets/resources.json`。运行 `node web/scripts/test-resources.mjs` 核验两端一致性、分类条数、唯一标识与学校域名链接。索引日期是抓取时间；办理流程、最新通知及附件版本以学校原文为准。

# 本科教学进程快照

`teaching-process.json` 把教务处「本科教学进程表」（`homepage/downloads/jincheng.htm`）整理成结构化数据：每个学期一张「班级 × 教学周」的符号网格，按校区拆开。网页构建时内联，离线可用，不登录、不访问教务。

从仓库根目录运行 `python3 tools/crawl-jincheng.py` 更新，默认只保留最近 8 个学期；需要全量（33 个学期，约 450KB）时加 `--all`。脚本用 Python 标准库解析 WPS 导出的表格，结构不符合预期时直接失败且不会覆盖旧文件。生成文件同步写入 `shared/teaching-process.json` 与 `android/app/src/main/assets/teaching-process.json`，Android 端离线读取同一份快照。

字段约定：

| 字段 | 说明 |
| --- | --- |
| `source` / `sourceUpdatedAt` | 原表地址与教务处的最后保存日期（页面展示，用于判断是否过期） |
| `patterns[i]` | 一整行符号的字典项：首个字符是哨兵，其后每周字形用竖线分隔；解码后长度等于该学期的周数（`decodePattern` 见 `web/src/utils/teachingProcess.js`） |
| `semesters[].id` | `学年-学期`，如 `2026-2027-1`；数组按学年学期倒序 |
| `semesters[].weeks[]` | 每周的周一/周日日期（ISO），直接来自原表表头 |
| `semesters[].legend` | 原表图例原文，逐行保留（不同年份措辞不同） |
| `semesters[].campuses[].classes[]` | `[班级名, 周学时, patterns 索引]`；班级名已去掉多余空白 |
| `warnings[]` | 抓取时发现但对不上账的地方（如个别学期表头错位），页面会一并展示 |

字形含义不在数据里，由 `web/src/utils/teachingProcessSymbols.js` 解释（同一含义在不同年份会用 `：`/`:`、`╳`/`Ⅹ`、`Ä`/`◆` 等不同写法）。数据保留原始字形，所以新增字形不需要重新抓取；`node web/scripts/test-teaching-process.mjs` 会检查解析结果是否超出显式白名单——原表出现新符号时测试变红，需要人工决定是补字典还是补白名单。

