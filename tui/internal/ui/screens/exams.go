package screens

import (
	"fmt"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// examsPane 是考试页的面板。
type examsPane int

const (
	paneExamList examsPane = iota
	paneExamDetail
	examsPaneCount
)

// windowCycle 是 `d` 键切换的考试回看窗口。
var windowCycle = []int{7, 14, 30, 90}

// Exams 是考试日程页：列表 + 倒计时详情 + 月度分布。
type Exams struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.ExamsData
	win   components.Window
	focus components.Focus

	all  bool
	days int

	keys struct {
		Toggle key.Binding
		Days   key.Binding
		Up     key.Binding
		Down   key.Binding
		Open   key.Binding
	}
}

// NewExams 构造考试页。
func NewExams(deps ui.Deps) ui.Screen {
	screen := &Exams{deps: deps, days: 7}
	screen.ScreenID = ui.ScreenExams
	screen.ScreenTitle = "考试日程"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(examsPaneCount))
	screen.keys.Toggle = key.NewBinding(key.WithKeys("a"), key.WithHelp("a", "全部/近期"))
	screen.keys.Days = key.NewBinding(key.WithKeys("d"), key.WithHelp("d", "时间窗口"))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "上移"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "下移"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦详情"))
	return screen
}

// Init 加载考试。
func (s *Exams) Init() tea.Cmd { return s.load() }

// load 按当前窗口查询。
func (s *Exams) load() tea.Cmd {
	days := s.days
	options := query.Options{All: s.all, Days: &days}
	return loadCmd(s.deps, ui.ScreenExams, func(service *query.Service) any {
		return service.Exams(options)
	})
}

// Update 处理消息。
func (s *Exams) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenExams {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		s.data, _ = typed.Data.(query.ExamsData)
		s.state.Empty = len(s.data.Exams) == 0
		s.state.EmptyText = "暂无考试安排：可以按 a 查看全部，或到图形界面刷新"
		s.win.Clamp(len(s.data.Exams))
		return s, nil
	case tea.KeyPressMsg:
		switch {
		case key.Matches(typed, s.keys.Toggle):
			s.all = !s.all
			return s, s.load()
		case key.Matches(typed, s.keys.Days):
			s.days = nextWindow(s.days)
			if s.all {
				s.all = false
			}
			return s, s.load()
		case key.Matches(typed, s.keys.Open):
			s.focus.Set(int(paneExamDetail))
			return s, nil
		}
		if s.focus.Is(int(paneExamList)) {
			switch {
			case key.Matches(typed, s.keys.Up):
				s.win.Move(-1, len(s.data.Exams))
			case key.Matches(typed, s.keys.Down):
				s.win.Move(1, len(s.data.Exams))
			}
		}
	}
	return s, nil
}

// nextWindow 返回下一个回看窗口。
func nextWindow(current int) int {
	for index, value := range windowCycle {
		if value == current {
			return windowCycle[(index+1)%len(windowCycle)]
		}
	}
	return windowCycle[0]
}

// CycleFocus 实现 ui.Focusable。
func (s *Exams) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(examsPaneCount)) % int(examsPaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Exams) FocusCount() int { return int(examsPaneCount) }

// Keys 返回页面按键。
func (s *Exams) Keys() []key.Binding {
	return []key.Binding{s.keys.Toggle, s.keys.Days, s.keys.Open}
}

// View 渲染考试页。
func (s *Exams) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取考试安排…"); handled {
		return content
	}

	if s.Width < 64 || s.Height < 14 {
		return s.renderList(th, s.Width, s.Height)
	}
	widths := components.ColumnWidths(s.Width-1, []int{5, 3})
	right := s.renderRight(th, widths[1], s.Height)
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: s.renderList(th, widths[0], s.Height)},
		components.Part{Width: widths[1], Content: right},
	)
}

// renderList 渲染考试列表。
func (s *Exams) renderList(th theme.Theme, width, height int) string {
	innerWidth := maxInt(16, width-4)
	scope := fmt.Sprintf("未来 + 过去 %d 天", s.data.Days)
	if s.data.All {
		scope = "全部考试"
	}
	if len(s.data.Exams) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Exams, Title: "考试安排", Subtitle: scope,
			Body: th.SubtleStyle().Render(s.state.EmptyText), Width: width, Height: height,
		}.Render(th)
	}

	columns := []components.Column{
		{Title: "状态", Min: 10, Max: 11},
		{Title: "课程", Flex: true},
		{Title: "时间", Max: 17},
		{Title: "考场", Max: 12},
	}
	rows := make([][]string, 0, len(s.data.Exams))
	for _, item := range s.data.Exams {
		rows = append(rows, []string{
			item.Status,
			item.Exam.CourseName,
			compactExamTime(item.Exam.Time),
			orDefault(item.Exam.Location, "—"),
		})
	}

	s.win.Size = maxInt(1, height-2-3)
	s.win.Clamp(len(rows))
	start, end := s.win.Range(len(rows))
	cursor := -1
	if s.focus.Is(int(paneExamList)) {
		cursor = s.win.Cursor - start
	}
	table := components.NewTable(columns, rows[start:end], cursor, th, innerWidth)
	body := table.Render()
	if end-start < len(rows) {
		body += "\n" + th.SubtleStyle().Render(fmt.Sprintf("  %d-%d / %d", start+1, end, len(rows)))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Exams,
		Title:    "考试安排",
		Subtitle: scope,
		Body:     body,
		Footer:   fmt.Sprintf("基准日 %s · 共 %d 场", s.data.Date, len(s.data.Exams)),
		Focused:  s.focus.Is(int(paneExamList)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// compactExamTime 把 `2026-11-10 09:00-11:00` 压成 `11-10 09:00-11:00`。
//
// 列表列宽有限，年份对"近期考试"没有信息量，去掉后完整时间才放得下。
func compactExamTime(raw string) string {
	text := strings.TrimSpace(raw)
	if len(text) > 10 && text[4] == '-' {
		text = text[5:]
	}
	return text
}

// renderRight 渲染右侧：详情 + 学期分布。
func (s *Exams) renderRight(th theme.Theme, width, height int) string {
	detailHeight := minInt(height, 11)
	if detailHeight < 8 {
		detailHeight = minInt(height, 8)
	}
	rest := height - detailHeight - 1
	parts := []components.Part{
		{Width: detailHeight, Content: s.renderDetail(th, width, detailHeight)},
	}
	if rest >= 6 {
		parts = append(parts, components.Part{Width: rest, Content: components.Card{
			Icon:     th.Glyphs.Icons.Trend,
			Title:    "按周分布",
			Subtitle: "考试密集程度",
			Body:     s.renderDistribution(th, width-4),
			Width:    width,
			Height:   rest,
		}.Render(th)})
	}
	return components.StackV(width, parts)
}

// renderDetail 渲染选中考试详情。
func (s *Exams) renderDetail(th theme.Theme, width, height int) string {
	if s.win.Cursor >= len(s.data.Exams) {
		return components.Card{
			Icon: th.Glyphs.Icons.Clock, Title: "考试详情",
			Body:    th.SubtleStyle().Render("在左侧选择一场考试"),
			Focused: s.focus.Is(int(paneExamDetail)), Width: width, Height: height,
		}.Render(th)
	}
	item := s.data.Exams[s.win.Cursor]
	inner := maxInt(10, width-4)
	line := func(label, value string) string {
		return th.SubtleStyle().Render(components.PadRight(label, 6)) +
			th.Body().Render(components.Truncate(value, maxInt(4, inner-7)))
	}

	lines := []string{
		th.Heading().Render(components.Truncate(item.Exam.CourseName, inner)),
		"",
		line("状态", item.Status),
		line("时间", orDefault(item.Exam.Time, "未记录")),
		line("考场", orDefault(item.Exam.Location, "未记录")),
		line("属性", orDefault(item.Exam.Property, "未记录")),
	}
	level := examLevel(item.Status)
	return components.Card{
		Icon:     th.Glyphs.Icons.Clock,
		Title:    "考试详情",
		Subtitle: item.Status,
		Body:     strings.Join(lines, "\n"),
		Level:    level,
		Focused:  s.focus.Is(int(paneExamDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderDistribution 按周统计考试数量。
func (s *Exams) renderDistribution(th theme.Theme, width int) string {
	type bucket struct {
		label string
		count int
	}
	buckets := make([]bucket, 0, 8)
	seen := map[string]int{}
	for _, item := range s.data.Exams {
		parsed, ok := academic.ParseExamTime(item.Exam.Time)
		if !ok {
			continue
		}
		// 以"周"为单位：取该日期所在教学周，没有校历就按 ISO 周聚。
		label := fmt.Sprintf("%d/%02d 周", parsed.Date.Month, parsed.Date.Day)
		if index, exists := seen[label]; exists {
			buckets[index].count++
			continue
		}
		seen[label] = len(buckets)
		buckets = append(buckets, bucket{label: label, count: 1})
	}
	if len(buckets) == 0 {
		return th.SubtleStyle().Render("考试时间无法解析，无法统计分布")
	}
	items := make([]components.Distribution, 0, len(buckets))
	for _, item := range buckets {
		items = append(items, components.Distribution{Label: item.label, Count: item.count})
	}
	return components.BarChart(th, items, width)
}
