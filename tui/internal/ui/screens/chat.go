package screens

import (
	"context"
	"fmt"
	"strings"
	"time"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"
	"charm.land/glamour/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/llm"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// chatRole 是对话条目类型。
type chatRole int

const (
	roleUser chatRole = iota
	roleAssistant
	roleTool
	roleError
	roleNotice
)

// chatEntry 是一条对话记录。
type chatEntry struct {
	role chatRole
	text string
	// rendered 是最终渲染结果（Markdown 已格式化）。
	rendered string
	// pending 表示仍在流式输出。
	pending bool
}

// Chat 是 AI 对话抽屉。
//
// 与普通页面不同，它的数据不来自本地缓存，而是来自用户配置的模型服务。
// 但所有工具调用仍走 `internal/query`，因此模型看到的数字与界面完全一致。
type Chat struct {
	ui.Base
	deps ui.Deps

	client  *llm.Client
	session *llm.Session

	entries   []chatEntry
	input     string
	streaming bool
	round     int
	offset    int
	lastErr   string

	cancel context.CancelFunc

	keys struct {
		Send   key.Binding
		Clear  key.Binding
		Cancel key.Binding
		Scroll key.Binding
	}
}

// NewChat 构造对话抽屉。
func NewChat(deps ui.Deps) ui.Screen {
	chat := &Chat{deps: deps}
	chat.ScreenID = "chat"
	chat.ScreenTitle = "AI 对话"
	chat.keys.Send = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "发送"))
	chat.keys.Clear = key.NewBinding(key.WithKeys("ctrl+n"), key.WithHelp("ctrl+n", "新对话"))
	chat.keys.Cancel = key.NewBinding(key.WithKeys("ctrl+x"), key.WithHelp("ctrl+x", "中断"))
	chat.keys.Scroll = key.NewBinding(key.WithKeys("pgup", "pgdown"), key.WithHelp("pgup/pgdn", "翻看"))
	return chat
}

// Init 在配置齐全时准备客户端。
func (s *Chat) Init() tea.Cmd {
	s.ensureClient()
	return nil
}

// ensureClient 依据配置创建客户端与会话。
func (s *Chat) ensureClient() bool {
	ready, _ := s.deps.Config.Ready(nil)
	if !ready {
		return false
	}
	if s.session != nil {
		return true
	}
	settings := s.deps.Config.LLM
	if s.deps.Toolset == nil {
		return false
	}
	s.client = llm.NewClient(
		settings.BaseURL,
		s.deps.Config.ResolvedAPIKey(nil),
		settings.Model,
		settings.Temperature,
		time.Duration(settings.TimeoutSecond)*time.Second,
	)

	definitions := s.deps.Toolset.Definitions()
	if !settings.SendProfile {
		// 关闭「发送学籍」时直接把工具从定义里摘掉：
		// 模型看不到 query_profile，也就不可能调用它。
		filtered := definitions[:0:0]
		for _, definition := range definitions {
			if definition.Function.Name == "query_profile" {
				continue
			}
			filtered = append(filtered, definition)
		}
		definitions = filtered
	}

	tools := make([]llm.Tool, 0, len(definitions))
	for _, definition := range definitions {
		tools = append(tools, llm.Tool{
			Type: definition.Type,
			Function: llm.ToolFunction{
				Name:        definition.Function.Name,
				Description: definition.Function.Description,
				Parameters:  definition.Function.Parameters,
			},
		})
	}

	s.session = llm.NewSession(s.client, s.deps.Toolset, tools,
		llm.DefaultSystemPrompt, settings.MaxToolRounds)
	return true
}

// Update 处理消息。
func (s *Chat) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DrawerEventMsg:
		s.applyEvent(typed.Event)
		return s, nil
	case ui.DrawerDoneMsg:
		s.finish(typed.Err)
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// applyEvent 把流式事件写入条目。
func (s *Chat) applyEvent(event llm.Event) {
	switch event.Kind {
	case llm.EventRound:
		s.round = event.Round
	case llm.EventText:
		s.appendStream(event.Text)
	case llm.EventReasoning:
		// 推理过程不写入历史，只在界面上以弱化文本显示。
		s.appendReasoning(event.Text)
	case llm.EventToolStart:
		s.entries = append(s.entries, chatEntry{role: roleTool, text: "调用 " + event.Text, pending: true})
	case llm.EventToolEnd:
		for index := len(s.entries) - 1; index >= 0; index-- {
			if s.entries[index].role == roleTool && s.entries[index].pending {
				s.entries[index].text = event.Text
				s.entries[index].pending = false
				break
			}
		}
	}
	s.scrollToBottom()
}

// appendStream 追加正文增量。
func (s *Chat) appendStream(delta string) {
	if len(s.entries) == 0 || s.entries[len(s.entries)-1].role != roleAssistant ||
		!s.entries[len(s.entries)-1].pending {
		s.entries = append(s.entries, chatEntry{role: roleAssistant, pending: true})
	}
	last := &s.entries[len(s.entries)-1]
	last.text += delta
	last.rendered = ""
	s.streaming = true
}

// appendReasoning 追加推理增量（与正文分开呈现）。
func (s *Chat) appendReasoning(delta string) {
	if len(s.entries) == 0 || s.entries[len(s.entries)-1].role != roleNotice ||
		!s.entries[len(s.entries)-1].pending {
		s.entries = append(s.entries, chatEntry{role: roleNotice, pending: true, text: "思考中…"})
		return
	}
	s.streaming = true
}

// finish 结束本轮对话。
func (s *Chat) finish(err error) {
	s.streaming = false
	s.round = 0

	kept := make([]chatEntry, 0, len(s.entries))
	for _, entry := range s.entries {
		// 推理占位只在生成期间有意义，结束后丢弃。
		if entry.role == roleNotice && entry.text == "思考中…" {
			continue
		}
		entry.pending = false
		kept = append(kept, entry)
	}
	s.entries = kept

	if err != nil {
		s.lastErr = err.Error()
		s.entries = append(s.entries, chatEntry{role: roleError, text: err.Error()})
	}

	// 完成后再渲染 Markdown：流式期间保持纯文本，避免每帧重排版。
	dark := s.deps.Themes.Get().Dark
	for index := range s.entries {
		if s.entries[index].role == roleAssistant && s.entries[index].rendered == "" {
			s.entries[index].rendered = renderMarkdown(
				s.entries[index].text, s.markdownWidth(), dark)
		}
	}
	s.scrollToBottom()
}

// handleKey 处理按键。
func (s *Chat) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	switch msg.String() {
	case "enter":
		return s.send()
	case "ctrl+n":
		s.reset()
		return s, nil
	case "ctrl+x":
		if s.cancel != nil {
			s.cancel()
			s.cancel = nil
		}
		return s, nil
	case "pgup":
		s.offset -= maxInt(1, s.Height/2)
		if s.offset < 0 {
			s.offset = 0
		}
		return s, nil
	case "pgdown":
		s.offset += maxInt(1, s.Height/2)
		return s, nil
	case "backspace":
		runes := []rune(s.input)
		if len(runes) > 0 {
			s.input = string(runes[:len(runes)-1])
		}
		return s, nil
	}
	if text := msg.String(); len([]rune(text)) == 1 {
		s.input += text
	}
	return s, nil
}

// send 发起一轮对话。
func (s *Chat) send() (ui.Screen, tea.Cmd) {
	if s.streaming {
		return s, nil
	}
	prompt := strings.TrimSpace(s.input)
	if prompt == "" {
		return s, nil
	}
	if !s.ensureClient() {
		_, reason := s.deps.Config.Ready(nil)
		s.entries = append(s.entries, chatEntry{role: roleError, text: reason})
		return s, nil
	}

	s.input = ""
	s.entries = append(s.entries, chatEntry{role: roleUser, text: prompt})
	s.streaming = true
	s.scrollToBottom()

	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	session := s.session
	emit := s.deps.Emit

	return s, func() tea.Msg {
		go func() {
			err := session.Ask(ctx, prompt, func(event llm.Event) {
				emit(ui.DrawerEventMsg{Event: event})
			})
			cancel()
			emit(ui.DrawerDoneMsg{Err: err})
		}()
		return nil
	}
}

// reset 清空对话。
func (s *Chat) reset() {
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	s.entries = nil
	s.session = nil
	s.client = nil
	s.streaming = false
	s.offset = 0
	s.lastErr = ""
}

// scrollToBottom 取消滚动偏移，跟随最新内容。
func (s *Chat) scrollToBottom() { s.offset = 0 }

// markdownWidth 返回 Markdown 渲染宽度。
func (s *Chat) markdownWidth() int {
	width := s.Width - 4
	if width < 20 {
		width = 20
	}
	return width
}

// WantsInput 抽屉始终接管输入。
func (s *Chat) WantsInput() bool { return true }

// Keys 返回抽屉按键。
func (s *Chat) Keys() []key.Binding {
	return []key.Binding{s.keys.Send, s.keys.Clear, s.keys.Cancel}
}

// View 渲染抽屉。
func (s *Chat) View() string {
	th := s.deps.Themes.Get()
	if ready, reason := s.deps.Config.Ready(nil); !ready {
		return s.renderSetup(th, reason)
	}

	lines := make([]string, 0, s.Height)
	lines = append(lines, s.renderHeader(th))
	lines = append(lines, components.Divider(th, s.Width))

	body := s.renderEntries(th)
	lines = append(lines, body...)

	// 输入行固定在底部。
	for len(lines) < s.Height-2 {
		lines = append(lines, "")
	}
	if len(lines) > s.Height-2 {
		lines = lines[:s.Height-2]
	}
	lines = append(lines, components.Divider(th, s.Width))
	lines = append(lines, s.renderInput(th))
	return strings.Join(lines, "\n")
}

// renderHeader 渲染抽屉标题栏。
func (s *Chat) renderHeader(th theme.Theme) string {
	state := th.SubtleStyle().Render("就绪")
	if s.streaming {
		round := ""
		if s.round > 0 {
			round = fmt.Sprintf(" 第 %d 轮", s.round)
		}
		state = th.LevelColorStyle("info").Render("生成中" + round)
	}
	turns := 0
	if s.session != nil {
		turns = s.session.Turns()
	}
	right := fmt.Sprintf("%d 轮对话", turns)
	return " " + th.Title().Render("AI 对话") + "  " + state +
		th.SubtleStyle().Render("   "+right)
}

// renderInput 渲染输入行。
func (s *Chat) renderInput(th theme.Theme) string {
	if s.streaming {
		return " " + th.LevelColorStyle("info").Render(th.Glyphs.Cursor) +
			th.SubtleStyle().Render(" 正在生成…  ctrl+x 中断")
	}
	prompt := " " + th.Title().Render(th.Glyphs.Prompt+" ")
	if s.input == "" {
		return prompt + th.SubtleStyle().Render("输入问题，enter 发送；ctrl+n 新对话")
	}
	return components.Truncate(prompt+th.Body().Render(s.input)+th.Title().Render(th.Glyphs.Cursor), s.Width)
}

// renderEntries 渲染对话记录，并应用滚动偏移。
func (s *Chat) renderEntries(th theme.Theme) []string {
	if len(s.entries) == 0 {
		return []string{
			"",
			th.SubtleStyle().Render("  试着问："),
			th.MutedStyle().Render("  · 我这学期还有多少学分没修完？"),
			th.MutedStyle().Render("  · 下周有哪些课和考试？"),
			th.MutedStyle().Render("  · 有哪些挂科，会不会触发留降级？"),
			th.MutedStyle().Render("  · 我的学位绩点达标了吗？"),
			"",
			th.SubtleStyle().Render("  工具调用会读取本地缓存，数字与页面完全一致。"),
		}
	}

	lines := make([]string, 0, len(s.entries)*3)
	for _, entry := range s.entries {
		lines = append(lines, s.renderEntry(th, entry)...)
		lines = append(lines, "")
	}

	// 从底部向上取可见区间。
	available := s.Height - 6
	if available < 3 {
		available = 3
	}
	if s.offset > 0 {
		end := len(lines) - s.offset
		if end < 0 {
			end = 0
		}
		start := end - available
		if start < 0 {
			start = 0
		}
		return lines[start:end]
	}
	if len(lines) > available {
		return lines[len(lines)-available:]
	}
	return lines
}

// renderEntry 渲染单条对话。
func (s *Chat) renderEntry(th theme.Theme, entry chatEntry) []string {
	switch entry.role {
	case roleUser:
		head := " " + th.Title().Render("你")
		body := components.Wrap(entry.text, maxInt(16, s.Width-4))
		out := []string{head}
		for _, line := range body {
			out = append(out, "   "+th.Body().Render(line))
		}
		return out
	case roleTool:
		glyph := th.Glyphs.Check
		level := "ok"
		if entry.pending {
			glyph = "·"
			level = "info"
		}
		return []string{" " + th.LevelColorStyle(level).Render(glyph) +
			th.SubtleStyle().Render(" "+components.Truncate(entry.text, maxInt(10, s.Width-4)))}
	case roleError:
		body := components.Wrap(entry.text, maxInt(16, s.Width-4))
		out := []string{" " + th.LevelColorStyle("fail").Render(th.Glyphs.Cross+" 出错了")}
		for _, line := range body {
			out = append(out, "   "+th.LevelColorStyle("fail").Render(line))
		}
		return out
	case roleNotice:
		return []string{" " + th.SubtleStyle().Render("· "+entry.text)}
	default:
		head := " " + th.Heading().Render("助手")
		if entry.pending {
			head += th.SubtleStyle().Render("  输出中…")
		}
		out := []string{head}
		content := entry.rendered
		if content == "" {
			content = entry.text
		}
		for _, line := range strings.Split(content, "\n") {
			out = append(out, " "+line)
		}
		return out
	}
}

// renderSetup 在未配置时给出引导卡片。
func (s *Chat) renderSetup(th theme.Theme, reason string) string {
	lines := []string{
		"",
		" " + th.Title().Render("AI 对话尚未就绪"),
		"",
		" " + th.Body().Render(reason),
		"",
		" " + th.MutedStyle().Render("在设置页（按 ,）完成以下三步："),
		" " + th.MutedStyle().Render("  1. 填写接口地址与模型名（OpenAI 兼容协议）"),
		" " + th.MutedStyle().Render("  2. 填写 API Key，或设置环境变量 "+orDefault(s.deps.Config.LLM.APIKeyEnv, "BETTERHRBUST_LLM_KEY")),
		" " + th.MutedStyle().Render("  3. 打开「AI 对话」并同意数据出网"),
		"",
		" " + th.SubtleStyle().Render("支持的示例："),
		" " + th.SubtleStyle().Render("  DeepSeek   https://api.deepseek.com/v1   deepseek-flash"),
		" " + th.SubtleStyle().Render("  Ollama     http://127.0.0.1:11434/v1     qwen2.5:7b"),
		"",
		" " + th.SubtleStyle().Render("隐私：只有提问与工具返回的查询结果会发送；"),
		" " + th.SubtleStyle().Render("证件号、手机号、邮箱、地址始终打码；对话不落盘。"),
	}
	for len(lines) < s.Height {
		lines = append(lines, "")
	}
	if len(lines) > s.Height {
		lines = lines[:s.Height]
	}
	for index, line := range lines {
		lines[index] = components.Truncate(line, s.Width)
	}
	return strings.Join(lines, "\n")
}

// renderMarkdown 用 glamour 渲染 Markdown，失败时退回原文。
//
// 只在消息完成后调用一次：流式期间每帧重排版会把界面拖卡。
func renderMarkdown(text string, width int, dark bool) string {
	style := "dark"
	if !dark {
		style = "light"
	}
	renderer, err := glamour.NewTermRenderer(
		glamour.WithStandardStyle(style),
		glamour.WithWordWrap(width),
		glamour.WithPreservedNewLines(),
	)
	if err != nil {
		return text
	}
	out, err := renderer.Render(text)
	if err != nil {
		return text
	}
	return strings.TrimRight(out, "\n")
}

// ChatFactory 返回创建对话抽屉的工厂，供 cmd 装配。
func ChatFactory() func(ui.Deps) ui.Screen {
	return func(deps ui.Deps) ui.Screen { return NewChat(deps) }
}
