package components

import (
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Focus 管理一组可聚焦面板的当前焦点。
//
// 页面用 Tab / Shift+Tab 在面板之间移动，用方向键在当前面板内部移动。
// 这两级别分开，是为了让"上下移动光标"不会意外跳面板——
// 前端里 Tab 换焦点、方向键在框内移动，是同一套心智模型。
type Focus struct {
	// Index 是当前聚焦的面板下标。
	Index int
	// Count 是面板数量。
	Count int
}

// NewFocus 构造焦点状态。
func NewFocus(count int) Focus {
	if count < 1 {
		count = 1
	}
	return Focus{Index: 0, Count: count}
}

// Next 切到下一个面板（循环）。
func (f *Focus) Next() {
	if f.Count < 1 {
		f.Count = 1
	}
	f.Index = (f.Index + 1) % f.Count
}

// Prev 切到上一个面板（循环）。
func (f *Focus) Prev() {
	if f.Count < 1 {
		f.Count = 1
	}
	f.Index = (f.Index - 1 + f.Count) % f.Count
}

// Move 按增量移动并夹在范围内（不循环）。
func (f *Focus) Move(delta int) {
	if f.Count < 1 {
		f.Count = 1
	}
	f.Index += delta
	if f.Index < 0 {
		f.Index = 0
	}
	if f.Index >= f.Count {
		f.Index = f.Count - 1
	}
}

// Set 设置焦点，越界时夹住。
func (f *Focus) Set(index int) {
	if f.Count < 1 {
		f.Count = 1
	}
	if index < 0 {
		index = 0
	}
	if index >= f.Count {
		index = f.Count - 1
	}
	f.Index = index
}

// Is 判断某个面板是否聚焦。
func (f Focus) Is(index int) bool { return f.Index == index }

// Label 返回 `2/3` 形式的焦点指示，供卡片副标题使用。
func (f Focus) Label() string {
	if f.Count <= 1 {
		return ""
	}
	return itoa(f.Index+1) + "/" + itoa(f.Count)
}

// FocusHint 返回页脚用的提示文案。
func FocusHint(count int) string {
	if count <= 1 {
		return ""
	}
	return "tab 切换面板"
}

// PaneTitle 拼接"图标 + 标题"，无图标时不留多余空格。
func PaneTitle(icon, title string) string {
	if icon == "" {
		return title
	}
	return icon + " " + title
}

// ListRow 渲染一行列表项，宽度正好 width。
//
// 选中时用主色并在左侧打标记，未选中时留出同样的标记位——
// 否则选中行会比其它行宽一格，边框会抖。
type ListRow struct {
	Text    string
	Detail  string
	Meta    string
	Focused bool
}

// RenderListRow 渲染列表行。
func RenderListRow(th theme.Theme, row ListRow, width int) string {
	marker := "  "
	style := th.Body()
	if row.Focused {
		marker = th.Glyphs.Selected + " "
		style = th.SelectedRow()
	}

	meta := ""
	if row.Meta != "" {
		meta = row.Meta
	}
	metaWidth := Width(meta)
	textWidth := width - 2 - metaWidth
	if metaWidth > 0 {
		textWidth--
	}
	if textWidth < 4 {
		textWidth = 4
	}

	line := marker + style.Render(PadRight(Truncate(row.Text, textWidth), textWidth))
	if row.Detail != "" {
		detailWidth := width - 2 - textWidth - metaWidth - 2
		if detailWidth > 2 {
			line += " " + th.SubtleStyle().Render(Truncate(row.Detail, detailWidth))
		}
	}
	if meta != "" {
		line += " " + th.MutedStyle().Render(meta)
	}
	return Truncate(line, width)
}
