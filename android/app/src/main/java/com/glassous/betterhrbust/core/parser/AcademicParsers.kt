package com.glassous.betterhrbust.core.parser

import com.glassous.betterhrbust.core.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.min

object AcademicParsers {

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "middot" to "·", "deg" to "°",
        "laquo" to "«", "raquo" to "»",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’"
    )

    fun stripHtmlComments(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        return html.replace(Regex("<!--[\\s\\S]*?-->"), "")
    }

    fun decodeEntities(text: String?): String {
        if (text == null || !text.contains('&')) return text ?: ""
        val pattern = Regex("&(?:#x([0-9a-fA-F]+)|#([0-9]+)|([a-zA-Z][a-zA-Z0-9]*));")
        var out: String = text
        for (i in 0 until 3) {
            var replaced = false
            val next = pattern.replace(out) { matchResult ->
                replaced = true
                val hex = matchResult.groups[1]?.value
                val dec = matchResult.groups[2]?.value
                val name = matchResult.groups[3]?.value
                when {
                    hex != null -> {
                        val code = hex.toIntOrNull(16)
                        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else matchResult.value
                    }
                    dec != null -> {
                        val code = dec.toIntOrNull(10)
                        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else matchResult.value
                    }
                    name != null -> NAMED_ENTITIES[name.lowercase()] ?: matchResult.value
                    else -> matchResult.value
                }
            }
            if (!replaced || next == out) break
            out = next
        }
        return out
    }

    fun isLoginPage(html: String?): Boolean {
        if (html.isNullOrEmpty()) return false
        return Jsoup.parse(html).select("form[action*=j_acegi_security_check], input[name=j_captcha]").isNotEmpty()
    }

    fun parseLoginFailureReason(html: String?): String {
        if (html.isNullOrEmpty()) return "登录失败，请检查学号与密码"
        val text = Jsoup.parse(html).text()
        return when {
            Regex("锁定|已冻结|已禁用|accountLocked", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "账号已被系统锁定，请稍后再试"
            Regex("用户不存在|学号不存在|用户名不存在").containsMatchIn(text) -> "该学号不存在"
            Regex("验证码\\s*(?:输入|校验|验证)?\\s*(错误|已?过期|失效|不正确|无效)|captcha\\s*(invalid|incorrect|expired)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "验证码错误或已过期，请刷新重试"
            Regex("密码\\s*(?:输入)?\\s*(错误|不正确|无效)|badCredentials|Bad credentials", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "学号或密码错误"
            else -> "登录失败，请检查学号与密码"
        }
    }

    fun parseStudentContext(html: String): StudentContext {
        val doc: Document = Jsoup.parse(html)

        var studentId = ""
        var year = ""
        val ctrtMatch = Regex("""studentid=["']?(\d+)["']?[\s\S]*?year=["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(html)
        if (ctrtMatch != null) {
            studentId = ctrtMatch.groupValues[1]
            year = ctrtMatch.groupValues[2]
        } else {
            val sidM = Regex("""studentid\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(html)
            val yrM = Regex("""year\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(html)
            if (sidM != null) studentId = sidM.groupValues[1]
            if (yrM != null) year = yrM.groupValues[1]
        }

        var term = "2"
        val termSelect = doc.selectFirst("select[name=term]")
        if (termSelect != null) {
            val selectedOption = termSelect.selectFirst("option[selected]") ?: termSelect.selectFirst("option")
            if (selectedOption != null) {
                term = selectedOption.`val`()
            }
        }

        val courses = mutableListOf<CurrentCourseItem>()
        val table = doc.selectFirst("table.infolist_tab")
        if (table != null) {
            val rows = table.select("tr.infolist_common")
            for (tr in rows) {
                val tds = tr.select("td")
                if (tds.size >= 6) {
                    courses.add(
                        CurrentCourseItem(
                            courseId = tds.getOrNull(0)?.text()?.trim() ?: "",
                            courseSeq = tds.getOrNull(1)?.text()?.trim() ?: "",
                            courseName = tds.getOrNull(2)?.text()?.trim() ?: "",
                            teacher = tds.getOrNull(3)?.text()?.trim() ?: "",
                            credit = tds.getOrNull(4)?.text()?.trim()?.toDoubleOrNull() ?: 0.0,
                            property = tds.getOrNull(5)?.text()?.trim() ?: "",
                            examWay = tds.getOrNull(6)?.text()?.trim() ?: "",
                            examType = tds.getOrNull(7)?.text()?.trim() ?: "",
                            timeAndPlace = tds.getOrNull(9)?.text()?.trim() ?: ""
                        )
                    )
                }
            }
        }

        return StudentContext(studentId = studentId, year = year, term = term, courses = courses)
    }

    fun parsePersonalInfo(html: String): PersonalInfo {
        val doc: Document = Jsoup.parse(html)
        var studentNumber = ""
        var realName = ""
        var college = ""
        var major = ""
        var direction = ""
        var studentType = ""
        var grade = ""
        var className = ""
        var idCard = ""
        var email = ""
        var phone = ""
        var address = ""
        var postalCode = ""
        var photoUrl = ""
        val changes = mutableListOf<StatusChange>()

        val photoImg = doc.selectFirst("img[src*=loadphoto_added.jsdo], img[src*=showStudentImage.jsp]")
        if (photoImg != null) {
            photoUrl = photoImg.attr("src")
        }

        val formTable = doc.selectFirst("table.form")
        if (formTable != null) {
            val rows = formTable.select("tr")
            for (row in rows) {
                val ths = row.select("th")
                val tds = row.select("td")
                for (i in 0 until min(ths.size, tds.size)) {
                    val key = ths[i].text().trim()
                    val value = tds[i].text().replace("\u00A0", "").trim()
                    when {
                        key.contains("用户名") -> studentNumber = value
                        key.contains("真实姓名") -> realName = value
                        key.contains("所在院系") -> college = value
                        key.contains("专业") && !key.contains("方向") -> major = value
                        key.contains("方向") -> direction = value
                        key.contains("学生类别") -> studentType = value
                        key.contains("年级") -> grade = value
                        key.contains("班级") -> className = value
                        key.contains("证件号码") -> idCard = value
                        key.contains("电子邮箱") -> email = value
                        key.contains("联系电话") -> phone = value
                        key.contains("通讯地址") -> address = value
                        key.contains("邮政编码") -> postalCode = value
                    }
                }
            }
        }

        val changesTable = doc.selectFirst("table.datalist")
        if (changesTable != null) {
            val rows = changesTable.select("tr.infolist_hr_common, tr.infolist_common")
            for (tr in rows) {
                val tds = tr.select("td")
                if (tds.size >= 4) {
                    changes.add(
                        StatusChange(
                            type = tds[0].text().trim(),
                            date = tds[1].text().trim(),
                            reason = tds[2].text().trim(),
                            remark = tds[3].text().trim()
                        )
                    )
                }
            }
        }

        return PersonalInfo(
            studentNumber = studentNumber,
            realName = realName,
            college = college,
            major = major,
            direction = direction,
            studentType = studentType,
            grade = grade,
            className = className,
            idCard = idCard,
            email = email,
            phone = phone,
            address = address,
            postalCode = postalCode,
            status = "在籍（注册）",
            photoUrl = photoUrl,
            changes = changes
        )
    }

    fun parseTimetable(html: String): TimetableResult {
        val doc: Document = Jsoup.parse(html)
        val cells = mutableListOf<TimetableCell>()
        val unarranged = mutableListOf<UnarrangedCourse>()

        val table = doc.selectFirst("table#timetable")
        if (table != null) {
            val rows = table.select("tr.infolist_hr_common")
            for ((rowIndex, tr) in rows.withIndex()) {
                val th = tr.selectFirst("th")
                val sectionLabel = if (th != null) {
                    decodeEntities(th.text().replace(Regex("<br\\s*/?>"), "").trim())
                } else {
                    "第${rowIndex + 1}大节"
                }

                val tds = tr.select("td")
                for ((dayIndex, td) in tds.withIndex()) {
                    val id = td.attr("id")
                    val rawHtml = td.html().trim()
                    if (rawHtml.isEmpty() || rawHtml == "&nbsp;" || td.text().trim().isEmpty()) {
                        continue
                    }

                    val lines = rawHtml
                        .split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
                        .map { decodeEntities(it.replace(Regex("<[^>]+>"), "")).trim() }
                        .filter { it.isNotEmpty() }
                    if (lines.isEmpty()) continue

                    var courseName = lines[0]
                    var courseSeq = ""
                    val nameMatch = Regex("""<<?(.*?)(?:>>|;)?(?:\s*;\s*(\d+))?$""").find(courseName)
                    if (nameMatch != null) {
                        courseName = nameMatch.groups[1]?.value ?: courseName
                        courseSeq = nameMatch.groups[2]?.value ?: ""
                    }
                    courseName = courseName.replace(Regex("""^[<《]+|[>》]+$"""), "").trim()

                    val location = lines.getOrNull(1) ?: ""
                    val teacher = lines.getOrNull(2) ?: ""
                    val weeks = lines.getOrNull(3) ?: ""
                    val hoursType = lines.getOrNull(4) ?: ""

                    var day = dayIndex + 1
                    var courseId = ""
                    if (id.isNotEmpty()) {
                        val parts = id.split("-")
                        val parsedDay = parts[0].toIntOrNull()
                        if (parsedDay != null && parsedDay in 1..7) day = parsedDay
                        if (parts.size > 1 && parts[1].matches(Regex("""\d+"""))) courseId = parts[1]
                    }

                    cells.add(
                        TimetableCell(
                            id = id,
                            courseId = courseId,
                            day = day,
                            sectionIndex = rowIndex + 1,
                            sectionLabel = sectionLabel,
                            courseName = courseName,
                            courseSeq = courseSeq,
                            location = location,
                            teacher = teacher,
                            weeks = weeks,
                            hoursType = hoursType,
                            rawLines = lines
                        )
                    )
                }
            }
        }

        val noArrTable = doc.selectFirst("table#noArrangement")
        if (noArrTable != null) {
            val rows = noArrTable.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 8) {
                    unarranged.add(
                        UnarrangedCourse(
                            courseId = decodeEntities(tds[0].text().trim()),
                            courseName = decodeEntities(tds[1].text().trim()),
                            courseSeq = decodeEntities(tds[2].text().trim()),
                            teacher = decodeEntities(tds[3].text().trim()),
                            mergeClass = decodeEntities(tds[4].text().trim()),
                            weeks = decodeEntities(tds[5].text().trim()),
                            day = decodeEntities(tds[6].text().trim()),
                            location = decodeEntities(tds[7].text().trim())
                        )
                    )
                }
            }
        }

        return TimetableResult(cells = cells, unarranged = unarranged)
    }

    fun isCourseActiveInWeek(weeksExpr: String?, targetWeek: Int): Boolean {
        if (weeksExpr.isNullOrBlank()) return true
        val isOdd = weeksExpr.contains("单")
        val isEven = weeksExpr.contains("双")
        if (isOdd && targetWeek % 2 == 0) return false
        if (isEven && targetWeek % 2 != 0) return false

        val ranges = Regex("""\d+(?:-\d+)?""").findAll(weeksExpr).toList()
        if (ranges.isEmpty()) return true

        for (match in ranges) {
            val parts = match.value.split("-").mapNotNull { it.toIntOrNull() }
            if (parts.isNotEmpty()) {
                val start = parts[0]
                val end = if (parts.size > 1) parts[1] else start
                if (targetWeek in start..end) return true
            }
        }
        return false
    }

    /**
     * DJB2 稳定哈希，映射到 12 个固定色板槽位。
     *
     * 课表配色已改用 [com.glassous.betterhrbust.core.ui.theme.CourseColorPalette] 的黄金角 HSL
     * 方案（颜色数量不限），该函数仅作为通用稳定哈希保留，供其他场景与既有测试使用。
     */
    fun hashCourseColor(key: String): Int {
        var hash = 5381L
        for (ch in key.replace(Regex("""\s+"""), "")) {
            hash = (((hash shl 5) + hash) + ch.code) and 0xFFFFFFFFL
        }
        return (hash % 12).toInt()
    }

    fun parseScores(html: String): ScoreResult {
        val doc: Document = Jsoup.parse(html)
        val yearOptions = mutableListOf<YearOption>()
        val yearSelect = doc.selectFirst("select[name=year]")
        if (yearSelect != null) {
            val options = yearSelect.select("option")
            for (opt in options) {
                val value = opt.`val`().trim()
                val label = opt.text().trim()
                if (value.isNotEmpty()) {
                    yearOptions.add(YearOption(value = value, label = label))
                }
            }
        }

        val scores = mutableListOf<ScoreItem>()
        val table = doc.selectFirst("table.datalist")
        if (table != null) {
            val trList = table.select("tr")
            if (trList.size > 1) {
                val ths = trList[0].select("th")
                val headers = ths.map { it.text().trim() }

                val colYear = headers.indexOfFirst { it == "学年" }
                val colTerm = headers.indexOfFirst { it == "学期" }
                val colCode = headers.indexOfFirst { it == "课程号" }
                val colName = headers.indexOfFirst { it == "课程名" }
                val colSeq = headers.indexOfFirst { it == "课序号" }
                val colGroup = headers.indexOfFirst { it == "课组" }
                val colTotal = headers.indexOfFirst { it == "总评" } // 精确匹配总评
                val colCredit = headers.indexOfFirst { it == "学分" }
                val colHours = headers.indexOfFirst { it == "学时" }
                val colProp = headers.indexOfFirst { it == "选课属性" }
                val colRemark = headers.indexOfFirst { it == "备注" }
                val colExamType = headers.indexOfFirst { it == "考试性质" }
                val colPassMark = headers.indexOfFirst { it == "及格标志" }

                // 等级制成绩文本（二级制/五级制/免修类）：总评列本身即是明确的通过结论，
                // 反之为明确的不通过；其余文本（缓考/缺考/未出分）仍按 pending 保守处理（与 web parser.js 同构）
                val passGradeTexts = setOf("优秀", "良好", "中等", "及格", "合格", "通过", "免修", "免听", "免考")
                val failGradeTexts = setOf("不及格", "不合格", "不通过")

                for (i in 1 until trList.size) {
                    val tds = trList[i].select("td")
                    if (tds.isEmpty()) continue

                    fun getTd(index: Int): String = if (index >= 0 && index < tds.size) tds[index].text().trim() else ""

                    val year = getTd(colYear)
                    val term = getTd(colTerm)
                    val courseId = getTd(colCode)
                    val courseName = getTd(colName)
                    val courseSeq = getTd(colSeq)
                    val courseGroup = getTd(colGroup)
                    val rawScore = getTd(colTotal)
                    val credit = getTd(colCredit).toDoubleOrNull() ?: 0.0
                    val hours = getTd(colHours).toIntOrNull() ?: 0
                    val property = getTd(colProp)
                    val remark = getTd(colRemark)
                    val examType = getTd(colExamType)
                    val passMark = getTd(colPassMark)

                    // 保守方向：未确认通过 ≠ 已通过——未出分（无成绩无标志）与
                    // 缓考/缺考等无明确结论的行标记 pending，不计入已获学分也不计入挂科
                    val numScore = rawScore.toDoubleOrNull()
                    var passed = false
                    var pending = false
                    when {
                        numScore != null -> passed = numScore >= 60.0
                        passMark.isNotEmpty() -> passed = !passMark.contains("不") && passMark != "不及格"
                        rawScore in passGradeTexts -> passed = true
                        rawScore in failGradeTexts -> passed = false
                        else -> pending = true
                    }

                    scores.add(
                        ScoreItem(
                            year = year,
                            term = term,
                            courseId = courseId,
                            courseName = courseName,
                            courseSeq = courseSeq,
                            courseGroup = courseGroup,
                            score = rawScore,
                            credit = credit,
                            hours = hours,
                            property = property,
                            remark = remark,
                            examType = examType,
                            passMark = passMark,
                            passed = passed,
                            pending = pending
                        )
                    )
                }
            }
        }

        return ScoreResult(scores = scores, yearOptions = yearOptions)
    }

    /**
     * 学业统计（保留原有签名以兼容既有调用点与单元测试）
     *
     * 算法已收敛到 [com.glassous.betterhrbust.core.util.GpaCalculator]：
     * 五分制绩点、重修/补考合并去重、必修课学分口径与四项特色算法。
     */
    fun calculateGpaStats(scores: List<ScoreItem>): ScoreStats =
        com.glassous.betterhrbust.core.util.GpaCalculator.buildStats(scores)

    /**
     * 定位考试数据表。
     *
     * 不同入口的页面结构不一致：
     *  - `studentQueryAllExam.do`（全部考试）：数据行 class 为 `classicLook0/1/2…`，
     *    表格自身的 class 并不固定（常见为 `classicLook0` 或直接带 id），**不能只认 `datalist`**；
     *  - `student/exam/index.jsdo`（近期考试）：`table.infolist_tab`，数据行 `infolist_common`；
     *  - 其它模块复用 `table.datalist`。
     *
     * 因此先按已知 class 查找，再兜底「首个含至少一行 >=4 个 td 的表格」（排除分页表）。
     */
    private fun findExamTable(doc: Document): org.jsoup.nodes.Element? {
        doc.selectFirst("table.datalist")?.let { return it }
        doc.selectFirst("table.infolist_tab")?.let { return it }
        doc.selectFirst("table.classicLook0")?.let { return it }
        doc.selectFirst("table[id]")?.takeIf { table ->
            table.select("tr").any { it.select("td").size >= 4 }
        }?.let { return it }
        return doc.select("table").firstOrNull { table ->
            table.select("tr").any { tr ->
                tr.select("td").size >= 4 && !tr.className().contains("PagingTag")
            }
        }
    }

    fun parseExams(html: String): List<ExamItem> {
        val cleanHtml = stripHtmlComments(html)
        val doc: Document = Jsoup.parse(cleanHtml)
        val exams = mutableListOf<ExamItem>()

        val table = findExamTable(doc)
        val trList = table?.select("tr") ?: emptyList()

        // 表头可能不在第 0 行（部分页面存在标题行），按首个含 th 的行定位
        val headerIndex = trList.indexOfFirst { it.select("th").isNotEmpty() }
        if (headerIndex >= 0) {
            val headers = trList[headerIndex].select("th").map { it.text().trim() }

            val colCode = headers.indexOfFirst { it.contains("课程号") || it.contains("课程代码") }
            val colName = headers.indexOfFirst { it.contains("课程名") }
            val colTime = headers.indexOfFirst { it.contains("考试时间") }
            val colLoc = headers.indexOfFirst { it.contains("考试地点") || it.contains("考场") }
            val colProp = headers.indexOfFirst { it.contains("考试性质") }

            for (i in headerIndex + 1 until trList.size) {
                val tr = trList[i]
                if (tr.className().contains("PagingTag")) continue

                val tds = tr.select("td")
                if (tds.size < 4) continue

                // 表头缺失时按 URP 默认列序兜底：课程号 / 课程名 / 考试时间 / 考试地点 / 考试性质
                fun cell(index: Int, fallback: Int): String {
                    val target = if (index >= 0) index else fallback
                    return if (target in tds.indices) tds[target].text().trim() else ""
                }

                val courseId = cell(colCode, 0)
                val courseName = cell(colName, 1)
                val time = cell(colTime, 2)
                val location = cell(colLoc, 3)
                val property = cell(colProp, 4).ifEmpty { "正常考试" }

                if (courseName.isEmpty() && courseId.isEmpty()) continue

                exams.add(
                    ExamItem(
                        courseId = courseId,
                        courseName = courseName,
                        time = time,
                        location = location,
                        property = property
                    )
                )
            }
        }

        return refreshExamCountdown(exams)
    }

    /**
     * 依据当前日期重算考试倒计时与「是否未考」。
     *
     * 考试缓存会在本地保存数天，若直接复用落库时的 countdownDays，隔天后就会出现
     * 「还有 N 天」过期、已考考试仍按未考排序等问题，因此读取缓存时必须重算。
     */
    fun refreshExamCountdown(exams: List<ExamItem>): List<ExamItem> {
        if (exams.isEmpty()) return exams
        val today = LocalDate.now()
        val dateRegex = Regex("""(\d{4}-\d{2}-\d{2})""")

        return exams.map { exam ->
            val dateMatch = dateRegex.find(exam.time)
            if (dateMatch == null) {
                exam
            } else {
                try {
                    val examDate = LocalDate.parse(dateMatch.value, DateTimeFormatter.ISO_LOCAL_DATE)
                    val diff = ChronoUnit.DAYS.between(today, examDate).toInt()
                    exam.copy(countdownDays = diff, isUpcoming = diff >= 0)
                } catch (_: Exception) {
                    exam
                }
            }
        }
    }

    fun parseCurriculumPlan(html: String): CurriculumPlanResult {
        val doc: Document = Jsoup.parse(html)
        val groups = mutableListOf<CurriculumGroup>()

        val select = doc.selectFirst("select#syt12") ?: doc.selectFirst("select[name=syt]")
        if (select != null) {
            val options = select.select("option")
            for (opt in options) {
                val text = opt.text().trim()
                val m = Regex("""^(.*?)\s+选课属性：(.*?)?\s*学分要求=([\d.]+)\s*门数要求=(\d+)""").find(text)
                if (m != null) {
                    groups.add(
                        CurriculumGroup(
                            id = opt.`val`(),
                            name = m.groupValues[1].trim(),
                            property = m.groupValues[2].trim().ifEmpty { "必修" },
                            requiredCredits = m.groupValues[3].toDoubleOrNull() ?: 0.0,
                            requiredCourses = m.groupValues[4].toIntOrNull() ?: 0,
                            courses = mutableListOf()
                        )
                    )
                }
            }
        }

        val detailTable = doc.selectFirst("table#output_ctx") ?: doc.selectFirst("table.output_ctx")
        if (detailTable != null) {
            val rows = detailTable.select("tr")
            var currentGroupName = ""

            for (tr in rows) {
                val th = tr.selectFirst("th")
                if (th != null && th.text().contains("课组")) {
                    currentGroupName = th.text().replace(Regex(".*课组[：:]\\s*"), "").trim()
                }

                val tds = tr.select("td")
                if (tds.size >= 7) {
                    val cCode = tds[1].text().trim()
                    val cName = tds[2].text().trim()
                    val cCredit = tds[3].text().trim().toDoubleOrNull() ?: 0.0
                    val cHours = tds[4].text().trim().toIntOrNull() ?: 0
                    val cProp = tds[5].text().trim()

                    if (cCode.isNotEmpty() && cName.isNotEmpty()) {
                        val group = groups.find { it.name == currentGroupName } ?: groups.firstOrNull()
                        if (group != null) {
                            (group.courses as? MutableList<PlanCourseDetail>)?.add(
                                PlanCourseDetail(
                                    code = cCode,
                                    name = cName,
                                    credit = cCredit,
                                    hours = cHours,
                                    property = cProp
                                )
                            )
                        }
                    }
                }
            }
        }

        val total = Regex("""(?:方案总学分|毕业总学分|总学分要求|要求总学分|总学分)\s*[：:=]?\s*([0-9]+(?:\.[0-9]+)?)""").find(doc.text())?.groupValues?.get(1)?.toDoubleOrNull()
        return CurriculumPlanResult(groups = groups.distinctBy { it.id.ifBlank { it.name } }, totalRequiredCredits = total)
    }

    /** 读取下拉选项，过滤「请选择」占位项（value=-1） */
    private fun readClassroomOptions(doc: Document, name: String): List<NamedOption> {
        return doc.select("select[name=$name] option")
            .mapNotNull { opt ->
                val value = opt.`val`().trim()
                if (value.isEmpty() || value == "-1") null
                else NamedOption(value, opt.text().replace(Regex("\\s+"), " ").trim())
            }
    }

    fun parseClassroomQueryOptions(html: String): ClassroomQueryOptions {
        val doc: Document = Jsoup.parse(stripHtmlComments(html))
        val weeks = readClassroomOptions(doc, "whichweek")
            .filter { it.id.toIntOrNull() != null }
        val days = readClassroomOptions(doc, "week")
            .filter { it.id.toIntOrNull() in 1..7 }

        return ClassroomQueryOptions(
            areas = readClassroomOptions(doc, "aid"),
            buildings = readClassroomOptions(doc, "buildingid"),
            rooms = readClassroomOptions(doc, "room"),
            weeks = weeks,
            days = days
        )
    }

    /* ------------------------------------------------------------------ *
     * 空教室查询（教室时间占用）
     *
     * 实测（2026-11-04）：只有 roomschedule_week.jsdo（按周、整楼、一天）
     * 返回真实占用；roomschedule.jsdo（按教室）返回 26 周空骨架。
     * 结构：table.infolist_tab，每个教室一行（id=tr<教室ID>），前 6 列是
     * 教室信息，第 7 列内嵌 2 行表：第 1 行是第1-12节标签，第 2 行是占用标记。
     * ------------------------------------------------------------------ */

    private val CLASSROOM_WEEKDAY_TEXTS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 教务「提示信息」错误页（HTTP 200 但正文是错误） */
    fun isUrupErrorPage(html: String?): Boolean {
        if (html.isNullOrEmpty()) return false
        return Regex("<title>\\s*提示信息\\s*</title>", RegexOption.IGNORE_CASE).containsMatchIn(html)
    }

    /**
     * 提取「提示信息」错误页的可读文案；非错误页返回 null
     */
    fun parseUrupErrorMessage(html: String?): String? {
        if (!isUrupErrorPage(html)) return null
        val doc = Jsoup.parse(stripHtmlComments(html))
        val raw = doc.selectFirst("#content_margin")?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val code = raw
            .replace(Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}"), "")
            .replace(Regex("\\bTA\\d+\\b"), "")
            .trim()
        return when {
            code.isEmpty() -> "教务系统返回了提示信息页，未取到具体原因"
            code.startsWith("For input string") -> "查询参数为空或格式不正确"
            code == "null" -> "教务未返回数据，请更换筛选条件重试"
            code == "-1" -> "教务会话无效，请重新登录后重试"
            code == "-2" -> "查询条件不合法（周次、星期需为有效值）"
            Regex("^-?\\d+$").matches(code) -> "教务拒绝了本次查询（返回码 $code）"
            else -> code
        }
    }

    /** 只取表格的直接子行，避免把内嵌表格的行也算进来 */
    private fun directRows(table: org.jsoup.nodes.Element): List<org.jsoup.nodes.Element> {
        val rows = mutableListOf<org.jsoup.nodes.Element>()
        table.children().forEach { child ->
            when (child.tagName()) {
                "tr" -> rows.add(child)
                "tbody", "thead", "tfoot" -> rows.addAll(child.children().filter { it.tagName() == "tr" })
            }
        }
        return rows
    }

    /** 只取一行的直接单元格 */
    private fun directCells(row: org.jsoup.nodes.Element): List<org.jsoup.nodes.Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    private fun parseOccupancyType(cell: org.jsoup.nodes.Element): String {
        val marker = cell.selectFirst("font[color]")
        if (marker != null) {
            return when (marker.attr("color").lowercase()) {
                "#ff0000" -> "course"
                "#ff6600" -> "exam"
                "#006600" -> "other"
                else -> "occupied"
            }
        }
        return if (cell.text().isBlank()) "" else "occupied"
    }

    /** 解析占用标记的 title，跳过「占用人」（个人信息不展示） */
    private fun parseOccupancyReason(cell: org.jsoup.nodes.Element): String {
        val title = cell.selectFirst("a[title]")?.attr("title").orEmpty()
        if (title.isBlank()) return ""
        return title
            .split(Regex("\r?\n|\\s{2,}"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("占用人") }
            .joinToString(" · ")
    }

    private fun parseNestedOccupancy(cell: org.jsoup.nodes.Element, day: Int): List<ClassroomOccupancy> {
        val nested = cell.selectFirst("table") ?: return emptyList()
        val rows = directRows(nested)
        val dataRow = rows.getOrNull(1) ?: return emptyList()
        return directCells(dataRow).mapIndexedNotNull { index, sectionCell ->
            val type = parseOccupancyType(sectionCell)
            if (type.isEmpty()) return@mapIndexedNotNull null
            ClassroomOccupancy(
                day = day,
                start = index + 1,
                span = 1,
                type = type,
                reason = parseOccupancyReason(sectionCell)
            )
        }
    }

    private data class ClassroomHeading(
        val semester: String = "",
        val buildingName: String = "",
        val week: Int = 0,
        val day: Int = 0,
        val date: String = ""
    )

    private fun parseScheduleHeading(doc: Document, fallbackDay: Int): ClassroomHeading {
        val heading = doc.selectFirst("table#title td div")?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val week = Regex("第\\s*(\\d+)\\s*周").find(heading)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val dayMatch = Regex("(周[一二三四五六日])\\s*[（(]\\s*(\\d{1,2}-\\d{1,2})\\s*[)）]").find(heading)
        val day = dayMatch?.groupValues?.get(1)
            ?.let { CLASSROOM_WEEKDAY_TEXTS.indexOf(it) + 1 }
            ?.takeIf { it > 0 } ?: fallbackDay
        val date = dayMatch?.groupValues?.get(2).orEmpty()
        val head = heading.replace(Regex("\\s*第\\s*\\d+\\s*周[\\s\\S]*$"), "").trim()
        val semesterMatch = Regex("^(\\d{4}(?:-\\d{4})?\\s*(?:春|夏|秋|冬)?)(.*)$").find(head)

        return ClassroomHeading(
            semester = semesterMatch?.groupValues?.get(1)?.trim().orEmpty(),
            buildingName = semesterMatch?.groupValues?.get(2)?.trim() ?: head,
            week = week,
            day = day,
            date = date
        )
    }

    /**
     * 解析按周查询（roomschedule_week.jsdo）的整楼占用
     * @param fallbackDay 页面标题未标明星期时使用的兜底值
     */
    fun parseClassroomDaySchedule(html: String, fallbackDay: Int = 0): ClassroomDaySchedule {
        val doc = Jsoup.parse(stripHtmlComments(html))
        val heading = parseScheduleHeading(doc, fallbackDay)

        val rooms = mutableListOf<ClassroomRoom>()
        val table = doc.selectFirst("table.infolist_tab")
        if (table != null) {
            directRows(table).forEach { row ->
                if (row.select("th").isNotEmpty()) return@forEach
                val cells = directCells(row)
                if (cells.size < 7) return@forEach
                val name = cells[0].text().replace(Regex("\\s+"), " ").trim()
                if (name.isEmpty()) return@forEach

                rooms.add(
                    ClassroomRoom(
                        id = row.id().removePrefix("tr"),
                        name = name,
                        seats = cells[1].text().trim().toIntOrNull() ?: 0,
                        roomType = cells[5].text().replace(Regex("\\s+"), " ").trim(),
                        deskType = cells[4].text().replace(Regex("\\s+"), " ").trim(),
                        occupancy = parseNestedOccupancy(cells[6], heading.day)
                    )
                )
            }
        }

        return ClassroomDaySchedule(
            semester = heading.semester,
            buildingName = heading.buildingName,
            week = heading.week,
            day = heading.day,
            date = heading.date,
            rooms = rooms
        )
    }

    fun parseCourseList(html: String): List<CourseSearchItem> {
        val doc: Document = Jsoup.parse(html)
        val list = mutableListOf<CourseSearchItem>()

        val table = doc.selectFirst("table.datalist") ?: doc.selectFirst("table.infolist_tab")
        if (table != null) {
            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 4) {
                    list.add(
                        CourseSearchItem(
                            courseId = tds[0].text().trim(),
                            courseName = tds[1].text().trim(),
                            credit = tds.getOrNull(2)?.text()?.trim()?.toDoubleOrNull() ?: 0.0,
                            hours = tds.getOrNull(3)?.text()?.trim()?.toIntOrNull() ?: 0,
                            department = tds.getOrNull(4)?.text()?.trim() ?: "",
                            examWay = tds.getOrNull(5)?.text()?.trim() ?: ""
                        )
                    )
                }
            }
        }
        return list
    }

    fun parseCalendarInfo(html: String): CalendarInfo {
        val doc: Document = Jsoup.parse(html)
        var currentWeek = 1
        val curTd = doc.selectFirst(".curweek strong")
            ?: doc.selectFirst(".curweek")
            ?: doc.selectFirst(".week td.cur span")
            ?: doc.selectFirst(".week td.cur")
        if (curTd != null) {
            val num = curTd.text().trim().toIntOrNull()
                ?: Regex("""第\s*(\d+)\s*周""").find(curTd.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(curTd.text())?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num in 1..26) currentWeek = num
        }

        // 双重校验：若解析出的周数为 1，尝试 parseTeachingWeek 全文解析
        if (currentWeek <= 1) {
            val parsedWeek = parseTeachingWeek(html)
            if (parsedWeek in 1..26) {
                currentWeek = parsedWeek
            }
        }

        var semesterName = ""
        val curWeekDiv = doc.selectFirst(".curweek")
        if (curWeekDiv != null) {
            semesterName = curWeekDiv.text().replace(Regex("""\s+"""), " ").replace(Regex("第.*周"), "").trim()
        }

        // 教务源页面不含发布日期；留空而非用当天日期伪造（错误日期永不纠正），
        // UI 对空日期隐藏日期字段。id 用内容哈希：按序号会在每次同步后变化，
        // 配合 IGNORE 插入会把已读状态整表重置
        val notices = mutableListOf<NoticeItem>()
        val textWrapper = doc.selectFirst("#textwrapper")
        if (textWrapper != null) {
            val items = textWrapper.select("p, div, li")
            for (el in items) {
                val text = el.text().trim()
                if (text.isNotEmpty()) {
                    notices.add(
                        NoticeItem(
                            id = "notice_" + kotlin.math.abs(text.hashCode()),
                            title = if (text.length > 50) text.substring(0, 50) else text,
                            content = text,
                            date = ""
                        )
                    )
                }
            }
        }

        // 只接受校历明确写出的学期总周数，不把通用的 1..26 导航当作学期长度。
        val semesterText = doc.select("#semesterWeeks, .semester-weeks, .semesterweeks, .semester-calendar").text()
        val count = Regex("(?:共|总计|总周数[:：]?)\\s*(\\d{1,2})\\s*(?:个)?(?:教学)?周").find(semesterText.ifEmpty { doc.select("#textwrapper").text() })
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..26 }
        return CalendarInfo(
            teachingWeeks = count,
            currentWeek = currentWeek,
            semesterName = semesterName,
            notices = notices
        )
    }

    /**
     * 解析教学周。
     *
     * @return 识别到的周次（1..26）；**无法识别时返回 0**，调用方据此避免把
     * "解析失败"误当作"第 1 周"写入本地（刷新失败时不会把已保存的教学周写坏）。
     */
    fun parseTeachingWeek(html: String): Int {
        val doc = Jsoup.parse(html)
        val bodyText = doc.text()
        for (el in doc.select("#date, .curweek strong, .curweek")) {
            val text=el.text().trim()
            val number=Regex("第\\s*(\\d+)\\s*周").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: text.toIntOrNull()
            if(number != null && number in 1..26) return number
        }


        // 1. 全文文本正则匹配 "第 N 周"（doc.text() 会剥离 <strong> 等所有 html 标签并合并空白）
        val textMatch = Regex("""第\s*(\d+)\s*周""").find(bodyText)
        if (textMatch != null) {
            val num = textMatch.groupValues[1].toIntOrNull()
            if (num != null && num in 1..26) return num
        }

        // 2. 原始 HTML 正则匹配（支持可能带有标签如 第<strong>6</strong>周）
        val rawMatch = Regex("""第\s*(?:<[^>]+>)?\s*(\d+)\s*(?:<[^>]+>)?\s*周""").find(html)
        if (rawMatch != null) {
            val num = rawMatch.groupValues[1].toIntOrNull()
            if (num != null && num in 1..26) return num
        }

        // 3. 检查 #date p span 或 #date 结构
        val dateEl = doc.selectFirst("#date p span") ?: doc.selectFirst("#date span") ?: doc.selectFirst("#date")
        if (dateEl != null) {
            val dateText = dateEl.text()
            val m = Regex("""第\s*(\d+)\s*周""").find(dateText) ?: Regex("""(\d+)""").find(dateText)
            val num = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num in 1..26) return num
        }

        // 4. 检查 .curweek 或 .curweek strong
        val curWeekEl = doc.selectFirst(".curweek strong") ?: doc.selectFirst(".curweek")
        if (curWeekEl != null) {
            val curText = curWeekEl.text()
            val m = Regex("""第\s*(\d+)\s*周""").find(curText) ?: Regex("""(\d+)""").find(curText)
            val num = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num in 1..26) return num
        }

        // 5. 检查 .week td.cur span 或 .week td.cur
        val curTd = doc.selectFirst(".week td.cur span") ?: doc.selectFirst(".week td.cur")
        if (curTd != null) {
            val num = curTd.text().trim().toIntOrNull()
                ?: Regex("""(\d+)""").find(curTd.text())?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num in 1..26) return num
        }

        // 全部结构均无法识别：返回 0 表示"未知"，避免被当成第 1 周
        return 0
    }
}
