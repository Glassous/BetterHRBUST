package query

import (
	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// 本文件是给 LLM 用的瘦身投影。
//
// 目的有两个，顺序也重要：
//  1. **隐私**：任何字段在离开本进程前都必须已经打码，且不携带与问题无关的标识
//     （内部 ID、照片地址）。这里没有「先给原始值再脱敏」的代码路径。
//  2. **token 纪律**：课表的 rawLines、公告全文之外的冗余字段都不进上下文。

// leanCell 是剥掉原始行与内部 id 的课表格子。
type leanCell struct {
	CourseID     string `json:"courseId,omitempty"`
	CourseName   string `json:"courseName"`
	Teacher      string `json:"teacher,omitempty"`
	Location     string `json:"location,omitempty"`
	Weeks        string `json:"weeks,omitempty"`
	HoursType    string `json:"hoursType,omitempty"`
	Day          int    `json:"day,omitempty"`
	SectionIndex int    `json:"sectionIndex,omitempty"`
	SectionLabel string `json:"sectionLabel,omitempty"`
	// Slot 是换算后的起止时间，模型据此回答"几点上课"。
	Slot string `json:"slot,omitempty"`
}

// leanCells 批量投影。
func leanCells(cells []model.TimetableCell) []leanCell {
	out := make([]leanCell, 0, len(cells))
	for _, cell := range cells {
		out = append(out, leanCell{
			CourseID:     cell.CourseID,
			CourseName:   cell.CourseName,
			Teacher:      cell.Teacher,
			Location:     cell.Location,
			Weeks:        cell.Weeks,
			HoursType:    cell.HoursType,
			Day:          cell.Day,
			SectionIndex: cell.SectionIndex,
			SectionLabel: cell.SectionLabel,
			Slot:         academic.CombineSlotTime(cell.SectionIndex),
		})
	}
	return out
}

// compactSummary 投影概览。
func compactSummary(data SummaryData) map[string]any {
	return map[string]any{
		"profile":       data.Profile,
		"date":          data.Date,
		"isToday":       data.IsToday,
		"semester":      data.Semester,
		"stats":         data.Stats,
		"todayCourses":  leanCells(data.TodayCourses),
		"upcomingExams": data.UpcomingExams,
	}
}

// compactTimetable 投影课程表：按天分组的课程改用瘦身格子。
func compactTimetable(data TimetableData) map[string]any {
	days := make([]map[string]any, 0, len(data.Days))
	for _, block := range data.Days {
		days = append(days, map[string]any{
			"day":     block.Day,
			"weekday": block.Weekday,
			"date":    block.Date,
			"courses": leanCells(block.Courses),
		})
	}
	return map[string]any{
		"week":          data.Week,
		"currentWeek":   data.CurrentWeek,
		"teachingWeeks": data.TeachingWeeks,
		"base":          data.Base,
		"holiday":       data.Holiday,
		"days":          days,
		"unarranged":    data.Unarranged,
	}
}

// compactScores 投影成绩：统计原样保留，记录与课程清单按上限截断。
func compactScores(data ScoresData, limit int) ScoresData {
	out := data
	if limit > 0 && len(out.Scores) > limit {
		out.Scores = out.Scores[:limit]
	}
	if limit > 0 && len(out.Courses) > limit {
		out.Courses = out.Courses[:limit]
	}
	return out
}

// compactPlan 投影培养方案：课组的候选课程可能上百门，只保留计数与前若干门。
func compactPlan(data PlanData) map[string]any {
	groups := make([]map[string]any, 0, len(data.Groups))
	for _, group := range data.Groups {
		courses := group.Courses
		truncated := false
		if len(courses) > MaxToolRecords {
			courses = courses[:MaxToolRecords]
			truncated = true
		}
		groups = append(groups, map[string]any{
			"id":              group.ID,
			"name":            group.Name,
			"property":        group.Property,
			"requiredCredits": group.RequiredCredits,
			"requiredCourses": group.RequiredCourses,
			"earnedCredits":   group.EarnedCredits,
			"passedCourses":   group.PassedCourses,
			"courseCount":     len(group.Courses),
			"courses":         courses,
			"truncated":       truncated,
		})
	}
	return map[string]any{
		"groups":               groups,
		"progress":             data.Progress,
		"totalRequiredCredits": data.TotalRequiredCredits,
	}
}
