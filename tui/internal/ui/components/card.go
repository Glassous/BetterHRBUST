package components

import (
	"strings"

	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Card 是一个带标题栏的面板。
//
// 标题嵌在上边框里（`╭─ 标题 ──────╮`），比"边框 + 第一行标题"省一行、
// 也让整屏看起来更像前端的卡片栅格。焦点卡片换用主色边框并带焦点标记。
type Card struct {
	// Icon 是可选图标（仅 Nerd Font 档位非空）。
	Icon string
	// Title 是卡片标题。
	Title string
	// Subtitle 右对齐显示在标题栏，通常是计数或状态。
	Subtitle string
	// Body 是卡片正文，会按宽度折行。
	Body string
	// Footer 是正文之后、底边框之前的一行，用于操作提示或汇总。
	Footer string
	// Focused 为真时边框用主色并显示焦点标记。
	Focused bool
	// Level 决定标题色（ok / warn / fail / info / 空）。
	Level string

	Width  int
	Height int
}

// Render 渲染卡片，输出正好 Width×Height。
func (c Card) Render(th theme.Theme) string {
	width, height := c.Width, c.Height
	if width < 6 {
		width = 6
	}
	if height < 3 {
		height = 3
	}
	inner := width - 2

	border := th.Glyphs.Border
	borderColor := th.Border
	titleStyle := th.PanelTitle()
	if c.Level != "" {
		borderColor = th.LevelColor(c.Level)
		titleStyle = th.LevelColorStyle(c.Level)
	}
	if c.Focused {
		borderColor = th.Primary
	}
	edge := lipgloss.NewStyle().Foreground(borderColor)

	// 正文可用高度：去掉上下边框与可能的 footer。
	bodyHeight := height - 2
	var footerLine string
	if c.Footer != "" {
		bodyHeight--
		footerLine = th.SubtleStyle().Render(
			PadRight(Truncate(c.Footer, inner-2), inner))
	}

	var bodyLines []string
	if bodyHeight > 0 {
		body := fitBody(c.Body, inner-2, bodyHeight)
		for _, line := range strings.Split(body, "\n") {
			bodyLines = append(bodyLines, " "+line+" "+
				strings.Repeat(" ", maxInt(0, inner-2-Width(line))))
		}
	}

	lines := make([]string, 0, height)
	lines = append(lines, edge.Render(string(border.TopLeft))+
		c.titleBar(th, titleStyle, inner)+
		edge.Render(string(border.TopRight)))
	for _, line := range bodyLines {
		lines = append(lines, edge.Render(string(border.Left))+line+edge.Render(string(border.Right)))
	}
	if footerLine != "" {
		lines = append(lines, edge.Render(string(border.Left))+footerLine+edge.Render(string(border.Right)))
	}
	lines = append(lines, edge.Render(string(border.BottomLeft))+
		edge.Render(RepeatTo(string(border.Bottom), inner))+
		edge.Render(string(border.BottomRight)))
	return strings.Join(lines, "\n")
}

// titleBar 渲染 `─ 图标 标题 ───────── 副标题 ─`，宽度正好 inner。
//
// 宽度预算是"先量再填"：先拼出让不可省略的部分，剩下的才交给填充线。
// 反过来做（先算填充再拼）很容易多出几个字符，标题末尾就会冒出 `..`。
func (c Card) titleBar(th theme.Theme, titleStyle lipgloss.Style, inner int) string {
	edge := lipgloss.NewStyle().Foreground(th.Border)
	if c.Focused {
		edge = lipgloss.NewStyle().Foreground(th.Primary)
	}
	tick := edge.Render(string(th.Glyphs.Border.Top))

	title := strings.TrimSpace(c.Title)
	if c.Icon != "" {
		title = c.Icon + " " + title
	}
	if c.Focused {
		title = th.Glyphs.Focus + " " + title
	}

	// 起步：一个边框线 + 空格 + 标题 + 空格。
	prefix := " " + title + " "
	// 收尾：空格 + 副标题 + 空格（放不下就整体丢掉副标题）。
	suffix := ""
	if subtitle := strings.TrimSpace(c.Subtitle); subtitle != "" {
		candidate := " " + subtitle + " "
		if Width(prefix)+Width(candidate) <= inner-1 {
			suffix = candidate
		}
	}
	if room := inner - 1 - Width(prefix) - Width(suffix); room < 0 {
		prefix = Truncate(prefix, maxInt(0, inner-1-Width(suffix)))
	}

	fillWidth := inner - 1 - Width(prefix) - Width(suffix)
	if fillWidth < 0 {
		fillWidth = 0
	}
	return tick + titleStyle.Render(prefix) +
		edge.Render(RepeatTo(string(th.Glyphs.Border.Top), fillWidth)) +
		th.SubtleStyle().Render(suffix)
}

// fitBody 把正文折行并裁剪/补齐到 width×height。
func fitBody(content string, width, height int) string {
	if height <= 0 {
		return ""
	}
	if width < 1 {
		width = 1
	}
	lines := make([]string, 0, height)
	for _, paragraph := range strings.Split(content, "\n") {
		if paragraph == "" {
			lines = append(lines, "")
			continue
		}
		lines = append(lines, Wrap(paragraph, width)...)
	}
	if len(lines) > height {
		lines = lines[:height]
	}
	for len(lines) < height {
		lines = append(lines, "")
	}
	for index, line := range lines {
		lines[index] = Truncate(line, width)
	}
	return strings.Join(lines, "\n")
}

// maxInt 返回较大值。
func maxInt(left, right int) int {
	if left > right {
		return left
	}
	return right
}
