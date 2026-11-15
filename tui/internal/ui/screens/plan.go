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

// planPane 是培养方案页的面板。
type planPane int

const (
	paneGroups planPane = iota
	paneGroupDetail
	planPaneCount
)

// Plan 是培养方案页：左侧课组进度条 + 右侧课组明细。
type Plan struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.PlanData
	win   components.Window
	focus components.Focus

	keys struct {
		Up     key.Binding
		Down   key.Binding
		Open   key.Binding
		Filter key.Binding
	}
	filtering bool
	filter    string
}

// NewPlan 构造培养方案页。
func NewPlan(deps ui.Deps) ui.Screen {
	screen := &Plan{deps: deps}
	screen.ScreenID = ui.ScreenPlan
	screen.ScreenTitle = "培养方案"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(planPaneCount))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "上一个课组"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "下一个课组"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦明细"))
	screen.keys.Filter = key.NewBinding(key.WithKeys("/"), key.WithHelp("/", "筛选课组"))
	return screen
}

// Init 加载培养方案。
func (s *Plan) Init() tea.Cmd { return s.load() }

// load 查询培养方案。
func (s *Plan) load() tea.Cmd {
	options := query.Options{Group: strings.TrimSpace(s.filter)}
	return loadCmd(s.deps, ui.ScreenPlan, func(service *query.Service) any {
		return service.Plan(options)
	})
}

// Update 处理消息。
func (s *Plan) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenPlan {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		s.data, _ = typed.Data.(query.PlanData)
		s.state.Empty = len(s.data.Groups) == 0
		s.state.EmptyText = "培养方案为空：请在图形界面刷新一次"
		s.win.Clamp(len(s.data.Groups))
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// handleKey 处理页面按键。
func (s *Plan) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	if s.filtering {
		switch msg.String() {
		case "esc":
			s.filtering = false
			s.filter = ""
			return s, s.load()
		case "enter":
			s.filtering = false
			return s, s.load()
		case "backspace":
			runes := []rune(s.filter)
			if len(runes) > 0 {
				s.filter = string(runes[:len(runes)-1])
			}
			return s, nil
		}
		if text := msg.String(); len([]rune(text)) == 1 {
			s.filter += text
		}
		return s, nil
	}

	switch {
	case key.Matches(msg, s.keys.Filter):
		s.filtering = true
		return s, nil
	case key.Matches(msg, s.keys.Open):
		s.focus.Set(int(paneGroupDetail))
		return s, nil
	}
	if s.focus.Is(int(paneGroups)) {
		switch {
		case key.Matches(msg, s.keys.Up):
			s.win.Move(-1, len(s.data.Groups))
		case key.Matches(msg, s.keys.Down):
			s.win.Move(1, len(s.data.Groups))
		}
	}
	return s, nil
}

// WantsInput 在筛选时接管键盘。
func (s *Plan) WantsInput() bool { return s.filtering }

// CycleFocus 实现 ui.Focusable。
func (s *Plan) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(planPaneCount)) % int(planPaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Plan) FocusCount() int { return int(planPaneCount) }

// Keys 返回页面按键。
func (s *Plan) Keys() []key.Binding {
	return []key.Binding{s.keys.Up, s.keys.Down, s.keys.Open, s.keys.Filter}
}

// View 渲染培养方案。
func (s *Plan) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取培养方案…"); handled {
		return content
	}

	stripHeight := 5
	if s.Height < 24 {
		stripHeight = 4
	}
	strip := s.renderStrip(th, s.Width, stripHeight)

	bodyHeight := s.Height - stripHeight - 1
	if bodyHeight < 4 {
		bodyHeight = 4
	}
	if s.Width < 64 {
		return strings.Join([]string{strip, s.renderGroups(th, s.Width, bodyHeight)}, "\n")
	}

	widths := components.ColumnWidths(s.Width-1, []int{5, 4})
	body := components.SplitHGap(bodyHeight, 1,
		components.Part{Width: widths[0], Content: s.renderGroups(th, widths[0], bodyHeight)},
		components.Part{Width: widths[1], Content: s.renderGroupDetail(th, widths[1], bodyHeight)},
	)
	return strings.Join([]string{strip, body}, "\n")
}

// renderStrip 渲染总进度带。
func (s *Plan) renderStrip(th theme.Theme, width, height int) string {
	progress := s.data.Progress
	inner := maxInt(20, width-4)

	columns := components.ColumnWidths(inner, []int{1, 1, 1, 1})
	cellHeight := 2
	if height >= 5 {
		cellHeight = 3
	}
	cell := func(label, value, detail, level string) string {
		return components.RenderMiniStat(th, components.MiniStat{
			Label: label, Value: value, Detail: detail, Level: level,
		}, columns[0], cellHeight)
	}

	body := components.SplitH(cellHeight,
		components.Part{Width: columns[0], Content: cell("已获学分",
			academic.FormatCredits(progress.EarnedTotal), "按要求口径归集", "")},
		components.Part{Width: columns[1], Content: cell("要求学分",
			academic.FormatCredits(progress.RequiredTotal), "读取方案真实值", "")},
		components.Part{Width: columns[2], Content: cell("完成度",
			fmt.Sprintf("%d%%", progress.CompletionPercent), "已获 / 要求",
			okIf(progress.CompletionPercent >= 100))},
		components.Part{Width: columns[3], Content: cell("课组数",
			fmt.Sprintf("%d 组", len(s.data.Groups)), "已归集", "")},
	)
	if height >= 5 {
		body += "\n" + th.SubtleStyle().Render("总进度 ") +
			components.Meter(th, float64(progress.CompletionPercent)/100, maxInt(6, inner-8))
	}

	subtitle := "全部课组"
	if s.filter != "" {
		subtitle = "筛选：" + s.filter
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Plan,
		Title:    "学分进度",
		Subtitle: subtitle,
		Body:     body,
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderGroups 渲染课组列表。
func (s *Plan) renderGroups(th theme.Theme, width, height int) string {
	inner := maxInt(16, width-4)
	if len(s.data.Groups) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Plan, Title: "课组",
			Body: th.SubtleStyle().Render(s.state.EmptyText), Width: width, Height: height,
		}.Render(th)
	}

	s.win.Size = maxInt(1, height-2)
	s.win.Clamp(len(s.data.Groups))
	start, end := s.win.Range(len(s.data.Groups))

	lines := make([]string, 0, end-start)
	for index := start; index < end; index++ {
		group := s.data.Groups[index]
		selected := index == s.win.Cursor && s.focus.Is(int(paneGroups))
		lines = append(lines, s.renderGroupRow(th, group, inner, selected))
	}
	body := strings.Join(lines, "\n")
	if end-start < len(s.data.Groups) {
		body += "\n" + th.SubtleStyle().Render(fmt.Sprintf("  %d-%d / %d",
			start+1, end, len(s.data.Groups)))
	}

	footer := "要求学分读取方案原始数值"
	if s.filtering {
		footer = "/ " + s.filter + th.Glyphs.Cursor
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Plan,
		Title:    "课组完成度",
		Subtitle: fmt.Sprintf("%d 组", len(s.data.Groups)),
		Body:     body,
		Footer:   footer,
		Focused:  s.focus.Is(int(paneGroups)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderGroupRow 渲染单个课组（两行：进度条 + 门数说明）。
func (s *Plan) renderGroupRow(th theme.Theme, group model.PlanGroup, width int, selected bool) string {
	required := group.RequiredCredits
	earned := group.EarnedCredits
	ratio := 0.0
	if required > 0 {
		ratio = earned / required
		if ratio > 1 {
			ratio = 1
		}
	}

	marker := "  "
	style := th.Body()
	if selected {
		marker = th.Glyphs.Selected + " "
		style = th.SelectedRow()
	}

	barWidth := minInt(12, maxInt(6, width/4))
	nameWidth := maxInt(8, width-barWidth-18)
	name := components.Truncate(group.Name, nameWidth)

	line1 := marker + style.Render(components.PadRight(name, nameWidth)) + " " +
		components.Meter(th, ratio, barWidth) + " " +
		th.Title().Render(components.PadLeft(
			academic.FormatCredits(earned)+"/"+academic.FormatCredits(required), 11))

	detail := orDefault(group.Property, "未分类")
	if group.RequiredCourses > 0 {
		detail = fmt.Sprintf("%s · %d 门", detail, group.RequiredCourses)
	}
	line2 := "    " + th.SubtleStyle().Render(components.Truncate(detail, width-4))

	return line1 + "\n" + line2
}

// renderGroupDetail 渲染选中课组的候选课程。
func (s *Plan) renderGroupDetail(th theme.Theme, width, height int) string {
	if s.win.Cursor < 0 || s.win.Cursor >= len(s.data.Groups) {
		return components.Card{
			Icon: th.Glyphs.Icons.Courses, Title: "课组明细",
			Body:    th.SubtleStyle().Render("在左侧选择一个课组"),
			Focused: s.focus.Is(int(paneGroupDetail)), Width: width, Height: height,
		}.Render(th)
	}
	group := s.data.Groups[s.win.Cursor]
	inner := maxInt(10, width-4)

	header := []string{
		th.Heading().Render(components.Truncate(group.Name, inner)),
		th.SubtleStyle().Render(fmt.Sprintf("%s · 要求 %s 学分",
			orDefault(group.Property, "未分类"), academic.FormatCredits(group.RequiredCredits))),
		"",
	}

	if len(group.Courses) == 0 {
		header = append(header, th.SubtleStyle().Render("该课组没有候选课程明细"))
		return components.Card{
			Icon: th.Glyphs.Icons.Courses, Title: "课组明细",
			Subtitle: group.Name, Body: strings.Join(header, "\n"),
			Focused: s.focus.Is(int(paneGroupDetail)), Width: width, Height: height,
		}.Render(th)
	}

	budget := maxInt(1, height-2-len(header))
	lines := header
	for index, course := range group.Courses {
		if index >= budget {
			lines = append(lines, th.SubtleStyle().Render(fmt.Sprintf(
				"…… 另有 %d 门候选课程", len(group.Courses)-budget)))
			break
		}
		lines = append(lines, th.MutedStyle().Render(components.PadRight(course.Code, 10))+
			" "+th.Body().Render(components.Truncate(course.Name, maxInt(4, inner-22)))+
			th.SubtleStyle().Render("  "+academic.FormatCredits(course.Credit)+" 学分"))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Courses,
		Title:    "课组明细",
		Subtitle: fmt.Sprintf("%d 门候选", len(group.Courses)),
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(paneGroupDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}
