package screens

import (
	"strconv"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// settingKind 决定一行的交互方式。
type settingKind int

const (
	settingToggle settingKind = iota
	settingCycle
	settingText
	settingSecret
	settingInfo
	settingConfirm
)

// settingRow 是设置项。
type settingRow struct {
	label string
	kind  settingKind
	// value 返回当前显示值。
	value func(config.Config) string
	// apply 应用一次变更。
	apply func(config.Config) config.Config
	// hint 是说明文本。
	hint string
}

// Settings 是设置页。
type Settings struct {
	ui.Base
	deps  ui.Deps
	state components.State
	win   components.Window
	edit  searchBox
	rows  []settingRow
}

// NewSettings 构造设置页。
func NewSettings(deps ui.Deps) ui.Screen {
	screen := &Settings{deps: deps}
	screen.ScreenID = ui.ScreenSettings
	screen.ScreenTitle = "设置"
	screen.rows = screen.buildRows()
	return screen
}

// Init 设置页不需要异步加载。
func (s *Settings) Init() tea.Cmd { return nil }

// buildRows 定义全部设置项。
func (s *Settings) buildRows() []settingRow {
	return []settingRow{
		{
			label: "主题", kind: settingCycle,
			value: func(c config.Config) string {
				switch c.UI.Theme {
				case config.ThemeDark:
					return "深色"
				case config.ThemeLight:
					return "浅色"
				case config.ThemeANSI16:
					return "16 色兼容"
				default:
					return "跟随终端"
				}
			},
			apply: func(c config.Config) config.Config {
				c.UI.Theme = nextInCycle([]string{
					config.ThemeAuto, config.ThemeDark, config.ThemeLight, config.ThemeANSI16,
				}, c.UI.Theme)
				return c
			},
			hint: "也可以随时按 ctrl+t 切换",
		},
		{
			label: "ASCII 边框", kind: settingToggle,
			value: func(c config.Config) string { return onOff(c.UI.ASCII) },
			apply: func(c config.Config) config.Config { c.UI.ASCII = !c.UI.ASCII; return c },
			hint:  "终端字体缺字或出现乱码时打开",
		},
		{
			label: "左侧导航", kind: settingToggle,
			value: func(c config.Config) string { return onOff(c.UI.Sidebar) },
			apply: func(c config.Config) config.Config { c.UI.Sidebar = !c.UI.Sidebar; return c },
			hint:  "宽度不足 88 列时自动隐藏",
		},
		{
			label: "教务地址", kind: settingText,
			value: func(c config.Config) string {
				if strings.TrimSpace(c.Academic.BaseURLOverride) != "" {
					return c.EffectiveAcademicBase() + "（--base-url 临时覆盖）"
				}
				if strings.TrimSpace(c.Academic.BaseURL) == "" {
					return "官方默认 " + campus.OfficialBaseURL
				}
				return c.EffectiveAcademicBase()
			},
			apply: func(c config.Config) config.Config { return c },
			hint: "可填其他 URP 教务部署地址（如 https://jwzx.example.edu.cn），留空即官方默认；" +
				"切换后如已登录请重新登录一次",
		},
		{
			label: "恢复默认教务地址", kind: settingConfirm,
			value: func(c config.Config) string {
				if strings.TrimSpace(c.Academic.BaseURL) == "" && strings.TrimSpace(c.Academic.BaseURLOverride) == "" {
					return "已是官方默认"
				}
				return "恢复"
			},
			apply: func(c config.Config) config.Config { return c },
			hint:  "删除自定义地址，回到 " + campus.OfficialBaseURL,
		},
		{
			label: "AI 对话", kind: settingConfirm,
			value: func(c config.Config) string { return onOff(c.LLM.Enabled) },
			apply: func(c config.Config) config.Config { c.LLM.Enabled = !c.LLM.Enabled; return c },
			hint:  "打开后会询问是否允许数据出网",
		},
		{
			label: "允许数据出网", kind: settingToggle,
			value: func(c config.Config) string { return onOff(c.LLM.Consent) },
			apply: func(c config.Config) config.Config { c.LLM.Consent = !c.LLM.Consent; return c },
			hint:  "关闭后立即停止发送任何内容",
		},
		{
			label: "接口地址", kind: settingText,
			value: func(c config.Config) string { return orDefault(c.LLM.BaseURL, "未配置") },
			apply: func(c config.Config) config.Config { return c },
			hint:  "OpenAI 兼容服务的 /v1 根，例如 https://api.deepseek.com/v1",
		},
		{
			label: "模型", kind: settingText,
			value: func(c config.Config) string { return orDefault(c.LLM.Model, "未配置") },
			apply: func(c config.Config) config.Config { return c },
			hint:  "例如 deepseek-flash、gpt-4o-mini、qwen2.5:7b",
		},
		{
			label: "API Key", kind: settingSecret,
			value: func(c config.Config) string {
				if strings.TrimSpace(c.LLM.APIKey) != "" {
					return "已设置（已隐藏）"
				}
				return "未设置（将读取环境变量 " + orDefault(c.LLM.APIKeyEnv, "BETTERHRBUST_LLM_KEY") + "）"
			},
			apply: func(c config.Config) config.Config { return c },
			hint:  "保存在本机配置文件（权限 0600），不会写入图形界面的缓存",
		},
		{
			label: "温度", kind: settingCycle,
			value: func(c config.Config) string { return strconv.FormatFloat(c.LLM.Temperature, 'f', 1, 64) },
			apply: func(c config.Config) config.Config {
				next := nextInCycle([]string{"0.1", "0.3", "0.7", "1.0"},
					strconv.FormatFloat(c.LLM.Temperature, 'f', 1, 64))
				value, _ := strconv.ParseFloat(next, 64)
				c.LLM.Temperature = value
				return c
			},
			hint: "越低越稳定，查数据建议 0.1–0.3",
		},
		{
			label: "工具轮次上限", kind: settingCycle,
			value: func(c config.Config) string { return strconv.Itoa(c.LLM.MaxToolRounds) },
			apply: func(c config.Config) config.Config {
				next := nextInCycle([]string{"4", "8", "12", "16"}, strconv.Itoa(c.LLM.MaxToolRounds))
				value, _ := strconv.Atoi(next)
				c.LLM.MaxToolRounds = value
				return c
			},
			hint: "模型最多连续调用多少轮工具",
		},
		{
			label: "请求超时", kind: settingCycle,
			value: func(c config.Config) string { return strconv.Itoa(c.LLM.TimeoutSecond) + " 秒" },
			apply: func(c config.Config) config.Config {
				next := nextInCycle([]string{"60", "120", "300"},
					strconv.Itoa(c.LLM.TimeoutSecond))
				value, _ := strconv.Atoi(next)
				c.LLM.TimeoutSecond = value
				return c
			},
			hint: "单次请求的整体上限",
		},
		{
			label: "发送学籍", kind: settingToggle,
			value: func(c config.Config) string { return onOff(c.LLM.SendProfile) },
			apply: func(c config.Config) config.Config { c.LLM.SendProfile = !c.LLM.SendProfile; return c },
			hint:  "关闭时模型看不到学籍；即使打开，证件号与手机号也已打码",
		},
	}
}

// Update 处理消息。
func (s *Settings) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	keyMsg, ok := msg.(tea.KeyPressMsg)
	if !ok {
		return s, nil
	}

	if s.edit.Active() {
		return s.handleEdit(keyMsg)
	}

	switch keyMsg.String() {
	case "up", "k":
		s.win.Move(-1, len(s.rows))
	case "down", "j":
		s.win.Move(1, len(s.rows))
	case "home", "g":
		s.win.Home(len(s.rows))
	case "end", "G":
		s.win.End(len(s.rows))
	case "enter", " ":
		return s.activate()
	}
	return s, nil
}

// handleEdit 处理文本编辑。
func (s *Settings) handleEdit(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	switch msg.String() {
	case "esc":
		s.edit.Clear()
		return s, nil
	case "enter":
		value := strings.TrimSpace(s.edit.text)
		label := s.edit.label
		s.edit.Clear()
		return s, s.commitText(label, value)
	case "backspace":
		s.edit.Backspace()
		return s, nil
	}
	if text := msg.String(); len([]rune(text)) == 1 {
		s.edit.Push([]rune(text)[0])
	}
	return s, nil
}

// activate 执行当前行的主操作。
func (s *Settings) activate() (ui.Screen, tea.Cmd) {
	if s.win.Cursor < 0 || s.win.Cursor >= len(s.rows) {
		return s, nil
	}
	row := s.rows[s.win.Cursor]
	current := s.deps.Config

	switch row.kind {
	case settingInfo:
		return s, nil
	case settingText:
		s.edit.Open(row.label)
		switch row.label {
		case "模型":
			s.edit.text = current.LLM.Model
		case "教务地址":
			// 预填当前自定义值（官方默认时留空，直接回车即恢复默认）
			s.edit.text = strings.TrimSpace(current.Academic.BaseURL)
		default:
			s.edit.text = current.LLM.BaseURL
		}
		return s, nil
	case settingSecret:
		s.edit.Open(row.label)
		s.edit.text = ""
		return s, nil
	case settingConfirm:
		if row.label == "恢复默认教务地址" {
			return s, s.confirmResetAcademicBase(current)
		}
		// 打开 AI 需要显式确认数据会离开本机。
		if current.LLM.Enabled {
			updated := current
			updated.LLM.Enabled = false
			return s, s.save(updated, "已关闭 AI 对话")
		}
		text := "打开 AI 对话后，你的提问与查询结果（学籍已打码）会发送到\n" +
			"你配置的服务商：" + current.ProviderHost() + "\n\n" +
			"确定要打开吗？（之后可随时在设置里关闭）"
		return s, func() tea.Msg {
			return ui.ConfirmMsg{
				Text: text,
				Action: func() tea.Msg {
					updated := current
					updated.LLM.Enabled = true
					updated.LLM.Consent = true
					return ui.ApplyConfigMsg{Config: updated, Toast: "AI 对话已开启"}
				},
			}
		}
	default:
		updated := row.apply(current)
		return s, s.save(updated, "已更新："+row.label)
	}
}

// confirmResetAcademicBase 让"恢复默认教务地址"走一次确认，避免误触。
func (s *Settings) confirmResetAcademicBase(current config.Config) tea.Cmd {
	text := "将删除自定义教务地址，回到官方默认：\n" + campus.OfficialBaseURL + "\n\n" +
		"已保存的登录会话不会被删除，但切换站点后可能需要重新登录。确定恢复吗？"
	return func() tea.Msg {
		return ui.ConfirmMsg{
			Text: text,
			Action: func() tea.Msg {
				updated := current
				updated.Academic.BaseURL = ""
				// --base-url 的临时覆盖也一并清除，否则"恢复默认"看起来没生效
				updated.Academic.BaseURLOverride = ""
				return ui.ApplyConfigMsg{Config: updated, Toast: "已恢复官方教务地址"}
			},
		}
	}
}

// commitText 把文本编辑结果写入配置。
func (s *Settings) commitText(label, value string) tea.Cmd {
	updated := s.deps.Config
	switch label {
	case "接口地址":
		updated.LLM.BaseURL = value
	case "模型":
		updated.LLM.Model = value
	case "API Key":
		updated.LLM.APIKey = value
	case "教务地址":
		normalized, err := campus.NormalizeBase(value)
		if err != nil {
			// 非法地址不落盘：留一条提示，用户可以直接重试试
			return func() tea.Msg { return ui.ToastMsg{Text: err.Error(), Level: "fail"} }
		}
		if normalized == campus.OfficialBaseURL {
			updated.Academic.BaseURL = ""
		} else {
			updated.Academic.BaseURL = normalized
		}
	default:
		return nil
	}
	return s.save(updated, "已更新："+label)
}

// save 把配置变更交给外壳持久化。
func (s *Settings) save(updated config.Config, toast string) tea.Cmd {
	return func() tea.Msg {
		return ui.ApplyConfigMsg{Config: updated, Toast: toast}
	}
}

// WantsInput 在编辑时接管键盘。
func (s *Settings) WantsInput() bool { return s.edit.Active() }

// Keys 返回页面按键。
func (s *Settings) Keys() []key.Binding {
	return []key.Binding{
		key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "修改")),
		key.NewBinding(key.WithKeys("up", "down"), key.WithHelp("↑/↓", "选择")),
	}
}

// View 渲染设置页：左侧设置项 + 右侧说明。
func (s *Settings) View() string {
	th := s.deps.Themes.Get()

	if s.Width < 64 || s.Height < 16 {
		return s.renderRows(th, s.Width, s.Height)
	}
	widths := components.ColumnWidths(s.Width-1, []int{3, 2})
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: s.renderRows(th, widths[0], s.Height)},
		components.Part{Width: widths[1], Content: s.renderHelp(th, widths[1], s.Height)},
	)
}

// renderRows 渲染设置项列表。
func (s *Settings) renderRows(th theme.Theme, width, height int) string {
	cfg := s.deps.Config
	inner := maxInt(16, width-4)

	s.win.Size = maxInt(1, height-4)
	s.win.Clamp(len(s.rows))
	start, end := s.win.Range(len(s.rows))

	lines := make([]string, 0, end-start)
	for index := start; index < end; index++ {
		row := s.rows[index]
		selected := index == s.win.Cursor
		labelWidth := minInt(14, maxInt(8, inner/3))
		marker := "  "
		style := th.Body()
		if selected {
			marker = th.Glyphs.Selected + " "
			style = th.SelectedRow()
		}
		value := row.value(cfg)
		lines = append(lines, marker+style.Render(components.PadRight(row.label, labelWidth))+
			th.Title().Render(components.Truncate(value, maxInt(4, inner-labelWidth-2))))
	}

	body := strings.Join(lines, "\n")
	if s.edit.Active() {
		body += "\n\n" + th.MutedStyle().Render(s.edit.label+" › ") +
			th.Body().Render(maskSecret(s.edit.label, s.edit.text)) +
			th.Title().Render(th.Glyphs.Cursor)
	}

	footer := "enter 修改 · ↑/↓ 选择"
	if s.edit.Active() {
		footer = "enter 保存 · esc 取消"
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Settings,
		Title:    "设置",
		Subtitle: "修改后立即写盘",
		Body:     body,
		Footer:   footer,
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderHelp 渲染右侧说明与状态。
func (s *Settings) renderHelp(th theme.Theme, width, height int) string {
	cfg := s.deps.Config
	inner := maxInt(10, width-4)

	hint := ""
	if s.win.Cursor >= 0 && s.win.Cursor < len(s.rows) {
		hint = s.rows[s.win.Cursor].hint
	}

	helpBody := th.SubtleStyle().Render("把光标移到左侧某项后，这里显示它的说明。")
	if hint != "" {
		helpBody = th.Body().Render(hint)
	}

	helpHeight := minInt(height, 8)
	statusHeight := height - helpHeight - 1
	if statusHeight < 6 {
		return components.Card{
			Icon: th.Glyphs.Icons.Info, Title: "说明",
			Body: helpBody, Width: width, Height: height,
		}.Render(th)
	}

	ready, reason := cfg.Ready(nil)
	statusLines := []string{}
	if ready {
		statusLines = append(statusLines, th.LevelColorStyle("ok").Render(
			th.Glyphs.Check+" AI 对话可用"))
	} else {
		statusLines = append(statusLines, th.LevelColorStyle("warn").Render(
			"! "+reason))
	}
	statusLines = append(statusLines,
		"",
		th.SubtleStyle().Render(components.PadRight("配置", 8))+
			th.MutedStyle().Render(components.Truncate(orDefault(cfg.Path, "未确定"), inner-9)),
		th.SubtleStyle().Render(components.PadRight("服务商", 8))+
			th.MutedStyle().Render(components.Truncate(cfg.ProviderHost(), inner-9)),
		th.SubtleStyle().Render(components.PadRight("教务", 8))+
			th.MutedStyle().Render(components.Truncate(cfg.EffectiveAcademicBase(), inner-9)),
		th.SubtleStyle().Render(components.PadRight("版本", 8))+
			th.MutedStyle().Render(s.deps.Version),
	)

	return components.StackV(width, []components.Part{
		{Width: helpHeight, Content: components.Card{
			Icon: th.Glyphs.Icons.Info, Title: "说明",
			Body: helpBody, Width: width, Height: helpHeight,
		}.Render(th)},
		{Width: statusHeight, Content: components.Card{
			Icon: th.Glyphs.Icons.AI, Title: "AI 状态",
			Level: levelOfReadiness(boolCode(ready)),
			Body:  strings.Join(statusLines, "\n"),
			Width: width, Height: statusHeight,
		}.Render(th)},
	})
}

// boolCode 把布尔转成就绪码，复用同一套级别映射。
func boolCode(ready bool) string {
	if ready {
		return "ready"
	}
	return "noData"
}

// renderFooterInfo 渲染配置状态摘要。
func (s *Settings) renderFooterInfo(th theme.Theme, cfg config.Config) string {
	ready, reason := cfg.Ready(nil)
	status := th.LevelColorStyle("ok").Render(th.Glyphs.Check + " AI 可用")
	if !ready {
		status = th.SubtleStyle().Render("AI 不可用：" + reason)
	}
	pairs := [][2]string{
		{"配置文件", orDefault(cfg.Path, "未确定")},
		{"服务商", cfg.ProviderHost()},
		{"状态", status},
		{"版本", s.deps.Version},
	}
	return lipgloss.NewStyle().PaddingLeft(2).Render(components.KV(th, pairs, 10))
}

// maskSecret 对密钥类输入做打码显示。
func maskSecret(label, text string) string {
	if label != "API Key" {
		return text
	}
	if text == "" {
		return ""
	}
	return strings.Repeat("*", minInt(len(text), 24))
}

// onOff 把布尔渲染成中文开关。
func onOff(value bool) string {
	if value {
		return "开"
	}
	return "关"
}
