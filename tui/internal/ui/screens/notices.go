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

// noticesPane 是公告页的面板。
type noticesPane int

const (
	paneNoticeList noticesPane = iota
	paneNoticeBody
	noticesPaneCount
)

// Notices 是教学公告页：左侧标题列表 + 右侧正文。
//
// 右侧正文常驻而不是"按 enter 才出现"：公告的价值在内容，
// 让它一直可见就少一次按键，也让版面不至于一半空着。
type Notices struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.NoticesData
	win   components.Window
	focus components.Focus

	week   int
	search searchBox

	keys struct {
		PrevWeek key.Binding
		NextWeek key.Binding
		All      key.Binding
		Search   key.Binding
		Up       key.Binding
		Down     key.Binding
		Open     key.Binding
	}
}

// NewNotices 构造公告页。
func NewNotices(deps ui.Deps) ui.Screen {
	screen := &Notices{deps: deps}
	screen.ScreenID = ui.ScreenNotices
	screen.ScreenTitle = "教学公告"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(noticesPaneCount))
	screen.keys.PrevWeek = key.NewBinding(key.WithKeys("h", "["), key.WithHelp("h/[", "上一周"))
	screen.keys.NextWeek = key.NewBinding(key.WithKeys("l", "]"), key.WithHelp("l/]", "下一周"))
	screen.keys.All = key.NewBinding(key.WithKeys("a"), key.WithHelp("a", "全部"))
	screen.keys.Search = key.NewBinding(key.WithKeys("/"), key.WithHelp("/", "搜索"))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "上一条"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "下一条"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦正文"))
	return screen
}

// Init 加载公告。
func (s *Notices) Init() tea.Cmd { return s.load() }

// load 按当前周与关键字查询。
func (s *Notices) load() tea.Cmd {
	options := query.Options{}
	if s.week > 0 {
		week := s.week
		options.Week = &week
	}
	if text := strings.TrimSpace(s.search.text); text != "" {
		options.Query = text
	}
	return loadCmd(s.deps, ui.ScreenNotices, func(service *query.Service) any {
		return service.Notices(options)
	})
}

// Update 处理消息。
func (s *Notices) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenNotices {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		s.data, _ = typed.Data.(query.NoticesData)
		s.state.Empty = len(s.data.Notices) == 0
		s.state.EmptyText = s.emptyText()
		s.win.Clamp(len(s.data.Notices))
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// emptyText 给出空态原因。
func (s *Notices) emptyText() string {
	switch {
	case s.search.text != "":
		return "没有匹配「" + s.search.text + "」的公告"
	case s.week > 0 && s.data.Empty:
		return fmt.Sprintf("第 %d 周已缓存，该周没有公告", s.week)
	case s.week > 0:
		return fmt.Sprintf("第 %d 周没有缓存公告：可在图形界面刷新", s.week)
	default:
		return "本地缓存里没有公告：请在图形界面刷新一次"
	}
}

// handleKey 处理页面按键。
func (s *Notices) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
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
	case key.Matches(msg, s.keys.PrevWeek):
		return s.stepWeek(-1)
	case key.Matches(msg, s.keys.NextWeek):
		return s.stepWeek(1)
	case key.Matches(msg, s.keys.All):
		if s.week != 0 {
			s.week = 0
			return s, s.load()
		}
		return s, nil
	case key.Matches(msg, s.keys.Search):
		s.search.Open("搜索公告")
		return s, nil
	case key.Matches(msg, s.keys.Open):
		s.focus.Set(int(paneNoticeBody))
		return s, nil
	}

	if s.focus.Is(int(paneNoticeList)) {
		switch {
		case key.Matches(msg, s.keys.Up):
			s.win.Move(-1, len(s.data.Notices))
		case key.Matches(msg, s.keys.Down):
			s.win.Move(1, len(s.data.Notices))
		}
	}
	return s, nil
}

// stepWeek 在已缓存的周次之间切换。
func (s *Notices) stepWeek(delta int) (ui.Screen, tea.Cmd) {
	weeks := s.data.Weeks
	if len(weeks) == 0 {
		return s, nil
	}
	current := s.week
	index := -1
	for position, week := range weeks {
		if week == current {
			index = position
			break
		}
	}
	if index < 0 {
		s.week = weeks[0]
		return s, s.load()
	}
	next := index + delta
	if next < 0 || next >= len(weeks) {
		return s, nil
	}
	if weeks[next] == s.week {
		return s, nil
	}
	s.week = weeks[next]
	return s, s.load()
}

// WantsInput 在搜索时接管键盘。
func (s *Notices) WantsInput() bool { return s.search.Active() }

// CycleFocus 实现 ui.Focusable。
func (s *Notices) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(noticesPaneCount)) % int(noticesPaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Notices) FocusCount() int { return int(noticesPaneCount) }

// Keys 返回页面按键。
func (s *Notices) Keys() []key.Binding {
	return []key.Binding{
		s.keys.PrevWeek, s.keys.NextWeek, s.keys.All, s.keys.Search, s.keys.Open,
	}
}

// View 渲染公告页。
func (s *Notices) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取教学公告…"); handled {
		return content
	}

	if s.Width < 64 || s.Height < 14 {
		return s.renderList(th, s.Width, s.Height)
	}
	widths := components.ColumnWidths(s.Width-1, []int{2, 3})
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: s.renderList(th, widths[0], s.Height)},
		components.Part{Width: widths[1], Content: s.renderBody(th, widths[1], s.Height)},
	)
}

// renderList 渲染标题列表。
func (s *Notices) renderList(th theme.Theme, width, height int) string {
	inner := maxInt(14, width-4)
	scope := "全部"
	if s.week > 0 {
		scope = fmt.Sprintf("第 %d 周", s.week)
	}

	if len(s.data.Notices) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Notices, Title: "公告", Subtitle: scope,
			Body: th.SubtleStyle().Render(s.emptyText()), Width: width, Height: height,
		}.Render(th)
	}

	s.win.Size = maxInt(1, height-2-2)
	s.win.Clamp(len(s.data.Notices))
	start, end := s.win.Range(len(s.data.Notices))

	lines := make([]string, 0, end-start)
	for index := start; index < end; index++ {
		notice := s.data.Notices[index]
		selected := index == s.win.Cursor
		if selected && s.focus.Is(int(paneNoticeList)) {
			lines = append(lines, th.SelectedRow().Render(th.Glyphs.Selected+" ")+
				th.SelectedRow().Render(components.Truncate(notice.Title, inner-2)))
			continue
		}
		if selected {
			lines = append(lines, "  "+th.Body().Render(components.Truncate(notice.Title, inner-2)))
			continue
		}
		lines = append(lines, "  "+th.MutedStyle().Render(components.Truncate(notice.Title, inner-2)))
	}
	body := strings.Join(lines, "\n")
	if end-start < len(s.data.Notices) {
		body += "\n" + th.SubtleStyle().Render(fmt.Sprintf("  %d-%d / %d",
			start+1, end, len(s.data.Notices)))
	}

	footer := "/ " + "搜索标题与正文"
	if s.search.text != "" {
		footer = "关键字：" + s.search.text
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Notices,
		Title:    "公告",
		Subtitle: fmt.Sprintf("%s · %d 条", scope, s.data.Total),
		Body:     body,
		Footer:   footer,
		Focused:  s.focus.Is(int(paneNoticeList)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderBody 渲染选中公告的正文。
func (s *Notices) renderBody(th theme.Theme, width, height int) string {
	if s.win.Cursor < 0 || s.win.Cursor >= len(s.data.Notices) {
		return components.Card{
			Icon: th.Glyphs.Icons.Notices, Title: "公告正文",
			Body:    th.SubtleStyle().Render("在左侧选择一条公告"),
			Focused: s.focus.Is(int(paneNoticeBody)), Width: width, Height: height,
		}.Render(th)
	}
	notice := s.data.Notices[s.win.Cursor]
	inner := maxInt(12, width-4)

	body := notice.Content
	if strings.TrimSpace(body) == "" {
		body = "（该公告没有正文内容）"
	}
	footer := "公告来源本身没有发布日期，这里不显示日期"
	return components.Card{
		Icon:     th.Glyphs.Icons.Notices,
		Title:    "公告正文",
		Subtitle: fmt.Sprintf("%d / %d", s.win.Cursor+1, len(s.data.Notices)),
		Body:     th.Heading().Render(components.Truncate(notice.Title, inner)) + "\n\n" + th.Body().Render(body),
		Footer:   footer,
		Focused:  s.focus.Is(int(paneNoticeBody)),
		Width:    width,
		Height:   height,
	}.Render(th)
}
