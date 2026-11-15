package academic

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

// school-calendar.json 是 `shared/school-calendar.json` 的副本。
//
// Go 的 go:embed 不能引用父目录，因此这里放一份副本随二进制走；
// `calendar_embed_test.go` 逐字节校验两份文件一致，防止副本漂移。
//
//go:embed school-calendar.json
var calendarJSON []byte

// Semester 是校历里的一个学期。
type Semester struct {
	Label           string `json:"label"`
	StartDate       string `json:"startDate"`
	TeachingEndDate string `json:"teachingEndDate"`
	PeriodEndDate   string `json:"periodEndDate"`
	TeachingWeeks   int    `json:"teachingWeeks"`
}

// Calendar 是校历文件结构。
type Calendar struct {
	AcademicYear string     `json:"academicYear"`
	VerifiedOn   string     `json:"verifiedOn"`
	SourceURL    string     `json:"sourceUrl"`
	Semesters    []Semester `json:"semesters"`
}

var (
	calendarOnce sync.Once
	calendar     Calendar
	calendarErr  error
)

// LoadCalendar 返回内嵌校历。解析失败只可能因为仓库里的 JSON 写坏了，
// 此时返回空校历并保留错误供诊断页展示，而不是 panic。
func LoadCalendar() (Calendar, error) {
	calendarOnce.Do(func() {
		calendarErr = json.Unmarshal(calendarJSON, &calendar)
	})
	return calendar, calendarErr
}

// Semesters 返回全部学期。解析失败时为空。
func Semesters() []Semester {
	loaded, _ := LoadCalendar()
	return loaded.Semesters
}

// SemesterOn 返回当天所在学期（含考试周，按 PeriodEndDate 收尾）。
//
// 校历未收录的日期返回 false——**绝不猜测**学期起点。
func SemesterOn(today Date) (Semester, bool) {
	day := today.DayNumber()
	for _, semester := range Semesters() {
		start, okStart := ParseISODate(semester.StartDate)
		end, okEnd := ParseISODate(semester.PeriodEndDate)
		if !okStart || !okEnd {
			continue
		}
		if day >= start.DayNumber() && day <= end.DayNumber() {
			return semester, true
		}
	}
	return Semester{}, false
}

// TeachingWeekOn 返回当前教学周（1 起）。假期或校历未收录时返回 0, false。
func TeachingWeekOn(today Date) (int, bool) {
	semester, ok := SemesterOn(today)
	if !ok {
		return 0, false
	}
	start, okStart := ParseISODate(semester.StartDate)
	end, okEnd := ParseISODate(semester.TeachingEndDate)
	if !okStart || !okEnd {
		return 0, false
	}
	day := today.DayNumber()
	if day < start.DayNumber() || day > end.DayNumber() {
		return 0, false
	}
	return int((day-start.DayNumber())/7) + 1, true
}

// TeachingWeeks 返回当前学期的教学周总数（校历未收录时按 20 周兜底）。
func TeachingWeeks(today Date) int {
	if semester, ok := SemesterOn(today); ok && semester.TeachingWeeks > 0 {
		return semester.TeachingWeeks
	}
	return 20
}

// WeekRange 是一个闭区间周次范围。
type WeekRange struct {
	Start int
	End   int
}

// WeekRanges 提取 `\d+(?:-\d+)?` 形式的区间，如「3-5,9-16周(双)」→ [{3,5},{9,16}]。
func WeekRanges(expression string) []WeekRange {
	runes := []rune(expression)
	ranges := make([]WeekRange, 0, 2)
	index := 0
	for index < len(runes) {
		if !isASCIIDigit(runes[index]) {
			index++
			continue
		}
		startBegin := index
		for index < len(runes) && isASCIIDigit(runes[index]) {
			index++
		}
		start, _ := strconv.Atoi(string(runes[startBegin:index]))
		end := start
		if index+1 < len(runes) && runes[index] == '-' && isASCIIDigit(runes[index+1]) {
			index++
			endBegin := index
			for index < len(runes) && isASCIIDigit(runes[index]) {
				index++
			}
			parsed, _ := strconv.Atoi(string(runes[endBegin:index]))
			// 与 JS `const to = end || start` 一致：缺省或 0 时退回起始周。
			if parsed != 0 {
				end = parsed
			}
		}
		ranges = append(ranges, WeekRange{Start: start, End: end})
	}
	return ranges
}

func isASCIIDigit(r rune) bool { return r >= '0' && r <= '9' }

// IsCourseActiveInWeek 判断课程在指定教学周是否上课。
//
// 单/双周是**全局前置过滤**（不是逐区间过滤），空表达式视为全学期有效。
func IsCourseActiveInWeek(weeks string, week int) bool {
	if weeks == "" {
		return true
	}
	oddOnly := strings.Contains(weeks, "单")
	evenOnly := strings.Contains(weeks, "双")
	if oddOnly && week%2 == 0 {
		return false
	}
	if evenOnly && week%2 != 0 {
		return false
	}
	ranges := WeekRanges(weeks)
	for _, item := range ranges {
		if week >= item.Start && week <= item.End {
			return true
		}
	}
	return len(ranges) == 0
}

// ExpandCourseWeeks 展开为具体教学周列表（单/双周过滤）。
func ExpandCourseWeeks(expression string, maxWeek int) []int {
	oddOnly := strings.Contains(expression, "单")
	evenOnly := strings.Contains(expression, "双")
	ranges := WeekRanges(expression)

	weeks := make([]int, 0, maxWeek)
	if len(ranges) == 0 {
		for week := 1; week <= maxWeek; week++ {
			weeks = append(weeks, week)
		}
	} else {
		for _, item := range ranges {
			for week := item.Start; week <= item.End && week <= maxWeek; week++ {
				weeks = append(weeks, week)
			}
		}
	}

	filtered := weeks[:0]
	for _, week := range weeks {
		if oddOnly && week%2 == 0 {
			continue
		}
		if evenOnly && week%2 != 0 {
			continue
		}
		filtered = append(filtered, week)
	}
	return filtered
}

// CombineSlots 是大节（1~6）的起止时间，来源：学校官方作息时间表。
var CombineSlots = [6]string{
	"08:10 - 09:50",
	"10:10 - 11:50",
	"13:30 - 15:10",
	"15:30 - 17:10",
	"18:00 - 19:40",
	"19:50 - 21:30",
}

// CombineSlotTime 返回第 period 大节的起止时间；越界返回空串。
func CombineSlotTime(period int) string {
	if period < 1 || period > len(CombineSlots) {
		return ""
	}
	return CombineSlots[period-1]
}

// CombineSlotStartTime 返回第 period 大节的开课时间。
func CombineSlotStartTime(period int) string {
	text := CombineSlotTime(period)
	if text == "" {
		return ""
	}
	return strings.TrimSpace(strings.SplitN(text, "-", 2)[0])
}

// CombineSlotEndMinutes 返回第 period 大节的下课时刻（当天分钟数）。
func CombineSlotEndMinutes(period int) (int, bool) {
	text := CombineSlotTime(period)
	if text == "" {
		return 0, false
	}
	parts := strings.SplitN(text, "-", 2)
	if len(parts) != 2 {
		return 0, false
	}
	hour, minute, ok := parseClockText(strings.TrimSpace(parts[1]))
	if !ok {
		return 0, false
	}
	return hour*60 + minute, true
}

// HasCombineSlotEnded 判断该大节是否已下课。下课时刻起即视为结束。
func HasCombineSlotEnded(period int, minutesOfDay int) bool {
	end, ok := CombineSlotEndMinutes(period)
	if !ok {
		return false
	}
	return minutesOfDay >= end
}

// parseClockText 解析 `H:MM` / `HH:MM`。
func parseClockText(text string) (int, int, bool) {
	parts := strings.SplitN(text, ":", 2)
	if len(parts) != 2 {
		return 0, 0, false
	}
	hour, err := strconv.Atoi(strings.TrimSpace(parts[0]))
	if err != nil {
		return 0, 0, false
	}
	minute, err := strconv.Atoi(strings.TrimSpace(parts[1]))
	if err != nil {
		return 0, 0, false
	}
	if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
		return 0, 0, false
	}
	return hour, minute, true
}

// TimetableDayDate 返回所选教学周第 day（1~7）天的 `MM/DD` 标签。
//
// 无法确定学期时退回「以今天所在周为基准」的推算；仍无法确定时返回空串，
// **不推测日期**。
func TimetableDayDate(currentWeek, selectedWeek, day int, today Date) string {
	if day < 1 || day > 7 || currentWeek < 1 || currentWeek > 26 {
		return ""
	}
	if semester, ok := SemesterOn(today); ok {
		if selectedWeek < 1 || selectedWeek > semester.TeachingWeeks {
			return ""
		}
		start, okStart := ParseISODate(semester.StartDate)
		if !okStart {
			return ""
		}
		offset := (selectedWeek-1)*7 + day - 1
		target := FromDayNumber(start.DayNumber() + int64(offset))
		return fmt.Sprintf("%02d/%02d", target.Month, target.Day)
	}
	if selectedWeek < 1 || selectedWeek > 26 {
		return ""
	}
	monday := today.DayNumber() - int64(today.Weekday()-1)
	offset := (selectedWeek-currentWeek)*7 + day - 1
	target := FromDayNumber(monday + int64(offset))
	return fmt.Sprintf("%02d/%02d", target.Month, target.Day)
}

// ExamTime 是解析出的考试时间。
type ExamTime struct {
	// Date 是考试日期（当天 00:00）。
	Date Date
	// Start 是开考时刻；原文缺时间时 ok 为 false。
	StartHour   int
	StartMinute int
	HasStart    bool
	// EndHour / EndMinute 是结束时刻。
	EndHour   int
	EndMinute int
	HasEnd    bool
}

// ParseExamTime 解析「2026-01-05 14:00-16:00」「2026年1月5日 14:00~16:00」等写法。
//
// 只有日期时不编造开考时间；完全无法识别（如「待定」）返回 false。
func ParseExamTime(text string) (ExamTime, bool) {
	runes := []rune(text)
	year, month, day, used, ok := parseDatePart(runes)
	if !ok {
		return ExamTime{}, false
	}
	result := ExamTime{Date: Date{Year: year, Month: month, Day: day}}
	startHour, startMinute, endHour, endMinute, hasRange := parseTimeRange(runes[used:])
	if hasRange {
		result.StartHour, result.StartMinute, result.HasStart = startHour, startMinute, true
		result.EndHour, result.EndMinute, result.HasEnd = endHour, endMinute, true
	}
	return result, true
}

// parseDatePart 匹配 `(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})`。
func parseDatePart(runes []rune) (int, int, int, int, bool) {
	for index := 0; index+4 <= len(runes); index++ {
		if !allDigits(runes[index : index+4]) {
			continue
		}
		year, _ := strconv.Atoi(string(runes[index : index+4]))
		cursor := index + 4
		if cursor >= len(runes) || !isDateSeparator(runes[cursor]) {
			continue
		}
		cursor++
		monthStart := cursor
		for cursor < len(runes) && cursor-monthStart < 2 && isASCIIDigit(runes[cursor]) {
			cursor++
		}
		if cursor == monthStart || cursor >= len(runes) {
			continue
		}
		month, _ := strconv.Atoi(string(runes[monthStart:cursor]))
		if !isDateSeparator(runes[cursor]) {
			continue
		}
		cursor++
		dayStart := cursor
		for cursor < len(runes) && cursor-dayStart < 2 && isASCIIDigit(runes[cursor]) {
			cursor++
		}
		if cursor == dayStart {
			continue
		}
		day, _ := strconv.Atoi(string(runes[dayStart:cursor]))
		if month < 1 || month > 12 || day < 1 || day > 31 {
			continue
		}
		return year, month, day, cursor, true
	}
	return 0, 0, 0, 0, false
}

func allDigits(runes []rune) bool {
	for _, r := range runes {
		if !isASCIIDigit(r) {
			return false
		}
	}
	return len(runes) > 0
}

func isDateSeparator(r rune) bool {
	return r == '-' || r == '/' || r == '.' || r == '年' || r == '月'
}

func isTimeSeparator(r rune) bool {
	switch r {
	case '-', '–', '—', '~', '～', '至':
		return true
	default:
		return false
	}
}

// parseTimeRange 匹配 `(\d{1,2}):(\d{2})\s*(?:[-–—~～]|至|to)\s*(\d{1,2}):(\d{2})`。
func parseTimeRange(runes []rune) (int, int, int, int, bool) {
	index := 0
	for index < len(runes) {
		hour, minute, end, ok := parseClockAt(runes, index)
		if !ok {
			index++
			continue
		}
		cursor := end
		for cursor < len(runes) && isSpace(runes[cursor]) {
			cursor++
		}
		if cursor >= len(runes) {
			return 0, 0, 0, 0, false
		}
		skip := 0
		switch {
		case isTimeSeparator(runes[cursor]):
			skip = 1
		case (runes[cursor] == 't' || runes[cursor] == 'T') && cursor+1 < len(runes) &&
			(runes[cursor+1] == 'o' || runes[cursor+1] == 'O'):
			skip = 2
		default:
			index = end
			continue
		}
		cursor += skip
		for cursor < len(runes) && isSpace(runes[cursor]) {
			cursor++
		}
		endHour, endMinute, _, okEnd := parseClockAt(runes, cursor)
		if okEnd {
			return hour, minute, endHour, endMinute, true
		}
		index = end
	}
	return 0, 0, 0, 0, false
}

// parseClockAt 在给定位置解析 `H:MM` / `HH:MM`。
func parseClockAt(runes []rune, from int) (int, int, int, bool) {
	cursor := from
	hourStart := cursor
	for cursor < len(runes) && cursor-hourStart < 2 && isASCIIDigit(runes[cursor]) {
		cursor++
	}
	if cursor == hourStart || cursor >= len(runes) || runes[cursor] != ':' {
		return 0, 0, 0, false
	}
	hour, _ := strconv.Atoi(string(runes[hourStart:cursor]))
	cursor++
	minuteStart := cursor
	for cursor < len(runes) && cursor-minuteStart < 2 && isASCIIDigit(runes[cursor]) {
		cursor++
	}
	if cursor-minuteStart != 2 {
		return 0, 0, 0, false
	}
	minute, _ := strconv.Atoi(string(runes[minuteStart:cursor]))
	if hour > 23 || minute > 59 {
		return 0, 0, 0, false
	}
	return hour, minute, cursor, true
}
