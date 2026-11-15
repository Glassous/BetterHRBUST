package screens

import (
	"fmt"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Profile 是学籍档案页：三张卡片组成的双栏栅格。
//
// 只读且强制打码：即使屏幕上有空间，也不提供"显示明文"的开关。
// 终端里出现的任何内容都可能被截图、录屏、日志或 AI 上下文带走。
type Profile struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.ProfileData
}

// NewProfile 构造学籍页。
func NewProfile(deps ui.Deps) ui.Screen {
	screen := &Profile{deps: deps}
	screen.ScreenID = ui.ScreenProfile
	screen.ScreenTitle = "学籍档案"
	screen.state = components.State{Loading: true}
	return screen
}

// Init 加载学籍。
func (s *Profile) Init() tea.Cmd { return s.load() }

// load 查询学籍。
func (s *Profile) load() tea.Cmd {
	return loadCmd(s.deps, ui.ScreenProfile, func(service *query.Service) any {
		return service.Profile()
	})
}

// Update 处理消息。
func (s *Profile) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	typed, ok := msg.(ui.DataMsg)
	if !ok || typed.Screen != ui.ScreenProfile {
		return s, nil
	}
	s.state.Loading = false
	if typed.Err != nil {
		s.state.Err = typed.Err
		return s, nil
	}
	s.state.Err = nil
	s.data, _ = typed.Data.(query.ProfileData)
	profile := s.data.Profile
	s.state.Empty = profile.StudentNumber == "" && profile.RealName == ""
	s.state.EmptyText = "学籍档案为空：请在图形界面刷新一次"
	return s, nil
}

// Keys 返回页面按键（只读页没有页面级按键）。
func (s *Profile) Keys() []key.Binding {
	return []key.Binding{
		key.NewBinding(key.WithKeys("ctrl+r"), key.WithHelp("ctrl+r", "重新读取")),
	}
}

// View 渲染学籍。
func (s *Profile) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取学籍档案…"); handled {
		return content
	}
	profile := s.data.Profile

	headerHeight := 6
	if s.Height < 28 {
		headerHeight = 5
	}
	header := s.renderHeader(th, s.Width, headerHeight)

	bodyHeight := s.Height - headerHeight - 1
	if bodyHeight < 6 {
		bodyHeight = 6
	}
	if s.Width < 64 {
		return strings.Join([]string{
			header,
			s.renderCard(th, "学籍信息", s.identityRows(profile), s.Width, bodyHeight/2),
			s.renderCard(th, "联系与证件", s.contactRows(profile), s.Width, bodyHeight-bodyHeight/2),
		}, "\n")
	}

	widths := components.ColumnWidths(s.Width-1, []int{1, 1})
	leftHeight := bodyHeight
	left := s.renderCard(th, "学籍信息", s.identityRows(profile), widths[0], leftHeight)
	right := s.renderRight(th, widths[1], bodyHeight)
	return strings.Join([]string{header, components.SplitHGap(bodyHeight, 1,
		components.Part{Width: widths[0], Content: left},
		components.Part{Width: widths[1], Content: right},
	)}, "\n")
}

// renderHeader 渲染身份带。
func (s *Profile) renderHeader(th theme.Theme, width, height int) string {
	profile := s.data.Profile
	inner := maxInt(20, width-4)

	columns := components.ColumnWidths(inner, []int{1, 1, 1})
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
		components.Part{Width: columns[0], Content: cell("姓名", orDefault(profile.RealName, "未记录"),
			orDefault(profile.StudentNumber, "未记录"), "")},
		components.Part{Width: columns[1], Content: cell("年级班级", orDefault(profile.Grade, "未记录"),
			orDefault(profile.ClassName, "未记录"), "")},
		components.Part{Width: columns[2], Content: cell("学籍状态", orDefault(profile.Status, "未记录"),
			orDefault(profile.StudentType, "未记录"), "")},
	)

	return components.Card{
		Icon:     th.Glyphs.Icons.Profile,
		Title:    "学籍概览",
		Subtitle: orDefault(profile.College, ""),
		Body:     body,
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderRight 渲染右栏：联系方式 + 异动记录。
func (s *Profile) renderRight(th theme.Theme, width, height int) string {
	contactHeight := minInt(height, 10)
	changeHeight := height - contactHeight - 1
	if changeHeight < 5 {
		return s.renderCard(th, "联系与证件", s.contactRows(s.data.Profile), width, height)
	}
	return components.StackV(width, []components.Part{
		{Width: contactHeight, Content: s.renderCard(th, "联系与证件", s.contactRows(s.data.Profile), width, contactHeight)},
		{Width: changeHeight, Content: s.renderChanges(th, width, changeHeight)},
	})
}

// renderCard 把一个 KV 表渲染成卡片。
func (s *Profile) renderCard(th theme.Theme, title string, rows [][2]string, width, height int) string {
	inner := maxInt(10, width-4)
	labelWidth := minInt(10, maxInt(4, inner/3))
	lines := make([]string, 0, len(rows))
	for _, row := range rows {
		lines = append(lines, th.Label().Render(components.PadRight(row[0], labelWidth))+
			"  "+th.Value().Render(components.Truncate(row[1], maxInt(4, inner-labelWidth-2))))
	}
	return components.Card{
		Icon:  th.Glyphs.Icons.Profile,
		Title: title,
		Body:  strings.Join(lines, "\n"),
		Width: width, Height: height,
	}.Render(th)
}

// renderChanges 渲染学籍异动记录。
func (s *Profile) renderChanges(th theme.Theme, width, height int) string {
	changes := s.data.Profile.Changes
	if len(changes) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Notices, Title: "学籍异动", Subtitle: "0 条",
			Body: th.SubtleStyle().Render("没有学籍异动记录"), Width: width, Height: height,
		}.Render(th)
	}

	inner := maxInt(10, width-4)
	budget := maxInt(1, height-2)
	lines := make([]string, 0, budget)
	for index, change := range changes {
		if index >= budget {
			lines = append(lines, th.SubtleStyle().Render(fmt.Sprintf("…… 另有 %d 条", len(changes)-budget)))
			break
		}
		lines = append(lines, th.Title().Render(components.Truncate(change.Kind, inner))+
			th.SubtleStyle().Render("  "+change.Date))
		if change.Reason != "" {
			lines = append(lines, "  "+th.MutedStyle().Render(
				components.Truncate(change.Reason, maxInt(4, inner-2))))
		}
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Notices,
		Title:    "学籍异动",
		Subtitle: fmt.Sprintf("%d 条", len(changes)),
		Body:     strings.Join(lines, "\n"),
		Footer:   "证件号、手机号、邮箱与地址固定打码",
		Width:    width,
		Height:   height,
	}.Render(th)
}

// identityRows 返回学籍信息行。
func (s *Profile) identityRows(profile queryProfile) [][2]string {
	return [][2]string{
		{"姓名", orDefault(profile.RealName, "未记录")},
		{"学号", orDefault(profile.StudentNumber, "未记录")},
		{"学院", orDefault(profile.College, "未记录")},
		{"专业", orDefault(profile.Major, "未记录")},
		{"方向", orDefault(profile.Direction, "未记录")},
		{"年级", orDefault(profile.Grade, "未记录")},
		{"班级", orDefault(profile.ClassName, "未记录")},
		{"类别", orDefault(profile.StudentType, "未记录")},
		{"状态", orDefault(profile.Status, "未记录")},
		{"邮编", orDefault(profile.PostalCode, "未记录")},
	}
}

// contactRows 返回联系方式行（全部已打码）。
func (s *Profile) contactRows(profile queryProfile) [][2]string {
	return [][2]string{
		{"证件号码", maskedOrDash(profile.IDCard)},
		{"联系电话", maskedOrDash(profile.Phone)},
		{"电子邮箱", maskedOrDash(profile.Email)},
		{"通讯地址", maskedOrDash(profile.Address)},
	}
}

// queryProfile 是学籍结构的别名，避免在多处重复写包名。
type queryProfile = model.Profile

// maskedOrDash 空值显示为「未记录」。
func maskedOrDash(value string) string {
	if strings.TrimSpace(value) == "" {
		return "未记录"
	}
	return value
}
