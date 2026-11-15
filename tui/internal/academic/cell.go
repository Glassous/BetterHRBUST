package academic

import (
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// 本文件复刻 `web/src/utils/courseCell.js` 的课表格子字段拆解。
//
// 教务单元格的常规格式是「课程名 / 地点 / 教师 / 周次 / 学时类型」五行，
// 但行数并不固定：
//   - 没录入任课教师的课程（军训、体育等实践类）会少一行，后续字段整体前移；
//   - 部分课程在周次之后还会多出一行说明（如「一上午」），学时类型被挤到下一行。
//
// 按固定下标读取就会错位：缺教师时「学时类型」被当成周次，周次判断随之失效，
// 已经结束的课程在后续教学周仍然显示为正常颜色。因此以「周次行」为锚点定位。

// CellFields 是拆解后的课表单元格字段。
type CellFields struct {
	Location  string
	Teacher   string
	Weeks     string
	HoursType string
}

// isWeeksLine 判断某一行是否是周次表达式：至少含一个数字且含「周」。
func isWeeksLine(line string) bool {
	if !strings.Contains(line, "周") {
		return false
	}
	for _, r := range line {
		if isASCIIDigit(r) {
			return true
		}
	}
	return false
}

// SplitCourseCellFields 从单元格各行拆出地点 / 教师 / 周次 / 学时类型。
//
// `lines[0]` 是课程名行。无法识别周次行时退回常规五行位置，行为与前端一致。
func SplitCourseCellFields(lines []string) CellFields {
	fallback := CellFields{
		Location:  at(lines, 1),
		Teacher:   at(lines, 2),
		Weeks:     at(lines, 3),
		HoursType: at(lines, 4),
	}

	rest := lines
	if len(rest) > 0 {
		rest = rest[1:]
	}
	weeksIndex := -1
	for index, line := range rest {
		if isWeeksLine(line) {
			weeksIndex = index
			break
		}
	}
	if weeksIndex < 0 {
		return fallback
	}

	before := rest[:weeksIndex]
	after := rest[weeksIndex+1:]

	var location, teacher string
	switch {
	case len(before) >= 2:
		location = before[0]
		teacher = strings.Join(before[1:], " ")
	case len(before) == 1:
		// 只剩一行时无法可靠区分地点与教师，按地点处理：
		// 教务中地点通常必填、教师可缺省。即使实际是教师名，
		// 也只影响这一行展示，不影响周次判断。
		location = before[0]
	}

	hoursType := ""
	for _, line := range after {
		if strings.Contains(line, "学时") {
			hoursType = line
			break
		}
	}
	if hoursType == "" && len(after) > 0 {
		hoursType = after[0]
	}

	return CellFields{
		Location:  location,
		Teacher:   teacher,
		Weeks:     rest[weeksIndex],
		HoursType: hoursType,
	}
}

// at 安全取下标。
func at(lines []string, index int) string {
	if index < 0 || index >= len(lines) {
		return ""
	}
	return lines[index]
}

// NormalizeTimetable 用 `RawLines` 重算单元格字段，并丢弃没有课程名的占位格子。
//
// 只对带了原始行（`len(rawLines) >= 2`）的格子生效：
// 新版缓存已经由前端算好字段、`rawLines` 为空；旧缓存才有错位的风险。
func NormalizeTimetable(table model.Timetable) model.Timetable {
	out := model.Timetable{
		Cells:      make([]model.TimetableCell, 0, len(table.Cells)),
		Unarranged: table.Unarranged,
	}
	for _, cell := range table.Cells {
		if strings.TrimSpace(cell.CourseName) == "" {
			continue
		}
		if len(cell.RawLines) >= 2 {
			fields := SplitCourseCellFields(cell.RawLines)
			cell.Location = fields.Location
			cell.Teacher = fields.Teacher
			cell.Weeks = fields.Weeks
			cell.HoursType = fields.HoursType
		}
		out.Cells = append(out.Cells, cell)
	}
	return out
}

// SanitizeNotices 清空公告日期。
func SanitizeNotices(notices []model.Notice) []model.Notice {
	out := make([]model.Notice, len(notices))
	for index, notice := range notices {
		notice.Date = ""
		out[index] = notice
	}
	return out
}
