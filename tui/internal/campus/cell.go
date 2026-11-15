package campus

import "strings"

// 与 `web/src/utils/courseCell.js` 的 splitCourseCellFields 逐条对应。
//
// 教务单元格的常规格式是「课程名 / 地点 / 教师 / 周次 / 学时类型」五行，
// 但行数并不固定：没有教师的课程（军训、体育）会少一行，周次之后也可能
// 多出一行说明。以「周次行」（同时含数字与「周」）为锚点定位，
// 再按相对位置取其余字段，缺行也不会错位。

// isWeeksLine 判断一行是否是周次表达式，如「1-16周」「3-5,9-16周(双)」。
func isWeeksLine(line string) bool {
	if !strings.Contains(line, "周") {
		return false
	}
	for _, char := range line {
		if char >= '0' && char <= '9' {
			return true
		}
	}
	return false
}

// courseCellFields 是单元格拆解结果。
type courseCellFields struct {
	Location  string
	Teacher   string
	Weeks     string
	HoursType string
}

// splitCourseCellFields 从单元格各行拆出地点 / 教师 / 周次 / 学时类型。
func splitCourseCellFields(lines []string) courseCellFields {
	fallback := courseCellFields{
		Location:  at(lines, 1),
		Teacher:   at(lines, 2),
		Weeks:     at(lines, 3),
		HoursType: at(lines, 4),
	}
	if len(lines) <= 1 {
		return fallback
	}

	rest := lines[1:]
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
		// 只剩一行时无法区分地点与教师，按地点处理（教务中地点通常必填）
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

	return courseCellFields{
		Location:  location,
		Teacher:   teacher,
		Weeks:     rest[weeksIndex],
		HoursType: hoursType,
	}
}

// at 返回下标处的元素，越界返回空串。
func at(lines []string, index int) string {
	if index < 0 || index >= len(lines) {
		return ""
	}
	return lines[index]
}
