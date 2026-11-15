package model

// 本文件把「map[string]any → 结构体」的转换显式写出来。
//
// 不用 `encoding/json` 的结构体标签直接反序列化，原因有二：
//  1. 需要复现 Rust 侧不对称的类型容错（见 flex.go 的文件注释）；
//  2. 需要逐条容错——一条记录字段类型异常只丢这一条，不能让整张表清空。

// DecodeScore 把一条缓存记录转成 ScoreRecord。
func DecodeScore(object map[string]any) ScoreRecord {
	return ScoreRecord{
		Year:        text(object, "year"),
		Term:        text(object, "term"),
		CourseID:    text(object, "courseId"),
		CourseName:  text(object, "courseName"),
		CourseSeq:   text(object, "courseSeq"),
		CourseGroup: text(object, "courseGroup"),
		Score:       text(object, "score"),
		Credit:      number(object, "credit"),
		Hours:       number(object, "hours"),
		Property:    text(object, "property"),
		Remark:      text(object, "remark"),
		ExamType:    text(object, "examType"),
		PassMark:    text(object, "passMark"),
		Passed:      flag(object, "passed"),
		Pending:     flag(object, "pending"),
	}
}

// DecodeTimetableCell 把一条课表格子转成 TimetableCell。
func DecodeTimetableCell(object map[string]any) TimetableCell {
	return TimetableCell{
		ID:           text(object, "id"),
		CourseID:     text(object, "courseId"),
		Day:          count(object, "day"),
		SectionIndex: count(object, "sectionIndex"),
		SectionLabel: text(object, "sectionLabel"),
		CourseName:   text(object, "courseName"),
		CourseSeq:    text(object, "courseSeq"),
		Location:     text(object, "location"),
		Teacher:      text(object, "teacher"),
		Weeks:        text(object, "weeks"),
		HoursType:    text(object, "hoursType"),
		RawLines:     texts(object, "rawLines"),
	}
}

// DecodeUnarranged 把一条未安排课程转成 UnarrangedCourse。
func DecodeUnarranged(object map[string]any) UnarrangedCourse {
	return UnarrangedCourse{
		CourseID:   text(object, "courseId"),
		CourseName: text(object, "courseName"),
		CourseSeq:  text(object, "courseSeq"),
		Teacher:    text(object, "teacher"),
		MergeClass: text(object, "mergeClass"),
		Weeks:      text(object, "weeks"),
		Day:        text(object, "day"),
		Location:   text(object, "location"),
	}
}

// DecodeExam 把一条考试记录转成 ExamRecord。
func DecodeExam(object map[string]any) ExamRecord {
	return ExamRecord{
		CourseID:   text(object, "courseId"),
		CourseName: text(object, "courseName"),
		Time:       text(object, "time"),
		Location:   text(object, "location"),
		Property:   text(object, "property"),
	}
}

// DecodeNotice 把一条公告转成 Notice。
//
// `date` 一律清空：教务处公告列表本身没有日期字段，
// 前端也做同样的清洗（`sanitizeNotices`），这里保持一致，绝不臆造日期。
func DecodeNotice(object map[string]any) Notice {
	return Notice{
		Title:   text(object, "title"),
		Content: text(object, "content"),
		Date:    "",
	}
}

// DecodePlanCourse 把一条培养方案候选课程转成 PlanCourse。
func DecodePlanCourse(object map[string]any) PlanCourse {
	return PlanCourse{
		Code:     text(object, "code"),
		Name:     text(object, "name"),
		Credit:   number(object, "credit"),
		Hours:    number(object, "hours"),
		Property: text(object, "property"),
	}
}

// DecodePlanGroup 把一个课组转成 PlanGroup。
func DecodePlanGroup(object map[string]any) PlanGroup {
	group := PlanGroup{
		ID:              text(object, "id"),
		Name:            text(object, "name"),
		Property:        text(object, "property"),
		RequiredCredits: number(object, "requiredCredits"),
		RequiredCourses: count(object, "requiredCourses"),
		EarnedCredits:   number(object, "earnedCredits"),
		PassedCourses:   count(object, "passedCourses"),
	}
	for _, child := range objects(object, "courses") {
		group.Courses = append(group.Courses, DecodePlanCourse(child))
	}
	return group
}

// DecodePlan 把整份培养方案转成 Plan。
func DecodePlan(object map[string]any) Plan {
	plan := Plan{TotalRequiredCredits: optNumber(object, "totalRequiredCredits")}
	for _, child := range objects(object, "groups") {
		plan.Groups = append(plan.Groups, DecodePlanGroup(child))
	}
	return plan
}

// DecodeCurrentCourse 把一条本学期课程转成 CurrentCourse。
func DecodeCurrentCourse(object map[string]any) CurrentCourse {
	return CurrentCourse{
		CourseID:   text(object, "courseId"),
		CourseSeq:  text(object, "courseSeq"),
		CourseName: text(object, "courseName"),
		Teacher:    text(object, "teacher"),
		Credit:     number(object, "credit"),
		Hours:      number(object, "hours"),
	}
}

// DecodeProfileChange 把一条学籍异动转成 ProfileChange。
func DecodeProfileChange(object map[string]any) ProfileChange {
	return ProfileChange{
		Kind:   text(object, "type"),
		Date:   text(object, "date"),
		Reason: text(object, "reason"),
		Remark: text(object, "remark"),
	}
}

// DecodeProfile 把学籍档案转成 Profile。
func DecodeProfile(object map[string]any) Profile {
	profile := Profile{
		StudentNumber: text(object, "studentNumber"),
		InternalID:    text(object, "internalId"),
		RealName:      text(object, "realName"),
		College:       text(object, "college"),
		Major:         text(object, "major"),
		Direction:     text(object, "direction"),
		Grade:         text(object, "grade"),
		ClassName:     text(object, "className"),
		StudentType:   text(object, "studentType"),
		Status:        text(object, "status"),
		IDCard:        text(object, "idCard"),
		Email:         text(object, "email"),
		Phone:         text(object, "phone"),
		Address:       text(object, "address"),
		PostalCode:    text(object, "postalCode"),
		PhotoURL:      text(object, "photoUrl"),
	}
	for _, child := range objects(object, "changes") {
		profile.Changes = append(profile.Changes, DecodeProfileChange(child))
	}
	return profile
}

// DecodeSyncMeta 把同步元信息转成 SyncMeta。
func DecodeSyncMeta(object map[string]any) SyncMeta {
	return SyncMeta{
		LastSyncAt:   stamp(object, "lastSyncAt"),
		LastSyncDate: text(object, "lastSyncDate"),
		LastError:    text(object, "lastError"),
	}
}

// DecodeYearOption 把一条学年选项转成 YearOption。
func DecodeYearOption(object map[string]any) YearOption {
	return YearOption{Value: text(object, "value"), Label: text(object, "label")}
}

// DecodeTimetable 把整张课表转成 Timetable。
func DecodeTimetable(object map[string]any) Timetable {
	var table Timetable
	for _, child := range objects(object, "cells") {
		table.Cells = append(table.Cells, DecodeTimetableCell(child))
	}
	for _, child := range objects(object, "unarranged") {
		table.Unarranged = append(table.Unarranged, DecodeUnarranged(child))
	}
	return table
}

// DecodeList 是 decodeList 的导出形式，供测试夹具直接传入已解析的数组。
func DecodeList[T any](items []any, decode func(map[string]any) T) []T {
	out := make([]T, 0, len(items))
	for _, item := range items {
		object := asMap(item)
		if object == nil {
			continue
		}
		out = append(out, decode(object))
	}
	return out
}
