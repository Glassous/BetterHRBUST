package screens

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"strings"
	"time"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// 同步过程的时间上限：教务系统在校园网外会一直挂着，按钮不能永远转圈。
const syncTimeout = 120 * time.Second

// syncField 是登录表单的一个输入项。
type syncField struct {
	label  string
	secret bool
	value  string
}

// Sync 是「登录与同步」页。
//
// 它的存在是为了让终端不再依赖图形界面：在这里输入学号 / 密码 / 验证码，
// 登录成功后立刻全量同步，结果写回图形界面读的那份 storage.json。
// 之后打开图形界面，数据与登录态都在，用户不需要知道数据是谁同步的。
type Sync struct {
	ui.Base
	deps ui.Deps

	fields  []syncField
	focus   int
	editing bool

	remember bool
	busy     string
	status   campus.Status

	captcha     image.Image
	captchaHint string

	notice      string
	noticeLevel string
	log         []string
}

// NewSync 构造登录与同步页。
func NewSync(deps ui.Deps) ui.Screen {
	screen := &Sync{deps: deps, remember: true}
	screen.ScreenID = ui.ScreenSync
	screen.ScreenTitle = "登录与同步"
	screen.fields = []syncField{
		{label: "学号"},
		{label: "密码", secret: true},
		{label: "验证码"},
	}
	screen.refreshStatus()
	return screen
}

// Init 载入会话状态、已保存的账号密码与验证码。
func (s *Sync) Init() tea.Cmd {
	if s.deps.Sync == nil {
		s.notice = "同步功能不可用：请确认程序完整"
		s.noticeLevel = "fail"
		return nil
	}
	s.refreshStatus()
	s.prefill()
	return s.captchaCmd()
}

// prefill 用共享缓存里的「记住密码」预填表单。
//
// 图形界面勾选过「记住密码」时这里能直接拿到学号与密码，
// 终端就连"再输一遍账号"这一步也省了。
func (s *Sync) prefill() {
	if s.deps.Sync == nil || s.deps.Service == nil {
		return
	}
	if username, password, ok := s.deps.Sync.SavedCredential(s.deps.Service.Snapshot); ok {
		s.fields[0].value = username
		s.fields[1].value = password
		return
	}
	if number := s.deps.Sync.SavedNumber(s.deps.Service.Snapshot); number != "" {
		s.fields[0].value = number
		return
	}
	if s.status.StudentNumber != "" {
		s.fields[0].value = s.status.StudentNumber
	}
}

// refreshStatus 刷新会话状态快照。
func (s *Sync) refreshStatus() {
	if s.deps.Sync == nil {
		return
	}
	s.status = s.deps.Sync.Status()
}

// ---------- 异步命令 ----------

// syncEventMsg 是登录 / 同步 / 验证码任务的回执。
type syncEventMsg struct {
	kind   string
	err    error
	image  []byte
	login  campus.LoginResult
	result campus.SyncResult
}

// Target 让外壳把回执投递回本页（用户可能已经切到别的页面）。
func (m syncEventMsg) Target() ui.ID { return ui.ScreenSync }

// captchaCmd 拉取一张新的验证码。
func (s *Sync) captchaCmd() tea.Cmd {
	service := s.deps.Sync
	if service == nil {
		return nil
	}
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		data, err := service.Captcha(ctx)
		return syncEventMsg{kind: "captcha", image: data, err: err}
	}
}

// loginCmd 登录并立即同步：一次操作把"能用"这件事做完。
func (s *Sync) loginCmd() tea.Cmd {
	service := s.deps.Sync
	if service == nil {
		return nil
	}
	request := campus.LoginRequest{
		Username: s.fields[0].value,
		Password: s.fields[1].value,
		Captcha:  s.fields[2].value,
		Remember: s.remember,
	}
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), syncTimeout)
		defer cancel()
		result, err := service.Login(ctx, request)
		if err != nil {
			return syncEventMsg{kind: "login", err: err}
		}
		syncResult, syncErr := service.Sync(ctx, campus.SyncOptions{Account: result.StudentNumber})
		return syncEventMsg{kind: "login", login: result, result: syncResult, err: syncErr}
	}
}

// syncCmd 只做同步（已有会话时）。
func (s *Sync) syncCmd() tea.Cmd {
	service := s.deps.Sync
	if service == nil {
		return nil
	}
	account := s.account()
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), syncTimeout)
		defer cancel()
		result, err := service.Sync(ctx, campus.SyncOptions{Account: account})
		return syncEventMsg{kind: "sync", result: result, err: err}
	}
}

// account 返回用于校验档案一致性的学号。
//
// 缓存里的档案学号比会话文件更可信：会话文件只有 Cookie 时可能是图形界面
// 登录写下的，学号未必同步更新。
func (s *Sync) account() string {
	if s.deps.Service != nil && s.deps.Service.Snapshot != nil {
		if account := s.deps.Service.Snapshot.Account(); account != "" {
			return account
		}
	}
	return s.status.StudentNumber
}

// logoutCmd 结束会话。
func (s *Sync) logoutCmd() tea.Cmd {
	service := s.deps.Sync
	if service == nil {
		return nil
	}
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return syncEventMsg{kind: "logout", err: service.Logout(ctx)}
	}
}

// ---------- 交互 ----------

// Update 处理按键与任务回执。
func (s *Sync) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	if event, ok := msg.(syncEventMsg); ok {
		return s.handleEvent(event)
	}
	if _, ok := msg.(syncTriggerLogoutMsg); ok {
		if s.busy != "" {
			return s, nil
		}
		s.busy = "退出中"
		return s, s.logoutCmd()
	}
	keyMsg, ok := msg.(tea.KeyPressMsg)
	if !ok {
		return s, nil
	}
	// 图形界面可能刚刚登录（会话文件被它更新）：按键时顺手刷一次状态，
	// 用户不必退出重进就能看到"会话可用"。
	s.refreshStatus()
	if s.editing {
		return s.handleEdit(keyMsg)
	}

	switch keyMsg.String() {
	case "up", "k", "shift+tab":
		s.focus = (s.focus + len(s.fields) - 1) % len(s.fields)
	case "down", "j", "tab":
		s.focus = (s.focus + 1) % len(s.fields)
	case "enter":
		s.editing = true
	case "m":
		s.remember = !s.remember
		s.setNotice("记住密码："+onOff(s.remember), "info")
	case "c":
		if s.deps.Sync == nil {
			return s, nil
		}
		s.captchaHint = ""
		return s, s.captchaCmd()
	case "l":
		if s.deps.Sync == nil {
			s.setNotice("同步功能不可用", "fail")
			return s, nil
		}
		if s.busy != "" {
			return s, nil
		}
		s.busy = "登录中"
		s.setNotice("正在登录并同步…", "info")
		return s, s.loginCmd()
	case "s":
		if s.deps.Sync == nil {
			s.setNotice("同步功能不可用", "fail")
			return s, nil
		}
		if s.busy != "" {
			return s, nil
		}
		s.busy = "同步中"
		s.setNotice("正在同步…", "info")
		return s, s.syncCmd()
	case "x":
		if s.deps.Sync == nil {
			return s, nil
		}
		return s, s.confirmLogout()
	}
	return s, nil
}

// handleEdit 处理输入状态下的按键。
func (s *Sync) handleEdit(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	switch msg.String() {
	case "esc":
		s.editing = false
		return s, nil
	case "enter":
		s.editing = false
		s.focus = (s.focus + 1) % len(s.fields)
		return s, nil
	case "backspace":
		value := []rune(s.fields[s.focus].value)
		if len(value) > 0 {
			s.fields[s.focus].value = string(value[:len(value)-1])
		}
		return s, nil
	}
	if text := msg.String(); len([]rune(text)) == 1 {
		s.fields[s.focus].value += text
	}
	return s, nil
}

// handleEvent 处理异步回执。
func (s *Sync) handleEvent(event syncEventMsg) (ui.Screen, tea.Cmd) {
	s.busy = ""
	s.refreshStatus()

	switch event.kind {
	case "captcha":
		if event.err != nil {
			s.captcha = nil
			s.captchaHint = event.err.Error()
			return s, nil
		}
		img, _, decodeErr := image.Decode(bytes.NewReader(event.image))
		if decodeErr != nil {
			s.captcha = nil
			s.captchaHint = "验证码图片无法识别，按 c 重试"
			return s, nil
		}
		s.captcha = img
		s.captchaHint = ""
		return s, nil
	case "login":
		if event.err != nil && event.login.StudentNumber == "" {
			s.setNotice("登录失败："+event.err.Error(), "fail")
			s.fields[2].value = ""
			return s, tea.Batch(s.captchaCmd(), func() tea.Msg {
				return ui.ToastMsg{Text: "登录失败，请重试", Level: "fail"}
			})
		}
		s.appendLog("已登录：" + event.login.StudentNumber)
		s.fields[2].value = ""
		return s.finishSync(event, "登录成功并已同步")
	case "sync":
		return s.finishSync(event, "同步完成")
	case "logout":
		if event.err != nil {
			s.setNotice("退出登录失败："+event.err.Error(), "fail")
			return s, nil
		}
		s.appendLog("已退出登录")
		s.fields[1].value = ""
		s.fields[2].value = ""
		return s, tea.Batch(
			func() tea.Msg { return ui.ReloadMsg{} },
			func() tea.Msg {
				return ui.ToastMsg{Text: "已退出教务会话，本地缓存仍可离线查看", Level: "info"}
			},
		)
	}
	return s, nil
}

// finishSync 汇总一次同步结果，并让外壳重读缓存。
func (s *Sync) finishSync(event syncEventMsg, okText string) (ui.Screen, tea.Cmd) {
	switch {
	case errors.Is(event.err, campus.ErrNoSession):
		s.setNotice("尚未登录：先在左侧输入学号、密码与验证码", "fail")
	case event.result.Expired || errors.Is(event.err, campus.ErrSessionExpired):
		s.setNotice("教务会话已失效，请重新登录", "fail")
		s.appendLog("会话已失效")
		s.fields[2].value = ""
		return s, tea.Batch(
			func() tea.Msg { return ui.ReloadMsg{} },
			s.captchaCmd(),
			func() tea.Msg { return ui.ToastMsg{Text: "会话已失效，请重新登录", Level: "fail"} },
		)
	case event.err != nil:
		s.setNotice("同步失败："+event.err.Error(), "fail")
	case len(event.result.Failures) > 0:
		s.setNotice("部分数据同步失败："+strings.Join(event.result.Failures, "；"), "warn")
		for _, failure := range event.result.Failures {
			s.appendLog("跳过 " + failure)
		}
	default:
		s.setNotice(okText, "ok")
		s.appendLog(fmt.Sprintf("写入 %d 项缓存", event.result.Written))
	}

	level := "ok"
	if s.noticeLevel != "ok" {
		level = s.noticeLevel
	}
	return s, tea.Batch(
		func() tea.Msg { return ui.ReloadMsg{} },
		func() tea.Msg { return ui.ToastMsg{Text: s.notice, Level: level} },
	)
}

// confirmLogout 弹确认框后登出。
func (s *Sync) confirmLogout() tea.Cmd {
	return func() tea.Msg {
		return ui.ConfirmMsg{
			Text: "退出教务会话后，本地缓存仍可离线查看，但下次需要重新登录。\n确定要退出登录吗？",
			Action: func() tea.Msg {
				return syncTriggerLogoutMsg{}
			},
		}
	}
}

// syncTriggerLogoutMsg 是确认框回来的登出请求。
type syncTriggerLogoutMsg struct{}

// setNotice 记录一条提示。
func (s *Sync) setNotice(text, level string) {
	s.notice = text
	s.noticeLevel = level
}

// appendLog 追加一行操作日志（只保留最近 6 行）。
func (s *Sync) appendLog(line string) {
	s.log = append(s.log, line)
	if len(s.log) > 6 {
		s.log = s.log[len(s.log)-6:]
	}
}

// WantsInput 在编辑时接管字母与数字键。
func (s *Sync) WantsInput() bool { return s.editing }

// Keys 返回页面按键。
func (s *Sync) Keys() []key.Binding {
	return []key.Binding{
		key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "输入")),
		key.NewBinding(key.WithKeys("l"), key.WithHelp("l", "登录并同步")),
		key.NewBinding(key.WithKeys("s"), key.WithHelp("s", "同步")),
		key.NewBinding(key.WithKeys("c"), key.WithHelp("c", "换验证码")),
		key.NewBinding(key.WithKeys("x"), key.WithHelp("x", "退出登录")),
	}
}

// View 渲染整页。
func (s *Sync) View() string {
	th := s.deps.Themes.Get()
	if s.Width < 68 || s.Height < 18 {
		return components.Card{
			Icon: th.Glyphs.Icons.Info, Title: "登录与同步",
			Body:  strings.Join(s.flatLines(th), "\n"),
			Width: s.Width, Height: s.Height,
		}.Render(th)
	}
	widths := components.ColumnWidths(s.Width-1, []int{3, 2})
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: s.renderForm(th, widths[0], s.Height)},
		components.Part{Width: widths[1], Content: s.renderStatus(th, widths[1], s.Height)},
	)
}

// flatLines 是窄屏下的单列渲染。
func (s *Sync) flatLines(th theme.Theme) []string {
	lines := []string{}
	lines = append(lines, s.formLines(th, s.Width-4)...)
	if captcha := s.captchaLines(th, maxInt(16, s.Width-8)); len(captcha) > 0 {
		lines = append(lines, captcha...)
	} else if hint := s.captchaText(th); hint != "" {
		lines = append(lines, "", th.MutedStyle().Render(hint))
	}
	lines = append(lines, "", th.SubtleStyle().Render(s.statusLine()))
	if s.notice != "" {
		lines = append(lines, th.LevelColorStyle(s.noticeLevel).Render(s.notice))
	}
	return lines
}

// captchaLines 渲染验证码图像；无色主题下返回 nil（真彩色转义会被当成噪声）。
func (s *Sync) captchaLines(th theme.Theme, width int) []string {
	if s.captcha == nil || th.Plain {
		return nil
	}
	return renderImage(s.captcha, width)
}

// captchaText 返回验证码区域不可用时的说明。
func (s *Sync) captchaText(th theme.Theme) string {
	switch {
	case s.captcha != nil && th.Plain:
		return "当前主题已去掉颜色，验证码无法在终端里显示：换一个彩色主题，或到图形界面登录一次"
	case s.captchaHint != "":
		return s.captchaHint
	default:
		return ""
	}
}

// renderForm 渲染左侧登录表单。
func (s *Sync) renderForm(th theme.Theme, width, height int) string {
	body := strings.Join(s.formLines(th, width-4), "\n")

	if captcha := s.captchaLines(th, maxInt(12, width-8)); len(captcha) > 0 {
		body += "\n\n" + strings.Join(captcha, "\n")
	} else if hint := s.captchaText(th); hint != "" {
		body += "\n\n" + th.MutedStyle().Render(hint)
	}

	footer := "enter 输入 · tab 换行 · c 换一张图"
	if s.status.HasSession {
		footer = "enter 输入 · l 重新登录 · s 同步 · x 退出"
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Profile,
		Title:    "登录教务系统",
		Subtitle: "登录后立即同步，无需图形界面",
		Level:    s.cardLevel(),
		Body:     body,
		Footer:   footer,
		Width:    width,
		Height:   height,
	}.Render(th)
}

// formLines 渲染三行输入。
func (s *Sync) formLines(th theme.Theme, width int) []string {
	lines := make([]string, 0, len(s.fields)+2)
	for index, field := range s.fields {
		marker := "  "
		style := th.Body()
		if index == s.focus {
			marker = th.Glyphs.Selected + " "
			style = th.SelectedRow()
		}
		value := field.value
		if field.secret && value != "" && !(s.editing && index == s.focus) {
			value = strings.Repeat("*", minInt(len([]rune(value)), 24))
		}
		if s.editing && index == s.focus {
			value += th.Glyphs.Cursor
		}
		if value == "" {
			value = " "
		}
		lines = append(lines, marker+th.SubtleStyle().Render(components.PadRight(field.label, 8))+
			style.Render(components.Truncate(value, maxInt(6, width-12))))
	}
	lines = append(lines, "  "+th.SubtleStyle().Render(components.PadRight("记住密码", 8))+
		th.Title().Render(onOff(s.remember))+"  "+th.MutedStyle().Render("m 切换"))
	return lines
}

// renderStatus 渲染右侧状态与日志。
func (s *Sync) renderStatus(th theme.Theme, width, height int) string {
	inner := maxInt(10, width-4)
	lines := []string{
		th.SubtleStyle().Render("会话") + "  " +
			th.LevelColorStyle(s.sessionLevel()).Render(s.sessionLabel()),
	}
	if s.status.StudentNumber != "" {
		lines = append(lines, th.SubtleStyle().Render("学号")+"  "+th.Body().Render(s.status.StudentNumber))
	}
	if !s.status.UpdatedAt.IsZero() {
		lines = append(lines, th.SubtleStyle().Render("更新")+"  "+
			th.MutedStyle().Render(s.status.UpdatedAt.Format("2006-01-02 15:04")))
	}
	lines = append(lines, th.SubtleStyle().Render("缓存")+"  "+
		th.MutedStyle().Render(components.Truncate(orDefault(s.deps.SyncText, "尚未同步"), inner-8)))

	if s.notice != "" {
		lines = append(lines, "", th.LevelColorStyle(s.noticeLevel).Render(
			components.Truncate(s.notice, inner)))
	}
	if s.busy != "" {
		lines = append(lines, th.Title().Render(s.busy+"…"))
	}
	if len(s.log) > 0 {
		lines = append(lines, "", th.SubtleStyle().Render("最近操作"))
		for _, entry := range s.log {
			lines = append(lines, "  "+th.MutedStyle().Render(components.Truncate(entry, inner-2)))
		}
	}

	footer := "l 登录并同步 · s 同步"
	if s.status.HasSession {
		footer = "s 同步 · x 退出登录"
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Info,
		Title:    "同步状态",
		Subtitle: "与图形界面共用同一份缓存",
		Level:    s.cardLevel(),
		Body:     strings.Join(lines, "\n"),
		Footer:   footer,
		Width:    width,
		Height:   height,
	}.Render(th)
}

// sessionLabel 返回会话状态短标签。
func (s *Sync) sessionLabel() string {
	switch {
	case s.deps.Sync == nil:
		return "不可用"
	case s.status.HasSession && s.status.Source == "gui":
		return "可用（来自图形界面）"
	case s.status.HasSession:
		return "可用"
	default:
		return "未登录"
	}
}

// sessionLevel 把会话状态映射成颜色级别。
func (s *Sync) sessionLevel() string {
	if s.status.HasSession {
		return "ok"
	}
	return "warn"
}

// cardLevel 把会话状态映射成卡片级别。
func (s *Sync) cardLevel() string {
	if s.status.HasSession {
		return "ok"
	}
	return "warn"
}

// statusLine 是窄屏下的状态摘要。
func (s *Sync) statusLine() string {
	return "会话：" + s.sessionLabel()
}
