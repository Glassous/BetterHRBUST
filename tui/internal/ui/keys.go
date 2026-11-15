package ui

import "charm.land/bubbles/v2/key"

// KeyMap 是外壳的全局按键。
//
// 全局按键刻意只有十来个：数字键跳页、几个 ctrl 组合、帮助与退出。
// 其余字母键全部留给当前页面，避免"在搜索框里打字被当成快捷键"。
type KeyMap struct {
	Dashboard key.Binding
	Timetable key.Binding
	Scores    key.Binding
	Exams     key.Binding
	Plan      key.Binding
	Notices   key.Binding
	Profile   key.Binding
	Courses   key.Binding
	Sync      key.Binding
	Doctor    key.Binding
	Settings  key.Binding

	Palette key.Binding
	AI      key.Binding
	Help    key.Binding
	Reload  key.Binding
	Theme   key.Binding
	Back    key.Binding
	Quit    key.Binding
}

// DefaultKeyMap 返回默认键位。
func DefaultKeyMap() KeyMap {
	return KeyMap{
		Dashboard: key.NewBinding(key.WithKeys("1"), key.WithHelp("1", "概览")),
		Timetable: key.NewBinding(key.WithKeys("2"), key.WithHelp("2", "课表")),
		Scores:    key.NewBinding(key.WithKeys("3"), key.WithHelp("3", "成绩")),
		Exams:     key.NewBinding(key.WithKeys("4"), key.WithHelp("4", "考试")),
		Plan:      key.NewBinding(key.WithKeys("5"), key.WithHelp("5", "培养方案")),
		Notices:   key.NewBinding(key.WithKeys("6"), key.WithHelp("6", "公告")),
		Profile:   key.NewBinding(key.WithKeys("7"), key.WithHelp("7", "学籍")),
		Courses:   key.NewBinding(key.WithKeys("8"), key.WithHelp("8", "课程")),
		Sync:      key.NewBinding(key.WithKeys("9"), key.WithHelp("9", "登录/同步")),
		Doctor:    key.NewBinding(key.WithKeys("0"), key.WithHelp("0", "诊断")),
		Settings:  key.NewBinding(key.WithKeys(","), key.WithHelp(",", "设置")),

		Palette: key.NewBinding(key.WithKeys("ctrl+p"), key.WithHelp("ctrl+p", "命令面板")),
		AI:      key.NewBinding(key.WithKeys("ctrl+j"), key.WithHelp("ctrl+j", "AI 对话")),
		Help:    key.NewBinding(key.WithKeys("?"), key.WithHelp("?", "帮助")),
		Reload:  key.NewBinding(key.WithKeys("ctrl+r"), key.WithHelp("ctrl+r", "重读缓存")),
		Theme:   key.NewBinding(key.WithKeys("ctrl+t"), key.WithHelp("ctrl+t", "换主题")),
		Back:    key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "返回")),
		Quit:    key.NewBinding(key.WithKeys("ctrl+c"), key.WithHelp("ctrl+c", "退出")),
	}
}

// GlobalBindings 返回帮助浮层里要展示的全局按键。
func (k KeyMap) GlobalBindings() []key.Binding {
	return []key.Binding{
		k.Palette, k.AI, k.Help, k.Reload, k.Theme, k.Quit,
	}
}

// NavBindings 返回跳页按键（顺序与侧栏一致）。
func (k KeyMap) NavBindings() []key.Binding {
	return []key.Binding{
		k.Dashboard, k.Timetable, k.Scores, k.Exams, k.Plan,
		k.Notices, k.Profile, k.Courses, k.Sync, k.Doctor, k.Settings,
	}
}

// FooterBindings 返回底部始终显示的那几个键。
func (k KeyMap) FooterBindings() []key.Binding {
	return []key.Binding{k.Palette, k.AI, k.Help, k.Quit}
}

// HelpKeys 把绑定列表渲染成 `键 说明` 的短文本，供页脚使用。
func HelpKeys(bindings []key.Binding, limit int) []string {
	parts := make([]string, 0, len(bindings))
	for index, binding := range bindings {
		if limit > 0 && index >= limit {
			break
		}
		if !binding.Enabled() {
			continue
		}
		parts = append(parts, binding.Help().Key+" "+binding.Help().Desc)
	}
	return parts
}
