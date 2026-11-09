package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.ClassroomOccupancy
import com.glassous.betterhrbust.core.model.ExamItem
import com.glassous.betterhrbust.core.model.ScoreItem
import com.glassous.betterhrbust.core.model.TimetableCell
import com.glassous.betterhrbust.core.model.TimetableResult
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.core.parser.ClassroomScheduleLogic
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AcademicParsersTest {

    private fun loadFixture(filename: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$filename")
            ?: File("src/test/resources/fixtures/$filename").inputStream()
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun curriculumUsesExplicitTotalAndDeduplicatesGroupOptions() {
        val html = """<p>方案总学分：158.5</p><select id="syt12">
          <option value="1">基础课程 选课属性：必修 学分要求=20 门数要求=5</option>
          <option value="1">基础课程 选课属性：必修 学分要求=20 门数要求=5</option>
        </select>"""
        val plan = AcademicParsers.parseCurriculumPlan(html)
        assertEquals(158.5, plan.totalRequiredCredits!!, 0.001)
        assertEquals(1, plan.groups.size)
    }

    @Test
    fun loginLabelsDoNotOverrideTheActualFailure() {
        val labels = "<form><label>教务密码</label><label>安全验证码</label></form>"
        assertEquals("学号或密码错误", AcademicParsers.parseLoginFailureReason(labels + "<p>密码输入错误</p>"))
        assertEquals("账号已被系统锁定，请稍后再试", AcademicParsers.parseLoginFailureReason(labels + "<p>账号已被系统锁定</p>"))
        assertEquals("该学号不存在", AcademicParsers.parseLoginFailureReason(labels + "<p>用户不存在</p>"))
        assertEquals("登录失败，请检查学号与密码", AcademicParsers.parseLoginFailureReason(labels))
        assertEquals("学号或密码错误", AcademicParsers.parseLoginFailureReason(labels + "<p>用户名或密码错误</p>"))
        assertEquals("登录失败，请检查学号与密码", AcademicParsers.parseLoginFailureReason(labels + "<script>const msg='验证码错误';</script>"))
    }

    @Test
    fun testStripHtmlComments() {
        val rawHtml = "<table><tr><th>课程号</th><!--<th>考试方式</th>--><th>课程名</th></tr><tr><td>U001</td><!--<td></td>--><td>离散数学</td></tr></table>"
        val clean = AcademicParsers.stripHtmlComments(rawHtml)
        assertFalse(clean.contains("考试方式"))
        assertTrue(clean.contains("离散数学"))
        assertTrue(clean.contains("U001"))
    }

    @Test
    fun testDecodeEntities() {
        val encoded = "&amp;lt;&amp;lt;高等数学&amp;gt;&amp;gt;&nbsp;&copy;&nbsp;&#65;&#x42;"
        val decoded = AcademicParsers.decodeEntities(encoded)
        assertTrue(decoded.contains("<<高等数学>>"))
        assertTrue(decoded.contains("©"))
        assertTrue(decoded.contains("AB"))
    }

    @Test
    fun testIsLoginPage() {
        val loginHtml = "<html><form action='j_acegi_security_check' method='post'><input name='j_captcha'></form></html>"
        assertTrue(AcademicParsers.isLoginPage(loginHtml))

        val normalHtml = "<html><head><title>个人信息</title></head><body><h1>学生档案</h1></body></html>"
        assertFalse(AcademicParsers.isLoginPage(normalHtml))
    }

    @Test
    fun testParseLoginFailureReason() {
        assertEquals("验证码错误或已过期，请刷新重试", AcademicParsers.parseLoginFailureReason("验证码错误，请重新输入"))
        assertEquals("学号或密码错误", AcademicParsers.parseLoginFailureReason("Reason: badCredentials"))
        assertEquals("学号或密码错误", AcademicParsers.parseLoginFailureReason("密码输入错误"))
        assertEquals("该学号不存在", AcademicParsers.parseLoginFailureReason("用户不存在或状态异常"))
        assertEquals("账号已被系统锁定，请稍后再试", AcademicParsers.parseLoginFailureReason("账号已被系统锁定"))
        assertEquals("登录失败，请检查学号与密码", AcademicParsers.parseLoginFailureReason("未知系统异常"))
    }

    @Test
    fun testParseStudentContext() {
        val html = loadFixture("0022-student.context.html")
        val context = AcademicParsers.parseStudentContext(html)

        // Year must be 46 in sample
        assertEquals("46", context.year)
        assertEquals("2", context.term)
        assertTrue("Course list should not be empty", context.courses.isNotEmpty())

        val firstCourse = context.courses[0]
        assertTrue(firstCourse.courseId.isNotEmpty())
        assertTrue(firstCourse.courseName.isNotEmpty())
        assertTrue(firstCourse.credit > 0.0)
    }

    @Test
    fun testParsePersonalInfo() {
        val html = loadFixture("0015-student.myInfo.html")
        val info = AcademicParsers.parsePersonalInfo(html)

        assertTrue("Student number should be extracted", info.studentNumber.isNotEmpty())
        assertTrue("College should be extracted", info.college.isNotEmpty())
        assertTrue("Major should be extracted", info.major.isNotEmpty())
        assertEquals("在籍（注册）", info.status)
    }

    @Test
    fun testParseTimetable() {
        val html = loadFixture("0032-timetable.student.html")
        val timetable = AcademicParsers.parseTimetable(html)

        assertTrue("Cells should not be empty", timetable.cells.isNotEmpty())
        for (cell in timetable.cells) {
            assertTrue("Day must be between 1 and 7", cell.day in 1..7)
            assertTrue("Section index must be between 1 and 6", cell.sectionIndex in 1..6)
            assertTrue("Course name must not be blank", cell.courseName.isNotBlank())
        }
    }

    /**
     * 单元格行数并不固定：缺教师行 / 周次后多一行说明时，
     * 按固定下标读取会把学时类型当成周次，已结束的课程因此仍显示为正常颜色。
     */
    @Test
    fun splitCellFieldsHandlesVariableLineCount() {
        val standard = AcademicParsers.splitCellFields(
            listOf("<<算法设计与分析>>;2", "西-新A306", "张淑丽B", "4-10周", "讲课学时")
        )
        assertEquals("西-新A306", standard.location)
        assertEquals("张淑丽B", standard.teacher)
        assertEquals("4-10周", standard.weeks)
        assertEquals("讲课学时", standard.hoursType)

        // 缺教师行（军训 / 体育等实践课）：字段整体前移一行
        val noTeacher = AcademicParsers.splitCellFields(
            listOf("<<军事技能训练>>", "西-区田径场", "4-5周", "实践学时")
        )
        assertEquals("西-区田径场", noTeacher.location)
        assertEquals("", noTeacher.teacher)
        assertEquals("4-5周", noTeacher.weeks)
        assertEquals("实践学时", noTeacher.hoursType)

        // 地点与教师都没有
        val onlyWeeks = AcademicParsers.splitCellFields(listOf("<<军事技能训练>>", "4-5周", "实践学时"))
        assertEquals("", onlyWeeks.teacher)
        assertEquals("4-5周", onlyWeeks.weeks)

        // 周次后多一行说明（真实数据：复变函数与积分变换 → 一上午 → 讲课学时）
        val extraLine = AcademicParsers.splitCellFields(
            listOf("<<复变函数与积分变换>>;334", "西-新B502", "张三", "1-6周", "一上午", "讲课学时")
        )
        assertEquals("1-6周", extraLine.weeks)
        assertEquals("讲课学时", extraLine.hoursType)
    }

    private fun timetableCellHtml(cellInner: String): String = """
        <table id="timetable" class="infolist_hr">
        <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
        <tr class="infolist_hr_common"><th>第二大节<br></th>
        <td id="1-4368">&nbsp;</td><td id="2-4368">&nbsp;</td><td id="3-4368">&nbsp;</td>
        <td id="4-4368">&nbsp;</td><td id="5-4368">&nbsp;</td><td id="6-4368">$cellInner</td><td id="7-4368">&nbsp;</td></tr>
        </table>
    """.trimIndent()

    /** 军训这类没有教师行的课程，周次必须解析成真实区间，才能在后续周次正确置灰 */
    @Test
    fun timetableWithoutTeacherKeepsRealWeekRange() {
        val html = timetableCellHtml("&lt;&lt;军事技能训练&gt;&gt;<br>西-区田径场<br>4-5周<br>实践学时")
        val cell = AcademicParsers.parseTimetable(html).cells.single()

        assertEquals(6, cell.day)
        assertEquals("", cell.teacher)
        assertEquals("4-5周", cell.weeks)
        assertEquals("实践学时", cell.hoursType)
        assertTrue("第 5 周仍有课", AcademicParsers.isCourseActiveInWeek(cell.weeks, 5))
        assertFalse("第 11 周不应再显示 4-5 周的课程", AcademicParsers.isCourseActiveInWeek(cell.weeks, 11))
    }

    /** 旧版本写入的缓存按 rawLines 重算即可修复，无需重新联网 */
    @Test
    fun cachedTimetableIsRepairedFromRawLines() {
        val legacy = TimetableResult(
            cells = listOf(
                TimetableCell(
                    id = "6-4368",
                    courseId = "4368",
                    day = 6,
                    sectionIndex = 2,
                    sectionLabel = "第二大节",
                    courseName = "军事技能训练",
                    courseSeq = "",
                    location = "西-区田径场",
                    teacher = "4-5周", // 旧解析：周次被当成教师
                    weeks = "实践学时", // 旧解析：学时类型被当成周次
                    hoursType = "",
                    rawLines = listOf("<<军事技能训练>>", "西-区田径场", "4-5周", "实践学时")
                )
            )
        )

        val fixed = AcademicParsers.normalizeTimetableCells(legacy).cells.single()
        assertEquals("", fixed.teacher)
        assertEquals("4-5周", fixed.weeks)
        assertEquals("实践学时", fixed.hoursType)
        assertFalse("修复后第 11 周应判定为无课", AcademicParsers.isCourseActiveInWeek(fixed.weeks, 11))
    }

    @Test
    fun testIsCourseActiveInWeek() {
        val oddExpr = "1-16周(单)"
        assertTrue(AcademicParsers.isCourseActiveInWeek(oddExpr, 1))
        assertFalse(AcademicParsers.isCourseActiveInWeek(oddExpr, 2))
        assertTrue(AcademicParsers.isCourseActiveInWeek(oddExpr, 3))
        assertFalse(AcademicParsers.isCourseActiveInWeek(oddExpr, 4))

        val evenExpr = "1-16周(双)"
        assertFalse(AcademicParsers.isCourseActiveInWeek(evenExpr, 1))
        assertTrue(AcademicParsers.isCourseActiveInWeek(evenExpr, 2))
        assertFalse(AcademicParsers.isCourseActiveInWeek(evenExpr, 3))
        assertTrue(AcademicParsers.isCourseActiveInWeek(evenExpr, 4))

        val rangeExpr = "4-10周"
        assertFalse(AcademicParsers.isCourseActiveInWeek(rangeExpr, 3))
        assertTrue(AcademicParsers.isCourseActiveInWeek(rangeExpr, 4))
        assertTrue(AcademicParsers.isCourseActiveInWeek(rangeExpr, 7))
        assertTrue(AcademicParsers.isCourseActiveInWeek(rangeExpr, 10))
        assertFalse(AcademicParsers.isCourseActiveInWeek(rangeExpr, 11))
    }

    @Test
    fun testHashCourseColor() {
        val hash1 = AcademicParsers.hashCourseColor("数据结构与算法")
        val hash2 = AcademicParsers.hashCourseColor("数据结构与算法")
        val hash3 = AcademicParsers.hashCourseColor("大学英语")

        assertEquals("Hash must be deterministic", hash1, hash2)
        assertTrue("Hash must map to 0..11 palette", hash1 in 0..11)
        assertTrue("Hash must map to 0..11 palette", hash3 in 0..11)
    }

    @Test
    fun testParseScores() {
        val html = loadFixture("0028-score.all.html")
        val result = AcademicParsers.parseScores(html)

        assertTrue("Scores list should not be empty", result.scores.isNotEmpty())
        for (item in result.scores) {
            assertTrue("Course name must not be empty", item.courseName.isNotEmpty())
            assertTrue("Credit must be >= 0", item.credit >= 0.0)
            assertTrue("Score value must not be empty", item.score.isNotEmpty())
        }
    }

    @Test
    fun testParseScoresPendingConservative() {
        // L-1：未出分 / 缓考行没有明确结论 → passed=false 且 pending=true，
        // 不再默认视为已通过（保守口径：未确认通过 ≠ 已通过，
        // 不计入已获学分，也不计入挂科统计）。
        // 等级制成绩（合格/优秀/免修）是明确的通过结论 → passed=true 且 pending=false，
        // 不通过是明确的挂科结论 → passed=false 且 pending=false
        val html = """
            <table class="datalist">
              <tr><th>学年</th><th>学期</th><th>课程号</th><th>课程名</th><th>课序号</th><th>课组</th><th>总评</th><th>学分</th><th>学时</th><th>选课属性</th><th>备注</th><th>考试性质</th><th>及格标志</th></tr>
              <tr><td>2024</td><td>秋</td><td>C01</td><td>已出分课程</td><td>1</td><td>必修</td><td>88</td><td>3.0</td><td>48</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
              <tr><td>2024</td><td>秋</td><td>C02</td><td>缓考课程</td><td>2</td><td>必修</td><td>缓考</td><td>2.0</td><td>32</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
              <tr><td>2024</td><td>秋</td><td>C03</td><td>未出分课程</td><td>3</td><td>必修</td><td></td><td>1.0</td><td>16</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
              <tr><td>2024</td><td>秋</td><td>C04</td><td>等级制合格课程</td><td>4</td><td>必修</td><td>合格</td><td>2.0</td><td>32</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
              <tr><td>2024</td><td>秋</td><td>C05</td><td>等级制不通过课程</td><td>5</td><td>必修</td><td>不通过</td><td>1.0</td><td>16</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
              <tr><td>2024</td><td>秋</td><td>C06</td><td>免修课程</td><td>6</td><td>必修</td><td>免修</td><td>2.0</td><td>0</td><td>必修</td><td></td><td>正常考试</td><td></td></tr>
            </table>
        """.trimIndent()
        val result = AcademicParsers.parseScores(html)
        val byId = result.scores.associateBy { it.courseId }
        assertEquals(6, result.scores.size)
        assertEquals(true, byId["C01"]?.passed)
        assertEquals(false, byId["C01"]?.pending)
        assertEquals(false, byId["C02"]?.passed)
        assertEquals(true, byId["C02"]?.pending)
        assertEquals(false, byId["C03"]?.passed)
        assertEquals(true, byId["C03"]?.pending)
        assertEquals(true, byId["C04"]?.passed)
        assertEquals(false, byId["C04"]?.pending)
        assertEquals(false, byId["C05"]?.passed)
        assertEquals(false, byId["C05"]?.pending)
        assertEquals(true, byId["C06"]?.passed)
        assertEquals(false, byId["C06"]?.pending)
    }

    @Test
    fun testCalculateGpaStats() {
        val sampleScores = listOf(
            ScoreItem(
                year = "2024", term = "秋", courseId = "C01", courseName = "软件工程", courseSeq = "1",
                courseGroup = "必修", score = "90", credit = 3.0, hours = 48, property = "必修",
                remark = "", examType = "正常考试", passMark = "及格", passed = true
            ),
            ScoreItem(
                year = "2024", term = "秋", courseId = "C02", courseName = "操作系统", courseSeq = "2",
                courseGroup = "必修", score = "80", credit = 4.0, hours = 64, property = "必修",
                remark = "", examType = "正常考试", passMark = "及格", passed = true
            ),
            ScoreItem(
                year = "2024", term = "秋", courseId = "C03", courseName = "计算机网络", courseSeq = "3",
                courseGroup = "必修", score = "50", credit = 3.0, hours = 48, property = "必修",
                remark = "", examType = "正常考试", passMark = "不及格", passed = false
            )
        )

        val stats = AcademicParsers.calculateGpaStats(sampleScores)
        // C01: score 90 -> gpa = (90-50)/10 = 4.0, weight = 4.0 * 3.0 = 12.0
        // C02: score 80 -> gpa = (80-50)/10 = 3.0, weight = 3.0 * 4.0 = 12.0
        // C03: score 50 (<60) -> gpa = 0.0, weight = 0.0
        // total credits = 10.0, earned credits = 7.0
        // total gpa weight = 24.0 -> gpa = 24.0 / 10.0 = 2.4
        assertEquals(10.0, stats.totalCredits, 0.01)
        assertEquals(7.0, stats.earnedCredits, 0.01)
        assertEquals(1, stats.failedCount)
        assertEquals(2.4, stats.gpa, 0.01)
    }

    @Test
    fun testParseExams() {
        val html = loadFixture("0038-exam.all.html")
        val exams = AcademicParsers.parseExams(html)

        assertTrue("Exams list should not be empty", exams.isNotEmpty())
        for (exam in exams) {
            assertTrue("Course name must not be empty", exam.courseName.isNotEmpty())
            assertTrue("Exam time must not be empty", exam.time.isNotEmpty())
            assertTrue("Exam location must not be empty", exam.location.isNotEmpty())
        }
    }

    @Test
    fun testParseExams_ClassicLookTableWithoutDatalistClass() {
        // 回归用例：真实「全部考试」页的表格 class 不固定（常见为 classicLook0），
        // 数据行 class 为 classicLook*，早期实现只认 table.datalist 会解析为空
        val html = """
            <html><body>
            <table class="classicLook0">
              <tr>
                <th>课程号</th><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>考试性质</th>
              </tr>
              <tr class="classicLook0">
                <td>U080123TW06W3</td><td>复变函数与积分变换</td>
                <td>2026-11-01 08:10--09:50</td><td>西区&nbsp;新教学楼&nbsp;西-新A308</td><td>开班重修</td>
              </tr>
              <tr class="classicLook1">
                <td>U040023XN07W4</td><td>操作系统</td>
                <td>2026-07-04 13:30--15:10</td><td>西区&nbsp;新教学楼&nbsp;西-新A514</td><td>正常考试</td>
              </tr>
              <tr class="classicLookPagingTag PagingTag">
                <td class="classicLookSummary Summary">共<b>2</b>条，<b>1</b> / <b>1</b>页</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = AcademicParsers.parseExams(html)

        assertEquals(2, exams.size)
        assertEquals("U080123TW06W3", exams[0].courseId)
        assertEquals("复变函数与积分变换", exams[0].courseName)
        assertEquals("开班重修", exams[0].property)
        assertEquals("操作系统", exams[1].courseName)
    }

    @Test
    fun testParseExams_RecentExamTableWithoutTableClass() {
        // 无识别 class 的表格也应通过「含 >=4 个 td 的数据行」兜底命中
        val html = """
            <html><body>
            <table id="dataTable">
              <tr><th>课程号</th><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>考试性质</th></tr>
              <tr><td>U010203TW04W5</td><td>大学英语</td><td>2026-12-20 08:10--09:50</td><td>西-新B406</td><td>正常考试</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = AcademicParsers.parseExams(html)

        assertEquals(1, exams.size)
        assertEquals("大学英语", exams[0].courseName)
        assertEquals("西-新B406", exams[0].location)
    }

    @Test
    fun testRefreshExamCountdown() {
        // 缓存跨天后必须按当前日期重算倒计时，避免"还有 N 天"失效
        val today = java.time.LocalDate.now()
        val past = today.minusDays(3).toString()
        val future = today.plusDays(5).toString()

        val exams = listOf(
            ExamItem(courseId = "A", courseName = "已考", time = "$past 08:10--09:50", location = "L1", property = "正常考试"),
            ExamItem(courseId = "B", courseName = "未考", time = "$future 08:10--09:50", location = "L2", property = "正常考试")
        )

        val refreshed = AcademicParsers.refreshExamCountdown(exams)

        assertEquals(-3, refreshed[0].countdownDays)
        assertFalse(refreshed[0].isUpcoming)
        assertEquals(5, refreshed[1].countdownDays)
        assertTrue(refreshed[1].isUpcoming)
    }

    @Test
    fun testParseClassroomQueryOptions() {
        val html = loadFixture("0042-classroom.query.html")
        val options = AcademicParsers.parseClassroomQueryOptions(html)

        assertTrue("Campus areas should be extracted", options.areas.isNotEmpty())
        assertTrue("Buildings should be extracted", options.buildings.isNotEmpty())
    }

    @Test
    fun classroomQueryOptionsCarryWeeksAndDays() {
        val options = AcademicParsers.parseClassroomQueryOptions(loadFixture("0042-classroom.query.html"))

        assertEquals("whichweek 应有 26 个可选周次", 26, options.weeks.size)
        assertEquals("week 应有 7 个星期", 7, options.days.size)
        assertEquals("1", options.weeks.first().id)
        assertEquals("1", options.days.first().id)
        assertEquals("周一", options.days.first().name)
    }

    @Test
    fun parseClassroomDayScheduleReadsOccupancyMatrix() {
        val schedule = AcademicParsers.parseClassroomDaySchedule(loadFixture("0043-classroom.week.html"), 1)

        assertEquals("2026秋", schedule.semester)
        assertEquals("西区一号楼", schedule.buildingName)
        assertEquals(10, schedule.week)
        assertEquals(1, schedule.day)
        assertEquals("11-02", schedule.date)
        assertEquals(6, schedule.rooms.size)

        val room = schedule.rooms.first { it.id == "665" }
        assertEquals("西1-0303", room.name)
        assertEquals(224, room.seats)
        assertEquals("多媒体教室", room.roomType)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), room.occupancy.map { it.start })
        assertTrue("颜色 #FF0000 应识别为课程", room.occupancy.all { it.type == "course" })

        val freeRoom = schedule.rooms.first { it.id == "4180" }
        assertTrue("整日无课的教室不应有占用记录", freeRoom.occupancy.isEmpty())
    }

    @Test
    fun classroomDefaultBuildingSelection() {
        val available = listOf("1168", "584", "1688")

        assertEquals("584", ClassroomScheduleLogic.pickBuildingId(available, "584"))
        assertEquals("1688", ClassroomScheduleLogic.pickBuildingId(available, null, "1688"))
        // 默认教学楼不属于当前教学区 → 回落到第一个
        assertEquals("1168", ClassroomScheduleLogic.pickBuildingId(available, "999"))
        // 默认与上次都无效 → 第一个
        assertEquals("1168", ClassroomScheduleLogic.pickBuildingId(available, "999", "888"))
        assertEquals("", ClassroomScheduleLogic.pickBuildingId(emptyList(), "584"))
    }

    @Test
    fun classroomFreeRangesMergeConsecutiveSections() {
        val occupancy = (1..6).map {
            ClassroomOccupancy(day = 1, start = it, span = 1, type = "course")
        }

        val monday = ClassroomScheduleLogic.dayRanges(occupancy, 1)
        assertEquals(1, monday.size)
        assertEquals(7, monday.first().start)
        assertEquals(12, monday.first().end)
        assertEquals("周一 7-12 节", ClassroomScheduleLogic.formatRange(monday.first()))

        // 整周：周一 1 段 + 其余 6 天各 1 段
        assertEquals(7, ClassroomScheduleLogic.weekRanges(occupancy).size)
        assertEquals(6, ClassroomScheduleLogic.occupiedSections(occupancy))
    }

    @Test
    fun urupErrorPageIsDetectedInsteadOfReturningEmptyData() {
        val errorHtml = """
            <html><head><title>提示信息</title></head><body>
              <table class="error"><tr><td id="content_margin">-2 2026-11-04 23:20:05 TA1</td></tr></table>
            </body></html>
        """.trimIndent()

        assertTrue(AcademicParsers.isUrupErrorPage(errorHtml))
        assertEquals("查询条件不合法（周次、星期需为有效值）", AcademicParsers.parseUrupErrorMessage(errorHtml))

        // 正常表单页会用 <em class="error"> 做必填占位，不能被误判
        val formHtml = """<html><head><title>教室时间占用</title></head><body><em class="error"></em></body></html>"""
        assertFalse(AcademicParsers.isUrupErrorPage(formHtml))
        assertNull(AcademicParsers.parseUrupErrorMessage(formHtml))
    }

    @Test
    fun testParseCalendarInfo() {
        val html = loadFixture("0051-notice.calendarInfo.html")
        val calendar = AcademicParsers.parseCalendarInfo(html)

        assertEquals("Current week should be 6", 6, calendar.currentWeek)
        assertTrue("Semester name should not be blank", calendar.semesterName.isNotBlank())
    }

    @Test
    fun testParseTeachingWeek() {
        val html = loadFixture("0005-auth.listLeft.html")
        val week = AcademicParsers.parseTeachingWeek(html)

        assertEquals("Teaching week should be 6", 6, week)

        val calHtml = loadFixture("0051-notice.calendarInfo.html")
        val calWeek = AcademicParsers.parseTeachingWeek(calHtml)
        assertEquals("Teaching week from calendarInfo fixture should be 6", 6, calWeek)
    }

    @Test
    fun testParseTeachingWeekReturnsZeroWhenPageIsNotRecognized() {
        // 无法识别时返回 0（而非误判为第 1 周），刷新失败才不会把已保存的教学周写坏
        assertEquals("空内容应返回 0", 0, AcademicParsers.parseTeachingWeek(""))
        assertEquals("未知页面应返回 0", 0, AcademicParsers.parseTeachingWeek("<html><body>系统维护中</body></html>"))
    }
}
