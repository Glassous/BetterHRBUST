package campus

import (
	"strings"
	"testing"
)

// 这些用例的 HTML 是按教务真实页面结构裁剪出来的最小样本。
// 断言的不是"能解析出东西"，而是**每个字段的位置与 JS 解析器一致**：
// 一旦有人改了 Go 侧的取值顺序，图形界面与终端写出的缓存就会出现两种形态。

const scoresHTML = `<html><body>
<select name="year">
  <option value="">全部</option>
  <option value="2025">2025-2026</option>
</select>
<table class="datalist">
  <tr><th>学年</th><th>学期</th><th>课程号</th><th>课程名</th><th>课序号</th><th>课组</th>
      <th>总评</th><th>学分</th><th>学时</th><th>选课属性</th><th>备注</th><th>考试性质</th><th>及格标志</th></tr>
  <tr><td>2025-2026</td><td>1</td><td>A001</td><td>高等数学</td><td>01</td><td>学科基础</td>
      <td>85</td><td>5</td><td>80</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
  <tr><td>2025-2026</td><td>1</td><td>A002</td><td>体育</td><td>02</td><td></td>
      <td>合格</td><td>1</td><td>32</td><td>必修</td><td></td><td>考查</td><td></td></tr>
  <tr><td>2025-2026</td><td>1</td><td>A003</td><td>大学物理</td><td>03</td><td></td>
      <td>缓考</td><td>3</td><td>48</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
</table>
</body></html>`

func TestParseScoresMatchesFrontendFields(t *testing.T) {
	result := parseScores(scoresHTML)

	if len(result.YearOptions) != 1 || result.YearOptions[0].Value != "2025" {
		t.Fatalf("学年选项应只保留非空 value：%+v", result.YearOptions)
	}
	if len(result.Scores) != 3 {
		t.Fatalf("应解析出 3 条成绩，实际 %d", len(result.Scores))
	}

	first := result.Scores[0]
	if first.CourseName != "高等数学" || first.Credit != 5 || first.Hours != 80 {
		t.Fatalf("首条成绩字段错位：%+v", first)
	}
	if !first.Passed || first.Pending {
		t.Fatalf("85 分应判定为通过且非待定：%+v", first)
	}

	second := result.Scores[1]
	if !second.Passed || second.Pending {
		t.Fatalf("等级制「合格」应判定为通过：%+v", second)
	}

	third := result.Scores[2]
	if third.Passed || !third.Pending {
		t.Fatalf("「缓考」应判定为待定：%+v", third)
	}
}

const timetableHTML = `<html><body>
<table id="timetable">
  <tr class="infolist_hr_common"><th>第一大节</th>
    <td id="1-4367">&lt;&lt;高等数学&gt;&gt;;01<br>教1-101<br>张三<br>1-16周<br>理论学时</td>
    <td id="2-5001">&lt;&lt;体育&gt;&gt;;02<br>操场<br>3-5,9-16周<br>实践学时</td>
    <td>&nbsp;</td>
  </tr>
</table>
<table id="noArrangement">
  <tr><th>课程号</th><th>课程名</th></tr>
  <tr><td>C1</td><td>军训</td><td>01</td><td>李四</td><td>合班</td><td>1-2周</td><td>周一</td><td>操场</td></tr>
</table>
</body></html>`

func TestParseTimetableAnchorsOnWeeksLine(t *testing.T) {
	timetable := parseTimetable(timetableHTML)
	if len(timetable.Cells) != 2 {
		t.Fatalf("空单元格必须跳过，应有 2 个格子，实际 %d", len(timetable.Cells))
	}

	full := timetable.Cells[0]
	if full.CourseName != "高等数学" || full.CourseSeq != "01" {
		t.Fatalf("课程名与课序号解析错误：%+v", full)
	}
	if full.Location != "教1-101" || full.Teacher != "张三" || full.Weeks != "1-16周" ||
		full.HoursType != "理论学时" {
		t.Fatalf("常规五行单元格字段错位：%+v", full)
	}
	if full.Day != 1 || full.SectionIndex != 1 || full.SectionLabel != "第一大节" {
		t.Fatalf("位置字段错误：%+v", full)
	}

	// 没有教师行：地点之后直接是周次，字段整体前移也不能错位
	noTeacher := timetable.Cells[1]
	if noTeacher.Location != "操场" || noTeacher.Teacher != "" {
		t.Fatalf("缺教师行时地点/教师解析错误：%+v", noTeacher)
	}
	if noTeacher.Weeks != "3-5,9-16周" || noTeacher.HoursType != "实践学时" {
		t.Fatalf("缺教师行时周次/学时解析错误：%+v", noTeacher)
	}

	if len(timetable.Unarranged) != 1 || timetable.Unarranged[0].CourseName != "军训" {
		t.Fatalf("未排课课程解析错误：%+v", timetable.Unarranged)
	}
	if timetable.Unarranged[0].Day != "周一" || timetable.Unarranged[0].Location != "操场" {
		t.Fatalf("未排课课程字段错位：%+v", timetable.Unarranged[0])
	}
}

const profileHTML = `<html><body>
<img src="/academic/loadphoto_added.jsdo?id=1">
<table class="form">
  <tr><th>用户名</th><td>2304060322</td><th>真实姓名</th><td>张三</td></tr>
  <tr><th>所在院系</th><td>计算机科学与技术学院</td><th>专业</th><td>软件工程</td></tr>
  <tr><th>专业方向</th><td>无</td><th>年级</th><td>2024</td></tr>
  <tr><th>联系电话</th><td>13800000000</td><th>证件号码</th><td>110101199001011234</td></tr>
</table>
<table class="datalist">
  <tr><th>异动类型</th><th>日期</th><th>原因</th><th>备注</th></tr>
  <tr class="infolist_common"><td>休学</td><td>2025-01-01</td><td>病假</td><td>已复学</td></tr>
</table>
</body></html>`

func TestParsePersonalInfoSkipsHeaderRow(t *testing.T) {
	profile := parsePersonalInfo(profileHTML)

	if profile.StudentNumber != "2304060322" || profile.RealName != "张三" {
		t.Fatalf("基础字段解析错误：%+v", profile)
	}
	if profile.Major != "软件工程" || profile.Direction != "无" {
		t.Fatalf("专业与方向必须分开：%+v", profile)
	}
	if profile.PhotoURL == "" {
		t.Fatal("头像地址未取到")
	}
	if len(profile.Changes) != 1 {
		t.Fatalf("表头行不应被当成异动记录，实际 %d 条：%+v", len(profile.Changes), profile.Changes)
	}
	if profile.Changes[0].Type != "休学" || profile.Changes[0].Remark != "已复学" {
		t.Fatalf("异动字段错位：%+v", profile.Changes[0])
	}
}

const contextHTML = `<html><body>
<a href="javascript:goto('studentid=12345&year=2025')">x</a>
<select name="term">
  <option value="1">春</option>
  <option value="2" selected>秋</option>
</select>
<table class="infolist_tab">
  <tr class="infolist_common">
    <td>C1</td><td>01</td><td>高等数学</td><td>张三</td><td>5</td><td>必修</td>
    <td>考试</td><td>闭卷</td><td>-</td><td>周一 1-2 节</td>
  </tr>
</table>
</body></html>`

func TestParseStudentContext(t *testing.T) {
	context := parseStudentContext(contextHTML)
	if context.StudentID != "12345" || context.Year != "2025" {
		t.Fatalf("内部学号与学年解析错误：%+v", context)
	}
	if context.Term != "2" {
		t.Fatalf("学期应取 selected 选项，实际 %q", context.Term)
	}
	if len(context.Courses) != 1 {
		t.Fatalf("课程名录解析错误：%+v", context.Courses)
	}
	course := context.Courses[0]
	if course.CourseName != "高等数学" || course.Credit != 5 || course.TimeAndPlace != "周一 1-2 节" {
		t.Fatalf("课程名录字段错位：%+v", course)
	}
}

const examsHTML = `<html><body>
<table class="datalist">
  <tr><th>课程号</th><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>考试性质</th></tr>
  <tr><td>A001</td><td>高等数学</td><td>2026-01-05 09:00</td><td>教1-101</td><td>正常考试</td></tr>
</table>
</body></html>`

func TestParseExamsUsesHeaderColumns(t *testing.T) {
	exams := parseExams(examsHTML)
	if len(exams) != 1 {
		t.Fatalf("应解析出 1 条考试，实际 %d", len(exams))
	}
	exam := exams[0]
	if exam.CourseName != "高等数学" || exam.Time != "2026-01-05 09:00" ||
		exam.Location != "教1-101" || exam.Property != "正常考试" {
		t.Fatalf("考试字段错位：%+v", exam)
	}
}

const planHTML = `<html><body>
<select id="syt12">
  <option value="1">学科基础课程（2023） 选课属性：必修 学分要求=26.5 门数要求=8</option>
<option value="2">通识课程 选课属性：限选 学分要求=4 门数要求=2</option>
</select>
<table id="output_ctx">
  <tr><th>课组：学科基础课程（2023）</th></tr>
  <tr><td>1</td><td>A001</td><td>高等数学</td><td>5</td><td>80</td><td>必修</td><td>-</td></tr>
  <tr><th>课组：通识课程</th></tr>
  <tr><td>2</td><td>B001</td><td>艺术鉴赏</td><td>2</td><td>32</td><td>限选</td><td>-</td></tr>
</table>
<div>毕业总学分：170</div>
</body></html>`

func TestParseCurriculumPlan(t *testing.T) {
	plan := parseCurriculumPlan(planHTML)
	if len(plan.Groups) != 2 {
		t.Fatalf("应解析出 2 个课组，实际 %d：%+v", len(plan.Groups), plan.Groups)
	}
	first := plan.Groups[0]
	if first.Name != "学科基础课程（2023）" || first.RequiredCredits != 26.5 || first.RequiredCourses != 8 {
		t.Fatalf("课组学分要求解析错误：%+v", first)
	}
	if len(first.Courses) != 1 || first.Courses[0].Name != "高等数学" {
		t.Fatalf("课组课程未挂到正确的课组：%+v", first.Courses)
	}
	if len(plan.Groups[1].Courses) != 1 || plan.Groups[1].Courses[0].Name != "艺术鉴赏" {
		t.Fatalf("第二个课组的课程错位：%+v", plan.Groups[1])
	}
	if plan.TotalRequiredCredits == nil || *plan.TotalRequiredCredits != 170 {
		t.Fatalf("总学分要求解析错误：%v", plan.TotalRequiredCredits)
	}
}

const calendarHTML = `<html><body>
<div class="curweek">2026 秋 第 <strong>11</strong> 周</div>
<div id="textwrapper">
  <p>关于选课的通知</p>
  <div>第二条公告</div>
</div>
</body></html>`

func TestParseCalendarInfo(t *testing.T) {
	info := parseCalendarInfo(calendarHTML, nil, 0, false)
	if info.CurrentWeek != 11 {
		t.Fatalf("当前周次解析错误：%d", info.CurrentWeek)
	}
	if info.SemesterName != "2026 秋" {
		t.Fatalf("学期名解析错误：%q", info.SemesterName)
	}
	if len(info.Notices) != 2 {
		t.Fatalf("公告条数错误：%d", len(info.Notices))
	}
	if info.Notices[0].Date != "" {
		t.Fatalf("公告日期必须留空（源页面没有该信息）：%q", info.Notices[0].Date)
	}

	// 有官方校历时以校历为准：学期名、总周数、当前教学周都由它决定
	semester := &SemesterInfo{Label: "2026-2027 秋", TeachingWeeks: 19}
	withCalendar := parseCalendarInfo(calendarHTML, semester, 9, true)
	if withCalendar.SemesterName != "2026-2027 秋" || withCalendar.CurrentWeek != 9 {
		t.Fatalf("校历应覆盖页面解析结果：%+v", withCalendar)
	}
	if withCalendar.TeachingWeeks == nil || *withCalendar.TeachingWeeks != 19 {
		t.Fatalf("总周数应取校历：%v", withCalendar.TeachingWeeks)
	}
}

func TestLoginPageAndFailureReason(t *testing.T) {
	loginPage := `<html><body><form action="/academic/j_acegi_security_check">
<input name="j_username"><input name="j_captcha"></form></body></html>`
	if !isLoginPage(loginPage) {
		t.Fatal("应识别为登录页")
	}
	if isLoginPage(`<html><body>成绩单</body></html>`) {
		t.Fatal("正常页面不应被识别成登录页")
	}

	reason := loginFailureReason(`<html><body><script>var t = "密码错误";</script>
<div>验证码错误，请重试</div></body></html>`)
	if reason != "验证码错误或已过期，请刷新重试" {
		t.Fatalf("失败原因解析错误：%q", reason)
	}
}

func TestSplitCourseCellFieldsWithoutWeeksLine(t *testing.T) {
	// 完全认不出周次行时按常规五行位置兜底
	fields := splitCourseCellFields([]string{"课程", "教1-101", "张三", "说明", "理论学时"})
	if fields.Location != "教1-101" || fields.Teacher != "张三" ||
		fields.Weeks != "说明" || fields.HoursType != "理论学时" {
		t.Fatalf("兜底位置读取错误：%+v", fields)
	}
}

func TestDecodeEntitiesLoopsUntilStable(t *testing.T) {
	if got := decodeEntities("&amp;lt;数学&amp;gt;"); got != "<数学>" {
		t.Fatalf("双重编码应被解开，实际 %q", got)
	}
	if got := decodeEntities("&#20320;&#22909;"); got != "你好" {
		t.Fatalf("数字实体解码错误：%q", got)
	}
	if got := decodeEntities(strings.Repeat("A", 10) + "&#x4E2D;"); got != strings.Repeat("A", 10)+"中" {
		t.Fatalf("十六进制实体解码错误：%q", got)
	}
}

func TestJSNumberParsing(t *testing.T) {
	cases := map[string]float64{
		"5":     5,
		"3.5学分": 3.5,
		"16学时":  16,
		" 2.0 ": 2,
		"abc":   0,
		"-1.5周": -1.5,
		"0x10":  0,
		".5":    0.5,
	}
	for input, want := range cases {
		if got := jsParseFloat(input); got != want {
			t.Fatalf("parseFloat(%q) = %v，期望 %v", input, got, want)
		}
	}
	if got := jsParseInt("26 周"); got != 26 {
		t.Fatalf("parseInt 取前缀失败：%d", got)
	}
	if _, ok := jsParseFloatOK("暂无"); ok {
		t.Fatal("非数字应返回未解析")
	}
}
