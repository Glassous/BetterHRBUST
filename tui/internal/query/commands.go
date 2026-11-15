package query

import (
	"sort"
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// SemesterBlock 是概览页的学期信息。
type SemesterBlock struct {
	Label         *string `json:"label"`
	TeachingWeek  *int    `json:"teachingWeek"`
	TeachingWeeks *int    `json:"teachingWeeks"`
	Holiday       bool    `json:"holiday"`
}

// ExamWithStatus 是带相对状态的考试。
type ExamWithStatus struct {
	Exam   model.ExamRecord `json:"exam"`
	Status string           `json:"status"`
}

// SummaryData 是概览载荷。
type SummaryData struct {
	Profile       model.Profile          `json:"profile"`
	Date          string                 `json:"date"`
	IsToday       bool                   `json:"isToday"`
	Semester      SemesterBlock          `json:"semester"`
	Stats         academic.AcademicStats `json:"stats"`
	TodayCourses  []model.TimetableCell  `json:"todayCourses"`
	UpcomingExams []ExamWithStatus       `json:"upcomingExams"`
}

// Summary 组装概览数据。
//
// 学籍在这里就打码：概览是 LLM 最常调用的命令，绝不能让原始证件号
// 顺着工具结果流进模型上下文。
func (s *Service) Summary(options Options) SummaryData {
	today, isToday := s.anchor(options)
	days := 7
	if options.Days != nil {
		days = *options.Days
	}

	block := SemesterBlock{Holiday: true}
	if semester, ok := academic.SemesterOn(today); ok {
		label := semester.Label
		block.Label = &label
		weeks := semester.TeachingWeeks
		block.TeachingWeeks = &weeks
	}
	if week, ok := academic.TeachingWeekOn(today); ok {
		block.TeachingWeek = &week
		block.Holiday = false
	}

	courses := s.dayCourses(today, isToday)
	if courses == nil {
		courses = []model.TimetableCell{}
	}

	return SummaryData{
		Profile:       academic.MaskProfile(s.Snapshot.Profile()),
		Date:          today.String(),
		IsToday:       isToday,
		Semester:      block,
		Stats:         academic.BuildAcademicStats(s.Snapshot.Scores()),
		TodayCourses:  courses,
		UpcomingExams: s.recentExams(today, days),
	}
}

// WeekdayNames 是 ISO 星期到中文名的映射（1 起）。
var WeekdayNames = [8]string{"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"}

// DayBlock 是课表里的某一天。
type DayBlock struct {
	Day     int                   `json:"day"`
	Weekday string                `json:"weekday"`
	Date    string                `json:"date"`
	Courses []model.TimetableCell `json:"courses"`
}

// TimetableData 是课程表载荷。
type TimetableData struct {
	Week          int                      `json:"week"`
	CurrentWeek   int                      `json:"currentWeek"`
	TeachingWeeks int                      `json:"teachingWeeks"`
	Base          bool                     `json:"base"`
	Holiday       bool                     `json:"holiday"`
	Days          []DayBlock               `json:"days"`
	Unarranged    []model.UnarrangedCourse `json:"unarranged"`
}

// Timetable 组装课程表数据。
//
// 周次优先级：`--week` > 当前教学周 > 第 1 周（假期时）。
//
// `--day` 只保留指定星期；`--today` 只保留今天的剩余课程（与概览页同口径）。
func (s *Service) Timetable(options Options) TimetableData {
	today, isToday := s.anchor(options)
	currentWeek, hasWeek := academic.TeachingWeekOn(today)
	teachingWeeks := academic.TeachingWeeks(today)

	week := currentWeek
	if !hasWeek {
		week = 1
	}
	if options.Week != nil {
		week = *options.Week
	}

	table := academic.NormalizeTimetable(s.Snapshot.Timetable(options.Base))
	days := make([]DayBlock, 0, 7)
	for day := 1; day <= 7; day++ {
		block := DayBlock{
			Day:     day,
			Weekday: WeekdayNames[day],
			Courses: []model.TimetableCell{},
		}
		if hasWeek {
			block.Date = academic.TimetableDayDate(currentWeek, week, day, today)
		}
		for _, cell := range table.Cells {
			if cell.Day != day {
				continue
			}
			if !academic.IsCourseActiveInWeek(cell.Weeks, week) {
				continue
			}
			block.Courses = append(block.Courses, cell)
		}
		sortBySection(block.Courses)
		days = append(days, block)
	}

	var unarranged []model.UnarrangedCourse
	if options.Today {
		// 「今天的剩余课程」是一个列表，不是一周网格：共用当天筛选逻辑。
		remaining := s.dayCourses(today, isToday)
		days = []DayBlock{{
			Day:     today.Weekday(),
			Weekday: WeekdayNames[today.Weekday()],
			Date:    today.String(),
			Courses: remaining,
		}}
		week = currentWeek
	} else if options.Day != nil {
		if *options.Day >= 1 && *options.Day <= 7 {
			days = days[*options.Day-1 : *options.Day]
		}
	} else {
		unarranged = table.Unarranged
	}
	if unarranged == nil {
		unarranged = []model.UnarrangedCourse{}
	}

	return TimetableData{
		Week:          week,
		CurrentWeek:   currentWeek,
		TeachingWeeks: teachingWeeks,
		Base:          options.Base,
		Holiday:       !hasWeek,
		Days:          days,
		Unarranged:    unarranged,
	}
}

// CourseEntry 是去重合并后的一门课。
//
// 包装成 `{record, isRetake, recordCount}` 而不是把字段塞进记录本身：
// 原始记录形状必须与缓存一致，否则 `--json` 的消费方会误以为缓存里多了字段。
type CourseEntry struct {
	Record      model.ScoreRecord `json:"record"`
	IsRetake    bool              `json:"isRetake"`
	RecordCount int               `json:"recordCount"`
}

// ScoresData 是成绩载荷。
type ScoresData struct {
	Stats        academic.AcademicStats `json:"stats"`
	Scores       []model.ScoreRecord    `json:"scores"`
	Courses      []CourseEntry          `json:"courses"`
	Total        int                    `json:"total"`
	DedupedCount int                    `json:"dedupedCount"`
	Filters      FiltersBlock           `json:"filters"`
	Years        []model.YearOption     `json:"years"`
}

// FiltersBlock 记录本次成绩筛选条件，便于调用方判断结果口径。
type FiltersBlock struct {
	Year     *string `json:"year"`
	Term     *string `json:"term"`
	Course   *string `json:"course"`
	Property *string `json:"property"`
	Failed   bool    `json:"failedOnly"`
	Merged   bool    `json:"merged"`
}

// Scores 组装成绩数据。
//
// 三个字段的口径必须分清（与 SKILL 文档一致）：
//   - `stats` 始终按**全部成绩**统计，不受筛选影响；
//   - `scores` 是筛选后的原始记录，同一门课可能多条；
//   - `courses` 是筛选后再去重合并的课程清单，带 isRetake / recordCount。
func (s *Service) Scores(options Options) ScoresData {
	all := s.Snapshot.Scores()

	keyword := strings.TrimSpace(options.Course)
	records := make([]model.ScoreRecord, 0, len(all))
	for _, record := range all {
		if keyword != "" && !containsFold(record.CourseName, keyword) &&
			!containsFold(record.CourseID, keyword) {
			continue
		}
		if options.Property != nil && academic.ResolveProperty(record.Property) != *options.Property {
			continue
		}
		if options.Failed && (record.Passed || record.Pending) {
			continue
		}
		records = append(records, record)
	}
	records = sortScores(records)

	deduped := academic.Dedupe(records)
	courses := make([]CourseEntry, 0, len(deduped))
	for _, record := range deduped {
		courses = append(courses, CourseEntry{
			Record:      record,
			IsRetake:    record.IsRetake,
			RecordCount: record.RecordCount,
		})
	}

	var courseFilter *string
	if keyword != "" {
		value := keyword
		courseFilter = &value
	}
	var propertyFilter *string
	if options.Property != nil {
		value := options.Property.Label()
		propertyFilter = &value
	}

	return ScoresData{
		Stats:        academic.BuildAcademicStats(all),
		Scores:       records,
		Courses:      courses,
		Total:        len(all),
		DedupedCount: len(courses),
		Filters: FiltersBlock{
			Course:   courseFilter,
			Property: propertyFilter,
			Failed:   options.Failed,
			Merged:   options.Merged,
		},
		Years: s.Snapshot.ScoreYears(),
	}
}

// sortScores 按学期时间轴倒序（最近的在前），同学期内保持稳定。
func sortScores(records []model.ScoreRecord) []model.ScoreRecord {
	sorted := make([]model.ScoreRecord, len(records))
	copy(sorted, records)
	sort.SliceStable(sorted, func(left, right int) bool {
		leftKey := academic.SemesterSortKey(sorted[left].Year, sorted[left].Term)
		rightKey := academic.SemesterSortKey(sorted[right].Year, sorted[right].Term)
		return leftKey > rightKey
	})
	return sorted
}

// ExamsData 是考试载荷。
type ExamsData struct {
	Date  string           `json:"date"`
	All   bool             `json:"all"`
	Days  int              `json:"days"`
	Exams []ExamWithStatus `json:"exams"`
}

// Exams 组装考试数据。
func (s *Service) Exams(options Options) ExamsData {
	today, _ := s.anchor(options)
	days := 7
	if options.Days != nil {
		days = *options.Days
	}
	list := s.recentExams(today, days)
	if options.All {
		list = s.allExams(today)
	}
	if list == nil {
		list = []ExamWithStatus{}
	}
	return ExamsData{Date: today.String(), All: options.All, Days: days, Exams: list}
}

// PlanData 是培养方案载荷。
type PlanData struct {
	Groups               []model.PlanGroup        `json:"groups"`
	Progress             academic.CreditsProgress `json:"progress"`
	TotalRequiredCredits *float64                 `json:"totalRequiredCredits"`
}

// Plan 组装培养方案数据。
func (s *Service) Plan(options Options) PlanData {
	plan := s.Snapshot.Plan()
	groups := plan.Groups
	if groups == nil {
		groups = []model.PlanGroup{}
	}
	keyword := strings.TrimSpace(options.Group)
	filtered := make([]model.PlanGroup, 0, len(groups))
	for _, group := range groups {
		if keyword != "" && !containsFold(group.Name, keyword) &&
			!containsFold(group.Property, keyword) {
			continue
		}
		filtered = append(filtered, filterGroupCourses(group, keyword))
	}
	// 学分进度始终按完整方案计算，不受 --group 筛选影响。
	progress := academic.ComputeCreditsProgress(
		s.Snapshot.Scores(), groups, plan.TotalRequiredCredits, academic.EarnedCreditsRequiredOnly)

	return PlanData{Groups: filtered, Progress: progress, TotalRequiredCredits: plan.TotalRequiredCredits}
}

// filterGroupCourses 在按课组名筛选时保留全部候选课程；
// 按课程关键字筛选时只保留命中课程，避免把无关候选一起显示。
func filterGroupCourses(group model.PlanGroup, keyword string) model.PlanGroup {
	if keyword == "" || containsFold(group.Name, keyword) {
		if group.Courses == nil {
			group.Courses = []model.PlanCourse{}
		}
		return group
	}
	kept := make([]model.PlanCourse, 0, len(group.Courses))
	for _, course := range group.Courses {
		if containsFold(course.Name, keyword) || containsFold(course.Code, keyword) {
			kept = append(kept, course)
		}
	}
	group.Courses = kept
	return group
}

// NoticesData 是公告载荷。
type NoticesData struct {
	Notices []model.Notice `json:"notices"`
	Week    *int           `json:"week"`
	Weeks   []int          `json:"weeks"`
	Query   *string        `json:"query"`
	Limit   *int           `json:"limit"`
	Total   int            `json:"total"`
	// Empty 为真表示该周已缓存但确实没有公告（与「未缓存」区分）。
	Empty bool `json:"empty"`
}

// Notices 组装公告数据。
//
// 日期一律清空：来源没有日期字段，前端也做同样清洗，这里不臆造。
func (s *Service) Notices(options Options) NoticesData {
	weeksMap := s.Snapshot.NoticeWeeks()
	weeks := make([]int, 0, len(weeksMap))
	for key := range weeksMap {
		if week, ok := parseWeekKey(key); ok {
			weeks = append(weeks, week)
		}
	}
	sort.Ints(weeks)

	var list []model.Notice
	empty := false
	if options.Week != nil {
		key := itoa(*options.Week)
		if cached, ok := weeksMap[key]; ok {
			list = cached
			empty = len(cached) == 0
		} else {
			empty = true
		}
	} else {
		list = s.Snapshot.Notices()
	}
	list = academic.SanitizeNotices(list)

	keyword := strings.TrimSpace(options.Query)
	if keyword != "" {
		kept := make([]model.Notice, 0, len(list))
		for _, notice := range list {
			if containsFold(notice.Title, keyword) || containsFold(notice.Content, keyword) {
				kept = append(kept, notice)
			}
		}
		list = kept
	}
	if list == nil {
		list = []model.Notice{}
	}
	total := len(list)
	if options.Limit != nil && len(list) > *options.Limit {
		list = list[:*options.Limit]
	}

	var query *string
	if keyword != "" {
		query = &keyword
	}
	return NoticesData{
		Notices: list,
		Week:    options.Week,
		Weeks:   weeks,
		Query:   query,
		Limit:   options.Limit,
		Total:   total,
		Empty:   empty,
	}
}

// parseWeekKey 把周次键转成整数。
func parseWeekKey(key string) (int, bool) {
	value := 0
	if key == "" {
		return 0, false
	}
	for _, r := range key {
		if r < '0' || r > '9' {
			return 0, false
		}
		value = value*10 + int(r-'0')
	}
	return value, true
}

// itoa 把正整数转成字符串。
func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	digits := make([]byte, 0, 4)
	for value > 0 {
		digits = append([]byte{byte('0' + value%10)}, digits...)
		value /= 10
	}
	return string(digits)
}

// ProfileData 是学籍载荷。
type ProfileData struct {
	Profile model.Profile `json:"profile"`
	Masked  []string      `json:"masked"`
}

// MaskedProfileFields 是学籍里被打码的字段名，顺序固定。
var MaskedProfileFields = []string{"idCard", "phone", "email", "address"}

// Profile 返回打码后的学籍档案。
func (s *Service) Profile() ProfileData {
	return ProfileData{
		Profile: academic.MaskProfile(s.Snapshot.Profile()),
		Masked:  MaskedProfileFields,
	}
}

// CoursesData 是课程名录载荷。
type CoursesData struct {
	Total   int                   `json:"total"`
	Limit   *int                  `json:"limit"`
	Query   *string               `json:"query"`
	Courses []model.CurrentCourse `json:"courses"`
}

// Courses 组装本学期课程名录。
func (s *Service) Courses(options Options) CoursesData {
	all := s.Snapshot.CurrentCourses()
	keyword := strings.TrimSpace(options.Query)
	if keyword == "" {
		keyword = strings.TrimSpace(options.Course)
	}
	list := make([]model.CurrentCourse, 0, len(all))
	for _, course := range all {
		if keyword != "" && !containsFold(course.CourseName, keyword) &&
			!containsFold(course.CourseID, keyword) {
			continue
		}
		list = append(list, course)
	}
	if options.Limit != nil && len(list) > *options.Limit {
		list = list[:*options.Limit]
	}
	var query *string
	if keyword != "" {
		query = &keyword
	}
	return CoursesData{Total: len(all), Limit: options.Limit, Query: query, Courses: list}
}

// containsFold 是大小写无关的子串判断。
func containsFold(text, keyword string) bool {
	return strings.Contains(strings.ToLower(text), strings.ToLower(keyword))
}
