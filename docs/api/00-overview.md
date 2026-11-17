# 00 · 通用约定

本文说明所有模块共用的机制：系统架构、会话、编码、上下文参数、响应解析与错误处理。
各模块文档只写该模块特有的内容，通用部分不再重复。

---

## 1. 系统架构

| 项目 | 值 |
| --- | --- |
| 基址 | `http://jwzx.hrbust.edu.cn/academic/` |
| 系统类型 | 清华教育在线 / 优慕课 URP 教务系统 |
| 认证框架 | Spring Acegi Security（表单认证） |
| 页面技术 | JSP + frameset + jQuery，老式服务端渲染 |
| 通讯协议 | 仅 HTTP，无 HTTPS |
| 响应编码 | **按模块不同**，见 §3 |
| 会话载体 | `JSESSIONID` Cookie |
| 接口命名 | `.do`（表单/页面）、`.jsdo`（HTML 片段）、`.jsp`、`.htm` |

### 1.1 请求路径规则

所有接口都挂在 `/academic/` 下。文档中写作相对路径：

```
文档写法：  manager/score/studentOwnScore.do
实际 URL：  http://jwzx.hrbust.edu.cn/academic/manager/score/studentOwnScore.do
```

### 1.2 请求头

| 请求头 | 值 | 必要性 |
| --- | --- | --- |
| `Cookie` | `JSESSIONID=...` | **必需**，缺失则回落登录页 |
| `Content-Type` | `application/x-www-form-urlencoded` | POST 时必需 |
| `Referer` | 发起请求的页面地址 | 建议带上，部分接口会校验 |
| `User-Agent` | 常规浏览器 UA | 建议带上 |

浏览器环境还需 `fetch(url, { credentials: 'include' })`，否则 Cookie 不会随请求发送。

### 1.3 可配置的教务地址

基址中的 **scheme / host / port 可替换**，`/academic/` 路径与全部接口契约不变：

| 端 | 配置位置 | 生效方式 |
| --- | --- | --- |
| Web / 油猴 | 设置页「教务地址」，键 `better_hrbust_academic_base` | 留空 = 同源 `/academic/` 代理（官方）；自定义 = 优先直连该 origin，被浏览器拦下时自动改由同源代理转发（请求头带目标） |
| 桌面端 | 同一键，落在 `storage.json` | Rust 反代逐请求解析 upstream，保存即生效；`Host`/`Referer`/302 `Location` 随上游改写 |
| Web 开发 / 自托管 | `BETTER_HRBUST_ACADEMIC`（vite dev/preview 代理目标） | 仅影响代理层，不进前端产物 |
| 终端界面 | 设置页「教务地址」或 `--base-url <地址>` | 写入 `config.json` / 本次运行临时覆盖；未配置时只读复用桌面端保存的值 |

地址归一化规则（三端一致）：可粘贴主页链接（`.../homepage/index.do`）或裸域名，
路径与查询串丢弃，统一为 `协议://主机[:端口]/academic/`；缺协议时官方域名用 `http`、
其他部署默认 `https`；拒绝非 http(s)、空主机与带用户名密码的输入。
切换地址不删除既有会话文件；会话 Cookie 按站点隔离，不会把官方站点的 Cookie 发给第三方站点。

---

## 2. 会话与认证

### 2.1 登录流程

登录分四步，**顺序不能颠倒**：

```
① GET  /academic/
       → 302 到 /academic/common/security/login.jsp
       → 取得 JSESSIONID

② GET  /academic/getCaptcha.do?_t=<时间戳>
       → 验证码图片

③ POST /academic/checkCaptcha.do?captchaCode=<4位数字>
       → 返回纯文本 true / false

④ POST /academic/j_acegi_security_check
       表单字段：j_username / j_password / j_captcha
```

第 ③ 步是教务系统自身页面逻辑的一部分（点击登录按钮时先校验验证码，
通过后才提交表单）。直接跳到第 ④ 步通常也能登录成功，但按原流程实现更稳妥。

### 2.2 密码是明文提交

站点 `styles/js/md5.js` 中：

```js
function plaintext(s, salt) { return s; }
```

是恒等函数。**不需要做任何前端加密**，直接提交原始密码即可。

> 该文件位于 `/academic/styles/js/md5.js`。其他 URP 部署版本可能启用真正的
> 加密逻辑，若登录失败可先检查这个文件的实际内容。

### 2.3 登录成功的判断

Acegi 认证成功后返回 **302 重定向**到门户页。判断方式：

| 结果 | 响应特征 |
| --- | --- |
| 成功 | HTTP 302，`Location` 指向门户页（如 `index_new.jsp`），响应体为空 |
| 失败 | HTTP 200，返回登录页 HTML，含 `badCredentials` / `密码错误` / `用户名不存在` / `验证码错误` |

**不要只看状态码**：失败时返回的是 200 + 登录页。

### 2.4 会话失效的判定

会话过期后，受保护接口**不返回 401**，而是把登录页 HTML 当作正常响应返回
（HTTP 200）。判定特征：响应体包含 `j_acegi_security_check` 或 `getCaptcha.do`。

但有个重要例外——**单条接口回落登录页不等于会话失效**：

```
index.jsp           → 必然 302 回登录页（它不是登录后的入口）
其他受保护接口      → 同一会话下全部正常
```

因此判断会话是否有效，要看**成规模**的回落：

```
若「回落登录页的接口数 ≥ 2」且「占受保护接口的比例 ≥ 25%」→ 判定会话已失效
```

单条回落应视为该接口本身的特性（如 `index.jsp` 就不该被调用）。

### 2.5 退出登录

```
GET /academic/logout_security_check
```

会销毁服务端会话。调用后需重新登录。

---

## 3. 字符编码

**这是最容易踩的坑：教务系统的编码不是全站统一的。**

### 3.1 实际编码分布

| 接口 | 响应头 charset | 正文实际编码 |
| --- | --- | --- |
| `manager/score/studentOwnScore.do` | `UTF-8` | **UTF-8** |
| `calendar/calendarViewList.do` | `UTF-8` | UTF-8 |
| `calendarinfo/viewCalendarInfo.do` | `UTF-8` | UTF-8 |
| `showHeader.do` | `UTF-8` | UTF-8 |
| `student/studentinfo/studentInfoModifyIndex.do` | `UTF-8` | UTF-8 |
| `manager/examstu/studentQueryAllExam.do` | `UTF-8` | UTF-8 |
| `listLeft.do` | `GBK` | GBK |
| `student/currcourse/currcourse.jsdo` | `gbk` | GBK |
| `manager/coursearrange/showTimetable.do` | `GBK` | GBK |
| `index_new.jsp` | `GBK` | GBK |
| `top.jsp` | `gb2312` | GBK 兼容 |
| `main.jsp` | `ISO-8859-1` | 纯 ASCII |

注意 `listLeft.do` 的页面内 `<meta>` 声明是 UTF-8，但**实际是 GBK**——
以响应头为准，不要信 meta。

### 3.2 错误后果

按 GBK 解码 UTF-8 的成绩页，中文会变成 `涓汉鎴愮哗鏌ヨ`（应为「个人成绩查询」），
并且「课程」「成绩」「学分」等关键字**全部匹配失败**——
现象看起来像「接口没有数据」，很容易误判成接口不可用。

### 3.3 正确的解码顺序

```js
async function decodeResponse(response) {
  const buffer = await response.arrayBuffer();
  const contentType = response.headers.get('content-type') || '';

  // 1. 优先用响应头里的 charset
  let charset = (contentType.match(/charset=([\w-]+)/i) || [])[1]?.toLowerCase();

  // 2. 响应头缺失或不可靠时，嗅探 HTML meta
  if (!charset || charset === 'iso-8859-1' || charset === 'us-ascii') {
    const sniff = new TextDecoder('ascii').decode(buffer.slice(0, 2048));
    const meta = sniff.match(/charset=["']?([\w-]+)/i);
    charset = meta ? meta[1].toLowerCase() : null;
  }

  // 3. 兜底 GBK
  if (!charset) charset = 'gbk';

  let text = new TextDecoder(charset).decode(buffer);

  // 4. 解码后出现大量替换字符，改用 GBK 重试
  const bad = (text.match(/\uFFFD/g) || []).length;
  if (bad > text.length / 100) {
    text = new TextDecoder('gbk').decode(buffer);
  }

  return text;
}
```

> Node.js 需要**完整 ICU** 才能使用 `TextDecoder('gbk')`。
> 若报错「不支持 gbk」，说明是 small-icu 构建，改用 Node 官方发行版。

系统内 GBK 系与 UTF-8 系接口数量相当，两者都很常见，不能偏向任何一边。

---

## 4. 上下文参数

相当多的接口依赖三个上下文参数，它们由 `student/currcourse/currcourse.jsdo` 提供。

### 4.1 获取方式

```html
<!-- GET student/currcourse/currcourse.jsdo 的响应中 -->
<eduaffair:CTRT studentid="100001" year="46" ... windowStyle="main"></eduaffair:CTRT>
```

响应里还有两个下拉框，列出了可选的学年与学期：

```html
<select name="year">   <!-- value="46" > 2026 -->
<select name="term">
```

### 4.2 参数含义

| 参数 | 含义 | 示例 |
| --- | --- | --- |
| `studentid` | **教务内部学生 ID** | `100001` |
| `year` | 学年内部序号 | `46`（对应 2026） |
| `term` | 学期内部序号 | `2` |

### 4.3 重要：内部 ID 不是学号

这是必须注意的一点。内部 ID 与学号完全不同：

```
学号：   2023000001
内部 ID：100001
```

课表接口的 `id` 参数、成绩接口的 `year`/`term` 参数都必须使用内部 ID。
把学号填进 `id` 参数会得到空课表或错误页。

### 4.4 学年序号的规律

`year` 是累加的序号，与公元年份有固定偏移：

| 序号 | 年份 |
| ---: | --- |
| 26 | 2006 |
| 27 | 2007 |
| 28 | 2008 |
| … | … |
| 46 | 2026 |

即 **`year` = 公元年份 − 1980**。这解释了为什么是 `46` 而不是 `2026`。

不要自己拼接 `year` 值——不同学期的可选范围由服务端下发，应以
`currcourse.jsdo` 的 `<select name="year">` 选项为准。

### 4.5 生命周期

这套参数是「当前学年学期」的状态，会随学期推进而变化。
每次建立会话后重新获取，不要长期缓存。

### 4.6 加密参数

另有一类参数是**加密串**，必须从页面中提取，不能自己构造：

```
student/studentinfo/loadphoto_added.jsdo?primary=userid&kind=student&userid=AbCdEfGhIjKlMnOpQrStUv==
manager/studyschedule/scheduleJump.jsp?studentId=A1b2C3d4E5f6G7h8I9j0K1==
```

要拿到照片或教学计划，必须先从对应页面解析出这个加密串，再发起请求。

---

## 5. 模块调度与接口发现

### 5.1 菜单只给模块 ID

登录后的左侧菜单（`listLeft.do`）**不直接暴露功能页地址**，只给出一串 `moduleId`：

```html
<!-- listLeft.do 响应中的典型条目 -->
<a href="javascript:void(0)"
   onclick="toModule('210','','...')">教学计划管理</a>
```

### 5.2 通过 accessModule.do 揭示真实地址

```
GET /academic/accessModule.do?moduleId=210&groupId=&randomString=<随机串>
  → 302 → /academic/manager/studyschedule/help.htm
```

服务端按当前用户权限把 `moduleId` 解析并重定向到真实功能页。
**跟随这个 302，最终 URL 就是真实地址。**

`randomString` 的格式是 `yyyyMMddHHmmss` + 6 位随机字母数字（如
`20261008143022aB3xY9`），按同格式自行生成即可通过校验。

### 5.3 为什么不能猜路径

按通用 URP 命名规律推测的 **35 个路径全部返回 404**，包括：

```
manager/score/studentScoreStatistics.jsdo     ×
manager/studentinfo/studentInfo.do            ×
manager/exam/examArrange.do                   ×
manager/teachingplan/teachingPlan.do          ×
manager/classroom/freeClassroom.do            ×
manager/elective/courseList.do                ×
manager/message/messageList.do                ×
```

这些功能**都存在**，但真实地址是：

```
考试安排 → student/exam/index.jsdo
学籍信息 → student/studentinfo/studentInfoModifyIndex.do
空教室   → teacher/teachresource/roomschedulequery.jsdo
选课     → manager/electcourse/elective.do
```

所以新增接口时，正确做法是：

1. 扫描 `listLeft.do` 与门户页，提取全部 `moduleId`；
2. 逐个请求 `accessModule.do` 并跟随 302；
3. 把得到的真实地址作为待测接口。

参考实现见 `tools/probe/lib/discover.mjs`。

### 5.4 相对地址必须相对页面目录解析

从页面里提取到的相对地址，要相对**该页面自身所在目录**解析，而不是一律相对基址：

```
页面：  calendar/calendarViewList.do
链接：  findSchoolCalendarDetailByDate.do?dateVal=

正确：  calendar/findSchoolCalendarDetailByDate.do
错误：  findSchoolCalendarDetailByDate.do          → 404
```

---

## 6. 响应形态与解析

### 6.1 五种响应形态

| 形态 | 特征 | 处理方式 | 示例 |
| --- | --- | --- | --- |
| **完整 HTML** | 含 `<html>` / `<head>` | `DOMParser` + 选择器 | `studentInfoModifyIndex.do` |
| **HTML 片段** | 无 `<html>`，直接是 `<table>`，路径多为 `.jsdo` | `DOMParser`（容错），取 `body` 或根元素 | `student/exam/index.jsdo` |
| **frameset** | `<frameset>` / `<iframe>` | 解析 `src` 后递归请求 | `frameset_index.jsp` |
| **纯文本** | `text/plain` | 直接比较字符串 | `checkCaptcha.do` → `true` / `false` |
| **二进制** | `image/*` 等 | 当 `Blob` 处理 | `getCaptcha.do`（实际是 JPEG） |

**注意 `.jsdo` 返回 HTML 片段而非 JSON。** 路径命名有误导性。
另外 `getCaptcha.do` 的响应头是 `image/jpeg` 而不是 PNG，保存文件时
应按 `Content-Type` 决定扩展名，否则系统看图工具可能拒绝打开。

### 6.2 表格结构规律

URP 的表格 class 名有稳定规律，可作为解析锚点：

| class | 用途 |
| --- | --- |
| `infolist_tab` | 表头行 + 数据行（课表、考试、通知、教学评价） |
| `datalist` | 表头行 + 数据行（成绩查询、学籍异动） |
| `infolist_common` | 数据行（普通列表） |
| `infolist_hr_common` | 数据行（含跨行合并的列表，如课表） |
| `form` | 表单式键值表，结构为 `<th>标签</th><td>值</td>` |
| `button_tab` | 按钮区 |

**按表头文字动态映射列，不要写死列索引。** 不同学期、不同专业的列可能变化。

### 6.3 URP 通用分页组件

带分页的列表（如全部考试安排）使用同一套参数协议：

| 参数 | 含义 |
| --- | --- |
| `pagingNumberPerVLID` | 每页条数 |
| `pagingPageVLID` | 页码 |
| `sortColumnVLID` | 排序列（字段路径形式，如 `examRoom.exam.endTime`） |
| `sortDirectionVLID` | 排序方向，`1` 升序 / `-1` 降序 |

页面结构：

```html
<table class='classicLookPagingTag PagingTag'>
  <td class='classicLookSummary Summary'>共<b>31</b>条，<b>1</b> / <b>4</b>页</td>
```

数据行 class 为 `classicLook0`（不是 `infolist_*`），解析时注意区分。

### 6.4 解析 HTML 时的三个坑

**① 必须剥离 HTML 注释。**

教务页面里有大量被注释掉的历史代码：

```html
<td>考试地点</td><!--<td>考试方式</td>-->
```

不剥离会把注释里的列也算进表头，导致表头与列数错位。

**② 布尔属性只有名字没有值。**

老式页面常见 `<option value="2" selected>`、`<input type="checkbox" checked>`。
只匹配 `name=value` 形式会漏掉选中态，需要单独处理这类属性。

**③ 不要用 `class="error"` 判定错误页。**

正常表单页会用 `<em class="error"></em>` 作为必填校验提示的占位元素。
按此判定会把 31KB 的学籍信息页误判为错误页。

---

## 7. 错误处理

### 7.1 状态码不一定可靠

| 现象 | 实际含义 | 处理建议 |
| --- | --- | --- |
| 200 + 命中数据 | 正常 | 直接使用 |
| 200 + 0 字节 | 路径存在但缺参数 | 补参数重试 |
| 200 + 「提示信息」错误页 | 路径存在但无权限或参数非法 | 降级处理 |
| 200 + 登录页 | 会话失效，或该接口本就如此 | 见 §2.4 |
| 302 → 登录页 | 路径不存在，或无权限 | 移除该路径 |
| 404 | 路径在当前版本不存在 | 移除 |
| 500 | 缺少必需参数 | 补参数重试 |

### 7.2 识别 URP 错误页

错误页会以 **HTTP 200** 返回，不能只看状态码。按可靠性排序的特征：

1. **标题为「提示信息」** —— URP 错误页的固定标题，最具辨识度；
2. 网关 / Tomcat 错误页特征（`HTTP状态 404`、`Status Report`）；
3. 页面很小（< 6KB）且含权限类文案（`您无权`、`无权限`、`非法访问`、`会话超时`）。

参考实现见 `tools/probe/lib/analyzer.mjs`。

### 7.3 三种失败要区分对待

同一个 200 响应背后可能是三种完全不同的失败，UI 上应给出不同提示：

| 失败类型 | 判定依据 | 用户提示 |
| --- | --- | --- |
| 会话失效 | 成规模回落登录页 | 「登录已过期，请重新登录」 |
| 无权限 | 错误页标题为「提示信息」 | 「当前账号无权访问该功能」 |
| 接口不存在 | 404 / 302 到登录页 | 「该功能暂不可用」（不暴露技术细节） |

---

## 8. 前端接入实践

### 8.1 跨域与会话

纯前端应用需要让请求成为同源请求，否则 Cookie 无法携带。常用方案是开发期用代理：

```js
// vite.config.js
server: {
  proxy: {
    '/academic': {
      target: 'http://jwzx.hrbust.edu.cn',
      changeOrigin: true,
      secure: false
    }
  }
}
```

生产环境需要一个后端服务持有会话（浏览器无法直接读取跨域 Cookie）。
参考 `tools/probe/server.mjs` 的实现——它把会话托管在服务端，
浏览器只负责展示验证码与提交表单。

需要指向其他 URP 部署时，有两种方式（默认都是官方地址）：

```bash
# ① 代理默认目标（构建/启动期）
BETTER_HRBUST_ACADEMIC=https://jwzx.example.edu.cn npm run dev
```

```js
// ② 运行期逐请求：前端在设置有自定义地址时给 /academic 请求带上该头，
//    dev / preview 的内置中间件按请求把同一个代理实例指向该 origin
fetch('/academic/manager/score/studentOwnScore.do', {
  headers: { 'x-betterhrbust-academic-base': 'https://jwzx.example.edu.cn/academic/' }
})
```

两种方式都会同步改写 `Host`、`Referer` 与 302 `Location`，否则上游会拒绝请求或把浏览器带离本地代理。

浏览器不允许页面读取跨域响应（教务系统普遍不返回 `Access-Control-Allow-Origin`），
因此前端策略是：**先直连**，被拦下就把绝对地址折回同源的 `/academic/...` 并带上目标头，
由本地代理转发——Cookie 与 302 都留在本地，登录流程与官方默认地址一致。
纯静态部署没有这一层转发时，要么由部署端把 `/academic` 反代到目标站点，要么直接用桌面端 / 油猴。

### 8.2 拿数据的推荐顺序

```
1. 取验证码 → 登录 → 确认会话有效
2. 取上下文（currcourse.jsdo）得到 studentid / year / term
   └─ 失败则「课表 / 成绩 / 考试」等依赖上下文的功能整体降级
3. 并行拉取各模块数据
```

### 8.3 验证码处理

验证码接口支持加时间戳避免缓存：

```
GET /academic/getCaptcha.do?_t=<Date.now()>
```

刷新时同样要换时间戳，否则可能拿到缓存图片。响应为 JPEG，可直接作为
`<img>` 的 `src` 使用（需带上 Cookie）。

### 8.4 敏感数据

`showPersonalInfo.do` 与 `studentInfoModifyIndex.do` 返回**身份证号、手机号、
家庭住址**等内容。前端展示时：

- 默认脱敏（如 `2301**********1234`），需要时再展开；
- 不要写入日志、不要上报到第三方；
- 探测样本同样包含这些内容，不要分享 `tools/probe/output/`。

### 8.5 请求频率

教务系统是老式 Java 应用，抗压能力有限。建议：

- 串行或低并发（探测工具默认并发为 1）；
- 对静态数据（成绩、学籍）做本地缓存，不要每次进入页面都全量拉取；
- 避免短时间内重复请求。

---

## 9. DWR 接口（无业务价值，不必再探索）

系统使用 DWR 做站内消息推送。`dwr/index.html` 列出了对外暴露的全部 Bean：

```
messagePush  (net.theol.project.eduaffair.calendar.service.MessagePush)
pushStart    (net.theol.project.eduaffair.calendar.service.PushStart)
```

用 DWR 自带的测试页（`dwr/test/messagePush`、`dwr/test/pushStart`）枚举了全部方法：

| Bean | 业务方法 | 其余方法 |
| --- | --- | --- |
| `messagePush` | `onPageLoad()` | 全部继承自 `java.lang.Object` |
| `pushStart` | `sendMessageAuto(Integer, String)` | Struts Action 生命周期方法 + `Object` 继承方法 |

**结论：DWR 只用于站内消息推送，不暴露成绩 / 课表 / 学籍等业务数据。**
前端功能走 `.do` / `.jsdo` 页面接口即可，不需要在 DWR 上花时间。

调用协议（供参考）：

```
POST /academic/dwr/call/plaincall/<bean>.<method>.dwr
Content-Type: text/plain

callCount=1
c0-scriptName=<bean>
c0-methodName=<method>
c0-id=0
batchId=0
page=/academic/xxx.jsp
scriptSessionId=<会话中的随机串>
```
