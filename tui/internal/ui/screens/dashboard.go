package screens

import (
	"fmt"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// dashboardCard 是概览页的一张卡片。
type dashboardCard int

const (
	cardIdentity dashboardCard = iota
	cardSemester
	cardStats
	cardCourses
	cardExams
	cardHealth
	cardCount
)

// cardHealthIndex 是缓存健康卡在焦序中的位置。
const cardHealthIndex = cardHealth

// dashboardPayload 把概览、状态与本周课表一起带回。
//
// 一次查询取三份而不是三次往返：它们共用同一个内存快照，没有额外 IO，
// 却能让概览页把"今天只有一门课"时剩下的空间填成真正有用的信息。
type dashboardPayload struct {
	Summary   query.SummaryData
	Status    query.StatusData
	Timetable query.TimetableData
}

// Dashboard 是概览页：卡片栅格 + 可聚焦跳转。
//
// 布局按宽度分档：宽屏两列、中屏两列窄版、窄屏整列堆叠。
// 每张卡片都能用 tab 聚焦、enter 跳到对应页面，和前端仪表盘
// "卡片即入口"的心智一致。
type Dashboard struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.SummaryData
	stat  query.StatusData
	week  query.TimetableData
	focus components.Focus

	keys struct {
		Next  key.Binding
		Prev  key.Binding
		Open  key.Binding
		Score key.Binding
	}
}

// NewDashboard 构造概览页。
func NewDashboard(deps ui.Deps) ui.Screen {
	screen := &Dashboard{deps: deps}
	screen.ScreenID = ui.ScreenDashboard
	screen.ScreenTitle = "概览"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(cardCount))
	screen.keys.Next = key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "下一张卡片"))
	screen.keys.Prev = key.NewBinding(key.WithKeys("shift+tab"), key.WithHelp("shift+tab", "上一张"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "打开"))
	screen.keys.Score = key.NewBinding(key.WithKeys("s"), key.WithHelp("s", "看成绩"))
	return screen
}

// Init 加载数据。
func (s *Dashboard) Init() tea.Cmd { return s.load() }

// load 发起一次概览查询。
func (s *Dashboard) load() tea.Cmd {
	return loadCmd(s.deps, ui.ScreenDashboard, func(service *query.Service) any {
		// 一次取两份：概览与状态共用同一个快照，没有额外 IO。
		return dashboardPayload{
			Summary:   service.Summary(query.Options{}),
			Status:    service.Status(),
			Timetable: service.Timetable(query.Options{}),
		}
	})
}

// Update 处理消息。
func (s *Dashboard) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenDashboard {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		if payload, ok := typed.Data.(dashboardPayload); ok {
			s.data = payload.Summary
			s.stat = payload.Status
			s.week = payload.Timetable
		}
		return s, nil
	case tea.KeyPressMsg:
		switch {
		case key.Matches(typed, s.keys.Next):
			s.cycle(1)
			return s, nil
		case key.Matches(typed, s.keys.Prev):
			s.cycle(-1)
			return s, nil
		case key.Matches(typed, s.keys.Open), key.Matches(typed, s.keys.Score):
			return s, s.target()
		}
	}
	return s, nil
}

// cycle 移动卡片焦点。
func (s *Dashboard) cycle(delta int) {
	s.focus.Index = (s.focus.Index + delta + int(cardCount)) % int(cardCount)
}

// CycleFocus 实现 ui.Focusable。
func (s *Dashboard) CycleFocus(delta int) bool {
	s.cycle(delta)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Dashboard) FocusCount() int { return int(cardCount) }

// target 返回当前焦点卡片对应的跳转。
func (s *Dashboard) target() tea.Cmd {
	to := ui.ScreenDashboard
	switch dashboardCard(s.focus.Index) {
	case cardCourses, cardSemester:
		to = ui.ScreenTimetable
	case cardExams:
		to = ui.ScreenExams
	case cardStats, cardIdentity:
		to = ui.ScreenScores
	case cardHealth:
		to = ui.ScreenDoctor
	}
	if to == ui.ScreenDashboard {
		return nil
	}
	return func() tea.Msg { return ui.NavigateMsg{To: to, Push: true} }
}

// Keys 返回页面按键。
func (s *Dashboard) Keys() []key.Binding {
	return []key.Binding{s.keys.Next, s.keys.Open, s.keys.Score}
}

// View 渲染概览。
func (s *Dashboard) View() string {
	th := s.theme()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取本地缓存…"); handled {
		return content
	}

	width, height := s.Width, s.Height
	if width < 40 || height < 10 {
		return s.renderNarrow(th)
	}

	rowIdentity := 6
	rowStats := 9
	if height < 32 {
		rowIdentity = 5
		rowStats = 8
	}
	remaining := height - rowIdentity - rowStats
	if remaining < 7 {
		// 高度不够两张卡片并排：整列堆叠，最后一张吃掉剩余空间。
		heights := components.RowHeights(height, []int{rowIdentity, rowStats, 1, 1})
		blocks := []string{
			s.cardIdentity(th, width, heights[0]),
			s.cardStats(th, width, heights[1]),
			s.cardCourses(th, width, heights[2]),
			s.cardExams(th, width, heights[3]),
		}
		return strings.Join(blocks, "\n")
	}

	topWidths := components.ColumnWidths(width-1, []int{3, 2})
	top := components.SplitHGap(rowIdentity, 1,
		components.Part{Width: topWidths[0], Content: s.cardIdentity(th, topWidths[0], rowIdentity)},
		components.Part{Width: topWidths[1], Content: s.cardSemester(th, topWidths[1], rowIdentity)},
	)

	stats := s.cardStats(th, width, rowStats)

	// 底部：课程占整列，右侧竖向叠"考试"与"缓存"。留白被信息填满，
	// 而不是让单条课程撑成一张空箱子。
	bottomWidths := components.ColumnWidths(width-1, []int{3, 2})
	rightHeights := components.RowHeights(remaining, []int{1, 1})
	right := components.StackV(bottomWidths[1], []components.Part{
		{Width: rightHeights[0], Content: s.cardExams(th, bottomWidths[1], rightHeights[0])},
		{Width: rightHeights[1], Content: s.cardHealth(th, bottomWidths[1], rightHeights[1])},
	})
	bottom := components.SplitHGap(remaining, 1,
		components.Part{Width: bottomWidths[0], Content: s.cardCourses(th, bottomWidths[0], remaining)},
		components.Part{Width: bottomWidths[1], Content: right},
	)

	return strings.Join([]string{top, stats, bottom}, "\n")
}

// renderNarrow 在极窄终端下退化为纯文本摘要。
func (s *Dashboard) renderNarrow(th theme.Theme) string {
	stats := s.data.Stats
	lines := []string{
		th.Title().Render("概览"),
		th.MutedStyle().Render(orDefault(s.data.Profile.RealName, "未记录") + "  " +
			orDefault(s.data.Profile.StudentNumber, "未记录")),
		"",
		th.Body().Render("GPA " + academic.FormatGPA(stats.GPA)),
		th.Body().Render("已获 " + academic.FormatCredits(stats.EarnedCredits) + "/" +
			academic.FormatCredits(stats.TotalCredits) + " 学分"),
		th.Body().Render("挂科 " + fmt.Sprintf("%d 门", stats.FailedCount)),
	}
	return strings.Join(lines[:minInt(len(lines), maxInt(4, s.Height))], "\n")
}

// cardIdentity 渲染身份卡。
func (s *Dashboard) cardIdentity(th theme.Theme, width, height int) string {
	profile := s.data.Profile
	major := profile.Major
	if profile.Grade != "" {
		major += " · " + profile.Grade
	}
	if profile.ClassName != "" {
		major += " · " + profile.ClassName
	}

	inner := maxInt(8, width-4)
	name := orDefault(profile.RealName, "未记录")
	number := orDefault(profile.StudentNumber, "未记录")
	college := orDefault(profile.College, "未记录")
	major = orDefault(major, "未记录")

	body := th.Title().Render(name) + th.SubtleStyle().Render("  "+number) + "\n" +
		th.Body().Render(components.Truncate(college, inner)) + "\n" +
		th.MutedStyle().Render(components.Truncate(major, inner))

	return components.Card{
		Icon:     th.Glyphs.Icons.Profile,
		Title:    "学籍",
		Subtitle: s.deps.SyncText,
		Body:     body,
		Focused:  s.focus.Is(int(cardIdentity)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// cardSemester 渲染学期卡：大号周次 + 进度条。
func (s *Dashboard) cardSemester(th theme.Theme, width, height int) string {
	block := s.data.Semester
	label := "校历未收录"
	week := 0
	total := 0
	if block.Label != nil {
		label = *block.Label
	}
	if block.TeachingWeek != nil {
		week = *block.TeachingWeek
	}
	if block.TeachingWeeks != nil {
		total = *block.TeachingWeeks
	}

	var body strings.Builder
	if week > 0 {
		body.WriteString(th.Title().Render(fmt.Sprintf("第 %d 周", week)))
		body.WriteString(th.SubtleStyle().Render(fmt.Sprintf(" / 共 %d 周", total)))
	} else {
		body.WriteString(th.MutedStyle().Render("假期中"))
	}
	body.WriteString("\n")
	body.WriteString(th.Body().Render(components.Truncate(label, maxInt(6, width-4))))
	if total > 0 && week > 0 {
		body.WriteString("\n")
		body.WriteString(components.Meter(th, float64(week)/float64(total), maxInt(6, width-8)))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Calendar,
		Title:    "学期",
		Subtitle: weekdayLabel(s.data.Date),
		Body:     body.String(),
		Focused:  s.focus.Is(int(cardSemester)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// cardStats 渲染学业指标卡：3×2 指标块 + 学分进度条。
func (s *Dashboard) cardStats(th theme.Theme, width, height int) string {
	stats := s.data.Stats
	inner := maxInt(24, width-4)

	columns := components.ColumnWidths(inner, []int{1, 1, 1})
	cellHeight := 2
	if height >= 11 {
		cellHeight = 3
	}

	cell := func(label, value, detail, level string) string {
		return components.RenderMiniStat(th, components.MiniStat{
			Label: label, Value: value, Detail: detail, Level: level,
		}, columns[0], cellHeight)
	}

	row1 := components.SplitH(cellHeight,
		components.Part{Width: columns[0], Content: cell("平均学分绩点", academic.FormatGPA(stats.GPA), "必修课口径", "")},
		components.Part{Width: columns[1], Content: cell("加权平均分", academic.FormatGPA(stats.WeightedAvg), "学分加权", "")},
		components.Part{Width: columns[2], Content: cell("已获学分",
			academic.FormatCredits(stats.EarnedCredits)+" / "+academic.FormatCredits(stats.TotalCredits),
			fmt.Sprintf("%d%%", int(stats.EarnedRatio*100+0.5)), "")},
	)
	row2 := components.SplitH(cellHeight,
		components.Part{Width: columns[0], Content: cell("挂科",
			fmt.Sprintf("%d 门", stats.FailedCount),
			academic.FormatCredits(stats.FailedCredits)+" 学分", warnIf(stats.FailedCount > 0))},
		components.Part{Width: columns[1], Content: cell("学位绩点",
			academic.FormatGPA(stats.Degree.GPA), qualify(stats.Degree.Qualified), okIf(stats.Degree.Qualified))},
		components.Part{Width: columns[2], Content: cell("推免自检",
			qualify(stats.Recommend.Qualified),
			fmt.Sprintf("重修 %d/%d 门", stats.Recommend.RetakeCount, stats.Recommend.RetakeLimit),
			okIf(stats.Recommend.Qualified))},
	)

	barWidth := maxInt(6, inner-12)
	body := row1 + "\n" + row2 + "\n" +
		th.SubtleStyle().Render("学分 ") +
		components.Meter(th, stats.EarnedRatio, barWidth)

	footer := th.Glyphs.Check + " " + stats.Risk.Description
	if stats.Risk.Level != "none" {
		footer = th.Glyphs.Cross + " " + stats.Risk.Description
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Scores,
		Title:    "学业指标",
		Subtitle: "必修课口径",
		Body:     body,
		Footer:   footer,
		Level:    riskLevel(stats.Risk.Level),
		Focused:  s.focus.Is(int(cardStats)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// cardCourses 渲染课程卡：今日在前，空间够时继续列出本周其余安排。
//
// 只显示"今天有什么"会让卡片在没课的日子彻底空掉；补上本周其余，
// 既填满了版面，也回答了用户紧接着会问的下一个问题。
func (s *Dashboard) cardCourses(th theme.Theme, width, height int) string {
	inner := maxInt(10, width-4)
	// 卡片可能被压到 1–2 行高（窄屏或极扁终端），预算必须兜住非正值。
	budget := maxInt(1, height-2)

	lines := make([]string, 0, budget)
	appendCourse := func(cell model.TimetableCell) bool {
		if len(lines)+1 > budget {
			return false
		}
		start := academic.CombineSlotStartTime(cell.SectionIndex)
		if start == "" {
			start = orDefault(cell.SectionLabel, "—")
		}
		name := components.Truncate(cell.CourseName, maxInt(6, inner-14))
		lines = append(lines, th.SubtleStyle().Render(components.PadRight(start, 6))+
			" "+th.Body().Render(name))
		if len(lines)+1 > budget {
			return true
		}
		detail := cell.Location
		if cell.Teacher != "" {
			if detail != "" {
				detail += " · "
			}
			detail += cell.Teacher
		}
		if detail != "" {
			lines = append(lines, "       "+th.SubtleStyle().Render(
				components.Truncate(detail, maxInt(4, inner-7))))
		}
		return true
	}

	if len(s.data.TodayCourses) == 0 {
		reason := "今天没有课"
		switch {
		case s.data.Semester.Holiday:
			reason = "不在校历教学周内"
		case s.data.IsToday:
			reason = "今日无课或课程已结束"
		}
		lines = append(lines, th.SubtleStyle().Render(reason))
	} else {
		for _, cell := range s.data.TodayCourses {
			if !appendCourse(cell) {
				break
			}
		}
	}

	// 还有高度、且本周确实还有别的课，才补"本周其余"。
	if remainingLines := budget - len(lines); remainingLines > 2 && len(s.weekAhead()) > 0 {
		lines = append(lines, "")
		lines = append(lines, th.SubtleStyle().Render("本周其余"))
		for _, block := range s.weekAhead() {
			if len(lines)+2 > budget {
				break
			}
			lines = append(lines, th.MutedStyle().Render(
				"  "+block.Weekday+orDefault(" "+block.Date, "")))
			for _, cell := range block.Courses {
				if !appendCourse(cell) {
					break
				}
			}
		}
	}

	subtitle := fmt.Sprintf("%d 门", len(s.data.TodayCourses))
	return components.Card{
		Icon:     th.Glyphs.Icons.Timetable,
		Title:    s.courseTitle(),
		Subtitle: subtitle,
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(cardCourses)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// weekAhead 返回今天之后、本周仍有课的日期块（按星期顺序）。
func (s *Dashboard) weekAhead() []query.DayBlock {
	today := 0
	if parsed, ok := academic.ParseISODate(s.data.Date); ok {
		today = parsed.Weekday()
	}
	blocks := make([]query.DayBlock, 0, 6)
	for _, block := range s.week.Days {
		if block.Day <= today || len(block.Courses) == 0 {
			continue
		}
		blocks = append(blocks, block)
	}
	return blocks
}

// cardExams 渲染近期考试卡。
func (s *Dashboard) cardExams(th theme.Theme, width, height int) string {
	inner := maxInt(10, width-4)
	if len(s.data.UpcomingExams) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Exams, Title: "近期考试", Subtitle: "0 场",
			Body:    th.SubtleStyle().Render("暂无近期考试安排"),
			Focused: s.focus.Is(int(cardExams)), Width: width, Height: height,
		}.Render(th)
	}

	// 卡片有多高就显示多少场，不留空白。
	const rowHeight = 2
	visible := len(s.data.UpcomingExams)
	limit := maxInt(1, (maxInt(3, height)-2)/rowHeight)
	if limit < visible {
		visible = limit
	}
	if visible < 1 {
		visible = 1
	}

	lines := make([]string, 0, visible*rowHeight)
	for _, item := range s.data.UpcomingExams[:visible] {
		level := examLevel(item.Status)
		lines = append(lines, th.LevelColorStyle(level).Render(
			components.PadRight(components.Truncate(item.Status, 10), 10))+
			" "+th.Body().Render(components.Truncate(item.Exam.CourseName, maxInt(4, inner-14))))
		meta := item.Exam.Location
		if timeText := shortExamTime(item.Exam.Time); timeText != "" {
			if meta != "" {
				meta += " · "
			}
			meta += timeText
		}
		if meta != "" {
			lines = append(lines, "           "+th.SubtleStyle().Render(
				components.Truncate(meta, maxInt(4, inner-11))))
		}
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Exams,
		Title:    "近期考试",
		Subtitle: fmt.Sprintf("%d 场", len(s.data.UpcomingExams)),
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(cardExams)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// cardHealth 渲染缓存健康卡：数据量、同步时间、就绪状态。
func (s *Dashboard) cardHealth(th theme.Theme, width, height int) string {
	counts := s.stat.Counts
	level := "ok"
	if s.stat.Readiness != "ready" {
		level = "warn"
	}

	inner := maxInt(10, width-4)
	line := func(label, value string) string {
		pad := inner - 8 - components.Width(value)
		if pad < 1 {
			pad = 1
		}
		return th.SubtleStyle().Render(components.PadRight(label, 8)) +
			th.Body().Render(components.Truncate(value, maxInt(4, inner-9)))
	}

	lines := []string{
		line("成绩", fmt.Sprintf("%d 条", counts.Scores)),
		line("课表", fmt.Sprintf("%d 格", counts.TimetableCells)),
		line("考试", fmt.Sprintf("%d 场", counts.Exams)),
		line("方案", fmt.Sprintf("%d 组", counts.PlanGroups)),
		line("公告", fmt.Sprintf("%d 条", counts.Notices)),
		line("课程", fmt.Sprintf("%d 门", counts.Courses)),
	}
	if s.stat.SessionExpired {
		lines = append(lines, "", th.LevelColorStyle("warn").Render("会话已失效，仅本地缓存"))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Doctor,
		Title:    "缓存",
		Subtitle: readinessLabel(s.stat.Readiness),
		Body:     strings.Join(lines, "\n"),
		Footer:   s.deps.SyncText,
		Level:    level,
		Focused:  s.focus.Is(int(cardHealthIndex)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// readinessLabel 把就绪码换成中文。
func readinessLabel(code string) string {
	switch code {
	case "ready":
		return "可查询"
	case "needLogin":
		return "未登录"
	default:
		return "无数据"
	}
}

// courseTitle 返回今日课程卡标题。
func (s *Dashboard) courseTitle() string {
	if !s.data.IsToday {
		return "指定日期课程"
	}
	return "今日课程"
}

// theme 返回当前主题。
func (s *Dashboard) theme() theme.Theme { return s.deps.Themes.Get() }

// warnIf 在条件成立时返回警告级别。
func warnIf(bad bool) string {
	if bad {
		return "warn"
	}
	return ""
}

// okIf 把资格结果映射成语义级别。
func okIf(good bool) string {
	if good {
		return "ok"
	}
	return "warn"
}

// riskLevel 把风险等级映射成卡片标题色。
func riskLevel(level string) string {
	switch level {
	case "expel":
		return "fail"
	case "downgrade":
		return "warn"
	default:
		return ""
	}
}

// weekdayLabel 把 `YYYY-MM-DD` 换成 `周X`。
func weekdayLabel(date string) string {
	parsed, ok := academic.ParseISODate(date)
	if !ok {
		return date
	}
	return query.WeekdayNames[parsed.Weekday()]
}

// shortExamTime 截断过长的考试时间原文。
func shortExamTime(raw string) string {
	text := strings.TrimSpace(raw)
	if len(text) > 20 {
		return text[:20]
	}
	return text
}
