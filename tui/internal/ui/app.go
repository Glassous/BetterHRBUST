package ui

import (
	"fmt"
	"os"
	"strings"
	"time"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// 最小可用尺寸。低于该尺寸时整屏替换为提示，避免内容被压成噪声。
const (
	MinWidth  = 72
	MinHeight = 20
)

// maxHistory 是返回栈上限。
const maxHistory = 32

// ToastTTL 是提示条停留时长。
//
// 是变量而不是常量：测试里调小它，避免每个用例都真的等 3 秒。
var ToastTTL = 3 * time.Second

// overlayKind 是浮层类型。
type overlayKind int

const (
	overlayNone overlayKind = iota
	overlayPalette
	overlayHelp
	overlayConfirm
)

// App 是根模型：负责布局、路由、全局按键、浮层与 AI 抽屉。
//
// Update 是一条固定五段链（尺寸 → 全局 → 浮层 → 数据 → 当前页），
// 而不是按页面名称写巨型 switch。新增页面只需在注册表里加一行。
type App struct {
	deps     Deps
	keys     KeyMap
	themes   *ThemeState
	theme    theme.Theme
	screens  map[ID]Screen
	started  map[ID]bool
	order    []ID
	registry []Entry

	// history 是返回栈，配合 esc 使用。
	history []ID
	// active 是当前页面。
	active ID

	width  int
	height int

	palette *components.Palette
	overlay overlayKind
	// confirm 是待确认的破坏性操作（目前只有"清空对话"）。
	confirmAction func() tea.Cmd
	confirmText   string

	toast    components.Toast
	toastSeq int

	// chat 是 AI 抽屉里的对话面板；懒创建，避免未启用时也占内存。
	chat     Screen
	chatOpen bool

	// ready 为假时说明还没收到首个窗口尺寸消息。
	ready bool
	// storageMod 是上次看到的 storage.json 修改时间，用于发现外部刷新。
	storageMod time.Time
	// storagePath 是被监视的缓存文件路径。
	storagePath string
}

// NewApp 构造应用外壳。
func NewApp(deps Deps, registry []Entry, initial ID) *App {
	app := &App{
		deps:     deps,
		keys:     DefaultKeyMap(),
		themes:   deps.Themes,
		theme:    initialTheme(deps),
		screens:  map[ID]Screen{},
		started:  map[ID]bool{},
		registry: registry,
		active:   initial,
	}
	app.rebuildPalette()
	for _, entry := range registry {
		app.order = append(app.order, entry.ID)
	}
	if deps.Service != nil && deps.Service.Snapshot != nil {
		app.storagePath = deps.Service.Snapshot.StoragePath
		if info, err := os.Stat(app.storagePath); err == nil {
			app.storageMod = info.ModTime()
		}
	}
	return app
}

// initialTheme 返回启动时的主题。
//
// 优先用 `deps.Themes` 里已经算好的值：`cmd` 会在那里放入
// `--no-color` / `NO_COLOR` / `--ascii` 的处理结果，外壳不应该再覆盖一遍。
func initialTheme(deps Deps) theme.Theme {
	if deps.Themes != nil {
		return deps.Themes.Get()
	}
	return resolveTheme(deps, "")
}

// resolveTheme 按配置与终端能力选定主题。
func resolveTheme(deps Deps, override string) theme.Theme {
	name := override
	if name == "" {
		name = deps.Config.UI.Theme
	}
	tier := theme.TierFrom(deps.Config.UI.ASCII, deps.Config.UI.NerdFont)
	switch name {
	case string(theme.Dark):
		return theme.New(theme.Dark, tier)
	case string(theme.Light):
		return theme.New(theme.Light, tier)
	case string(theme.ANSI16):
		return theme.New(theme.ANSI16, tier)
	}
	// auto：默认深色；真实背景色由 BackgroundColorMsg 修正。
	return theme.New(theme.Dark, tier)
}

// Theme 返回当前主题（页面渲染时使用）。
func (a *App) Theme() theme.Theme { return a.theme }

// Init 实现 tea.Model。
func (a *App) Init() tea.Cmd {
	return tea.Batch(
		tea.RequestBackgroundColor,
		a.enter(a.active),
		scheduleCacheWatch(),
	)
}

// Update 实现 tea.Model。
func (a *App) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	if handled, cmd := a.stepSize(msg); handled {
		return a, cmd
	}
	if handled, cmd := a.stepGlobal(msg); handled {
		return a, cmd
	}
	if handled, cmd := a.stepOverlay(msg); handled {
		return a, cmd
	}
	if handled, cmd := a.stepData(msg); handled {
		return a, cmd
	}
	return a.stepScreen(msg)
}

// View 实现 tea.Model。
func (a *App) View() tea.View {
	view := tea.NewView(a.render())
	view.AltScreen = true
	view.WindowTitle = "BetterHRBUST"
	return view
}

// ---------- 五段式 Update ----------

// stepSize 处理窗口尺寸。
func (a *App) stepSize(msg tea.Msg) (bool, tea.Cmd) {
	size, ok := msg.(tea.WindowSizeMsg)
	if !ok {
		return false, nil
	}
	a.width, a.height = size.Width, size.Height
	a.ready = true
	a.layout()
	return true, nil
}

// stepGlobal 处理全局按键与主题切换。
func (a *App) stepGlobal(msg tea.Msg) (bool, tea.Cmd) {
	switch typed := msg.(type) {
	case tea.BackgroundColorMsg:
		// 仅在 auto 主题下跟随终端背景。
		if a.deps.Config.UI.Theme == config.ThemeAuto && !typed.IsDark() {
			a.theme = theme.New(theme.Light,
				theme.TierFrom(a.deps.Config.UI.ASCII, a.deps.Config.UI.NerdFont))
		}
		a.layout()
		return true, nil
	case tea.KeyPressMsg:
		return a.handleGlobalKey(typed)
	case ThemeToggledMsg:
		a.theme = resolveTheme(a.deps, typed.Name)
		a.layout()
		return true, nil
	case ConfigChangedMsg:
		a.deps.Config = typed.Config
		a.theme = resolveTheme(a.deps, "")
		a.layout()
		return true, nil
	default:
		return false, nil
	}
}

// handleGlobalKey 处理全局快捷键。
//
// 顺序很关键：带修饰键的动作与 esc 永远最先处理（用户必须始终能退出、
// 能打开命令面板），可打印字符则先问当前页要不要——输入型页面认领后
// 就不会被"按 3 跳到成绩页"截胡。
func (a *App) handleGlobalKey(msg tea.KeyPressMsg) (bool, tea.Cmd) {
	matches := func(binding key.Binding) bool { return key.Matches(msg, binding) }
	name := msg.String()

	switch {
	case matches(a.keys.Quit):
		return true, tea.Quit
	case matches(a.keys.Palette):
		a.overlay = overlayPalette
		a.palette.Reset()
		return true, nil
	case matches(a.keys.AI):
		a.chatOpen = !a.chatOpen
		if a.chatOpen {
			a.layout()
			return true, a.enterChat()
		}
		a.layout()
		return true, nil
	case matches(a.keys.Reload):
		return true, a.reload()
	case matches(a.keys.Theme):
		return true, a.cycleTheme()
	case name == "esc":
		// 关闭优先级：抽屉 → 浮层 → 返回栈。
		if a.chatOpen {
			a.chatOpen = false
			a.layout()
			return true, nil
		}
		if a.overlay != overlayNone {
			a.overlay = overlayNone
			return true, nil
		}
		if len(a.history) > 0 {
			return true, a.goBack()
		}
		return true, nil
	}

	// tab / shift+tab 先给"多面板页面"处理；没实现 Focusable 的页面
	// 自己消费（比如诊断页用 tab 切标签）。
	if name == "tab" || name == "shift+tab" {
		if focusable, ok := a.current().(Focusable); ok && focusable.FocusCount() > 1 {
			delta := 1
			if name == "shift+tab" {
				delta = -1
			}
			if focusable.CycleFocus(delta) {
				return true, nil
			}
		}
		return false, nil
	}

	// 输入型页面认领可打印字符：登录表单里敲 "2304060322"，每个数字
	// 都是学号的一部分，不是"跳到第 N 页"。
	if guard, ok := a.current().(KeyGuard); ok && isPlainKey(name) && guard.ConsumesKey(name) {
		return false, nil
	}

	// 帮助是单键，必须排在页面认领之后：在密码框里按 ? 应该输入 ?。
	if matches(a.keys.Help) {
		if a.overlay == overlayHelp {
			a.overlay = overlayNone
			return true, nil
		}
		a.overlay = overlayHelp
		return true, nil
	}

	// 页面需要输入时，数字与字母键都属于页面。
	if a.current() != nil && a.current().WantsInput() {
		return false, nil
	}

	for index, binding := range a.keys.NavBindings() {
		if !matches(binding) {
			continue
		}
		if index >= len(a.order) {
			return true, nil
		}
		// 跳页也入返回栈：esc 能原路退回，输入型页面才有明确的出口。
		return true, a.navigate(a.order[index], true)
	}

	if name == "q" {
		return true, tea.Quit
	}
	return false, nil
}

// isPlainKey 判断按键是否是"不带修饰键的可打印单字符"。
//
// 这类键才允许页面认领；`ctrl+c`、`alt+1`、`esc`、`tab` 一律不给，
// 否则用户会被困在某个页面里连退出都做不到。
func isPlainKey(name string) bool {
	if name == "space" {
		return true
	}
	runes := []rune(name)
	if len(runes) != 1 {
		return false
	}
	return runes[0] >= 0x20 && runes[0] != 0x7f
}

// stepOverlay 处理浮层输入。
func (a *App) stepOverlay(msg tea.Msg) (bool, tea.Cmd) {
	if a.overlay == overlayNone {
		return false, nil
	}
	keyMsg, ok := msg.(tea.KeyPressMsg)
	if !ok {
		return false, nil
	}

	switch a.overlay {
	case overlayHelp:
		a.overlay = overlayNone
		return true, nil
	case overlayConfirm:
		switch keyMsg.String() {
		case "y", "Y", "enter":
			action := a.confirmAction
			a.confirmAction = nil
			a.confirmText = ""
			a.overlay = overlayNone
			if action != nil {
				return true, action()
			}
			return true, nil
		case "n", "N", "esc", "q":
			a.confirmAction = nil
			a.confirmText = ""
			a.overlay = overlayNone
			return true, nil
		}
		return true, nil
	case overlayPalette:
		switch keyMsg.String() {
		case "esc":
			a.overlay = overlayNone
			return true, nil
		case "up", "ctrl+k":
			a.palette.Move(-1)
			return true, nil
		case "down", "ctrl+j":
			a.palette.Move(1)
			return true, nil
		case "enter":
			command, ok := a.palette.Selected()
			if !ok || !command.Enabled {
				return true, nil
			}
			a.overlay = overlayNone
			if command.Run == nil {
				return true, nil
			}
			if msg, has := command.Run().(tea.Msg); has {
				return true, func() tea.Msg { return msg }
			}
			return true, nil
		case "backspace":
			a.palette.Backspace()
			return true, nil
		}
		if text := keyMsg.String(); len([]rune(text)) == 1 {
			a.palette.Push([]rune(text)[0])
		}
		return true, nil
	}
	return false, nil
}

// stepData 处理外壳级消息。
func (a *App) stepData(msg tea.Msg) (bool, tea.Cmd) {
	switch typed := msg.(type) {
	case NavigateMsg:
		return true, a.navigate(typed.To, typed.Push)
	case BackMsg:
		return true, a.goBack()
	case ReloadMsg:
		return true, a.reload()
	case ReloadedMsg:
		a.deps = typed.Deps
		// 重建所有页面：重新读盘后每条数据都可能变化，
		// 让页面继续沿用旧快照比多一次磁盘解析危险得多。
		a.screens = map[ID]Screen{}
		a.started = map[ID]bool{}
		a.chat = nil
		a.layout()
		if fresh := a.deps.Service; fresh != nil && fresh.Snapshot != nil {
			a.storagePath = fresh.Snapshot.StoragePath
			if info, err := os.Stat(a.storagePath); err == nil {
				a.storageMod = info.ModTime()
			}
		}
		cmd := a.enter(a.active)
		level := "ok"
		text := "已重新读取本地缓存"
		if typed.Readiness != "ready" {
			level = "warn"
			text = "已重新读取：当前无可查询数据"
		}
		return true, tea.Batch(cmd, a.showToast(text, level))
	case ToastMsg:
		return true, a.showToast(typed.Text, typed.Level)
	case ConfirmMsg:
		a.overlay = overlayConfirm
		a.confirmText = typed.Text
		action := typed.Action
		a.confirmAction = func() tea.Cmd {
			if action == nil {
				return nil
			}
			return func() tea.Msg { return action() }
		}
		return true, nil
	case ApplyConfigMsg:
		updated := typed.Config
		if a.deps.SaveConfig != nil {
			if err := a.deps.SaveConfig(updated); err != nil {
				return true, a.showToast("保存配置失败："+err.Error(), "fail")
			}
		}
		a.deps.Config = updated
		// 教务地址可能刚被改过：立即应用到同步服务（无需重启），
		// 未改时 SetBaseURL 与当前值相同，提前返回。
		if a.deps.Sync != nil {
			if err := a.deps.Sync.SetBaseURL(updated.EffectiveAcademicBase()); err != nil {
				return true, a.showToast("教务地址无效，已保持原地址："+err.Error(), "fail")
			}
		}
		a.theme = resolveTheme(a.deps, "")
		a.layout()
		cmd := a.showToast(orDefaultText(typed.Toast, "设置已保存"), "ok")
		return true, tea.Batch(cmd, func() tea.Msg { return ConfigChangedMsg{Config: updated} })
	case ToastExpireMsg:
		if typed.Seq == a.toast.Seq {
			a.toast = components.Toast{}
		}
		return true, nil
	case CacheTickMsg:
		// 检查一次后立刻排下一次；命令本身在 tea.Cmd 里执行 stat，不阻塞渲染。
		return true, tea.Batch(watchCache(a.storagePath), scheduleCacheWatch())
	case CacheChangedMsg:
		if a.storageMod.IsZero() {
			a.storageMod = typed.ModTime
			return true, nil
		}
		if typed.ModTime.After(a.storageMod) {
			a.storageMod = typed.ModTime
			return true, a.showToast("检测到本地缓存已更新，按 ctrl+r 重读", "info")
		}
		return true, nil
	case DrawerToggleMsg:
		a.chatOpen = !a.chatOpen
		a.layout()
		if a.chatOpen {
			return true, a.enterChat()
		}
		return true, nil
	case QuitMsg:
		return true, tea.Quit
	default:
		return false, nil
	}
}

// stepScreen 把消息交给当前页；AI 抽屉打开时优先给它。
func (a *App) stepScreen(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmd tea.Cmd
	// 对话事件始终交给抽屉，即使抽屉此刻是关闭的：
	// 否则用户在生成中途按 esc 再打开，看到的会是一段残缺的回答。
	if a.chat != nil && isChatMessage(msg) {
		a.chat, cmd = a.chat.Update(msg)
		return a, cmd
	}
	// 带目标页的回执（同步任务等）即使当前页已经切走也要送回原页面：
	// 否则用户切页再回来，会看到一个永远停在"进行中"的界面。
	if targeted, ok := msg.(interface{ Target() ID }); ok {
		if screen, exists := a.screens[targeted.Target()]; exists && screen != nil {
			a.screens[targeted.Target()], cmd = screen.Update(msg)
			return a, cmd
		}
	}

	current := a.current()
	if current == nil {
		return a, nil
	}
	a.screens[a.active], cmd = current.Update(msg)
	return a, cmd
}

// isChatMessage 判断消息是否属于对话抽屉。
func isChatMessage(msg tea.Msg) bool {
	switch msg.(type) {
	case DrawerEventMsg, DrawerDoneMsg, DrawerTickMsg:
		return true
	default:
		return false
	}
}

// ---------- 路由 ----------

// current 返回当前页面实例（未创建时为 nil）。
func (a *App) current() Screen { return a.screens[a.active] }

// ensure 按需创建页面实例。
func (a *App) ensure(id ID) Screen {
	if screen, ok := a.screens[id]; ok {
		return screen
	}
	for _, entry := range a.registry {
		if entry.ID != id || entry.New == nil {
			continue
		}
		screen := entry.New(a.deps)
		screen.SetSize(a.screenWidth(), a.contentHeight())
		a.screens[id] = screen
		return screen
	}
	return nil
}

// enter 创建页面并触发首次加载。
func (a *App) enter(id ID) tea.Cmd {
	screen := a.ensure(id)
	if screen == nil {
		return nil
	}
	screen.SetSize(a.screenWidth(), a.contentHeight())
	if a.started[id] {
		return nil
	}
	a.started[id] = true
	return screen.Init()
}

// navigate 切换到目标页。
func (a *App) navigate(to ID, push bool) tea.Cmd {
	if to == a.active {
		return a.enter(to)
	}
	if push {
		a.history = append(a.history, a.active)
		// 返回栈只用于"回到刚才那一页"，留几十条足够；不设上限的话
		// 用户连按几十次数字键就会攒出一条无意义的浏览记录。
		if len(a.history) > maxHistory {
			a.history = a.history[len(a.history)-maxHistory:]
		}
	}
	a.active = to
	return a.enter(to)
}

// goBack 返回上一页。
func (a *App) goBack() tea.Cmd {
	if len(a.history) == 0 {
		return nil
	}
	previous := a.history[len(a.history)-1]
	a.history = a.history[:len(a.history)-1]
	a.active = previous
	return a.enter(previous)
}

// enterChat 创建并初始化对话面板。
func (a *App) enterChat() tea.Cmd {
	if a.deps.NewChat == nil {
		return a.showToast("AI 对话不可用", "fail")
	}
	if a.chat == nil {
		a.chat = a.deps.NewChat(a.deps)
	}
	a.chat.SetSize(a.drawerWidth(), a.contentHeight())
	a.layout()
	return a.chat.Init()
}

// reload 重新读取本地缓存。
func (a *App) reload() tea.Cmd {
	if a.deps.Reload == nil {
		return a.showToast("当前不支持重新读取", "warn")
	}
	previous := a.deps
	return func() tea.Msg {
		fresh := previous.Reload(previous)
		return ReloadedMsg{Readiness: fresh.Readiness.Code(), Deps: fresh}
	}
}

// cycleTheme 在深色 → 浅色 → 16 色之间循环并持久化。
func (a *App) cycleTheme() tea.Cmd {
	var next theme.Name
	switch a.theme.Name {
	case theme.Dark:
		next = theme.Light
	case theme.Light:
		next = theme.ANSI16
	default:
		next = theme.Dark
	}
	updated := a.deps.Config
	updated.UI.Theme = string(next)
	if a.deps.SaveConfig != nil {
		if err := a.deps.SaveConfig(updated); err != nil {
			return a.showToast("保存主题失败："+err.Error(), "fail")
		}
	}
	a.deps.Config = updated
	a.theme = theme.New(next, theme.TierFrom(updated.UI.ASCII, updated.UI.NerdFont))
	a.layout()
	return a.showToast("主题："+themeLabel(next), "ok")
}

// themeLabel 返回主题中文名。
func themeLabel(name theme.Name) string {
	switch name {
	case theme.Dark:
		return "深色"
	case theme.Light:
		return "浅色"
	case theme.ANSI16:
		return "16 色兼容"
	default:
		return string(name)
	}
}

// showToast 弹出一条会自动消失的提示。
func (a *App) showToast(text, level string) tea.Cmd {
	a.toastSeq++
	seq := a.toastSeq
	a.toast = components.Toast{
		Seq:    seq,
		Text:   text,
		Level:  level,
		Expire: time.Now().Add(ToastTTL),
	}
	return tea.Tick(ToastTTL, func(time.Time) tea.Msg { return ToastExpireMsg{Seq: seq} })
}

// rebuildPalette 依据注册表重建命令面板。
func (a *App) rebuildPalette() {
	commands := make([]components.Command, 0, len(a.registry)+8)
	for _, entry := range a.registry {
		entry := entry
		commands = append(commands, components.Command{
			Title:   entry.Title,
			Hint:    entry.Hint,
			Key:     entry.Key,
			Enabled: true,
			Run:     func() any { return NavigateMsg{To: entry.ID, Push: true} },
		})
	}
	commands = append(commands,
		components.Command{Title: "重读本地缓存", Hint: "重新解析 storage.json", Key: "ctrl+r", Enabled: true,
			Run: func() any { return ReloadMsg{} }},
		components.Command{Title: "打开 AI 对话", Hint: "需要先配置模型", Key: "ctrl+j", Enabled: true,
			Run: func() any { return DrawerToggleMsg{} }},
		components.Command{Title: "切换主题", Hint: "深色 / 浅色 / 16 色", Key: "ctrl+t", Enabled: true,
			Run: func() any { return nil }},
		components.Command{Title: "退出", Hint: "关闭终端界面", Key: "q", Enabled: true,
			Run: func() any { return QuitMsg{} }},
	)
	a.palette = components.NewPalette(commands)
	a.palette.SetWidth(a.paletteInnerWidth())
}

// ---------- 布局 ----------

// sidebarWidth 返回侧栏宽度；窄屏时返回 0（隐藏）。
func (a *App) sidebarWidth() int {
	if !a.deps.Config.UI.Sidebar || a.width < 88 {
		return 0
	}
	return 18
}

// drawerWidth 返回 AI 抽屉宽度。
//
// 窄屏时抽屉占满整屏：在 80 列的终端里塞一个 36 列的侧栏，正文就没法看了。
func (a *App) drawerWidth() int {
	if a.width < 100 {
		return a.width
	}
	width := a.width * 2 / 5
	if width < 36 {
		width = 36
	}
	if width > 64 {
		width = 64
	}
	return width
}

// screenWidth 返回内容区宽度。
func (a *App) screenWidth() int {
	width := a.width - a.sidebarWidth()
	if a.chatOpen {
		width -= a.drawerWidth()
	}
	if width < 10 {
		width = 10
	}
	return width
}

// contentHeight 返回内容区高度。
func (a *App) contentHeight() int {
	height := a.height - a.chromeHeight()
	if height < 3 {
		height = 3
	}
	return height
}

// chromeHeight 返回非内容区占用的行数。
func (a *App) chromeHeight() int {
	height := 1 // 状态栏
	if a.banner().Text != "" {
		height++
	}
	height += 2 // 分隔线 + 页脚
	if a.toast.Visible(time.Now()) {
		height++
	}
	return height
}

// paletteInnerWidth 返回命令面板内容宽度。
func (a *App) paletteInnerWidth() int {
	width := a.width * 3 / 5
	if width < 40 {
		width = a.width - 8
	}
	if width > 72 {
		width = 72
	}
	if width < 20 {
		width = 20
	}
	return width
}

// layout 把新尺寸下发给所有已创建页面与抽屉。
func (a *App) layout() {
	for _, screen := range a.screens {
		screen.SetSize(a.screenWidth(), a.contentHeight())
	}
	if a.chat != nil {
		a.chat.SetSize(a.drawerWidth(), a.contentHeight())
	}
	a.palette.SetWidth(a.paletteInnerWidth())
}

// banner 依据就绪状态与缓存新鲜度给出提示条。
func (a *App) banner() components.Banner {
	readiness := a.deps.Readiness
	switch {
	case readiness.Code() == "needLogin":
		return components.Banner{Text: "未检测到登录状态：按 9 打开「登录与同步」，在终端里登录即可", Level: "fail"}
	case readiness.Code() == "noData":
		return components.Banner{Text: "已登录但本地还没有缓存：按 9 打开「登录与同步」点一次同步", Level: "warn"}
	case readiness.Offline:
		return components.Banner{Text: "离线缓存：会话已失效，数据为上次登录时的快照", Level: "warn"}
	}
	if a.deps.StaleDays > 7 {
		return components.Banner{
			Text:  fmt.Sprintf("缓存已 %d 天未更新，按 9 打开「登录与同步」刷新即可", a.deps.StaleDays),
			Level: "warn",
		}
	}
	return components.Banner{}
}

// ---------- 渲染 ----------

// render 渲染整屏。
func (a *App) render() string {
	// 主题是外壳唯一真相，页面通过共享持有者读取。
	if a.themes != nil {
		a.themes.Set(a.theme)
	}
	if !a.ready {
		return "正在初始化…"
	}
	if a.width < MinWidth || a.height < MinHeight {
		return a.renderTooSmall()
	}

	contentHeight := a.contentHeight()
	main := a.renderMain(contentHeight)

	lines := make([]string, 0, a.height)
	lines = append(lines, a.renderStatusBar())
	if banner := a.banner(); banner.Text != "" {
		lines = append(lines, banner.Render(a.theme, a.width))
	}
	lines = append(lines, main)
	if a.toast.Visible(time.Now()) {
		lines = append(lines, " "+a.toast.Render(a.theme))
	}
	lines = append(lines, components.Divider(a.theme, a.width))
	lines = append(lines, a.renderFooter())

	body := strings.Join(lines, "\n")
	if a.overlay == overlayNone {
		return body
	}
	return a.renderOverlay(body)
}

// renderTooSmall 渲染"终端太小"的提示。
func (a *App) renderTooSmall() string {
	message := fmt.Sprintf(
		"终端窗口过小（%d×%d）\n至少需要 %d×%d\n\n可以按 ctrl+p 打开命令面板，或用 --json 在脚本里取数\n按 q 退出",
		a.width, a.height, MinWidth, MinHeight,
	)
	return components.Place(a.theme.MutedStyle().Render(message), a.width, a.height)
}

// renderStatusBar 渲染顶部状态栏。
func (a *App) renderStatusBar() string {
	readiness := a.deps.Readiness
	right := []string{}
	if a.deps.Service != nil {
		if account := a.deps.Service.Account(); account != "" {
			right = append(right, account)
		}
	}
	right = append(right, readiness.Label())

	center := []string{}
	if a.deps.Week > 0 {
		center = append(center, fmt.Sprintf("第 %d 周", a.deps.Week))
	}
	if a.deps.SemesterLabel != "" {
		center = append(center, a.deps.SemesterLabel)
	}

	bar := components.StatusBar{Title: "BetterHRBUST", Left: []string{"终端界面"}, Center: center, Right: right}
	return bar.Render(a.theme, a.width)
}

// renderMain 渲染侧栏 + 内容 + 抽屉。
func (a *App) renderMain(height int) string {
	screen := a.current()
	content := "正在加载…"
	if screen != nil {
		content = screen.View()
	}
	contentWidth := a.screenWidth()

	if a.chatOpen && a.chat != nil {
		drawer := a.chat.View()
		main := lipgloss.JoinHorizontal(lipgloss.Top,
			fitBlock(content, contentWidth, height),
			fitBlock(drawer, a.drawerWidth(), height),
		)
		return main
	}

	if sidebar := a.sidebarWidth(); sidebar > 0 {
		return lipgloss.JoinHorizontal(lipgloss.Top,
			fitBlock(a.renderSidebar(height), sidebar, height),
			fitBlock(content, contentWidth, height),
		)
	}
	return fitBlock(content, contentWidth, height)
}

// renderSidebar 渲染左侧导航。
func (a *App) renderSidebar(height int) string {
	lines := make([]string, 0, len(a.registry)+2)
	lines = append(lines, a.theme.SubtleStyle().Render("  页面"))
	for index, entry := range a.registry {
		if index >= len(a.order) {
			break
		}
		label := components.PadRight(entry.Title, 12)
		shortcut := ""
		if entry.Key != "" {
			shortcut = entry.Key
		}
		if entry.ID == a.active {
			lines = append(lines, a.theme.SelectedRow().Render(
				" "+a.theme.Glyphs.Selected+" "+label)+
				a.theme.SubtleStyle().Render(shortcut))
			continue
		}
		lines = append(lines, "   "+label+a.theme.SubtleStyle().Render(shortcut))
	}
	if len(lines) > height {
		lines = lines[:height]
	}
	return strings.Join(lines, "\n")
}

// renderFooter 渲染页脚。
func (a *App) renderFooter() string {
	hints := HelpKeys(a.keys.FooterBindings(), 0)
	if screen := a.current(); screen != nil {
		for _, binding := range screen.Keys() {
			if !binding.Enabled() {
				continue
			}
			hints = append(hints, binding.Help().Key+" "+binding.Help().Desc)
		}
		if focusable, ok := screen.(Focusable); ok && focusable.FocusCount() > 1 {
			hints = append(hints, "tab 切换面板")
			if _, cycle := screen.(interface{ FocusCycleLabel() string }); cycle {
				// 个别页面会自定义 tab 的行为说明，避免重复。
				hints = hints[:len(hints)-1]
			}
		}
	}
	if a.chat != nil && a.chatOpen {
		for _, binding := range a.chat.Keys() {
			if binding.Enabled() {
				hints = append(hints, binding.Help().Key+" "+binding.Help().Desc)
			}
		}
	}
	if len(hints) > 9 {
		hints = hints[:9]
	}
	note := "数据同步于 " + a.deps.SyncText
	footer := components.Footer{Hints: hints, Note: note}
	return footer.Render(a.theme, a.width)
}

// renderOverlay 把浮层叠在正文之上。
func (a *App) renderOverlay(body string) string {
	innerWidth := a.paletteInnerWidth()
	switch a.overlay {
	case overlayPalette:
		content := a.palette.Render(a.theme, 14)
		box := components.Box(a.theme, "命令面板", content, innerWidth+4, 18)
		return overlayOn(body, box, a.width, a.height)
	case overlayHelp:
		sections := []components.HelpSection{
			{Title: "导航", Bindings: a.keys.NavBindings()},
			{Title: "全局", Bindings: a.keys.GlobalBindings()},
		}
		if screen := a.current(); screen != nil {
			sections = append(sections, components.HelpSection{Title: screen.Title(), Bindings: screen.Keys()})
		}
		overlay := components.HelpOverlay{Sections: sections}
		box := components.Box(a.theme, "快捷键", overlay.Render(a.theme), innerWidth+4, a.height-4)
		return overlayOn(body, box, a.width, a.height)
	case overlayConfirm:
		inner := minInt(56, a.width-6)
		wrapped := components.Wrap(a.confirmText, inner-4)
		wrapped = append(wrapped, "", "  y / enter 确认    n / esc 取消")
		box := components.Box(a.theme, "确认操作", strings.Join(wrapped, "\n"), inner+4, len(wrapped)+4)
		return overlayOn(body, box, a.width, a.height)
	}
	return body
}

// overlayOn 用居中浮层覆盖正文。
//
// 逐行替换而不是用 lipgloss.Place 叠加：终端没有真正的 alpha 通道，
// 直接把中间若干行替换掉最稳，也不会留下残影。
func overlayOn(body, overlay string, width, height int) string {
	bodyLines := strings.Split(body, "\n")
	overlayLines := strings.Split(overlay, "\n")

	overlayWidth := 0
	for _, line := range overlayLines {
		if w := components.Width(line); w > overlayWidth {
			overlayWidth = w
		}
	}
	left := (width - overlayWidth) / 2
	if left < 0 {
		left = 0
	}
	top := (height - len(overlayLines)) / 2
	if top < 0 {
		top = 0
	}

	for len(bodyLines) < height {
		bodyLines = append(bodyLines, "")
	}
	if len(bodyLines) > height {
		bodyLines = bodyLines[:height]
	}
	for index := range bodyLines {
		if index >= top && index < top+len(overlayLines) {
			prefix := strings.Repeat(" ", left)
			bodyLines[index] = components.Truncate(prefix+overlayLines[index-top], width)
			continue
		}
		bodyLines[index] = components.Truncate(bodyLines[index], width)
	}
	return strings.Join(bodyLines, "\n")
}

// fitBlock 把一段内容补齐到正好 width×height，避免拼接后错行。
func fitBlock(content string, width, height int) string {
	lines := strings.Split(content, "\n")
	if len(lines) > height {
		lines = lines[:height]
	}
	for index, line := range lines {
		lines[index] = components.Truncate(line, width)
	}
	for len(lines) < height {
		lines = append(lines, "")
	}
	for index, line := range lines {
		lines[index] = components.PadRight(line, width)
	}
	return strings.Join(lines, "\n")
}

// orDefaultText 返回非空文本或默认值。
func orDefaultText(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

// minInt 返回较小值。
func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}
