/**
 * 已知 URP 接口目录
 *
 * 本目录已用 **两轮真实探测** 校准：
 *   ① 2026-10-07 首轮：发现「按通用 URP 命名猜的路径全部 404」，
 *      并确认真实地址要靠 accessModule.do?moduleId=X 调度揭示；
 *   ② 2026-10-08 二轮：门户页 + 模块调度 + 深度递进，挖出 20+ 个真实功能页。
 *
 * 因此本文件的路径基本都是**实测 200 且拿到业务数据**的，
 * 而不是按命名规律推测的。可靠度标记：
 *      verified   —— 实测返回业务数据（HTTP 200 且非错误页/登录页）
 *      likely     —— 路径存在但未取到数据（无权限 / 需参数）
 *      guess      —— 推测路径（未探测）
 *
 * mutating 为 true 的接口会改变教务数据，默认不探测。
 * params 支持占位符 {studentId} / {year} / {term} / {timestamp} / {randomString}。
 */

/** 模块定义：用于文档分组与统计 */
export const MODULES = {
  auth: { name: '认证与会话', doc: '01-auth.md' },
  dispatch: { name: '框架与模块调度', doc: '01-auth.md' },
  student: { name: '个人信息与学籍', doc: '04-student-info.md' },
  score: { name: '成绩查询', doc: '02-scores.md' },
  timetable: { name: '课表查询', doc: '03-timetable.md' },
  exam: { name: '考试安排', doc: '05-exam.md' },
  course: { name: '课程与教师查询', doc: '10-course.md' },
  plan: { name: '培养方案与学分', doc: '06-teachingplan.md' },
  classroom: { name: '空教室查询', doc: '08-classroom.md' },
  elective: { name: '选课与退课', doc: '07-course-selection.md' },
  notice: { name: '通知与校历', doc: '09-notice.md' },
  eva: { name: '教学评价', doc: '11-eva.md' },
  sysmgr: { name: '系统与账户', doc: '12-sysmgr.md' }
};

/**
 * @typedef {Object} EndpointSpec
 * @property {string} key
 * @property {string} name
 * @property {keyof MODULES} module
 * @property {string} path
 * @property {'GET'|'POST'} method
 * @property {Object.<string,string>} [params]
 * @property {string[]} [need]
 * @property {string[]} [markers]
 * @property {string}  [parser]
 * @property {'verified'|'likely'|'guess'} confidence
 * @property {boolean} [binary]
 * @property {boolean} [public]
 * @property {boolean} [mutating]
 * @property {boolean} [skipInBatch]
 * @property {string}  [note]
 */

/** @type {EndpointSpec[]} */
export const CATALOG = [
  /* ============================ 认证与会话 ============================ */
  {
    key: 'auth.root',
    name: '站点入口 / 登录页',
    module: 'auth',
    path: '',
    method: 'GET',
    confidence: 'verified',
    public: true,
    markers: ['j_acegi_security_check', 'getCaptcha.do'],
    note: '实测：HTTP 200，302 到 /academic/common/security/login.jsp。同时是会话失效判定基准。'
  },
  {
    key: 'auth.captcha',
    name: '获取图形验证码',
    module: 'auth',
    path: 'getCaptcha.do',
    method: 'GET',
    params: { _t: '{timestamp}' },
    confidence: 'verified',
    public: true,
    binary: true,
    note: '实测：HTTP 200，image/jpeg（实际是 JPEG 而非 PNG），约 1KB'
  },
  {
    key: 'auth.checkCaptcha',
    name: '校验图形验证码',
    module: 'auth',
    path: 'checkCaptcha.do',
    method: 'POST',
    params: { captchaCode: '0000' },
    confidence: 'verified',
    public: true,
    markers: ['true', 'false'],
    note: '实测：HTTP 200，纯文本 true / false'
  },
  {
    key: 'auth.login',
    name: '登录提交（Acegi）',
    module: 'auth',
    path: 'j_acegi_security_check',
    method: 'POST',
    params: { j_username: '{username}', j_password: '{password}', j_captcha: '{captcha}' },
    confidence: 'verified',
    public: true,
    mutating: true,
    skipInBatch: true,
    note: '密码为明文提交（md5.js 中 plaintext() 是恒等函数）'
  },
  {
    key: 'auth.logout',
    name: '退出登录',
    module: 'auth',
    path: 'logout_security_check',
    method: 'GET',
    confidence: 'likely',
    mutating: true,
    note: '会销毁会话，批量探测时跳过'
  },
  {
    key: 'auth.listLeft',
    name: '左侧菜单树（含教学周与模块 ID）',
    module: 'auth',
    path: 'listLeft.do',
    method: 'GET',
    confidence: 'verified',
    markers: ['周', '.do'],
    note:
      '实测：HTTP 200，16345 字节，GBK。含「第 N 周」教学周，以及 19 个 ' +
      'accessModule.do?moduleId=NNN 模块入口，是接口发现的主要来源。'
  },
  {
    key: 'auth.listLeftByType',
    name: '按模块类型筛选菜单',
    module: 'auth',
    path: 'listLeft.do',
    method: 'GET',
    params: { moduleType: '3' },
    confidence: 'likely',
    note:
      '由 showHeader.do 的链接发现：moduleType 可选 -1 / 3 / 5 / 6 / 7 / 8。' +
      '用于按权限类别枚举菜单，是补全模块列表的补充入口。'
  },
  {
    key: 'auth.top',
    name: '顶部导航页',
    module: 'auth',
    path: 'top.jsp',
    method: 'GET',
    confidence: 'verified',
    markers: ['退出', 'logout'],
    note: '实测：HTTP 200，2131 字节，gb2312'
  },
  {
    key: 'auth.main',
    name: '主内容页',
    module: 'auth',
    path: 'main.jsp',
    method: 'GET',
    confidence: 'verified',
    note: '实测：HTTP 200，367 字节（frameset 框架页）'
  },

  /* ========================== 框架与模块调度 ========================== */
  {
    key: 'dispatch.portal',
    name: '登录后门户页（index_new.jsp）',
    module: 'dispatch',
    path: 'index_new.jsp',
    method: 'GET',
    confidence: 'verified',
    markers: ['iframe', 'frameset'],
    parser: 'analyzePortal',
    note:
      '实测：HTTP 200，4000 字节，GBK，标题「综合教务管理系统」。' +
      '**这是登录后真正的入口页**（index.jsp 会 302 回登录页）。' +
      '内部只有一个 iframe 指向 frameset_index.jsp。'
  },
  {
    key: 'dispatch.frameset',
    name: '主框架页（frameset_index.jsp）',
    module: 'dispatch',
    path: 'frameset_index.jsp',
    method: 'GET',
    confidence: 'verified',
    markers: ['frameset', 'listLeft'],
    note:
      '实测：HTTP 200，1244 字节。真实框架结构：\n' +
      '  headerFrame → showHeader.do（由 listLeft.do 加载后指定，初始为空）\n' +
      '  menuFrame   → listLeft.do?randomString=xxx\n' +
      '  mainFrame   → ./checkPassword.do\n' +
      '  footerFrame → footer.jsp'
  },
  {
    key: 'dispatch.checkPassword',
    name: '主框架默认页（改密提醒）',
    module: 'dispatch',
    path: 'checkPassword.do',
    method: 'GET',
    confidence: 'likely',
    note: '登录后 mainFrame 默认加载的页面，用于提醒修改密码'
  },
  {
    key: 'dispatch.footer',
    name: '底部框架页',
    module: 'dispatch',
    path: 'footer.jsp',
    method: 'GET',
    confidence: 'likely'
  },
  {
    key: 'dispatch.accessModule',
    name: '模块调度（按 moduleId 揭示真实功能页）',
    module: 'dispatch',
    path: 'accessModule.do',
    method: 'GET',
    params: { moduleId: '210', groupId: '', randomString: '{randomString}' },
    confidence: 'verified',
    note:
      '实测：HTTP 200 并 302 到真实功能页。**本系统最关键的结构线索**：' +
      '菜单只暴露 moduleId，真实地址靠它解析。' +
      'randomString 格式为 yyyyMMddHHmmss + 6 位随机字符。'
  },
  {
    key: 'dispatch.showHeader',
    name: '顶部信息片段（showHeader.do）',
    module: 'dispatch',
    path: 'showHeader.do',
    method: 'GET',
    params: { randomString: '{randomString}' },
    confidence: 'verified',
    markers: ['教务'],
    note:
      '实测：HTTP 200，3847 字节，UTF-8，12 个链接。' +
      '从中发现 showPersonalInfo.do、viewCalendarInfo.do 与 listLeft.do?moduleType=N。'
  },
  {
    key: 'student.myInfo',
    name: '我的信息（个人资料 + 学籍异动）',
    module: 'student',
    path: 'showPersonalInfo.do',
    method: 'GET',
    confidence: 'verified',
    markers: ['我的信息', '姓名'],
    note:
      '实测：HTTP 200，4360 字节，UTF-8，标题「我的信息」。**这是最简洁的个人信息接口**。\n' +
      '表格 class=`form`，结构为 `<th>标签</th><td>值</td>`：\n' +
      '  用户名 | 真实姓名 | 所在院系 | 专业 | 方向 | 学生类别（含学制） | 年级 | 班级 |\n' +
      '  证件类型 | 证件号码 | 电子邮箱 | 联系电话 | 通讯地址 | 邮政编码\n' +
      '第二张表 class=`datalist`（行 `infolist_hr_common`）为学籍异动：\n' +
      '  异动类型 | 异动时间 | 原因 | 备注\n' +
      '⚠️ 该页面含身份证号、手机号、家庭住址等敏感信息，前端展示需谨慎。'
  },

  /* ------------------------------ DWR ------------------------------ */
  {
    key: 'dwr.index',
    name: 'DWR 接口总览页（暴露的 Java Bean 清单）',
    module: 'dispatch',
    path: 'dwr/index.html',
    method: 'GET',
    confidence: 'verified',
    markers: ['DWR Test Index', 'Classes known to DWR'],
    note:
      '实测：HTTP 200，358 字节，标题「DWR Test Index」。**这是本系统对外暴露的 DWR 接口总清单**：\n' +
      '```\n' +
      'messagePush  (net.theol.project.eduaffair.calendar.service.MessagePush)\n' +
      'pushStart    (net.theol.project.eduaffair.calendar.service.PushStart)\n' +
      '```\n' +
      '两个 Bean 都配有 DWR 自带测试页（见 dwr.testMessagePush / dwr.testPushStart），' +
      '测试页会**列出该 Bean 的全部方法与参数**。\n' +
      'DWR 调用方式：POST `/academic/dwr/call/plaincall/<bean>.<method>.dwr`，' +
      '请求体为 `callCount / c0-scriptName / c0-methodName / c0-id / batchId / page / scriptSessionId` 等键值对。'
  },
  {
    key: 'dwr.testMessagePush',
    name: 'DWR 测试页：messagePush（含方法清单）',
    module: 'dispatch',
    path: 'dwr/test/messagePush',
    method: 'GET',
    confidence: 'verified',
    markers: ['messagePush'],
    note:
      '实测：HTTP 200，11634 字节。已解析出的方法：\n' +
      '```\n' +
      'onPageLoad()                                      ← 唯一业务方法\n' +
      'wait(long,int) / wait(long) / wait()              ← java.lang.Object 继承\n' +
      'equals(Object) / toString() / hashCode() / getClass()\n' +
      'notify() / notifyAll()\n' +
      '```\n' +
      '除 `onPageLoad()` 外全部继承自 Object，**无业务价值**。'
  },
  {
    key: 'dwr.testPushStart',
    name: 'DWR 测试页：pushStart（含方法清单）',
    module: 'dispatch',
    path: 'dwr/test/pushStart',
    method: 'GET',
    confidence: 'verified',
    markers: ['pushStart'],
    note:
      '实测：HTTP 200，16512 字节。已解析出的方法：\n' +
      '```\n' +
      'sendMessageAuto(java.lang.Integer, ...)   ← 业务方法：服务端推送消息\n' +
      'execute(org.apache.struts.action.ActionMapping, ...)  ← Struts Action 入口\n' +
      'setServlet(ActionServlet) / getServlet()  ← Struts Action 生命周期\n' +
      'wait/equals/toString/hashCode/getClass/notify/notifyAll  ← Object 继承\n' +
      '```\n' +
      '⚠️ `pushStart` 是一个 **Struts Action**（不是普通 Service），被 DWR 一并暴露。'
  },
  {
    key: 'dwr.messagePush',
    name: 'DWR 接口描述（messagePush）',
    module: 'dispatch',
    path: 'dwr/interface/messagePush.js',
    method: 'GET',
    confidence: 'verified',
    note:
      '实测：HTTP 200，630 字节。已知方法：\n' +
      '  `messagePush.onPageLoad(callback)` —— 无业务参数，与后台交互以确保推送时能找到指定用户\n' +
      '`_path = "/academic/dwr"`。'
  },
  {
    key: 'dwr.pushStart',
    name: 'DWR 接口描述（pushStart）',
    module: 'dispatch',
    path: 'dwr/interface/pushStart.js',
    method: 'GET',
    confidence: 'likely',
    note:
      '由「我的信息」页发现：`pushStart.sendMessageAuto("接收人ID","推送内容")`，2 个参数。\n' +
      '⚠️ 这是**服务端向客户端推送消息**的接口，学生侧一般用不到。'
  },
  {
    key: 'dwr.engine',
    name: 'DWR 运行时',
    module: 'dispatch',
    path: 'dwr/engine.js',
    method: 'GET',
    confidence: 'verified',
    note:
      '实测：HTTP 200，78476 字节，UTF-8。用于确认 DWR 版本与调用协议。\n' +
      '注意：分析该文件会扫出大量 JS 表达式形式的伪地址（`dwr/MSXML2.DO`、`dwr/dom.do` 等），' +
      '提取逻辑已跳过纯 `.js` 文件。'
  },

  /* ======================== 个人信息与学籍 ======================== */
  {
    key: 'student.context',
    name: '学生上下文（内部学号 / 学年 / 学期）',
    module: 'student',
    path: 'student/currcourse/currcourse.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['studentid'],
    note:
      '实测：HTTP 200，32274 字节，GBK。**本学期的课程与课表总入口**：\n' +
      '  · 表格1 infolist_tab / 行 infolist_common：\n' +
      '    课程号|课程序号|课程名称|任课教师|学分|选课属性|考核方式|考试性质|\n' +
      '    是否缓考|上课时间、地点|教材|教学记录\n' +
      '  · 表格2：序号|名称|包含小节（课节分组）\n' +
      '  · 表单：GET，字段 year / term（学期切换）\n' +
      '⚠️ studentid 是教务内部 ID，不是学号（实测内部 100001 ↔ 学号 2023000001）'
  },
  {
    key: 'student.infoModify',
    name: '学籍信息（完整表单）',
    module: 'student',
    path: 'student/studentinfo/studentInfoModifyIndex.do',
    method: 'GET',
    confidence: 'verified',
    markers: ['学号', '姓名', '院系'],
    note:
      '实测：HTTP 200，31291 字节，UTF-8。**这是「个人信息」的真实来源**' +
      '（之前猜的 manager/studentinfo/studentInfo.do 是 404）。\n' +
      '含 5 张信息表：基本信息（学号/姓名/性别/出生日期/民族/政治面貌）、\n' +
      '院系专业班级（院系|专业|年级|学生类别|班级|校区）、联系方式（邮箱/手机/QQ/微信/地址）、\n' +
      '入学信息（入学日期/考生号/高考总分/中学）、毕业信息（毕业类型/学位）、\n' +
      '异动记录（infolist_hr_common：异动类型|异动日期|异动原因|异动前后班级）'
  },
  {
    key: 'student.photo',
    name: '学生照片（学籍页用）',
    module: 'student',
    path: 'manager/studentinfo/showStudentImage.jsp',
    method: 'GET',
    params: { id: '0.9', dataName: 'photo' },
    confidence: 'verified',
    binary: true,
    note:
      '实测：出现在学籍信息页 `<img src=".../showStudentImage.jsp?id=0.827...[&]dataName=photo" width="120" hight="150">`。' +
      'id 为随机小数，dataName=photo。二进制图片。'
  },
  {
    key: 'student.loadPhoto',
    name: '学生照片（我的信息页用）',
    module: 'student',
    path: 'student/studentinfo/loadphoto_added.jsdo',
    method: 'GET',
    // userid 是加密串（不能自己拼），只带能确定的固定参数；
    // 真实的 userid 依赖参数收割（harvest token）在第二轮生成带参候选
    params: { primary: 'userid', kind: 'student' },
    need: [],
    confidence: 'likely',
    binary: true,
    skipInBatch: true,
    note:
      '实测：出现在「我的信息」页 `<img src="/academic/student/studentinfo/loadphoto_added.jsdo?' +
      'primary=userid&kind=student&userid=AbCdEfGhIjKlMnOpQrStUv==" height="108">`。\n' +
      '⚠️ userid 是**加密串**（不是明文内部 ID），不能自己拼，必须从页面里取；' +
      '仅靠参数收割发现的带参地址才会被探测。'
  },
  {
    key: 'student.changeClass',
    name: '级联下拉：按专业取班级',
    module: 'student',
    path: 'student/studentinfo/changeClass.do',
    method: 'GET',
    params: { majorid: '' },
    confidence: 'likely',
    note:
      '由学籍信息页的 JS 发现（级联下拉用）。同类接口还有：\n' +
      '  `changeMajor.do?departmentid=`（按院系取专业）\n' +
      '  `changeDirection.do?majorid=`（按专业取方向）\n' +
      '  `changeArea.do?classid=`（按班级取校区）\n' +
      '  `changeLeanStyle.do?classid=`（按班级取学习形式）\n' +
      '  `changeOnylClass.do?majorid=`（按专业取班级，另一入口）\n' +
      '都带真实参数，需按参数调用，直接 GET 会返回「提示信息」错误页。'
  },
  {
    key: 'student.hanziToPinyin',
    name: '汉字转拼音',
    module: 'student',
    path: 'student/studentinfo/studentHanZI2PinYin.do',
    method: 'GET',
    confidence: 'likely',
    note: '由学籍信息页发现，用于自动生成姓名拼音（实测返回 200 但内容为空，需参数）'
  },
  {
    key: 'student.infoSave',
    name: '保存学籍信息',
    module: 'student',
    path: 'student/studentinfo/studentInfoModifyDo.do',
    method: 'POST',
    confidence: 'likely',
    mutating: true,
    note:
      '学籍信息页的表单提交目标。含约 100 个 hidden 字段（学号/姓名/院系/专业/班级/联系方式/入学/毕业…）。' +
      '**会修改学籍数据，默认不探测**。'
  },

  /* ============================ 成绩查询 ============================ */
  {
    key: 'score.all',
    name: '全部成绩（POST）',
    module: 'score',
    path: 'manager/score/studentOwnScore.do',
    method: 'POST',
    params: { year: '', term: '', prop: '', groupName: '', para: '0', sortColumn: '' },
    confidence: 'verified',
    markers: ['课程', '成绩', '学分'],
    note:
      '实测：HTTP 200，55554 字节，**UTF-8**（不是 GBK），标题「个人成绩查询」。\n' +
      '⚠️ 真实表头为：学年|学期|课程号|课程名|课序号|课组|**总评**|学分|学时|\n' +
      '   选课属性|备注|考试性质|**及格标志**|**过程性成绩**\n' +
      '表格 class=`datalist`（不是 infolist_tab）。\n' +
      '**注意**：总评列叫「总评」而非「成绩」，而「过程性成绩」里含「成绩」二字，' +
      '解析时若用「包含成绩」匹配列会**误命中过程性成绩**。\n' +
      '表单字段：year / term / prop / groupName / para / sortColumn / failedStatus / passedStatus / maxStatus'
  },
  {
    key: 'score.allGet',
    name: '全部成绩（GET 兜底）',
    module: 'score',
    path: 'manager/score/studentOwnScore.do',
    method: 'GET',
    params: { para: '0' },
    confidence: 'verified',
    markers: ['课程', '成绩'],
    note: '实测：HTTP 200，55554 字节，与 POST 完全等价'
  },
  {
    key: 'score.byTerm',
    name: '按学期成绩',
    module: 'score',
    path: 'manager/score/studentOwnScore.do',
    method: 'POST',
    params: { year: '{year}', term: '{term}', prop: '', groupName: '', para: '0', sortColumn: '' },
    need: ['year', 'term'],
    confidence: 'verified',
    markers: ['课程', '成绩'],
    note: '同一接口，year/term 留空为全部历史，带值则限定学期'
  },
  {
    key: 'score.yearTermList',
    name: '学年学期列表',
    module: 'score',
    path: 'calendar/showCalendarYearTerm.do',
    method: 'GET',
    params: { type: 'alias', yearId: '' },
    confidence: 'likely',
    note:
      '由成绩页发现：`/academic/calendar/showCalendarYearTerm.do?type=alias&yearId=`。' +
      '**这是此前一直缺失的「学年学期下拉数据源」**，可用于构建学期选择器。'
  },

  /* ============================ 课表查询 ============================ */
  {
    key: 'timetable.student',
    name: '学生课表（合并显示）',
    module: 'timetable',
    path: 'manager/coursearrange/showTimetable.do',
    method: 'GET',
    params: {
      id: '{studentId}',
      yearid: '{year}',
      termid: '{term}',
      timetableType: 'STUDENT',
      sectionType: 'COMBINE'
    },
    need: ['studentId', 'year', 'term'],
    confidence: 'verified',
    markers: ['timetable', '周', '节'],
    note:
      '实测：HTTP 200，8241 字节，GBK，命中 timetable/周/节。' +
      'id 用内部学生 ID（100001），不是学号。' +
      '**新发现的 sectionType=BASE**（见 timetable.studentBase），' +
      '出现在 currcourse.jsdo 的「个人课表」按钮里。'
  },
  {
    key: 'timetable.studentBase',
    name: '学生课表（BASE 基础课表）',
    module: 'timetable',
    path: 'manager/coursearrange/showTimetable.do',
    method: 'GET',
    params: {
      id: '{studentId}',
      yearid: '{year}',
      termid: '{term}',
      timetableType: 'STUDENT',
      sectionType: 'BASE'
    },
    need: ['studentId', 'year', 'term'],
    confidence: 'likely',
    markers: ['timetable', '周'],
    note:
      '由 currcourse.jsdo 的「个人课表」按钮 onclick 发现：' +
      '`showTimetable.do?id=100001&yearid=46&termid=2&timetableType=STUDENT&sectionType=BASE`。' +
      '这是教务处页面自己用的参数组合，**比 COMBINE / ALL 更可信**。'
  },
  {
    key: 'timetable.weekly',
    name: '周次课表',
    module: 'timetable',
    path: 'manager/coursearrange/studentWeeklyTimetable.do',
    method: 'GET',
    params: { yearid: '{year}' },
    need: ['year'],
    confidence: 'likely',
    markers: ['周'],
    note:
      '由 currcourse.jsdo 的「周次课表」按钮发现：' +
      '`manager/coursearrange/studentWeeklyTimetable.do?yearid=`'
  },
  {
    key: 'timetable.selfSchedule',
    name: '个人教学计划',
    module: 'timetable',
    path: 'manager/studyschedule/studentSelfSchedule.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['教学计划'],
    note:
      '实测：HTTP 200，4799 字节，标题「个人教学计划」。' +
      '含 4 个 scheduleJump.jsp 跳转链接（studentScheduleLineShow / ShowFrame / ' +
      'CourseTermOrder / ShowByTerm）。\n' +
      '⚠️ 链接里的 studentId 是**加密串**（如 `A1b2C3d4E5f6G7h8I9j0K1==`），' +
      '不是明文内部 ID，不能自己拼。'
  },
  {
    key: 'timetable.scheduleJump',
    name: '教学计划跳转器',
    module: 'timetable',
    path: 'manager/studyschedule/scheduleJump.jsp',
    method: 'GET',
    params: { link: 'studentScheduleShowByTerm.do', studentId: '{studentId}', classId: '' },
    confidence: 'likely',
    note: '统一跳转入口，link 参数指定目标 .do'
  },

  /* ============================ 考试安排 ============================ */
  {
    key: 'exam.arrange',
    name: '课程考试安排',
    module: 'exam',
    path: 'student/exam/index.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['考试'],
    note:
      '实测：HTTP 200，1819 字节，标题「学生考试安排」，**含真实考试数据**：\n' +
      '  课程号 | 课程名称 | 考试时间 | 考试地点 | 考试性质\n' +
      '  U010203TW04W5 | 复变函数与积分变换 | 2026-01-15 08:10--09:50 | 西-新A308 | 开班重修\n' +
      '表格 class=`infolist_tab`，数据行 class=`infolist_common`（**不是** infolist_hr_common）。\n' +
      '说明文字：只列出 7 天前开始的考试安排，更早的模糊显示。'
  },
  {
    key: 'exam.all',
    name: '查看全部考试安排（入口）',
    module: 'exam',
    path: 'manager/examstu/studentQueryAllExamPre.do',
    method: 'GET',
    confidence: 'likely',
    markers: ['考试'],
    note:
      '由 exam/index.jsdo 的表单发现：' +
      '`<form action="/academic/manager/examstu/studentQueryAllExamPre.do" method="post" target="_blank">`' +
      '（表单无字段，直接提交）。提交后进入下方 exam.allList 的带分页列表。'
  },
  {
    key: 'exam.allList',
    name: '全部考试安排（带分页参数，有数据）',
    module: 'exam',
    path: 'manager/examstu/studentQueryAllExam.do',
    method: 'GET',
    params: {
      pagingNumberPerVLID: '10',
      sortColumnVLID: 'executionPlan.course.pcourseid',
      pagingPageVLID: '1',
      sortDirectionVLID: '-1'
    },
    confidence: 'verified',
    markers: ['考试'],
    note:
      '实测：HTTP 200，**12761 字节，UTF-8，含真实数据**。这是深度递进探测从\n' +
      '`exam/index.jsdo` 样本里解析出来的完整考试列表接口，参数为 URP 通用分页协议：\n' +
      '  `pagingNumberPerVLID` 每页条数 / `pagingPageVLID` 页码\n' +
      '  `sortColumnVLID` 排序列 / `sortDirectionVLID` 排序方向（-1 降序）\n' +
      '**这是「考试安排」功能的数据主接口**，比 exam.arrange 更完整。'
  },

  /* ========================= 课程与教师查询 ========================= */
  {
    key: 'course.query',
    name: '课程查询（查询表单）',
    module: 'course',
    path: 'manager/querycourse/index.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['课程'],
    note:
      '实测：HTTP 200，34627 字节，标题「课程查询」。\n' +
      'POST 表单字段：keyvalue / terms / ctype / stusorts / depid / trgroup / keyword / ' +
      'roomsort / emanner / emode / status / orderby / orderseq\n' +
      '（terms、ctype、stusorts 为多选，depname/trgroupname/roomsortname/emannername/' +
      'emodename/statusname/keywordname 为 hidden 回显）'
  },
  {
    key: 'course.list',
    name: '课程查询结果列表',
    module: 'course',
    path: 'manager/querycourse/course_list.jsdo',
    method: 'GET',
    confidence: 'likely',
    markers: ['课程'],
    note: '由课程查询页的 JS 发现，为查询结果的数据接口'
  },
  {
    key: 'course.detail',
    name: '课程详情',
    module: 'course',
    path: 'manager/querycourse/course_detail.jsdo',
    method: 'GET',
    params: { cid: '' },
    need: ['cid'],
    confidence: 'verified',
    markers: ['课程', '学分'],
    note:
      'cid 是课程实例 ID，同一门课不同学期/班级各不相同，只能从 currcourse.jsdo 的' +
      '「教学记录」列链接里取（如 `course_detail.jsdo?cid=248686`）。' +
      '实测带真实 cid：HTTP 200，约 4.4KB；不带参数则是「提示信息」错误页。' +
      '探测工具会在运行期自动收割 cid 并带参补测（见 lib/harvest.mjs）。'
  },
  {
    key: 'course.export',
    name: '课程查询结果导出 Excel',
    module: 'course',
    path: 'manager/querycourse/excel_exp.jsdo',
    method: 'POST',
    params: {
      keyvalue: '', terms: '', ctype: '', stusorts: '', depid: '1', trgroup: '',
      keyword: '', roomsort: '', emanner: '', emode: '', status: '', orderby: '', orderseq: ''
    },
    confidence: 'likely',
    binary: true,
    skipInBatch: true,
    note:
      '页面 JS 中 `fp.action="excel_exp.jsdo"` 定义，**必须 POST 且携带完整查询条件**，' +
      '用 GET 直接请求会 404（探测工具因此默认跳过，避免每次运行都产生噪音）。' +
      '响应为 Excel 二进制。'
  },
  {
    key: 'course.teacherInfo',
    name: '教师信息',
    module: 'course',
    path: 'manager/teacherinfo/showTeacherInfoItem.do',
    method: 'GET',
    params: { userid: '' },
    need: ['userid'],
    confidence: 'verified',
    markers: ['姓名', '性别', '院系'],
    note:
      'userid 是教师明文 ID（与学生内部 ID 不同源），来源是 currcourse.jsdo ' +
      '课程列表的教师链接（如 `showTeacherInfoItem.do?userid=113679`），每位任课教师一条。' +
      '实测带真实 userid：HTTP 200，约 3.1KB，表头为 姓名|性别|院系|教研组。' +
      '不带参数则是「提示信息」错误页。'
  },

  /* ========================== 空教室查询 ========================== */
  {
    key: 'classroom.query',
    name: '教室排课查询（空教室表单）',
    module: 'classroom',
    path: 'teacher/teachresource/roomschedulequery.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['教室'],
    note:
      '实测：HTTP 200，5372 字节。**这是「空教室查询」的真实入口**' +
      '（之前猜的 manager/classroom/* 全是 404）。\n' +
      '表单 `form1` 为 `method="post"`、`action="roomschedulequery.jsdo"`，' +
      '下级下拉带 `onChange="this.form.submit()"`，因此**级联刷新是 POST 自身**，' +
      '不是 GET `?aid=`。表单字段：aid / buildingid / room / whichweek / week。\n' +
      '两个提交按钮：[确定] checkForm() → roomschedule.jsdo（按教室）、' +
      '[确定] checkWeekForm() → roomschedule_week.jsdo（按周）。'
  },
  {
    key: 'classroom.roomschedule',
    name: '教室排课（按教室）',
    module: 'classroom',
    path: 'teacher/teachresource/roomschedule.jsdo',
    method: 'POST',
    params: { aid: '', buildingid: '', room: '', whichweek: '', week: '' },
    confidence: 'verified',
    markers: ['教室时间占用维护'],
    note:
      '由 roomschedulequery.jsdo 的 `checkForm()` 提交（表单 method=post，action="./roomschedule.jsdo"）。\n' +
      '2026-11-04 带参实测（aid=353&buildingid=584&room=665&whichweek=10）：HTTP 200，约 92KB，' +
      '含 26 张 `infolists_tab`（每周一张 12 行 × 8 列）。\n' +
      '**但 26 周的所有格子都是空格，没有占用标记**——即使该教室在按周接口里明确有课。' +
      '即此页面不直出占用数据，不能用于取空闲教室。\n' +
      '不带参数时（2026-10-08）返回「提示信息」错误页 `For input string: ""`。'
  },
  {
    key: 'classroom.roomscheduleWeek',
    name: '教室排课（按周，整楼 × 一天）',
    module: 'classroom',
    path: 'teacher/teachresource/roomschedule_week.jsdo',
    method: 'POST',
    params: { aid: '', buildingid: '', room: '-1', whichweek: '', week: '' },
    confidence: 'verified',
    markers: ['教室占用维护', '占用情况(小节)'],
    note:
      '由 roomschedulequery.jsdo 的 `checkWeekForm()` 提交（表单 method=post，' +
      'action="./roomschedule_week.jsdo"）。`whichweek` 是**第几周**（1-26），' +
      '`week` 是**星期几**（1=周一 … 7=周日），两者不可互换，且都不能为 `-1`。\n' +
      '2026-11-04 实测：aid=353&buildingid=1688&room=-1&whichweek=10&week=1 → HTTP 200，318KB，' +
      '225 间教室 × 12 小节，耗时 6.9s（西区一号楼 61 间 / 85KB / 2.2s）。\n' +
      '结构：`table.infolist_tab`，每间教室一行（id="tr<教室ID>"），前 6 列为' +
      ' 教室名 / 座位数 / 排课容量 / 考试容量 / 桌椅类型 / 教室类型，' +
      '第 7 列内嵌 2 行表：第 1 行是第1-12节标签，第 2 行是占用标记。\n' +
      '空闲格 `<td valign="top">&nbsp;</td>`；占用格 `<a title="…"><font color="#FF0000">●</font></a>`，' +
      '颜色 #FF0000 课程 / #FF6600 课程考试 / #006600 其它。\n' +
      '`week=-1` 或 `whichweek=-1` 返回「提示信息」错误页，正文 `-2`。\n' +
      '**每次只返回一天**，整周需 week=1..7 共 7 次请求。'
  },

  /* ========================== 培养方案与学分 ========================== */
  //
  // 实测结论（2026-10-08，通过参数收割发现）：
  //   按 URP 命名推测的 manager/teachingplan/*、manager/studentcredit/* 全部 404。
  //   真实的培养方案数据在 manager/studyschedule/ 下，有四种展示形态，
  //   入口是 studentSelfSchedule.jsdo 页面里的 scheduleJump.jsp 跳转链接，
  //   **studentId 参数是加密串**，必须从页面里原样取出回传，不能自己拼。
  //   探测工具会自动完成「发现跳转 → 收割参数 → 探测真实页」的链式过程。
  {
    key: 'plan.help',
    name: '教学计划管理说明',
    module: 'plan',
    path: 'manager/studyschedule/help.htm',
    method: 'GET',
    confidence: 'verified',
    note: '实测：HTTP 200，2721 字节。是 moduleId=210 的默认落点（帮助页）'
  },
  {
    key: 'plan.showByTerm',
    name: '个人培养方案（按学期展示）',
    module: 'plan',
    path: 'manager/studyschedule/studentScheduleShowByTerm.do',
    method: 'GET',
    params: { z: 'z', studentId: '{encryptedStudentId}', classId: '' },
    need: ['encryptedStudentId'],
    confidence: 'verified',
    markers: ['教学计划', '课程', '学期'],
    note:
      '**这是培养方案的完整数据源**。实测 HTTP 200，约 442KB，结构分三部分：\n' +
      '  ① 头部信息表（datalist）：院系|专业|年级|学生类别|专业方向|最后修改时间|查询成绩时间\n' +
      '  ② 课组要求（<option>）：`课组名（年级） 选课属性：必修 学分要求=2.0 门数要求=2`\n' +
      '     —— 这就是毕业学分结构，如 专业必修课 17.0 学分/8 门、实践性教学环节 42.0/12\n' +
      '  ③ 课程明细（datalist，按学年学期分组）：\n' +
      '     课程号|课程名|考核方式|学分|学时|课程类别|课组|课组要求|专业方向\n' +
      '⚠️ studentId 是加密串，从 studentSelfSchedule.jsdo 的跳转链接里取。'
  },
  {
    key: 'plan.lineShow',
    name: '个人培养方案（按课程线展示）',
    module: 'plan',
    path: 'manager/studyschedule/studentScheduleLineShow.do',
    method: 'GET',
    params: { z: 'z', studentId: '{encryptedStudentId}', classId: '' },
    need: ['encryptedStudentId'],
    confidence: 'verified',
    markers: ['教学计划', '课程'],
    note: '实测 HTTP 200，约 312KB。与 showByTerm 数据同源，按课程线组织。'
  },
  {
    key: 'plan.courseTermOrder',
    name: '个人培养方案（按学期排序）',
    module: 'plan',
    path: 'manager/studyschedule/studentScheduleCourseTermOrder.do',
    method: 'GET',
    params: { z: 'z', studentId: '{encryptedStudentId}', classId: '' },
    need: ['encryptedStudentId'],
    confidence: 'verified',
    markers: ['教学计划', '课程'],
    note: '实测 HTTP 200，约 94KB。与 showByTerm 数据同源，按学期排序的精简视图。'
  },
  {
    key: 'plan.showFrame',
    name: '个人培养方案（框架页）',
    module: 'plan',
    path: 'manager/studyschedule/studentScheduleShowFrame.do',
    method: 'GET',
    params: { z: 'z', studentId: '{encryptedStudentId}', classId: '' },
    need: ['encryptedStudentId'],
    confidence: 'verified',
    markers: ['教学计划'],
    note: '实测 HTTP 200，约 10KB。frameset 容器，内嵌上述几种视图。'
  },

  /* ============================ 选课与退课 ============================ */
  {
    key: 'elective.entry',
    name: '选课入口',
    module: 'elective',
    path: 'student/selectcoursedb/jumppage.jsp',
    method: 'GET',
    confidence: 'verified',
    markers: ['选课'],
    note:
      '实测：HTTP 200，1320 字节。含表单 `GET /academic/manager/electcourse/elective.do`' +
      '（无字段，直接跳转）'
  },
  {
    key: 'elective.main',
    name: '选课主页面',
    module: 'elective',
    path: 'manager/electcourse/elective.do',
    method: 'GET',
    confidence: 'likely',
    markers: ['选课'],
    note: '**这是选课功能的真实入口**（之前猜的 manager/elective/courseList.do 是 404）'
  },
  {
    key: 'elective.group',
    name: '课节分组选择',
    module: 'elective',
    path: 'manager/electcourse/studentSelectCrgroupList.do',
    method: 'GET',
    params: { yearId: '{year}', termId: '{term}' },
    need: ['year', 'term'],
    confidence: 'likely',
    markers: ['课程'],
    note:
      '由 currcourse.jsdo 的「选择课节分组」按钮发现：' +
      '`studentSelectCrgroupList.do?yearId=46&termId=2`'
  },

  /* =========================== 通知与校历 =========================== */
  {
    key: 'notice.courseNotice',
    name: '课程通知',
    module: 'notice',
    path: 'manager/notify/notify_list_student.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['通知'],
    note:
      '实测：HTTP 200，1296 字节，标题「课程通知」。\n' +
      '表头：通知标题 | 课程号 | 课程名称 | 课序号 | 创建人 | 创建时间 | 修改时间\n' +
      '（当前无数据，说明该功能可用但暂无通知）'
  },
  {
    key: 'notice.calendar',
    name: '校历安排',
    module: 'notice',
    path: 'calendar/calendarViewList.do',
    method: 'GET',
    confidence: 'verified',
    markers: ['校历'],
    note:
      '实测：HTTP 200，5945 字节，**UTF-8**，标题「校历安排」。\n' +
      '含按钮 `outputWord(\'2026秋（26秋校历）校历\')`，即**导出 Word 校历**。' +
      '这可用于把课表的「第 N 周」换算成真实日期。'
  },
  {
    key: 'notice.calendarInfo',
    name: '校历信息',
    module: 'notice',
    path: 'calendarinfo/viewCalendarInfo.do',
    method: 'GET',
    confidence: 'likely',
    markers: ['校历'],
    note: '由 showHeader.do 发现'
  },
  {
    key: 'notice.calendarDetail',
    name: '校历详情（按日期）',
    module: 'notice',
    path: 'calendar/findSchoolCalendarDetailByDate.do',
    method: 'GET',
    params: { dateVal: '' },
    confidence: 'likely',
    markers: ['校历'],
    note:
      '由 `calendar/calendarViewList.do` 发现，写法是相对地址 `findSchoolCalendarDetailByDate.do?dateVal=`，' +
      '**必须相对页面所在目录解析**，真实路径是 `calendar/findSchoolCalendarDetailByDate.do`。\n' +
      '（早期当成相对基址解析成 `findSchoolCalendarDetailByDate.do` 导致 404，已修正）'
  },
  {
    key: 'notice.markRead',
    name: '标记消息已读',
    module: 'notice',
    path: 'calendarinfo/updateUserMessageBrowseStatus.do',
    method: 'POST',
    params: { mid: '1' },
    confidence: 'verified',
    mutating: true,
    note:
      '由门户页内联 JS 发现：`$.ajax({type:"post", url: ...+"/calendarinfo/updateUserMessageBrowseStatus.do?mid="+mid})`。' +
      '**必须用 POST**（用 GET 探测返回「提示信息」错误页）。mid 为消息 ID。会改变已读状态。'
  },

  /* ============================ 教学评价 ============================ */
  {
    key: 'eva.result',
    name: '教学评价结果',
    module: 'eva',
    path: 'eva/index/resultlist.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['评价'],
    note: '实测：HTTP 200，4874 字节'
  },
  {
    key: 'eva.report',
    name: '教学报告（学生视角）',
    module: 'eva',
    path: 'teacher/teachingtask/schoolTeachingReportIndexStudent.do',
    method: 'GET',
    params: { scoreid: '' },
    need: ['scoreid'],
    confidence: 'verified',
    markers: ['教学', '课程'],
    note:
      'scoreid 是成绩记录 ID，来源是 currcourse.jsdo 课程列表的「教学记录」列链接' +
      '（如 `?scoreid=233727590`），每门课一条。' +
      '实测带真实 scoreid：HTTP 200，约 4.9KB。不带参数则是「提示信息」错误页。'
  },

  /* ========================== 系统与账户 ========================== */
  {
    key: 'sysmgr.password',
    name: '修改密码页',
    module: 'sysmgr',
    path: 'sysmgr/user_password.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['密码'],
    note:
      '实测：HTTP 200，6638 字节，标题「修改密码.」。\n' +
      'POST 表单 → `./modifypasswd_user.jsdo`，字段：oldpasswd / newpasswd / confirmedpasswd / ff / gotoUrl(hidden) / code(hidden)'
  },
  {
    key: 'sysmgr.passwordSave',
    name: '提交修改密码',
    module: 'sysmgr',
    path: 'sysmgr/modifypasswd_user.jsdo',
    method: 'POST',
    confidence: 'likely',
    mutating: true,
    note: '**会真实修改密码，默认不探测**'
  },
  {
    key: 'student.remajor',
    name: '转专业报名设置',
    module: 'student',
    path: 'student/remajor/signupsetting.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['专业'],
    note: '实测：HTTP 200，6441 字节'
  },
  {
    key: 'student.skilltest',
    name: '技能测试',
    module: 'student',
    path: 'student/skilltest/skilltest.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['测试'],
    note: '实测：HTTP 200，3691 字节'
  },
  {
    key: 'student.skilltestScore',
    name: '技能测试成绩',
    module: 'student',
    path: 'student/queryscore/skilltestscore.jsdo',
    method: 'GET',
    confidence: 'verified',
    markers: ['成绩'],
    note: '实测：HTTP 200，1127 字节'
  }
];

/* ------------------------------------------------------------------ *
 * 已排除的接口（实测 404，通用 URP 命名在此版本不适用）
 *
 * 成绩：manager/score/studentCreditScore.do、studentScoreStatistics.jsdo、
 *       studentScoreQuery.do、studentScoreRank.do、studentOwnScorePrint.do
 * 学籍：manager/studentinfo/studentInfo.do、studentBaseInfo.do、studentInfo.jsdo、
 *       studentPhoto.do、studentStatus.do
 * 考试：manager/exam/examArrange.do、examArrange.jsdo、studentExamArrange.do、
 *       studentOwnExamArrange.do、examScore.do
 * 培养：manager/teachingplan/teachingPlan.do、studentTeachingPlan.do、
 *       manager/studentcredit/studentCredit.do、studentCreditStat.do、
 *       manager/coursetotal/studentCourseTotal.do
 * 选课：manager/elective/courseList.do、selectedCourse.do、student/elective/elective.jsdo
 * 教室：manager/classroom/freeClassroom.do、classroomQuery.do、classroomArrange.jsdo
 * 通知：manager/message/messageList.do、queryMessage.do、student/message/message.jsdo、
 *       manager/notice/noticeList.do、manager/calendar/weekInfo.do
 * 课表：manager/coursearrange/courseArrange.jsdo、termList.jsdo
 * 框架：welcome.jsp、index.jsp（会 302 回登录页）
 *
 * 教训：**不要按命名规律猜 URP 路径**。真实地址一律靠
 * accessModule.do?moduleId=X 调度 + 门户页/样本的结构化分析来发现。
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * DWR 探索结论（2026-10-08）：确认无额外业务价值，不必再探索
 *
 * 系统使用 DWR（Direct Web Remoting），`dwr/index.html` 列出了**全部**对外暴露的 Bean：
 *   · messagePush (net.theol.project.eduaffair.calendar.service.MessagePush)
 *   · pushStart   (net.theol.project.eduaffair.calendar.service.PushStart)
 *
 * 逐个用 DWR 自带测试页枚举了方法（dwr/test/messagePush、dwr/test/pushStart）：
 *   · messagePush 只有 onPageLoad() 一个业务方法（用于注册推送会话）
 *   · pushStart   是 Struts Action，业务方法只有 sendMessageAuto(Integer, String)
 * 其余方法全部继承自 java.lang.Object（wait/equals/toString/hashCode/getClass/notify/…）。
 *
 * 结论：**DWR 只用于站内消息推送，不暴露成绩/课表/学籍等业务数据**。
 * 前端功能仍需走 `.do` / `.jsdo` 页面接口，不必再花时间在 DWR 上。
 * ------------------------------------------------------------------ */

/** 可参与批量探测的接口（排除需单独处理与会改变数据的） */
export function batchEndpoints({ allowMutating = false } = {}) {
  return CATALOG.filter((ep) => {
    if (ep.skipInBatch) return false;
    if (ep.key === 'auth.logout') return false;
    if (ep.mutating && !allowMutating) return false;
    return true;
  });
}

/** 按 key 精确查找 */
export function findEndpoint(key) {
  return CATALOG.find((ep) => ep.key === key) || null;
}

/**
 * 按 --only 过滤器筛选
 * @param {EndpointSpec[]} endpoints
 * @param {string[]} only 支持精确 key 与前缀通配（如 'score.*'）
 */
export function filterEndpoints(endpoints, only = []) {
  if (!only || only.length === 0) return endpoints;
  const patterns = only.map((raw) => {
    const trimmed = raw.trim();
    if (trimmed.endsWith('*')) {
      const prefix = trimmed.slice(0, -1);
      return (key) => key.startsWith(prefix);
    }
    if (trimmed.endsWith('.')) {
      return (key) => key.startsWith(trimmed);
    }
    return (key) => key === trimmed;
  });
  return endpoints.filter((ep) => patterns.some((match) => match(ep.key)));
}

/** 用学生上下文替换路径与参数中的占位符 */
export function resolvePlaceholders(value, context = {}) {
  const table = {
    ...context,
    timestamp: Date.now(),
    randomString: makePlaceholderRandomString()
  };
  return String(value).replace(/\{(\w+)\}/g, (whole, name) =>
    Object.prototype.hasOwnProperty.call(table, name) ? String(table[name] ?? '') : whole
  );
}

/** 生成 randomString 占位符取值（格式与 URP 一致） */
function makePlaceholderRandomString() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp =
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let random = '';
  for (let i = 0; i < 6; i += 1) random += chars[Math.floor(Math.random() * chars.length)];
  return stamp + random;
}

/** 判断接口依赖的上下文是否齐备 */
export function missingContext(endpoint, context = {}) {
  return (endpoint.need || []).filter((field) => !context[field]);
}

/** 生成接口统计摘要 */
export function summarizeCatalog(endpoints = CATALOG) {
  const byModule = {};
  const byConfidence = {};
  for (const ep of endpoints) {
    byModule[ep.module] = (byModule[ep.module] || 0) + 1;
    byConfidence[ep.confidence] = (byConfidence[ep.confidence] || 0) + 1;
  }
  return { total: endpoints.length, byModule, byConfidence };
}
