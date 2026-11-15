// Package query 把缓存快照转成各命令的输出载荷。
//
// 这是终端界面**唯一**的数据出口：页面渲染、`--json` 输出、LLM 工具调用
// 三处都调用同一批方法，因此三者永远不会给出不一致的数字。
//
// 口径与 `BetterHRBUSTCLI` 对齐：字段名 camelCase、成功 / 失败结构一致、
// 退出码一致。唯一有意的差异是学籍一律打码（CLI 的 summary 会带出原始证件号，
// 那是需要修正的泄露面，不应该被复制）。
package query

import (
	"fmt"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// Clock 是查询用的时间基准。所有涉及"今天""当前周"的判断都走它，
// 便于测试与 `--date` 复现。
type Clock struct {
	Today       academic.Date
	MinuteOfDay int
	Location    *time.Location
}

// SystemClock 返回本机当前时间。
//
// 这是整个程序里唯一读系统时钟的地方（除 doctor 的文件时间外）。
func SystemClock() Clock {
	today, minutes := academic.Now()
	return Clock{Today: today, MinuteOfDay: minutes, Location: time.Local}
}

// At 返回指定日期 00:00 的时钟，用于 `--date`。
func (c Clock) At(date academic.Date) Clock {
	return Clock{Today: date, MinuteOfDay: 0, Location: c.Location}
}

// Service 是查询入口。
type Service struct {
	// Snapshot 是只读缓存快照。
	Snapshot *model.Snapshot
	// Clock 是可注入的时间基准。
	Clock Clock
	// GeneratedAt 是本次输出的生成时刻，写进 envelope。
	GeneratedAt time.Time
	// Version 是本程序版本，写进 status / doctor。
	Version string
}

// New 构造查询服务。
func New(snapshot *model.Snapshot, clock Clock, version string) *Service {
	return &Service{
		Snapshot:    snapshot,
		Clock:       clock,
		GeneratedAt: time.Now(),
		Version:     version,
	}
}

// Readiness 返回当前就绪状态。
func (s *Service) Readiness() store.Readiness { return store.Evaluate(s.Snapshot) }

// Offline 返回是否处于「无登录态、只有本地缓存」的状态。
func (s *Service) Offline() bool { return s.Readiness().Offline }

// LoggedIn 返回是否记录了登录态。
func (s *Service) LoggedIn() bool { return s.Snapshot.LoggedIn() }

// Account 返回账号。
func (s *Service) Account() string { return s.Snapshot.Account() }

// LastSyncText 返回上次同步时间文本。
func (s *Service) LastSyncText() string {
	meta := s.Snapshot.SyncMeta()
	if meta.LastSyncAt > 0 {
		return academic.FormatTimestamp(int64(meta.LastSyncAt), s.location())
	}
	if meta.LastSyncDate != "" {
		return meta.LastSyncDate + "（仅日期）"
	}
	return "未记录"
}

// location 返回时区，缺省用本机时区。
func (s *Service) location() *time.Location {
	if s.Clock.Location != nil {
		return s.Clock.Location
	}
	return time.Local
}

// GeneratedAtText 返回 `2006-01-02 15:04` 形式的生成时刻。
func (s *Service) GeneratedAtText() string {
	return s.GeneratedAt.In(s.location()).Format("2006-01-02 15:04")
}

// Options 是命令参数，与 `BetterHRBUSTCLI` 的选项一一对应。
type Options struct {
	// Week 指定教学周（1..26）。
	Week *int
	// Day 指定星期（1..7）。
	Day *int
	// Today 只看今天剩余的课。
	Today bool
	// Base 使用小节课表。
	Base bool
	// Date 指定基准日期。
	Date *academic.Date
	// Course 课程关键字。
	Course string
	// Property 选课属性筛选。
	Property *academic.Property
	// Failed 只看挂科。
	Failed bool
	// Merged 输出去重合并后的课程清单。
	Merged bool
	// Days 考试窗口天数。
	Days *int
	// All 考试返回全部。
	All bool
	// Limit 限制条数。
	Limit *int
	// Query 公告 / 课程名录关键字。
	Query string
	// Group 培养方案课组关键字。
	Group string
	// Strict 检查严格模式。
	Strict bool
}

// Validate 校验参数范围，返回可直接展示的中文错误。
func (o Options) Validate() error {
	if o.Week != nil && (*o.Week < 1 || *o.Week > 26) {
		return fmt.Errorf("--week 取值范围为 1..26")
	}
	if o.Day != nil && (*o.Day < 1 || *o.Day > 7) {
		return fmt.Errorf("--day 取值范围为 1..7")
	}
	if o.Days != nil && (*o.Days < 1 || *o.Days > 3650) {
		return fmt.Errorf("--days 取值范围为 1..3650")
	}
	if o.Limit != nil && *o.Limit < 1 {
		return fmt.Errorf("--limit 必须大于 0")
	}
	return nil
}

// anchor 返回本次查询的基准日期，以及它是否就是「今天」。
//
// `--date` 把基准日挪到指定日期；此时不再裁剪已下课的课程
// （指定日期查询的是「那一天有什么课」，不是「还剩下什么课」）。
func (s *Service) anchor(options Options) (academic.Date, bool) {
	if options.Date != nil {
		return *options.Date, options.Date.DayNumber() == s.Clock.Today.DayNumber()
	}
	return s.Clock.Today, true
}

// dayCourses 返回某天的课程。
func (s *Service) dayCourses(date academic.Date, dropEnded bool) []model.TimetableCell {
	week, ok := academic.TeachingWeekOn(date)
	if !ok {
		return nil
	}
	weekday := date.Weekday()
	minutes := 0
	if dropEnded {
		minutes = s.Clock.MinuteOfDay
	}

	cells := make([]model.TimetableCell, 0, 8)
	table := academic.NormalizeTimetable(s.Snapshot.Timetable(false))
	for _, cell := range table.Cells {
		if cell.Day != weekday || !academic.IsCourseActiveInWeek(cell.Weeks, week) {
			continue
		}
		if dropEnded && academic.HasCombineSlotEnded(cell.SectionIndex, minutes) {
			continue
		}
		cells = append(cells, cell)
	}
	sortBySection(cells)
	return cells
}

// examStatus 返回考试的相对状态。
//
// 基准日是**本次查询的锚点**而不是系统当前日期：`--date` 把基准挪到
// 2026-11-13 时，倒计时也必须相对那天计算，否则脚本拿到的数字是错的。
func (s *Service) examStatus(parsed academic.ExamTime, ok bool, today academic.Date) string {
	if !ok {
		return "时间未知"
	}
	days := parsed.Date.DayNumber() - today.DayNumber()
	switch {
	case days < 0:
		return "已结束"
	case days == 0:
		return "今日考试"
	default:
		return fmt.Sprintf("%d 天后", days)
	}
}

// recentExams 返回近期考试：未来全部 + 过去 days 天，按时间升序。
//
// 时间无法解析的记录一并返回——宁可显示「时间未知」也不隐藏。
func (s *Service) recentExams(today academic.Date, days int) []ExamWithStatus {
	todayNumber := today.DayNumber()
	window := int64(days)

	type entry struct {
		item   model.ExamRecord
		parsed academic.ExamTime
		ok     bool
		order  int64
	}
	entries := make([]entry, 0, len(s.Snapshot.Exams()))
	for _, exam := range s.Snapshot.Exams() {
		parsed, ok := academic.ParseExamTime(exam.Time)
		if exam.Time != "" {
			if ok && parsed.Date.DayNumber() < todayNumber-window {
				continue
			}
		}
		order := int64(1<<62 - 1)
		if ok {
			order = parsed.Date.DayNumber()
		}
		entries = append(entries, entry{item: exam, parsed: parsed, ok: ok, order: order})
	}
	sortEntries(entries, func(left, right entry) bool { return left.order < right.order })

	out := make([]ExamWithStatus, 0, len(entries))
	for _, item := range entries {
		out = append(out, ExamWithStatus{
			Exam:   item.item,
			Status: s.examStatus(item.parsed, item.ok, today),
		})
	}
	return out
}

// allExams 返回全部考试，按时间升序。倒计时相对 `today` 计算。
func (s *Service) allExams(today academic.Date) []ExamWithStatus {
	type entry struct {
		item   model.ExamRecord
		parsed academic.ExamTime
		ok     bool
		order  int64
	}
	entries := make([]entry, 0, len(s.Snapshot.Exams()))
	for _, exam := range s.Snapshot.Exams() {
		parsed, ok := academic.ParseExamTime(exam.Time)
		order := int64(1<<62 - 1)
		if ok {
			order = parsed.Date.DayNumber()
		}
		entries = append(entries, entry{item: exam, parsed: parsed, ok: ok, order: order})
	}
	sortEntries(entries, func(left, right entry) bool { return left.order < right.order })

	out := make([]ExamWithStatus, 0, len(entries))
	for _, item := range entries {
		out = append(out, ExamWithStatus{
			Exam:   item.item,
			Status: s.examStatus(item.parsed, item.ok, today),
		})
	}
	return out
}

// sortBySection 按大节序号升序排列。
func sortBySection(cells []model.TimetableCell) {
	for outer := 1; outer < len(cells); outer++ {
		for inner := outer; inner > 0 && cells[inner].SectionIndex < cells[inner-1].SectionIndex; inner-- {
			cells[inner], cells[inner-1] = cells[inner-1], cells[inner]
		}
	}
}

// sortEntries 是泛型插入排序，输入规模很小（考试最多几十条）。
func sortEntries[T any](items []T, less func(T, T) bool) {
	for outer := 1; outer < len(items); outer++ {
		for inner := outer; inner > 0 && less(items[inner], items[inner-1]); inner-- {
			items[inner], items[inner-1] = items[inner-1], items[inner]
		}
	}
}
