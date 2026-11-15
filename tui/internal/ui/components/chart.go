package components

import (
	"fmt"
	"strings"

	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Sparkline 渲染一条迷你折线。
//
// 用字符高度表达数值，适合放在指标卡里看趋势（每学期 GPA、每次考试分数）。
// 数据不足两个点时返回空串——一个点画不出趋势。
func Sparkline(th theme.Theme, values []float64, width int) string {
	if len(values) < 2 {
		return ""
	}
	if width <= 0 {
		return ""
	}

	// 采样到 width 个点：超长时取最近的 width 个，保持"越靠右越新"。
	sampled := values
	if len(sampled) > width {
		sampled = sampled[len(sampled)-width:]
	}

	minValue, maxValue := sampled[0], sampled[0]
	for _, value := range sampled {
		if value < minValue {
			minValue = value
		}
		if value > maxValue {
			maxValue = value
		}
	}
	span := maxValue - minValue

	var builder strings.Builder
	for _, value := range sampled {
		level := 0
		if span > 0 {
			level = int((value - minValue) / span * 7)
		}
		if level < 0 {
			level = 0
		}
		if level > 7 {
			level = 7
		}
		builder.WriteString(th.Glyphs.Spark[level])
	}
	return builder.String()
}

// Meter 是带分段刻度的进度条，比纯色条更容易读出比例。
//
// 输出宽度严格等于 width：段数与余量都在内部算清楚。
func Meter(th theme.Theme, ratio float64, width int) string {
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
	if filled < 0 {
		filled = 0
	}
	level := "ok"
	switch {
	case ratio < 0.3:
		level = "fail"
	case ratio < 0.6:
		level = "warn"
	}
	filledStyle := lipgloss.NewStyle().Foreground(th.LevelColor(level))
	return filledStyle.Render(strings.Repeat(th.Glyphs.BarFull, filled)) +
		th.SubtleStyle().Render(strings.Repeat(th.Glyphs.BarEmpty, width-filled))
}

// Distribution 是一个分布柱。
type Distribution struct {
	Label string
	Count int
	Level string
}

// BarChart 渲染横向条形图。
//
// 每行形如 `标签 ███████ 12`，条形长度按最大值归一化，
// 标签列宽度自适应，整体宽度正好 width。
func BarChart(th theme.Theme, items []Distribution, width int) string {
	if len(items) == 0 || width < 12 {
		return ""
	}
	maxCount := 0
	labelWidth := 0
	for _, item := range items {
		if item.Count > maxCount {
			maxCount = item.Count
		}
		if Width(item.Label) > labelWidth {
			labelWidth = Width(item.Label)
		}
	}
	if maxCount == 0 {
		maxCount = 1
	}
	if labelWidth > width/3 {
		labelWidth = width / 3
	}

	// 每行：标签 + 空格 + 条 + 空格 + 计数
	countWidth := len(fmt.Sprintf("%d", maxCount))
	barWidth := width - labelWidth - countWidth - 2
	if barWidth < 3 {
		barWidth = 3
	}

	lines := make([]string, 0, len(items))
	for _, item := range items {
		ratio := float64(item.Count) / float64(maxCount)
		filled := int(ratio*float64(barWidth) + 0.5)
		if maxCount == 1 {
			// 全是 1 的时候按比例画会得到满格长条，看上去像"很多"。
			// 这种场景只需要一个可见的方块表示"有一项"。
			filled = 1
		}
		if filled < 0 {
			filled = 0
		}
		if item.Count > 0 && filled == 0 {
			filled = 1
		}
		style := lipgloss.NewStyle().Foreground(th.LevelColor(item.Level))
		bar := style.Render(strings.Repeat(th.Glyphs.BarFull, filled)) +
			th.SubtleStyle().Render(strings.Repeat(th.Glyphs.BarEmpty, maxInt(0, barWidth-filled)))
		lines = append(lines, th.MutedStyle().Render(PadRight(Truncate(item.Label, labelWidth), labelWidth))+
			" "+bar+" "+th.Body().Render(PadLeft(fmt.Sprintf("%d", item.Count), countWidth)))
	}
	return strings.Join(lines, "\n")
}

// Trend 返回一组数值的涨跌描述与语义级别。
//
// 只比较首尾：中间怎么波动不重要，用户关心"比之前好还是差"。
func Trend(values []float64) (string, string) {
	if len(values) < 2 {
		return "", ""
	}
	first, last := values[0], values[len(values)-1]
	delta := last - first
	level := "info"
	arrow := "→"
	switch {
	case delta > 0.01:
		level = "ok"
		arrow = "↑"
	case delta < -0.01:
		level = "fail"
		arrow = "↓"
	}
	return fmt.Sprintf("%s %.2f", arrow, absFloat(delta)), level
}

// absFloat 返回绝对值。
func absFloat(value float64) float64 {
	if value < 0 {
		return -value
	}
	return value
}

// MiniStat 是一个"标签 + 大号值 + 可选副标题"的小指标块。
type MiniStat struct {
	Label   string
	Value   string
	Detail  string
	Level   string
	Focused bool
}

// RenderMiniStat 渲染指标块，宽度正好 width、高度正好 height。
func RenderMiniStat(th theme.Theme, stat MiniStat, width, height int) string {
	if width < 6 {
		width = 6
	}
	if height < 2 {
		height = 2
	}
	valueStyle := lipgloss.NewStyle().Foreground(th.LevelColor(stat.Level))
	if stat.Level == "" {
		valueStyle = th.Title()
	}

	lines := []string{
		th.SubtleStyle().Render(Truncate(stat.Label, width)),
		valueStyle.Render(Truncate(stat.Value, width)),
	}
	if stat.Detail != "" && height >= 3 {
		lines = append(lines, th.MutedStyle().Render(Truncate(stat.Detail, width)))
	}
	if stat.Focused {
		lines[0] = th.Title().Render(Truncate(th.Glyphs.Focus+" "+stat.Label, width))
	}
	return Block(strings.Join(lines, "\n"), width, height)
}
