package campus

import (
	"regexp"
	"strconv"
	"strings"

	"golang.org/x/net/html"
)

// 本文件是 `web/src/services/academic/parser.js` 的 Go 移植。
//
// 目标不是"解析出相似的结果"，而是**逐字段对齐**：终端同步写回的
// `storage.json` 必须与图形界面自己同步写出的结构完全一致，
// 否则同一份缓存会出现两种形态（少了字段、字段错位）。
// 因此每个解析函数上方都标注了对应的 JS 函数，改动时两边一起改。

// ---------- 数据模型（字段名与前端缓存逐字一致） ----------

// CurrentCourse 是「本学期课程名录」的一条。
type CurrentCourse struct {
	CourseID     string  `json:"courseId"`
	CourseSeq    string  `json:"courseSeq"`
	CourseName   string  `json:"courseName"`
	Teacher      string  `json:"teacher"`
	Credit       float64 `json:"credit"`
	Property     string  `json:"property"`
	ExamWay      string  `json:"examWay"`
	ExamType     string  `json:"examType"`
	TimeAndPlace string  `json:"timeAndPlace"`
}

// StudentContext 是教务上下文（内部学号、学年学期、本学期课程）。
type StudentContext struct {
	StudentID string          `json:"studentId"`
	Year      string          `json:"year"`
	Term      string          `json:"term"`
	Courses   []CurrentCourse `json:"courses"`
}

// ProfileChange 是学籍异动记录。
type ProfileChange struct {
	Type   string `json:"type"`
	Date   string `json:"date"`
	Reason string `json:"reason"`
	Remark string `json:"remark"`
}

// Profile 是学籍档案（与前端 `userProfile` 同键名）。
type Profile struct {
	StudentNumber string          `json:"studentNumber"`
	InternalID    string          `json:"internalId,omitempty"`
	RealName      string          `json:"realName"`
	College       string          `json:"college"`
	Major         string          `json:"major"`
	Direction     string          `json:"direction"`
	StudentType   string          `json:"studentType"`
	Grade         string          `json:"grade"`
	ClassName     string          `json:"className"`
	IDCard        string          `json:"idCard"`
	Email         string          `json:"email"`
	Phone         string          `json:"phone"`
	Address       string          `json:"address"`
	PostalCode    string          `json:"postalCode"`
	Status        string          `json:"status"`
	PhotoURL      string          `json:"photoUrl"`
	Changes       []ProfileChange `json:"changes"`
}

// ScoreEntry 是一条成绩。
type ScoreEntry struct {
	Year        string  `json:"year"`
	Term        string  `json:"term"`
	CourseID    string  `json:"courseId"`
	CourseName  string  `json:"courseName"`
	CourseSeq   string  `json:"courseSeq"`
	CourseGroup string  `json:"courseGroup"`
	Score       string  `json:"score"`
	Credit      float64 `json:"credit"`
	Hours       float64 `json:"hours"`
	Property    string  `json:"property"`
	Remark      string  `json:"remark"`
	ExamType    string  `json:"examType"`
	PassMark    string  `json:"passMark"`
	Passed      bool    `json:"passed"`
	Pending     bool    `json:"pending"`
}

// YearOption 是成绩筛选的学年选项。
type YearOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// ScoresResult 是成绩页的完整载荷。
type ScoresResult struct {
	Scores      []ScoreEntry `json:"scores"`
	YearOptions []YearOption `json:"yearOptions"`
}

// TimetableCell 是课表格子。
type TimetableCell struct {
	ID           string   `json:"id"`
	CourseID     string   `json:"courseId"`
	Day          int      `json:"day"`
	SectionIndex int      `json:"sectionIndex"`
	SectionLabel string   `json:"sectionLabel"`
	CourseName   string   `json:"courseName"`
	CourseSeq    string   `json:"courseSeq"`
	Location     string   `json:"location"`
	Teacher      string   `json:"teacher"`
	Weeks        string   `json:"weeks"`
	HoursType    string   `json:"hoursType"`
	RawLines     []string `json:"rawLines"`
}

// UnarrangedCourse 是没有排课时间/地点的课程。
type UnarrangedCourse struct {
	CourseID   string `json:"courseId"`
	CourseName string `json:"courseName"`
	CourseSeq  string `json:"courseSeq"`
	Teacher    string `json:"teacher"`
	MergeClass string `json:"mergeClass"`
	Weeks      string `json:"weeks"`
	Day        string `json:"day"`
	Location   string `json:"location"`
}

// Timetable 是一份课表（大节或小节）。
type Timetable struct {
	Cells      []TimetableCell    `json:"cells"`
	Unarranged []UnarrangedCourse `json:"unarranged"`
}

// ExamEntry 是一条考试安排。
type ExamEntry struct {
	CourseID   string `json:"courseId"`
	CourseName string `json:"courseName"`
	Time       string `json:"time"`
	Location   string `json:"location"`
	Property   string `json:"property"`
}

// Notice 是一条教学公告。
type Notice struct {
	Title   string `json:"title"`
	Content string `json:"content"`
	Date    string `json:"date"`
}

// CalendarInfo 是校历页的载荷。
type CalendarInfo struct {
	CurrentWeek   int      `json:"currentWeek"`
	SemesterName  string   `json:"semesterName"`
	TeachingWeeks *int     `json:"teachingWeeks"`
	Notices       []Notice `json:"notices"`
}

// PlanCourse 是培养方案中的一门候选课程。
type PlanCourse struct {
	Code     string  `json:"code"`
	Name     string  `json:"name"`
	Credit   float64 `json:"credit"`
	Hours    float64 `json:"hours"`
	Property string  `json:"property"`
}

// PlanGroup 是一个课组。
type PlanGroup struct {
	ID              string       `json:"id"`
	Name            string       `json:"name"`
	Property        string       `json:"property"`
	RequiredCredits float64      `json:"requiredCredits"`
	RequiredCourses int          `json:"requiredCourses"`
	EarnedCredits   float64      `json:"earnedCredits"`
	PassedCourses   int          `json:"passedCourses"`
	Courses         []PlanCourse `json:"courses"`
}

// Plan 是培养方案。
type Plan struct {
	Groups               []PlanGroup `json:"groups"`
	TotalRequiredCredits *float64    `json:"totalRequiredCredits"`
}

// ---------- 解析 ----------

// studentIDYearPattern 对应 parser.js 里 studentid/year 的联合匹配。
var studentIDYearPattern = regexp.MustCompile(`(?is)studentid=["']?(\d+)["']?.*?year=["']?(\d+)["']?`)

// studentIDPattern 是单独的 studentid 匹配。
var studentIDPattern = regexp.MustCompile(`(?i)studentid\s*=\s*["']?(\d+)["']?`)

// yearPattern 是单独的 year 匹配。
var yearPattern = regexp.MustCompile(`(?i)\byear\s*=\s*["']?(\d+)["']?`)

// parseStudentContext 对应 parser.js 的 parseStudentContext。
func parseStudentContext(source string) StudentContext {
	root := parseDocument(source)

	context := StudentContext{Term: "2", Courses: []CurrentCourse{}}
	if match := studentIDYearPattern.FindStringSubmatch(source); match != nil {
		context.StudentID = match[1]
		context.Year = match[2]
	} else {
		if match := studentIDPattern.FindStringSubmatch(source); match != nil {
			context.StudentID = match[1]
		}
		if match := yearPattern.FindStringSubmatch(source); match != nil {
			context.Year = match[1]
		}
	}

	if selectNode := queryFirst(root, `select[name="term"]`); selectNode != nil {
		if option := queryFirst(selectNode, "option[selected]"); option != nil {
			context.Term = attrOf(option, "value")
		} else if options := queryAll(selectNode, "option"); len(options) > 0 {
			context.Term = attrOf(options[0], "value")
		}
	}

	if table := queryFirst(root, "table.infolist_tab"); table != nil {
		for _, row := range queryAll(table, "tr.infolist_common") {
			cells := rowCells(row)
			if len(cells) < 6 {
				continue
			}
			context.Courses = append(context.Courses, CurrentCourse{
				CourseID:     trimCell(cells, 0),
				CourseSeq:    trimCell(cells, 1),
				CourseName:   trimCell(cells, 2),
				Teacher:      trimCell(cells, 3),
				Credit:       jsParseFloat(trimCell(cells, 4)),
				Property:     trimCell(cells, 5),
				ExamWay:      trimCell(cells, 6),
				ExamType:     trimCell(cells, 7),
				TimeAndPlace: trimCell(cells, 9),
			})
		}
	}
	return context
}

// parsePersonalInfo 对应 parser.js 的 parsePersonalInfo。
func parsePersonalInfo(source string) Profile {
	root := parseDocument(source)
	profile := Profile{Status: "在籍（注册）", Changes: []ProfileChange{}}

	if image := queryFirst(root, `img[src*="loadphoto_added.jsdo"]`); image != nil {
		profile.PhotoURL = attrOf(image, "src")
	} else if image := queryFirst(root, `img[src*="showStudentImage.jsp"]`); image != nil {
		profile.PhotoURL = attrOf(image, "src")
	}

	if table := queryFirst(root, "table.form"); table != nil {
		for _, row := range tableRows(table) {
			headers := childElementsByTag(row, "th")
			cells := childElementsByTag(row, "td")
			for index := range headers {
				if index >= len(cells) {
					break
				}
				key := strings.TrimSpace(nodeText(headers[index]))
				value := strings.TrimSpace(strings.ReplaceAll(nodeText(cells[index]), "&nbsp;", ""))
				if key == "" {
					continue
				}
				applyProfileField(&profile, key, value)
			}
		}
	}

	if table := queryFirst(root, "table.datalist"); table != nil {
		for _, row := range queryAll(table, "tr.infolist_hr_common, tr.infolist_common") {
			cells := rowCells(row)
			if len(cells) < 4 {
				continue
			}
			profile.Changes = append(profile.Changes, ProfileChange{
				Type:   strings.TrimSpace(strings.ReplaceAll(nodeText(cells[0]), "&nbsp;", "")),
				Date:   strings.TrimSpace(strings.ReplaceAll(nodeText(cells[1]), "&nbsp;", "")),
				Reason: strings.TrimSpace(strings.ReplaceAll(nodeText(cells[2]), "&nbsp;", "")),
				Remark: strings.TrimSpace(strings.ReplaceAll(nodeText(cells[3]), "&nbsp;", "")),
			})
		}
	}
	return profile
}

// applyProfileField 按表头关键字把值写进对应字段（顺序与 JS 的 if 链一致）。
func applyProfileField(profile *Profile, key, value string) {
	switch {
	case strings.Contains(key, "用户名"):
		profile.StudentNumber = value
	case strings.Contains(key, "真实姓名"):
		profile.RealName = value
	case strings.Contains(key, "所在院系"):
		profile.College = value
	case strings.Contains(key, "专业") && !strings.Contains(key, "方向"):
		profile.Major = value
	case strings.Contains(key, "方向"):
		profile.Direction = value
	case strings.Contains(key, "学生类别"):
		profile.StudentType = value
	case strings.Contains(key, "年级"):
		profile.Grade = value
	case strings.Contains(key, "班级"):
		profile.ClassName = value
	case strings.Contains(key, "证件号码"):
		profile.IDCard = value
	case strings.Contains(key, "电子邮箱"):
		profile.Email = value
	case strings.Contains(key, "联系电话"):
		profile.Phone = value
	case strings.Contains(key, "通讯地址"):
		profile.Address = value
	case strings.Contains(key, "邮政编码"):
		profile.PostalCode = value
	}
}

// passGradeTexts 是明确的"通过"等级制成绩文本。
var passGradeTexts = map[string]bool{
	"优秀": true, "良好": true, "中等": true, "及格": true,
	"合格": true, "通过": true, "免修": true, "免听": true, "免考": true,
}

// failGradeTexts 是明确的"不通过"等级制成绩文本。
var failGradeTexts = map[string]bool{"不及格": true, "不合格": true, "不通过": true}

// parseScores 对应 parser.js 的 parseScores。
func parseScores(source string) ScoresResult {
	root := parseDocument(source)
	result := ScoresResult{Scores: []ScoreEntry{}, YearOptions: []YearOption{}}

	if selectNode := queryFirst(root, `select[name="year"]`); selectNode != nil {
		for _, option := range queryAll(selectNode, "option") {
			value := strings.TrimSpace(attrOf(option, "value"))
			if value == "" {
				continue
			}
			result.YearOptions = append(result.YearOptions, YearOption{
				Value: value,
				Label: strings.TrimSpace(nodeText(option)),
			})
		}
	}

	table := queryFirst(root, "table.datalist")
	if table == nil {
		return result
	}
	rows := tableRows(table)
	if len(rows) < 2 {
		return result
	}

	headers := make([]string, 0, 16)
	for _, th := range childElementsByTag(rows[0], "th") {
		headers = append(headers, strings.TrimSpace(nodeText(th)))
	}
	column := func(name string) int {
		for index, header := range headers {
			if header == name {
				return index
			}
		}
		return -1
	}
	colYear := column("学年")
	colTerm := column("学期")
	colCode := column("课程号")
	colName := column("课程名")
	colSeq := column("课序号")
	colGroup := column("课组")
	colTotal := column("总评")
	colCredit := column("学分")
	colHours := column("学时")
	colProp := column("选课属性")
	colRemark := column("备注")
	colExamType := column("考试性质")
	colPassMark := column("及格标志")

	for _, row := range rows[1:] {
		cells := rowCells(row)
		if len(cells) == 0 {
			continue
		}
		raw := trimCell(cells, colTotal)
		passMark := trimCell(cells, colPassMark)

		number, hasNumber := jsParseFloatOK(raw)
		passed := false
		pending := false
		switch {
		case hasNumber:
			passed = number >= 60
		case passMark != "":
			passed = !strings.Contains(passMark, "不") && passMark != "不及格"
		case passGradeTexts[raw]:
			passed = true
		case failGradeTexts[raw]:
			passed = false
		default:
			pending = true
		}

		result.Scores = append(result.Scores, ScoreEntry{
			Year:        trimCell(cells, colYear),
			Term:        trimCell(cells, colTerm),
			CourseID:    trimCell(cells, colCode),
			CourseName:  trimCell(cells, colName),
			CourseSeq:   trimCell(cells, colSeq),
			CourseGroup: trimCell(cells, colGroup),
			Score:       raw,
			Credit:      jsParseFloat(trimCell(cells, colCredit)),
			Hours:       jsParseFloat(trimCell(cells, colHours)),
			Property:    trimCell(cells, colProp),
			Remark:      trimCell(cells, colRemark),
			ExamType:    trimCell(cells, colExamType),
			PassMark:    passMark,
			Passed:      passed,
			Pending:     pending,
		})
	}
	return result
}

// brPattern 匹配单元格内的换行标签。
var brPattern = regexp.MustCompile(`(?i)<br\s*/?>`)

// tagPattern 匹配残留标签。
var tagPattern = regexp.MustCompile(`<[^>]+>`)

// courseNamePattern 对应 JS 的 /<<?(.*?)(?:>>|;)?(?:\s*;\s*(\d+))?$/。
var courseNamePattern = regexp.MustCompile(`<<?(.*?)(?:>>|;)?(?:\s*;\s*(\d+))?$`)

// courseNameTrimPattern 去掉包裹课程名的尖括号与书名号。
var courseNameTrimPattern = regexp.MustCompile(`^[<《]+|[>》]+$`)

// parseTimetable 对应 parser.js 的 parseTimetable。
func parseTimetable(source string) Timetable {
	root := parseDocument(source)
	timetable := Timetable{Cells: []TimetableCell{}, Unarranged: []UnarrangedCourse{}}

	if table := queryFirst(root, "table#timetable"); table != nil {
		for rowIndex, row := range queryAll(table, "tr.infolist_hr_common") {
			sectionLabel := ""
			if header := queryFirst(row, "th"); header != nil {
				sectionLabel = decodeEntities(strings.TrimSpace(nodeText(header)))
			} else {
				sectionLabel = "第" + strconv.Itoa(rowIndex+1) + "大节"
			}

			for dayIndex, cell := range rowCells(row) {
				id := attrOf(cell, "id")
				rawHTML := strings.TrimSpace(nodeInnerHTML(cell))
				if rawHTML == "" || rawHTML == "&nbsp;" || strings.TrimSpace(nodeText(cell)) == "" {
					continue
				}

				lines := make([]string, 0, 6)
				for _, part := range brPattern.Split(rawHTML, -1) {
					line := strings.TrimSpace(decodeEntities(tagPattern.ReplaceAllString(part, "")))
					if line != "" {
						lines = append(lines, line)
					}
				}
				if len(lines) == 0 {
					continue
				}

				courseName := lines[0]
				courseSeq := ""
				if match := courseNamePattern.FindStringSubmatch(courseName); match != nil {
					courseName = match[1]
					courseSeq = match[2]
				}
				courseName = strings.TrimSpace(courseNameTrimPattern.ReplaceAllString(courseName, ""))

				fields := splitCourseCellFields(lines)

				day := dayIndex + 1
				courseID := ""
				if id != "" {
					parts := strings.Split(id, "-")
					if parsed, err := strconv.Atoi(parts[0]); err == nil && parsed >= 1 && parsed <= 7 {
						day = parsed
					}
					if len(parts) > 1 && isDigits(parts[1]) {
						courseID = parts[1]
					}
				}

				timetable.Cells = append(timetable.Cells, TimetableCell{
					ID:           id,
					CourseID:     courseID,
					Day:          day,
					SectionIndex: rowIndex + 1,
					SectionLabel: sectionLabel,
					CourseName:   courseName,
					CourseSeq:    courseSeq,
					Location:     fields.Location,
					Teacher:      fields.Teacher,
					Weeks:        fields.Weeks,
					HoursType:    fields.HoursType,
					RawLines:     lines,
				})
			}
		}
	}

	if table := queryFirst(root, "table#noArrangement"); table != nil {
		rows := tableRows(table)
		for _, row := range rows[1:] {
			cells := rowCells(row)
			if len(cells) < 8 {
				continue
			}
			value := func(index int) string {
				return decodeEntities(strings.TrimSpace(nodeText(cells[index])))
			}
			timetable.Unarranged = append(timetable.Unarranged, UnarrangedCourse{
				CourseID:   value(0),
				CourseName: value(1),
				CourseSeq:  value(2),
				Teacher:    value(3),
				MergeClass: value(4),
				Weeks:      value(5),
				Day:        value(6),
				Location:   value(7),
			})
		}
	}
	return timetable
}

// parseExams 对应 parser.js 的 parseExams。
func parseExams(source string) []ExamEntry {
	root := parseDocument(stripHTMLComments(source))
	exams := []ExamEntry{}

	table := queryFirst(root, "table.datalist")
	if table == nil {
		table = queryFirst(root, "table.infolist_tab")
	}
	if table == nil {
		return exams
	}
	rows := tableRows(table)
	if len(rows) < 2 {
		return exams
	}

	headers := make([]string, 0, 12)
	for _, th := range childElementsByTag(rows[0], "th") {
		headers = append(headers, strings.TrimSpace(nodeText(th)))
	}
	column := func(keyword string) int {
		for index, header := range headers {
			if strings.Contains(header, keyword) {
				return index
			}
		}
		return -1
	}
	colCode := column("课程号")
	colName := column("课程名称")
	colTime := column("考试时间")
	colLoc := column("考试地点")
	colProp := column("考试性质")

	indexOr := func(value, fallback int) int {
		if value >= 0 {
			return value
		}
		return fallback
	}

	for _, row := range rows[1:] {
		cells := rowCells(row)
		if len(cells) < 4 {
			continue
		}
		courseID := trimCell(cells, indexOr(colCode, 0))
		courseName := trimCell(cells, indexOr(colName, 1))
		if courseID == "" && courseName == "" {
			continue
		}
		property := trimCell(cells, indexOr(colProp, 4))
		if property == "" {
			property = "正常考试"
		}
		exams = append(exams, ExamEntry{
			CourseID:   courseID,
			CourseName: courseName,
			Time:       trimCell(cells, indexOr(colTime, 2)),
			Location:   trimCell(cells, indexOr(colLoc, 3)),
			Property:   property,
		})
	}
	return exams
}

// teachingWeeksPattern 匹配明确写出的学期总周数。
var teachingWeeksPattern = regexp.MustCompile(`(?:共|总计|总周数[:：]?)\s*(\d{1,2})\s*(?:个)?(?:教学)?周`)

// weekNumberPattern 从「第 11 周」中取周次。
var weekNumberPattern = regexp.MustCompile(`第\s*(\d+)\s*周`)

// whitespacePattern 折叠空白。
var whitespacePattern = regexp.MustCompile(`\s+`)

// parseCalendarInfo 对应 parser.js 的 parseCalendarInfo。
//
// 校历（`shared/school-calendar.json`）优先：教务页面的 1..26 导航
// 不是学期长度，官方校历才是。semester/week 由调用方传入，
// 使本函数保持纯函数、便于测试。
func parseCalendarInfo(source string, semester *SemesterInfo, teachingWeek int, hasTeachingWeek bool) CalendarInfo {
	root := parseDocument(source)

	currentWeek := 1
	if node := queryFirst(root, ".curweek strong"); node != nil {
		if parsed, ok := jsParseIntOK(strings.TrimSpace(nodeText(node))); ok {
			currentWeek = parsed
		}
	} else if node := queryFirst(root, ".week td.cur span"); node != nil {
		if parsed, ok := jsParseIntOK(strings.TrimSpace(nodeText(node))); ok {
			currentWeek = parsed
		}
	}

	semesterName := ""
	if node := queryFirst(root, ".curweek"); node != nil {
		text := whitespacePattern.ReplaceAllString(nodeText(node), " ")
		semesterName = strings.TrimSpace(regexp.MustCompile(`第.*周`).ReplaceAllString(text, ""))
	}

	var teachingWeeks *int
	semesterText := ""
	if node := queryFirst(root, "#semesterWeeks, .semester-weeks, .semesterweeks, .semester-calendar"); node != nil {
		semesterText = nodeText(node)
	} else if node := queryFirst(root, "#textwrapper"); node != nil {
		semesterText = nodeText(node)
	}
	if match := teachingWeeksPattern.FindStringSubmatch(semesterText); match != nil {
		if parsed, ok := jsParseIntOK(match[1]); ok && parsed >= 1 && parsed <= 26 {
			value := parsed
			teachingWeeks = &value
		}
	}

	notices := []Notice{}
	if wrapper := queryFirst(root, "#textwrapper"); wrapper != nil {
		for _, item := range queryAll(wrapper, "p, div, li") {
			text := strings.TrimSpace(nodeText(item))
			if text == "" {
				continue
			}
			notices = append(notices, Notice{
				Title:   truncateRunes(text, 50),
				Content: text,
				Date:    "",
			})
		}
	}

	info := CalendarInfo{
		CurrentWeek:   currentWeek,
		SemesterName:  semesterName,
		TeachingWeeks: teachingWeeks,
		Notices:       notices,
	}
	if semester != nil {
		info.SemesterName = semester.Label
		info.TeachingWeeks = &semester.TeachingWeeks
		if hasTeachingWeek {
			info.CurrentWeek = teachingWeek
		} else {
			info.CurrentWeek = 0
		}
	}
	return info
}

// planGroupPattern 解析课组下拉项里的学分要求。
var planGroupPattern = regexp.MustCompile(
	`^\s*(.*?)\s+选课属性\s*[：:]\s*(.*?)\s*学分要求\s*[=：:]\s*([\d.]+)\s*门数要求\s*[=：:]\s*(\d+)`)

// planGroupNamePattern 从表头「……课组：xxx」取出课组名。
var planGroupNamePattern = regexp.MustCompile(`(?s).*课组[：:]\s*`)

// totalCreditsPattern 匹配培养方案里的总学分要求。
var totalCreditsPattern = regexp.MustCompile(`(?:方案总学分|毕业总学分|总学分要求|要求总学分|总学分)\s*[：:=]?\s*([0-9]+(?:\.[0-9]+)?)`)

// parseCurriculumPlan 对应 parser.js 的 parseCurriculumPlan。
func parseCurriculumPlan(source string) Plan {
	root := parseDocument(source)
	groups := []PlanGroup{}

	if selectNode := queryFirst(root, `select#syt12`); selectNode != nil {
		groups = append(groups, planGroupsFromSelect(selectNode)...)
	} else if selectNode := queryFirst(root, `select[name="syt"]`); selectNode != nil {
		groups = append(groups, planGroupsFromSelect(selectNode)...)
	}

	detailTable := queryFirst(root, `table#output_ctx`)
	if detailTable == nil {
		detailTable = queryFirst(root, `table.output_ctx`)
	}
	if detailTable != nil {
		currentGroup := ""
		for _, row := range tableRows(detailTable) {
			if header := queryFirst(row, "th"); header != nil {
				if strings.Contains(nodeText(header), "课组") {
					currentGroup = strings.TrimSpace(planGroupNamePattern.ReplaceAllString(nodeText(header), ""))
				}
			}
			cells := rowCells(row)
			if len(cells) < 7 {
				continue
			}
			code := trimCell(cells, 1)
			name := trimCell(cells, 2)
			rawCredit := trimCell(cells, 3)
			credit, hasCredit := jsParseFloatOK(rawCredit)
			if code == "" || name == "" || !hasCredit {
				continue
			}
			target := -1
			for index := range groups {
				if groups[index].Name == currentGroup {
					target = index
					break
				}
			}
			if target < 0 && len(groups) > 0 {
				target = 0
			}
			if target < 0 {
				continue
			}
			groups[target].Courses = append(groups[target].Courses, PlanCourse{
				Code:     code,
				Name:     name,
				Credit:   credit,
				Hours:    jsParseFloat(trimCell(cells, 4)),
				Property: trimCell(cells, 5),
			})
		}
	}

	// 同样按 id || name 去重，保留首次出现的课组
	seen := map[string]bool{}
	unique := make([]PlanGroup, 0, len(groups))
	for _, group := range groups {
		key := group.ID
		if key == "" {
			key = group.Name
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		unique = append(unique, group)
	}

	plan := Plan{Groups: unique}
	bodyText := ""
	if body := queryFirst(root, "body"); body != nil {
		bodyText = nodeText(body)
	}
	if match := totalCreditsPattern.FindStringSubmatch(bodyText); match != nil {
		if value, err := strconv.ParseFloat(match[1], 64); err == nil {
			plan.TotalRequiredCredits = &value
		}
	}
	return plan
}

// planGroupsFromSelect 从课组下拉框提取学分要求。
func planGroupsFromSelect(selectNode *html.Node) []PlanGroup {
	out := []PlanGroup{}
	for _, option := range queryAll(selectNode, "option") {
		text := strings.TrimSpace(nodeText(option))
		match := planGroupPattern.FindStringSubmatch(text)
		if match == nil {
			continue
		}
		property := strings.TrimSpace(match[2])
		if property == "" {
			property = "必修"
		}
		out = append(out, PlanGroup{
			ID:              attrOf(option, "value"),
			Name:            strings.TrimSpace(match[1]),
			Property:        property,
			RequiredCredits: jsParseFloat(match[3]),
			RequiredCourses: jsParseInt(match[4]),
			Courses:         []PlanCourse{},
		})
	}
	return out
}

// ---------- 小工具 ----------

// parseDocument 把 HTML 文本解析成文档节点。
func parseDocument(source string) *html.Node {
	root, err := html.Parse(strings.NewReader(source))
	if err != nil {
		return &html.Node{Type: html.DocumentNode}
	}
	return root
}

// trimCell 返回单元格文本（去首尾空白）；下标非法或单元格不存在返回空串。
func trimCell(cells []*html.Node, index int) string {
	if index < 0 || index >= len(cells) {
		return ""
	}
	return strings.TrimSpace(nodeText(cells[index]))
}

// isDigits 判断字符串全部是数字。
func isDigits(text string) bool {
	if text == "" {
		return false
	}
	for _, char := range text {
		if char < '0' || char > '9' {
			return false
		}
	}
	return true
}
