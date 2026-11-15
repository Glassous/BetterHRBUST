package ui

import (
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/llm"
)

// NavigateMsg 请求切换页面。
type NavigateMsg struct {
	To ID
	// Push 为真时把当前页压入返回栈（命令面板用），否则平级切换（数字键用）。
	Push bool
}

// BackMsg 请求返回上一页。
type BackMsg struct{}

// DataMsg 承载一次异步加载的结果。
//
// `Data` 的类型由各页面自己约定；`Err` 非空时页面显示错误态。
type DataMsg struct {
	Screen ID
	Data   any
	Err    error
}

// ReloadMsg 请求重新读取本地缓存。
type ReloadMsg struct{}

// ReloadedMsg 表示缓存已重新读取。
//
// 外壳收到后用新的依赖重建全部页面，避免任何页面继续持有旧快照。
type ReloadedMsg struct {
	Readiness string
	Deps      Deps
}

// ToastMsg 申请弹出一条短提示。
type ToastMsg struct {
	Text  string
	Level string
}

// ToastExpireMsg 让指定序号的提示自动消失。
type ToastExpireMsg struct{ Seq int }

// ConfigChangedMsg 表示配置已保存，外壳需要换主题等。
type ConfigChangedMsg struct {
	Config config.Config
}

// ThemeToggledMsg 表示用户按 ctrl+t 切换了主题。
type ThemeToggledMsg struct {
	Name string
}

// DrawerToggleMsg 切换 AI 抽屉。
type DrawerToggleMsg struct{}

// DrawerEventMsg 是对话过程中的一条事件。
type DrawerEventMsg struct {
	Event llm.Event
}

// DrawerDoneMsg 表示本轮对话结束（或失败）。
type DrawerDoneMsg struct {
	Err error
}

// DrawerTickMsg 驱动流式渲染的去抖。
type DrawerTickMsg struct{}

// ConfirmMsg 请求外壳弹出确认框。
//
// 确认框放在外壳里而不是页面里：它要覆盖整屏、要独占键盘，
// 而且多个页面都可能用到（打开 AI、清空对话）。
type ConfirmMsg struct {
	Text   string
	Action func() tea.Msg
}

// ApplyConfigMsg 请求外壳保存配置。
type ApplyConfigMsg struct {
	Config config.Config
	Toast  string
}

// CacheTickMsg 触发一次本地缓存变更检查。
type CacheTickMsg struct{}

// CacheChangedMsg 表示 storage.json 的修改时间变了。
//
// 外壳只提示、不自动重读：用户可能正在翻页，突然换掉整页数据比晚几秒更糟。
type CacheChangedMsg struct {
	ModTime time.Time
}

// QuitMsg 请求退出。
type QuitMsg struct{}

// emit 是 Deps.Emit 的安全包装：未注入时什么也不做。
func emit(send func(tea.Msg), msg tea.Msg) {
	if send != nil {
		send(msg)
	}
}
