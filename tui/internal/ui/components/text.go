// Package components 是终端界面的可复用积木。
//
// 刻意不使用 bubbles 的 table / list / viewport：它们的宽度计算与
// 中文混排时的对齐行为不受本仓库控制，而这个界面大量出现"课程名 + 学分 + 时间"。
// 这里的表格按显示宽度补齐，并附带一条"每行等宽"的测试。
package components

import (
	"strings"

	"charm.land/lipgloss/v2"
)

// Width 返回字符串在终端中的显示宽度（grapheme 感知）。
func Width(text string) int { return lipgloss.Width(text) }

// PadRight 右补空格到指定显示宽度。
func PadRight(text string, width int) string {
	gap := width - Width(text)
	if gap <= 0 {
		return text
	}
	return text + strings.Repeat(" ", gap)
}

// PadLeft 左补空格到指定显示宽度。
func PadLeft(text string, width int) string {
	gap := width - Width(text)
	if gap <= 0 {
		return text
	}
	return strings.Repeat(" ", gap) + text
}

// Truncate 按显示宽度截断，超出部分以 `..` 收尾。
func Truncate(text string, maxWidth int) string {
	if maxWidth <= 0 {
		return ""
	}
	if Width(text) <= maxWidth {
		return text
	}
	if maxWidth <= 2 {
		return strings.Repeat(".", maxWidth)
	}
	target := maxWidth - 2
	var builder strings.Builder
	current := 0
	for _, r := range text {
		w := lipgloss.Width(string(r))
		if current+w > target {
			break
		}
		builder.WriteRune(r)
		current += w
	}
	builder.WriteString("..")
	return builder.String()
}

// Wrap 按显示宽度折行，不切断宽度为 2 的字符。
//
// 折行时优先在空格处断开（英文单词不会被拆开），找不到空格再按宽度硬断。
func Wrap(text string, width int) []string {
	if width < 4 {
		width = 4
	}
	lines := make([]string, 0, len(text)/width+1)
	for _, paragraph := range strings.Split(text, "\n") {
		if paragraph == "" {
			lines = append(lines, "")
			continue
		}
		remaining := paragraph
		for Width(remaining) > width {
			cut := breakPoint(remaining, width)
			lines = append(lines, strings.TrimRight(remaining[:cut], " "))
			remaining = strings.TrimLeft(remaining[cut:], " ")
		}
		lines = append(lines, remaining)
	}
	return lines
}

// breakPoint 返回不超过 width 的最佳断点字节下标。
func breakPoint(text string, width int) int {
	current := 0
	lastSpace := -1
	for index, r := range text {
		w := lipgloss.Width(string(r))
		if current+w > width {
			if lastSpace > 0 {
				return lastSpace
			}
			if index == 0 {
				// 单个字符就超宽（极窄终端），强制切一个字符，避免死循环。
				_, size := decodeRune(text)
				return size
			}
			return index
		}
		current += w
		if r == ' ' {
			lastSpace = index
		}
	}
	return len(text)
}

// decodeRune 返回首字符与其字节长度。
func decodeRune(text string) (rune, int) {
	for _, r := range text {
		size := len(string(r))
		return r, size
	}
	return 0, 1
}

// Bullet 给一组行加统一缩进与前缀。
func Bullet(glyph string, lines []string) []string {
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		out = append(out, glyph+" "+line)
	}
	return out
}
