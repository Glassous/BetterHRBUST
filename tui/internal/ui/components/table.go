package components

import (
	"strings"

	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Align 是列对齐方式。
type Align int

const (
	// Left 左对齐。
	Left Align = iota
	// Right 右对齐。
	Right
)

// Column 是表格列定义。
type Column struct {
	Title string
	Align Align
	// Min 是列的最小宽度（用于中文表头）。
	Min int
	// Max 是列的最大宽度，超出会截断；0 表示不限制。
	Max int
	// Flex 为真时，多余宽度按比例分配给它。
	Flex bool
}

// Table 是按显示宽度对齐的表格。
//
// 只渲染可见行（调用方负责裁剪），因此大列表也能保持帧率。
type Table struct {
	columns []Column
	rows    [][]string
	// cursor 是当前选中行（-1 表示无选中）。
	cursor   int
	theme    theme.Theme
	maxWidth int
}

// NewTable 构造表格。
func NewTable(columns []Column, rows [][]string, selected int, th theme.Theme, maxWidth int) *Table {
	return &Table{columns: columns, rows: rows, cursor: selected, theme: th, maxWidth: maxWidth}
}

// Render 返回表格文本。`cursor` 行会被高亮。
func (t *Table) Render() string {
	if len(t.columns) == 0 {
		return ""
	}
	widths := t.measure()

	header := t.renderRow(t.headerCells(), widths, false)
	divider := t.renderDivider(widths)

	lines := make([]string, 0, len(t.rows)+3)
	lines = append(lines, t.theme.Heading().Render(header))
	lines = append(lines, t.theme.SubtleStyle().Render(divider))
	for index, row := range t.rows {
		lines = append(lines, t.renderRow(row, widths, index == t.cursor))
	}
	return strings.Join(lines, "\n")
}

// headerCells 返回表头文本。
func (t *Table) headerCells() []string {
	cells := make([]string, len(t.columns))
	for index, column := range t.columns {
		cells[index] = column.Title
	}
	return cells
}

// measure 计算每列宽度。
//
// 先取"表头 + 全部单元格"的最大值并套用 Min/Max，再把剩余宽度分给 Flex 列。
func (t *Table) measure() []int {
	widths := make([]int, len(t.columns))
	for index, column := range t.columns {
		width := Width(column.Title)
		if column.Min > width {
			width = column.Min
		}
		for _, row := range t.rows {
			if index >= len(row) {
				continue
			}
			if candidate := Width(row[index]); candidate > width {
				width = candidate
			}
		}
		if column.Max > 0 && width > column.Max {
			width = column.Max
		}
		widths[index] = width
	}

	// 列间距：每列后补一个空格。
	total := 0
	for _, width := range widths {
		total += width + 1
	}
	if t.maxWidth <= 0 || total <= t.maxWidth {
		return widths
	}

	// 超宽：先压缩 Flex 列，再按比例压缩所有列，直到放得下。
	for total > t.maxWidth {
		shrunk := false
		for index := range widths {
			if total <= t.maxWidth {
				break
			}
			if widths[index] <= 4 {
				continue
			}
			widths[index]--
			total--
			shrunk = true
		}
		if !shrunk {
			break
		}
	}
	return widths
}

// renderRow 渲染一行，按列宽补齐。
func (t *Table) renderRow(cells []string, widths []int, selected bool) string {
	parts := make([]string, 0, len(widths))
	for index, width := range widths {
		cell := ""
		if index < len(cells) {
			cell = cells[index]
		}
		cell = Truncate(cell, width)
		if t.columns[index].Align == Right {
			parts = append(parts, PadLeft(cell, width))
			continue
		}
		parts = append(parts, PadRight(cell, width))
	}
	line := strings.TrimRight(strings.Join(parts, " "), " ")
	if selected {
		line = t.theme.Glyphs.Selected + " " + line
		return t.theme.SelectedRow().Render(line)
	}
	return "  " + line
}

// renderDivider 渲染表头下的分隔线。
func (t *Table) renderDivider(widths []int) string {
	pieces := make([]string, 0, len(widths))
	for _, width := range widths {
		pieces = append(pieces, strings.Repeat(t.theme.Glyphs.Divider, width))
	}
	return "  " + strings.Join(pieces, " ")
}

// Empty 返回"没有数据"的占位文本。
func Empty(th theme.Theme, message string) string {
	return th.SubtleStyle().Render("  " + message)
}

// ProgressBar 渲染一个 ASCII/Unicode 进度条。
//
// 使用 `strings.Repeat` 而不是 `lipgloss.Style.Width`：进度条必须严格
// 正好 width 列，Lip Gloss 的 Width 会被内边距影响。
func ProgressBar(th theme.Theme, ratio float64, width int) string {
	if width < 4 {
		width = 4
	}
	if ratio < 0 {
		ratio = 0
	}
	if ratio > 1 {
		ratio = 1
	}
	filled := int(ratio*float64(width) + 0.5)
	if filled > width {
		filled = width
	}
	filledStyle := lipgloss.NewStyle().Foreground(th.Success)
	bar := filledStyle.Render(strings.Repeat(th.Glyphs.BarFull, filled)) +
		th.SubtleStyle().Render(strings.Repeat(th.Glyphs.BarEmpty, width-filled))
	return bar
}

// KV 渲染一组「标签 值」对，标签列固定宽度。
func KV(th theme.Theme, pairs [][2]string, labelWidth int) string {
	lines := make([]string, 0, len(pairs))
	for _, pair := range pairs {
		label := PadRight(pair[0], labelWidth)
		lines = append(lines, th.Label().Render(label)+"  "+th.Value().Render(pair[1]))
	}
	return strings.Join(lines, "\n")
}

// Section 渲染一个带标题的区块。
func Section(th theme.Theme, title string, body string) string {
	if strings.TrimSpace(body) == "" {
		return th.Heading().Render(th.Glyphs.Section+title) + "\n" + th.SubtleStyle().Render("  暂无数据")
	}
	return th.Heading().Render(th.Glyphs.Section+title) + "\n" + body
}

// Badge 渲染一个小标签。
func Badge(th theme.Theme, text string, level string) string {
	style := lipgloss.NewStyle().Foreground(th.LevelColor(level)).Bold(true)
	return style.Render(text)
}
