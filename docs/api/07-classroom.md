# 07 · 教室查询

功能名为「教室时间占用」，用于查询教学楼在指定周次、指定星期的排课情况，
是找空闲教室（自习、活动场地）的基础。

真正的入口在 **`teacher/teachresource/`** 下，而不是想当然的 `manager/classroom/`。

**2026-11-04 实测结论**（样本见 `tools/probe/output/classroom/`，由
`tools/probe/classroom-probe.mjs` 抓取）：

- 只有「按周查询」返回真实占用；「按教室查询」返回 26 周空骨架，**没有占用标记**；
- 数据粒度是 **教学楼 × 周次 × 星期 × 12 小节**，一次请求拿一栋楼的一天；
- 整周需要按 `week=1..7` 逐日请求，一栋楼 7 次。

---

## 接口清单

| 接口 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 查询表单 | GET | `teacher/teachresource/roomschedulequery.jsdo` | 教学楼 / 教室 / 周次选择 |
| 级联刷新 | POST | `teacher/teachresource/roomschedulequery.jsdo` | 提交 `aid` / `buildingid` 后返回下级选项 |
| 按教室查询 | POST | `teacher/teachresource/roomschedule.jsdo` | 指定教室的 26 周表格（**占用为空**） |
| 按周查询 | POST | `teacher/teachresource/roomschedule_week.jsdo` | 指定周次 + 星期的整楼占用 |

---

## 1. 查询表单

```
GET /academic/teacher/teachresource/roomschedulequery.jsdo
```

### 1.1 响应

| 项目 | 值 |
| --- | --- |
| 状态 | `200` |
| 编码 | **GBK** |
| 大小 | 约 5.4 KB |
| 标题栏 | 教室时间占用 |

### 1.2 表单结构

```html
<form name="form1" method="post" action="roomschedulequery.jsdo">
```

表单 action 指向自身，且 `method="post"`；下级下拉带自动提交：

```html
<select name="aid" onChange="this.form.submit()">
<select name="buildingid" id="buildingid" onChange="this.form.submit()">
```

也就是说，浏览器里选教学区 / 教学楼时提交的是 **POST 本页**，
不是 GET 查询串。两种方式实测都能返回同一份选项，但实现时应按 POST 回放，
以免依赖未定义行为。

### 1.3 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `aid` | select | 教学区 |
| `buildingid` | select | 教学楼（与 `aid` 绑定） |
| `room` | select | 教室（选定教学楼后才有选项） |
| `whichweek` | select | **第几周**，1–26 |
| `week` | select | **星期几**，1=周一 … 7=周日 |

### 1.4 教学区选项

```html
<option value="-1">请选择</option>
<option value="353">西区</option>
<option value="712">南区</option>
<option value="17491">荣成校区</option>
```

### 1.5 教学楼选项（西区示例，18 个）

```html
<option value="1168">国际</option>
<option value="354">金工实习</option>
<option value="3428">理工大厦</option>
<option value="4470">纳米楼</option>
<option value="3792">实训楼</option>
<option value="356">实验</option>
<option value="1248">体育馆</option>
<option value="2388">网络通识课</option>
<option value="4198">西区7号楼</option>
<option value="703">西区操场</option>
<option value="358">西区二号楼</option>
<option value="692">西区计算机楼</option>
<option value="449">西区三号楼</option>
<option value="481">西区四号楼</option>
<option value="579">西区图书馆</option>
<option value="488">西区五号楼</option>
<option value="584">西区一号楼</option>
<option value="1688">新教学楼</option>
```

> 教学楼 ID 与教学区绑定。`buildingid` 只有在选定 `aid` 之后才有效，
> 不要跨教学区复用。

### 1.6 两个提交按钮

```html
<input type="submit" name="Submit2" value="确 定" onClick="return checkForm()">
<input type="submit" name="Submit"  value="确 定" onClick="return checkWeekForm()">
```

| 函数 | 目标 | 校验 |
| --- | --- | --- |
| `checkForm()` | `roomschedule.jsdo` | `buildingid`、`room` 不能为 `-1` |
| `checkWeekForm()` | `roomschedule_week.jsdo` | `buildingid`、`whichweek` 不能为 `-1` |

---

## 2. 按教室查询（26 周）

```
POST /academic/teacher/teachresource/roomschedule.jsdo
Content-Type: application/x-www-form-urlencoded
```

| 参数 | 必需 | 说明 |
| --- | --- | --- |
| `aid` | 是 | 教学区 ID |
| `buildingid` | 是 | 教学楼 ID |
| `room` | 是 | 教室 ID（来自 `select[name=room]`） |
| `whichweek` | 是 | 第几周（传数字；`-1` 会返回错误页） |
| `week` | 否 | 星期几（本接口不按星期过滤） |

### 2.1 响应结构

```
table#title                     标题：2026秋西-新401（自动化） 教室时间占用维护
table.subtitle × 26             第1周 … 第26周
table.infolists_tab × 26        每周一张 12 行 × 8 列的表
```

每周的表：第 1 行是表头（`&nbsp;` + 周一(08-31) … 周日(09-06)），
第 2–13 行是「第N节」× 7 天。

### 2.2 关键实测结论：**没有占用数据**

实测（西区一号楼 665 与 1688 的教室，26 周全部表）：

```html
<th>第1节</th>
<td> </td><td> </td> …          <!-- 7 天全是空单元格 -->
```

整份响应约 **92 KB / 53 张表**，但 26 周里**没有任何占用标记**，
即使该教室在「按周查询」里明确有课。说明这一页的占用标记不是服务端直出，
不能用于取空闲教室。

> 结论：**空教室功能不要用这个接口**。仅在需要「教室清单 / 各周日期」时参考。

---

## 3. 按周查询（整楼 × 一天）

```
POST /academic/teacher/teachresource/roomschedule_week.jsdo
Content-Type: application/x-www-form-urlencoded
```

| 参数 | 必需 | 说明 |
| --- | --- | --- |
| `aid` | 是 | 教学区 ID |
| `buildingid` | 是 | 教学楼 ID |
| `room` | 是 | 固定传 `-1`（该接口按整楼返回，不按教室过滤） |
| `whichweek` | 是 | 第几周，1–26 |
| `week` | 是 | **星期几**，1–7 |

### 3.1 参数语义（易错）

- `whichweek` 是「第几周」，`week` 是「星期几」，两者不可互换；
- 两者都不能为 `-1`：`week=-1` 实测返回 HTTP 200 的「提示信息」错误页，正文 `-2`；
- 每次只返回**一天**，整周需要 `week=1..7` 请求 7 次。

### 3.2 响应结构

```html
<table cellpadding="0" cellspacing="0" class="infolist_tab">
  <tr>
    <th>教室名</th><th>座位数</th><th>排课<br>容量</th>
    <th>考试<br>容量</th><th>桌椅<br>类型</th><th>教室<br>类型</th>
    <th>占用情况(小节)</th>
  </tr>
  <tr id="tr584_1" class="infolist_common">
    <td>西1-0303</td>
    <td>224</td>
    <td>224</td>
    <td>224</td>
    <td>未知</td>
    <td>多媒体教室</td>
    <td><table border="1">
      <tr align="center">
        <td>第1节</td> … <td>第12节</td>
      </tr>
      <tr align="center">
        <td valign="top">&nbsp;</td>                      <!-- 空闲 -->
        <td><a href="#" title="占用人:xxx&#13;占用原因:教务处&#13;备    注:新生晚自习">
              <font color="#FF0000">●</font></a></td>      <!-- 课程 -->
        …
      </tr>
    </table></td>
  </tr>
</table>
```

要点：

| 项目 | 说明 |
| --- | --- |
| 行 ID | `tr<教室ID>`，与 `select[name=room]` 的 value 一致 |
| 信息列 | 教室名 / 座位数 / 排课容量 / 考试容量 / 桌椅类型 / 教室类型（后两列常为「未知」） |
| 占用列 | 第 7 个 `td` 内嵌一张 **2 行** 表：第 1 行 12 个小节标签，第 2 行 12 个占用标记 |
| 空闲格 | `<td valign="top">&nbsp;</td>` |
| 占用格 | `<a title="…"><font color="#FF0000">●</font></a>` |
| 颜色语义 | `#FF0000` 课程、`#FF6600` 课程考试、`#006600` 其它 |
| 行间 | 每行后可能夹一段 `<script>`（`display:""`），解析时要忽略 |
| 标题 | `2026秋国际 第10周&nbsp;&nbsp;周一(11-02) 教室占用维护` |
| 图例 | 页面底部 `broken_tab`：`●`课程 `●`课程考试 `●`其它 |

`title` 里可能出现 `占用人:xxx`（登录名）—— **属于个人信息，不要展示**；
`占用原因` / `备注`（如「新生晚自习」）可用于提示。

### 3.3 体量（实测）

| 教学楼 | 教室数 | 单日响应 | 耗时 |
| --- | --- | --- | --- |
| 国际（1168） | 2 | 4.3 KB | 30 ms |
| 西区一号楼（584） | 61 | 85 KB | 2.2 s |
| 新教学楼（1688） | 225 | 318 KB | 6.9 s |

整周 7 天合计可达 **2 MB+ / 数十秒**，所以：单日查询作为主路径，
整周聚合必须并发受限（建议 2）并展示进度。

---

## 4. 找空闲教室的实现方式

服务端只给「占用」，空闲需要自行推算；且一次只有一天：

```
1. GET  roomschedulequery.jsdo                     → 教学区
2. POST roomschedulequery.jsdo (aid)               → 教学楼（与 aid 绑定）
3. POST roomschedule_week.jsdo
        { aid, buildingid, room:-1, whichweek, week }
                                                   → 该天整楼 12 小节占用
4. 需要整周时重复第 3 步 week=1..7（并发 2）
5. 空闲 = (1..7 天 × 1..12 节) − 已占用集合，再合并连续节次
```

合并连续节次的示例：

```js
function findFreeSlots(occupied, { days = 7, sections = 12, minSpan = 2 } = {}) {
  const slots = [];

  for (let day = 1; day <= days; day += 1) {
    let start = null;

    for (let sec = 1; sec <= sections + 1; sec += 1) {
      const isFree = sec <= sections && !occupied.has(`${day}-${sec}`);

      if (isFree && start === null) {
        start = sec;
      } else if (!isFree && start !== null) {
        const span = sec - start;
        if (span >= minSpan) slots.push({ day, start, end: sec - 1, span });
        start = null;
      }
    }
  }
  return slots;
}
```

只查了单日时，**只能在该天内扫描**，否则未查询的星期会被误判为整天空闲
（前端实现见 `web/src/services/academic/classroomSchedule.js` 的 `dayFreeRanges`）。

---

## 5. 实现建议

### 5.1 请求顺序

```
1. GET  roomschedulequery.jsdo                          → 教学区 + 周次 + 星期
2. POST roomschedulequery.jsdo  aid=353                 → 西区的教学楼
3. POST roomschedulequery.jsdo  aid=353&buildingid=584  → 该楼的教室（可选）
4. POST roomschedule_week.jsdo  whichweek=10&week=1     → 该天整楼占用
```

前 3 步是级联的，**不能跳步**——直接请求第 3 步拿不到教室列表。
教学区 / 教学楼 / 教室列表很少变动，建议按「当天日期」缓存（本项目 Web 端即如此）；
占用数据实时性要求高，不落盘。

### 5.2 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 404 | 用了 `manager/classroom/*` 路径 | 真实路径在 `teacher/teachresource/` 下 |
| 教学楼下拉为空 | 没先选教学区 | 按级联顺序请求 |
| 教室下拉为空 | 没先选教学楼 | 同上 |
| 返回「提示信息」错误页，正文 `-2` | `week` / `whichweek` 传了 `-1` 或空 | 传 1–26 / 1–7 的数字 |
| 返回「提示信息」错误页，正文 `For input string: ""` | 参数为空串 | 必须传数字 |
| 「按教室查询」26 周全是空的 | 该接口不直出占用标记 | 改用 `roomschedule_week.jsdo` |
| 中文乱码 | 按 UTF-8 解码了 | 本模块是 GBK |
| 整周查询很慢 | 一次只返回一天 | 并发 2、展示进度、缓存已加载的天 |
