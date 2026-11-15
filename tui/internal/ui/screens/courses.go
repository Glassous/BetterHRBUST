package screens

import (
	"fmt"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// coursesPane 是课程名录页的面板。
type coursesPane int

const (
	paneCourseList coursesPane = iota
	paneCourseDetail
	coursesPaneCount
)

// Courses 是课程名录页：列表 + 详情。
type Courses struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.CoursesData
	win   components.Window
	focus components.Focus

	limit  int
	search searchBox

	keys struct {
		Search key.Binding
		Up     key.Binding
		Down   key.Binding
		Open   key.Binding
	}
}

// NewCourses 构造课程名录页。
func NewCourses(deps ui.Deps) ui.Screen {
	screen := &Courses{deps: deps, limit: 200}
	screen.ScreenID = ui.ScreenCourses
	screen.ScreenTitle = "课程名录"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(coursesPaneCount))
	screen.keys.Search = key.NewBinding(key.WithKeys("/"), key.WithHelp("/", "搜索"))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "上移"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "下移"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦详情"))
	return screen
}

// Init 加载课程名录。
func (s *Courses) Init() tea.Cmd { return s.load() }

// load 查询课程名录。
func (s *Courses) load() tea.Cmd {
	limit := s.limit
	options := query.Options{Limit: &limit, Query: strings.TrimSpace(s.search.text)}
	return loadCmd(s.deps, ui.ScreenCourses, func(service *query.Service) any {
		return service.Courses(options)
	})
}

// Update 处理消息。
func (s *Courses) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenCourses {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		s.data, _ = typed.Data.(query.CoursesData)
		s.state.Empty = len(s.data.Courses) == 0
		s.state.EmptyText = "本地缓存中没有本学期课程名录（上下文接口未返回课程表）"
		s.win.Clamp(len(s.data.Courses))
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// handleKey 处理页面按键。
func (s *Courses) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	if s.search.Active() {
		switch msg.String() {
		case "esc":
			s.search.Clear()
			return s, s.load()
		case "enter":
			s.search.Close()
			return s, s.load()
		case "backspace":
			s.search.Backspace()
			return s, nil
		}
		if text := msg.String(); len([]rune(text)) == 1 {
			s.search.Push([]rune(text)[0])
		}
		return s, nil
	}

	switch {
	case key.Matches(msg, s.keys.Search):
		s.search.Open("搜索课程")
		return s, nil
	case key.Matches(msg, s.keys.Open):
		s.focus.Set(int(paneCourseDetail))
		return s, nil
	}
	if s.focus.Is(int(paneCourseList)) {
		switch {
		case key.Matches(msg, s.keys.Up):
			s.win.Move(-1, len(s.data.Courses))
		case key.Matches(msg, s.keys.Down):
			s.win.Move(1, len(s.data.Courses))
		}
	}
	return s, nil
}

// WantsInput 在搜索时接管键盘。
func (s *Courses) WantsInput() bool { return s.search.Active() }

// CycleFocus 实现 ui.Focusable。
func (s *Courses) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(coursesPaneCount)) % int(coursesPaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Courses) FocusCount() int { return int(coursesPaneCount) }

// Keys 返回页面按键。
func (s *Courses) Keys() []key.Binding {
	return []key.Binding{s.keys.Search, s.keys.Open}
}

// View 渲染课程名录。
func (s *Courses) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取课程名录…"); handled {
		return content
	}
	if s.Width < 64 || s.Height < 14 {
		return s.renderList(th, s.Width, s.Height)
	}
	widths := components.ColumnWidths(s.Width-1, []int{5, 3})
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: s.renderList(th, widths[0], s.Height)},
		components.Part{Width: widths[1], Content: s.renderDetail(th, widths[1], s.Height)},
	)
}

// renderList 渲染课程表格。
func (s *Courses) renderList(th theme.Theme, width, height int) string {
	inner := maxInt(16, width-4)
	if len(s.data.Courses) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Courses, Title: "本学期课程",
			Body: th.SubtleStyle().Render(s.state.EmptyText), Width: width, Height: height,
		}.Render(th)
	}

	columns := []components.Column{
		{Title: "课程号", Min: 8, Max: 12},
		{Title: "课程名称", Flex: true},
		{Title: "教师", Max: 12},
		{Title: "学分", Align: components.Right, Min: 4},
	}
	s.win.Size = maxInt(1, height-2-3)
	s.win.Clamp(len(s.data.Courses))
	start, end := s.win.Range(len(s.data.Courses))

	rows := make([][]string, 0, end-start)
	for _, course := range s.data.Courses[start:end] {
		rows = append(rows, []string{
			course.CourseID, course.CourseName, course.Teacher, formatFloat(course.Credit),
		})
	}
	cursor := -1
	if s.focus.Is(int(paneCourseList)) {
		cursor = s.win.Cursor - start
	}
	table := components.NewTable(columns, rows, cursor, th, inner)
	body := table.Render()
	if end-start < len(s.data.Courses) {
		body += "\n" + th.SubtleStyle().Render(fmt.Sprintf("  %d-%d / %d",
			start+1, end, len(s.data.Courses)))
	}

	footer := "/ 搜索课程名或课程号"
	if s.search.text != "" {
		footer = "关键字：" + s.search.text
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Courses,
		Title:    "本学期课程",
		Subtitle: fmt.Sprintf("%d 门", s.data.Total),
		Body:     body,
		Footer:   footer,
		Focused:  s.focus.Is(int(paneCourseList)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderDetail 渲染课程详情。
func (s *Courses) renderDetail(th theme.Theme, width, height int) string {
	if s.win.Cursor < 0 || s.win.Cursor >= len(s.data.Courses) {
		return components.Card{
			Icon: th.Glyphs.Icons.Courses, Title: "课程详情",
			Body:    th.SubtleStyle().Render("在左侧选择一门课程"),
			Focused: s.focus.Is(int(paneCourseDetail)), Width: width, Height: height,
		}.Render(th)
	}
	course := s.data.Courses[s.win.Cursor]
	inner := maxInt(10, width-4)
	line := func(label, value string) string {
		return th.SubtleStyle().Render(components.PadRight(label, 6)) +
			th.Body().Render(components.Truncate(value, maxInt(4, inner-7)))
	}

	lines := []string{
		th.Heading().Render(components.Truncate(course.CourseName, inner)),
		"",
		line("课程号", orDefault(course.CourseID, "未记录")),
		line("课序号", orDefault(course.CourseSeq, "未记录")),
		line("教师", orDefault(course.Teacher, "未记录")),
		line("学分", formatFloat(course.Credit)),
		line("学时", formatFloat(course.Hours)),
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Courses,
		Title:    "课程详情",
		Subtitle: fmt.Sprintf("%d / %d", s.win.Cursor+1, len(s.data.Courses)),
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(paneCourseDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// formatFloat 去掉无意义的小数点。
func formatFloat(value float64) string {
	if value == float64(int64(value)) {
		return fmt.Sprintf("%d", int64(value))
	}
	return fmt.Sprintf("%.1f", value)
}
