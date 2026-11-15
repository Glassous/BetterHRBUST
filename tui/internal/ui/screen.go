package ui

import (
	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// ID 是页面标识。
type ID string

// 内置页面标识。
const (
	ScreenDashboard ID = "dashboard"
	ScreenTimetable ID = "timetable"
	ScreenScores    ID = "scores"
	ScreenExams     ID = "exams"
	ScreenPlan      ID = "plan"
	ScreenNotices   ID = "notices"
	ScreenProfile   ID = "profile"
	ScreenCourses   ID = "courses"
	ScreenDoctor    ID = "doctor"
	ScreenSettings  ID = "settings"
	ScreenHelp      ID = "help"
)

// Deps 是页面依赖的能力集合。
//
// 页面不能直接 import `cmd` 或外壳，也不自己读配置文件：
// 所有副作用都通过这里注入，页面因此可以脱离真实终端被测试。
type Deps struct {
	// Service 是只读查询服务。
	Service *query.Service
	// Toolset 是 LLM 工具集（设置页展示工具列表时会用）。
	Toolset *query.Toolset
	// Config 是当前配置快照。
	Config config.Config
	// SaveConfig 持久化配置并广播变更。
	SaveConfig func(config.Config) error
	// Emit 把消息交回 Bubble Tea 主循环，用于异步任务上报。
	Emit func(tea.Msg)
	// Version 是本程序版本。
	Version string
	// Clock 是时间基准（设置页展示、日期换算用）。
	Clock query.Clock
	// Themes 是当前主题的共享持有者。
	Themes *ThemeState

	// Readiness 是外壳状态栏与提示条使用的就绪状态。
	Readiness store.Readiness
	// SyncText 是"数据同步于 …"的文本。
	SyncText string
	// Week 是当前教学周（0 表示假期或校历未收录）。
	Week int
	// SemesterLabel 是当前学期名。
	SemesterLabel string
	// StaleDays 是缓存距今的天数（0 表示未知）。
	StaleDays int
	// Reload 重新读取缓存并返回全新的依赖集合。
	//
	// 返回整个 Deps 而不是只返回服务指针：重新读盘后就绪状态、教学周、
	// 同步时间都会变，逐字段回填容易漏掉一个。
	Reload func(Deps) Deps
	// NewChat 创建 AI 对话面板（未启用时为 nil）。
	NewChat func(Deps) Screen
}

// Screen 是一个页面。
//
// 约定：
//   - `View()` 必须是纯渲染：不做 I/O、不读系统时钟、不改状态；
//   - 数据在 `Init()` 或收到 `DataMsg` 后写入字段；
//   - `Keys()` 返回的绑定会自动出现在底部提示与帮助浮层里。
type Screen interface {
	ID() ID
	Title() string
	Init() tea.Cmd
	Update(tea.Msg) (Screen, tea.Cmd)
	View() string
	SetSize(width, height int)
	Keys() []key.Binding
	// WantsInput 为真时，外壳不再把字母键与数字键当全局快捷键，
	// 避免用户在搜索框里打字时被"N 切换到第 N 页"打断。
	WantsInput() bool
}

// Focusable 是"页面内有多个面板"的可选能力。
//
// 实现它的页面会收到 tab / shift+tab，自己决定焦点怎么走；
// 没实现的页面，tab 保持原样交给页面自己处理（诊断页用它切标签）。
type Focusable interface {
	// CycleFocus 移动焦点，返回是否真的移动了。
	CycleFocus(delta int) bool
	// FocusCount 返回面板数量，用于页脚提示。
	FocusCount() int
}

// Entry 描述一个可注册的页面。
type Entry struct {
	ID      ID
	Title   string
	Aliases []string
	// Hint 是命令面板里显示的副标题。
	Hint string
	// Key 是直达该页的单键（如 "1"），空表示没有。
	Key string
	New func(Deps) Screen
}

// Base 是可嵌入的默认实现，省掉每个页面重复写 SetSize / Keys。
type Base struct {
	ScreenID    ID
	ScreenTitle string
	Width       int
	Height      int
}

// ID 返回页面标识。
func (b *Base) ID() ID { return b.ScreenID }

// Title 返回页面标题。
func (b *Base) Title() string { return b.ScreenTitle }

// SetSize 记录可用区域。
func (b *Base) SetSize(width, height int) {
	b.Width = width
	b.Height = height
}

// Keys 默认没有页面级按键。
func (b *Base) Keys() []key.Binding { return nil }

// WantsInput 默认不占据输入。
func (b *Base) WantsInput() bool { return false }
