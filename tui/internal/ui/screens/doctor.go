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

// Doctor 是诊断页：检查结果 + 路径 + 缓存键，三张卡片并排。
//
// 原来用 tab 在三个视图间切换，但 tab 现在是"换面板"的全局语义，
// 拆成并排卡片既避免了按键冲突，也让三块信息能同时被看到。
type Doctor struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.DoctorData
	check query.CheckData

	keys struct {
		Rerun key.Binding
	}
}

// NewDoctor 构造诊断页。
func NewDoctor(deps ui.Deps) ui.Screen {
	screen := &Doctor{deps: deps}
	screen.ScreenID = ui.ScreenDoctor
	screen.ScreenTitle = "诊断"
	screen.state = components.State{Loading: true}
	screen.keys.Rerun = key.NewBinding(key.WithKeys("c"), key.WithHelp("c", "重新检查"))
	return screen
}

// Init 加载诊断数据。
func (s *Doctor) Init() tea.Cmd { return s.load() }

// load 同时取 doctor 与 check。
func (s *Doctor) load() tea.Cmd {
	return loadCmd(s.deps, ui.ScreenDoctor, func(service *query.Service) any {
		return doctorPayload{
			Doctor: service.Doctor(),
			Check:  service.Check(query.Options{}),
		}
	})
}

// doctorPayload 把两个结果一起带回，避免两次异步往返。
type doctorPayload struct {
	Doctor query.DoctorData
	Check  query.CheckData
}

// Update 处理消息。
func (s *Doctor) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenDoctor {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		if payload, ok := typed.Data.(doctorPayload); ok {
			s.data = payload.Doctor
			s.check = payload.Check
		}
		return s, nil
	case tea.KeyPressMsg:
		if key.Matches(typed, s.keys.Rerun) {
			s.state.Loading = true
			return s, s.load()
		}
	}
	return s, nil
}

// Keys 返回页面按键。
func (s *Doctor) Keys() []key.Binding {
	return []key.Binding{s.keys.Rerun}
}

// View 渲染诊断页。
func (s *Doctor) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在检查本地数据…"); handled {
		return content
	}

	headerHeight := 5
	if s.Height < 26 {
		headerHeight = 4
	}
	header := s.renderHeader(th, s.Width, headerHeight)

	bodyHeight := s.Height - headerHeight - 1
	if bodyHeight < 6 {
		bodyHeight = 6
	}
	if s.Width < 68 {
		heights := components.RowHeights(bodyHeight, []int{3, 2})
		return strings.Join([]string{
			header,
			s.renderChecks(th, s.Width, heights[0]),
			s.renderPaths(th, s.Width, heights[1]),
		}, "\n")
	}

	widths := components.ColumnWidths(s.Width-1, []int{5, 4})
	rightHeights := components.RowHeights(bodyHeight, []int{1, 1})
	right := components.StackV(widths[1], []components.Part{
		{Width: rightHeights[0], Content: s.renderPaths(th, widths[1], rightHeights[0])},
		{Width: rightHeights[1], Content: s.renderKeys(th, widths[1], rightHeights[1])},
	})
	return strings.Join([]string{header, components.SplitHGap(bodyHeight, 1,
		components.Part{Width: widths[0], Content: s.renderChecks(th, widths[0], bodyHeight)},
		components.Part{Width: widths[1], Content: right},
	)}, "\n")
}

// renderHeader 渲染诊断摘要带。
func (s *Doctor) renderHeader(th theme.Theme, width, height int) string {
	summary := s.check.Summary
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
		components.Part{Width: columns[0], Content: cell("通过", fmt.Sprintf("%d", summary.OK), "检查项", "ok")},
		components.Part{Width: columns[1], Content: cell("注意", fmt.Sprintf("%d", summary.Warn), "建议处理", warnIf(summary.Warn > 0))},
		components.Part{Width: columns[2], Content: cell("失败", fmt.Sprintf("%d", summary.Fail), "需要修复", failIf(summary.Fail > 0))},
		components.Part{Width: columns[3], Content: cell("状态", readinessLabel(s.data.Readiness), "缓存可用性", levelOfReadiness(s.data.Readiness))},
	)

	return components.Card{
		Icon:     th.Glyphs.Icons.Doctor,
		Title:    "数据健康",
		Subtitle: s.data.Version,
		Body:     body,
		Level:    levelOfReadiness(s.data.Readiness),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderChecks 渲染检查项。
func (s *Doctor) renderChecks(th theme.Theme, width, height int) string {
	if len(s.check.Checks) == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Doctor, Title: "检查结果",
			Body: th.SubtleStyle().Render("没有检查项"), Width: width, Height: height,
		}.Render(th)
	}

	inner := maxInt(12, width-4)
	lines := make([]string, 0, len(s.check.Checks)*2)
	for _, item := range s.check.Checks {
		glyph := th.Glyphs.Check
		level := "ok"
		switch item.Level {
		case "warn":
			glyph = "!"
			level = "warn"
		case "fail":
			glyph = th.Glyphs.Cross
			level = "fail"
		}
		lines = append(lines, th.LevelColorStyle(level).Render(glyph)+" "+
			th.Body().Render(components.Truncate(item.Title, inner-2)))
		detail := item.Detail
		if item.Hint != "" {
			if detail != "" {
				detail += " · "
			}
			detail += item.Hint
		}
		if detail != "" {
			lines = append(lines, "  "+th.SubtleStyle().Render(
				components.Truncate(detail, maxInt(4, inner-2))))
		}
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Doctor,
		Title:    "检查结果",
		Subtitle: fmt.Sprintf("%d 项", len(s.check.Checks)),
		Body:     strings.Join(lines, "\n"),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderPaths 渲染路径与文件信息。
func (s *Doctor) renderPaths(th theme.Theme, width, height int) string {
	inner := maxInt(12, width-4)
	storage := s.data.StoragePath
	if !s.data.StorageExists {
		storage += "（不存在）"
	}
	line := func(label, value string) string {
		return th.SubtleStyle().Render(components.PadRight(label, 8)) +
			th.Body().Render(components.Truncate(value, maxInt(4, inner-9)))
	}

	lines := []string{
		line("数据目录", s.data.DataDir),
		line("缓存文件", storage),
		line("文件大小", humanSize(s.data.StorageSize)),
		line("上次同步", s.deps.SyncText),
		line("校历", boolLabel(s.data.CalendarOK, "可用", "解析失败")),
	}
	if s.data.LoadError != "" {
		lines = append(lines, th.LevelColorStyle("fail").Render(
			components.Truncate(s.data.LoadError, inner)))
	}
	if len(s.data.Sidecars) > 0 {
		lines = append(lines, line("残留文件", strings.Join(s.data.Sidecars, "、")))
	}

	footer := "只报告，不改动任何文件"
	return components.Card{
		Icon:   th.Glyphs.Icons.Doctor,
		Title:  "路径与文件",
		Body:   strings.Join(lines, "\n"),
		Footer: footer,
		Width:  width,
		Height: height,
	}.Render(th)
}

// renderKeys 渲染缓存键表。
func (s *Doctor) renderKeys(th theme.Theme, width, height int) string {
	inner := maxInt(12, width-4)
	lines := make([]string, 0, height)
	for index, item := range s.data.Keys {
		if index >= height-2 {
			lines = append(lines, th.SubtleStyle().Render(fmt.Sprintf(
				"…… 另有 %d 个键", len(s.data.Keys)-index)))
			break
		}
		status := th.LevelColorStyle("fail").Render(th.Glyphs.Cross)
		size := "—"
		if item.Present {
			status = th.LevelColorStyle("ok").Render(th.Glyphs.Check)
			if item.Sensitive {
				size = "已隐藏"
			} else {
				size = humanSize(int64(item.Bytes))
			}
		}
		lines = append(lines, " "+status+" "+th.MutedStyle().Render(
			components.Truncate(item.Key, maxInt(6, inner-9)))+th.SubtleStyle().Render("  "+size))
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Settings,
		Title:    "缓存键",
		Subtitle: fmt.Sprintf("%d 个", len(s.data.Keys)),
		Body:     strings.Join(lines, "\n"),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// failIf 在条件成立时返回失败级别。
func failIf(bad bool) string {
	if bad {
		return "fail"
	}
	return ""
}

// levelOfReadiness 把就绪码映射成级别。
func levelOfReadiness(code string) string {
	if code == "ready" {
		return "ok"
	}
	return "warn"
}

// boolLabel 把布尔渲染成中文。
func boolLabel(value bool, yes, no string) string {
	if value {
		return yes
	}
	return no
}

// humanSize 把字节数转成人类可读形式。
func humanSize(size int64) string {
	if size <= 0 {
		return "0 B"
	}
	const unit = 1024.0
	value := float64(size)
	switch {
	case value < unit:
		return fmt.Sprintf("%d B", size)
	case value < unit*unit:
		return fmt.Sprintf("%.1f KB", value/unit)
	default:
		return fmt.Sprintf("%.1f MB", value/(unit*unit))
	}
}
