package components

import "strings"

// Part 是分栏中的一块。
type Part struct {
	// Width 是该块的显示宽度；SplitV 时忽略。
	Width int
	// Content 是块内容，会被裁剪到正好 Width×Height。
	Content string
}

// Block 把内容裁剪/补齐到正好 width×height。
//
// 终端里没有"自动布局"：拼接前不统一尺寸，后续内容会整体错位。
func Block(content string, width, height int) string {
	if width < 0 {
		width = 0
	}
	if height < 0 {
		height = 0
	}
	lines := strings.Split(content, "\n")
	if len(lines) > height {
		lines = lines[:height]
	}
	out := make([]string, 0, height)
	for _, line := range lines {
		out = append(out, PadRight(Truncate(line, width), width))
	}
	for len(out) < height {
		out = append(out, strings.Repeat(" ", width))
	}
	return strings.Join(out, "\n")
}

// SplitH 水平拼接若干块，每块宽度取 Part.Width，总高为 height。
//
// 与 lipgloss.JoinHorizontal 的区别：这里把每块**强制**补齐到指定尺寸，
// 因此调用方不必担心内容行数不一致导致右侧整列下移。
func SplitH(height int, parts ...Part) string {
	lines := make([]string, height)
	for _, part := range parts {
		block := strings.Split(Block(part.Content, part.Width, height), "\n")
		for index := 0; index < height && index < len(block); index++ {
			lines[index] += block[index]
		}
	}
	return strings.Join(lines, "\n")
}

// SplitHGap 是带列间隙的 SplitH。
//
// 卡片之间留一列空白，视觉上才分得开——终端没有阴影和圆角，
// 全靠留白表达"这是两张卡"。
func SplitHGap(height, gap int, parts ...Part) string {
	if gap <= 0 || len(parts) < 2 {
		return SplitH(height, parts...)
	}
	gapped := make([]Part, 0, len(parts)*2)
	for index, part := range parts {
		if index > 0 {
			gapped = append(gapped, Part{Width: gap, Content: ""})
		}
		gapped = append(gapped, part)
	}
	return SplitH(height, gapped...)
}

// SplitV 垂直堆叠若干块，每块宽度取 width。
func SplitV(width int, parts ...Part) string {
	rendered := make([]string, 0, len(parts))
	for _, part := range parts {
		rendered = append(rendered, Block(part.Content, width, strings.Count(part.Content, "\n")+1))
	}
	return strings.Join(rendered, "\n")
}

// StackV 按给定高度垂直堆叠，每块被补齐到自己的高度。
func StackV(width int, parts []Part) string {
	rendered := make([]string, 0, len(parts))
	for _, part := range parts {
		rendered = append(rendered, Block(part.Content, width, part.Width))
	}
	return strings.Join(rendered, "\n")
}

// ColumnWidths 按权重把 total 分成若干列宽。
//
// 余数全部补给最后一列，保证各列之和**正好**等于 total。
func ColumnWidths(total int, weights []int) []int {
	if len(weights) == 0 {
		return nil
	}
	sum := 0
	for _, weight := range weights {
		if weight < 0 {
			weight = 0
		}
		sum += weight
	}
	if sum == 0 {
		weights = make([]int, len(weights))
		for index := range weights {
			weights[index] = 1
		}
		sum = len(weights)
	}

	widths := make([]int, len(weights))
	used := 0
	for index, weight := range weights {
		width := total * weight / sum
		widths[index] = width
		used += width
	}
	widths[len(widths)-1] += total - used
	if widths[len(widths)-1] < 0 {
		widths[len(widths)-1] = 0
	}
	return widths
}

// RowHeights 按高度把 total 分成若干行高（余数补给最后一行的反序）。
//
// `weights` 为 nil 时平均分配。最后一行吃掉余数，避免累计误差留白。
func RowHeights(total int, weights []int) []int {
	if len(weights) == 0 {
		return nil
	}
	sum := 0
	for _, weight := range weights {
		sum += weight
	}
	if sum <= 0 {
		weights = make([]int, len(weights))
		for index := range weights {
			weights[index] = 1
		}
		sum = len(weights)
	}
	heights := make([]int, len(weights))
	used := 0
	for index, weight := range weights {
		height := total * weight / sum
		heights[index] = height
		used += height
	}
	heights[len(heights)-1] += total - used
	if heights[len(heights)-1] < 1 {
		heights[len(heights)-1] = 1
	}
	return heights
}

// RepeatTo 返回重复 glyph 直到正好填满 width 的字符串。
func RepeatTo(glyph string, width int) string {
	if glyph == "" || width <= 0 {
		return ""
	}
	unit := Width(glyph)
	if unit <= 0 {
		return ""
	}
	count := width / unit
	remainder := width - count*unit
	return strings.Repeat(glyph, count) + strings.Repeat(" ", remainder)
}
