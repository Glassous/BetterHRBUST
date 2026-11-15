// Package screens 实现各页面与 AI 对话抽屉。
//
// 约定：
//   - 页面只通过 `ui.Deps` 拿依赖，不 import 外壳，也不自己读文件；
//   - 数据加载一律走 `loadCmd`（返回 `ui.DataMsg`），保证异步与可恢复；
//   - `View()` 纯渲染，任何状态变化都在 `Update` 里完成。
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

// loadCmd 把一次查询包成异步命令。
//
// 查询本身是纯内存计算，理论上不会 panic；但缓存来自外部文件，
// 一个越界下标就足以让整个界面消失。这里兜底成一条错误态，
// 用户至少还能看到"哪一页出了问题"并继续用其他页面。
func loadCmd(deps ui.Deps, id ui.ID, run func(*query.Service) any) tea.Cmd {
	return func() (message tea.Msg) {
		defer func() {
			if recovered := recover(); recovered != nil {
				message = ui.DataMsg{
					Screen: id,
					Err:    fmt.Errorf("解析本地数据时出错：%v", recovered),
				}
			}
		}()
		if deps.Service == nil {
			return ui.DataMsg{Screen: id, Err: fmt.Errorf("本地数据不可用")}
		}
		return ui.DataMsg{Screen: id, Data: run(deps.Service)}
	}
}

// listKeys 是长列表页面的通用按键。
type listKeys struct {
	Up       key.Binding
	Down     key.Binding
	Top      key.Binding
	Bottom   key.Binding
	PageUp   key.Binding
	PageDown key.Binding
}

func defaultListKeys() listKeys {
	return listKeys{
		Up:       key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "上移")),
		Down:     key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "下移")),
		Top:      key.NewBinding(key.WithKeys("g", "home"), key.WithHelp("g", "首项")),
		Bottom:   key.NewBinding(key.WithKeys("G", "end"), key.WithHelp("G", "末项")),
		PageUp:   key.NewBinding(key.WithKeys("ctrl+b", "pgup"), key.WithHelp("pgup", "上翻")),
		PageDown: key.NewBinding(key.WithKeys("ctrl+f", "pgdown"), key.WithHelp("pgdn", "下翻")),
	}
}

// bindings 返回全部列表按键。
func (k listKeys) bindings() []key.Binding {
	return []key.Binding{k.Up, k.Down, k.Top, k.Bottom, k.PageUp, k.PageDown}
}

// reloadKeys 是"重读缓存"的页面内按键。
func reloadKey() key.Binding {
	return key.NewBinding(key.WithKeys("ctrl+r"), key.WithHelp("ctrl+r", "重读缓存"))
}

// nextKey 返回"下一个"按键。
func nextKey(help string) key.Binding {
	return key.NewBinding(key.WithKeys("right", "tab"), key.WithHelp("→", help))
}

// prevKey 返回"上一个"按键。
func prevKey(help string) key.Binding {
	return key.NewBinding(key.WithKeys("left", "shift+tab"), key.WithHelp("←", help))
}

// openKey 返回"打开详情"按键。
func openKey(help string) key.Binding {
	return key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", help))
}

// describeErr 把错误转成可展示文本。
func describeErr(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// searchBox 是页面内的行内搜索框。
//
// 不用 bubbles 的 textinput：这里只需要"打字 / 退格 / 回车确认 / esc 取消"，
// 自己实现可以精确控制宽度，也避免每页都要维护 Focus 状态机。
type searchBox struct {
	active bool
	text   string
	label  string
}

// Open 打开搜索框。
func (b *searchBox) Open(label string) {
	b.active = true
	b.label = label
}

// Close 关闭搜索框（保留已输入内容，便于再次打开继续用）。
func (b *searchBox) Close() { b.active = false }

// Clear 关闭并清空。
func (b *searchBox) Clear() {
	b.active = false
	b.text = ""
}

// Active 返回是否处于输入状态。
func (b *searchBox) Active() bool { return b.active }

// Text 返回当前输入。
func (b *searchBox) Text() string { return b.text }

// Push 追加一个字符。
func (b *searchBox) Push(r rune) { b.text += string(r) }

// Backspace 删除最后一个字符。
func (b *searchBox) Backspace() {
	runes := []rune(b.text)
	if len(runes) == 0 {
		return
	}
	b.text = string(runes[:len(runes)-1])
}

// Render 渲染搜索行。
func (b *searchBox) Render(th theme.Theme, width int, hint string) string {
	if !b.active {
		return th.SubtleStyle().Render("/ " + hint)
	}
	cursor := th.Title().Render(th.Glyphs.Cursor)
	line := th.MutedStyle().Render(b.label+" › ") + th.Body().Render(b.text) + cursor
	if b.text == "" {
		line += th.SubtleStyle().Render("  enter 确认 · esc 取消")
	}
	return components.Truncate(line, width)
}

// minInt 返回较小值。
func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}

// maxInt 返回较大值。
func maxInt(left, right int) int {
	if left > right {
		return left
	}
	return right
}

// orDefault 返回非空值或默认值。
func orDefault(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

// qualify 把布尔资格渲染成中文。
func qualify(value bool) string {
	if value {
		return "达标"
	}
	return "未达标"
}

// examLevel 把考试状态映射成颜色级别。
func examLevel(status string) string {
	switch status {
	case "今日考试":
		return "warn"
	case "已结束":
		return "muted"
	case "时间未知":
		return "fail"
	default:
		return "ok"
	}
}
