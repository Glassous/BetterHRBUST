package components

import (
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// TestColumnWidthsSumExactly 断言按权重分列后总宽正好等于可用宽度。
//
// 少一列就会让右边框缩进，多一列会把内容挤到下一行——两种都会立刻看出来。
func TestColumnWidthsSumExactly(t *testing.T) {
	cases := []struct {
		total   int
		weights []int
	}{
		{140, []int{3, 2}},
		{141, []int{3, 2}},
		{120, []int{5, 3}},
		{99, []int{7, 3}},
		{80, []int{1, 1, 1}},
		{7, []int{1, 1, 1, 1}},
		{100, []int{0, 0}},
	}
	for _, item := range cases {
		widths := ColumnWidths(item.total, item.weights)
		sum := 0
		for _, width := range widths {
			sum += width
		}
		if sum != item.total {
			t.Fatalf("%v 分 %d 列后总和 %d，应为 %d", item.weights, item.total, sum, item.total)
		}
		for _, width := range widths {
			if width < 0 {
				t.Fatalf("出现负宽度：%v", widths)
			}
		}
	}
}

// TestRowHeightsSumExactly 断言按权重分行后总高正好等于可用高度。
func TestRowHeightsSumExactly(t *testing.T) {
	for _, total := range []int{42, 41, 30, 20} {
		heights := RowHeights(total, []int{6, 9, 1, 1})
		sum := 0
		for _, height := range heights {
			sum += height
		}
		if sum != total {
			t.Fatalf("%d 分 4 行后总和 %d", total, sum)
		}
	}
}

func TestBlockFillsExactSize(t *testing.T) {
	content := "第一行\n第二行比目标宽很多很多\n\n"
	for _, size := range [][2]int{{10, 3}, {4, 6}, {20, 1}} {
		block := Block(content, size[0], size[1])
		lines := strings.Split(block, "\n")
		if len(lines) != size[1] {
			t.Fatalf("%dx%d 得到 %d 行", size[0], size[1], len(lines))
		}
		for _, line := range lines {
			if got := Width(line); got != size[0] {
				t.Fatalf("%dx%d 某行宽 %d：%q", size[0], size[1], got, line)
			}
		}
	}
}

// TestCardFillsExactSize 断言卡片输出正好是 Width×Height，且每行等宽。
func TestCardFillsExactSize(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	for _, size := range [][2]int{{40, 8}, {60, 14}, {24, 5}, {10, 3}} {
		card := Card{
			Title:    "学业指标",
			Subtitle: "必修课口径",
			Body:     "第一行\n第二行会很长很长很长需要折行",
			Footer:   "页脚",
			Width:    size[0],
			Height:   size[1],
		}.Render(th)
		lines := strings.Split(card, "\n")
		if len(lines) != size[1] {
			t.Fatalf("%dx%d 得到 %d 行：\n%s", size[0], size[1], len(lines), card)
		}
		for index, line := range lines {
			if got := Width(line); got != size[0] {
				t.Fatalf("%dx%d 第 %d 行宽 %d：%q", size[0], size[1], index+1, got, line)
			}
		}
	}
}

// TestCardTitleNeverOverflows 断言标题过长时被截断而不是冲掉右边框。
func TestCardTitleNeverOverflows(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	card := Card{
		Title:    strings.Repeat("很长的标题", 12),
		Subtitle: strings.Repeat("副标题", 8),
		Width:    40,
		Height:   4,
	}.Render(th)
	for _, line := range strings.Split(card, "\n") {
		if Width(line) != 40 {
			t.Fatalf("宽度不符 %d：%q", Width(line), line)
		}
	}
}

func TestSplitHGapKeepsTotalWidth(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	left := Card{Title: "左", Width: 20, Height: 4}.Render(th)
	right := Card{Title: "右", Width: 30, Height: 4}.Render(th)
	out := SplitHGap(4, 1,
		Part{Width: 20, Content: left},
		Part{Width: 30, Content: right},
	)
	lines := strings.Split(out, "\n")
	if len(lines) != 4 {
		t.Fatalf("应输出 4 行，实际 %d", len(lines))
	}
	for _, line := range lines {
		if got := Width(line); got != 51 {
			t.Fatalf("带间隙拼接后宽 %d，应为 51：%q", got, line)
		}
	}
}

func TestStackVKeepsHeight(t *testing.T) {
	out := StackV(10, []Part{
		{Width: 3, Content: "a"},
		{Width: 2, Content: "b"},
	})
	if got := len(strings.Split(out, "\n")); got != 5 {
		t.Fatalf("应输出 5 行，实际 %d", got)
	}
}

func TestFocusCyclesBothWays(t *testing.T) {
	focus := NewFocus(3)
	if !focus.Is(0) {
		t.Fatal("初始焦点应在第 0 个")
	}
	focus.Next()
	focus.Next()
	focus.Next()
	if !focus.Is(0) {
		t.Fatalf("三次 Next 应回到 0，实际 %d", focus.Index)
	}
	focus.Prev()
	if !focus.Is(2) {
		t.Fatalf("Prev 应循环到 2，实际 %d", focus.Index)
	}
	focus.Move(5)
	if !focus.Is(2) {
		t.Fatalf("Move 应夹在范围内，实际 %d", focus.Index)
	}
	if got := focus.Label(); got != "3/3" {
		t.Fatalf("标签应为 3/3，实际 %q", got)
	}

	single := NewFocus(1)
	single.Next()
	if !single.Is(0) {
		t.Fatal("单面板不应移动")
	}
	if label := single.Label(); label != "" {
		t.Fatalf("单面板不该显示位置标签，实际 %q", label)
	}
}

func TestMeterIsExactWidth(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	for _, ratio := range []float64{0, 0.3, 0.5, 1, 1.5} {
		for _, width := range []int{4, 11, 30} {
			if got := Width(Meter(th, ratio, width)); got != width {
				t.Fatalf("比例 %v 宽度 %d 的进度条实际 %d", ratio, width, got)
			}
		}
	}
}

func TestSparklineLengthMatchesData(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	if got := Sparkline(th, []float64{1}, 10); got != "" {
		t.Fatalf("单点不应画线，实际 %q", got)
	}
	values := []float64{2.0, 2.5, 2.2, 3.1, 3.4}
	line := Sparkline(th, values, 10)
	if got := Width(line); got != len(values) {
		t.Fatalf("五个点应输出 5 列，实际 %d", got)
	}
	// 超长序列按最近的点采样。
	long := make([]float64, 100)
	if got := Width(Sparkline(th, long, 12)); got != 12 {
		t.Fatalf("应采样到 12 列，实际 %d", got)
	}
}

func TestBarChartFits(t *testing.T) {
	th := theme.New(theme.ANSI16, theme.TierASCII)
	items := []Distribution{
		{Label: "周一", Count: 3},
		{Label: "周二", Count: 1},
		{Label: "周三", Count: 0},
	}
	for _, width := range []int{20, 40, 60} {
		for _, line := range strings.Split(BarChart(th, items, width), "\n") {
			if got := Width(line); got > width {
				t.Fatalf("宽度 %d 下某行 %d：%q", width, got, line)
			}
		}
	}
	// 全为 1 时不能画出满格长条。
	single := BarChart(th, []Distribution{{Label: "a", Count: 1}, {Label: "b", Count: 1}}, 30)
	if strings.Count(single, strings.Repeat("#", 10)) > 0 {
		t.Fatal("计数全为 1 时不应出现长条")
	}
}

func TestTrendDirection(t *testing.T) {
	text, level := Trend([]float64{2.0, 3.0})
	if level != "ok" || !strings.Contains(text, "↑") {
		t.Fatalf("上升应判 ok 并带箭头，实际 %q %q", text, level)
	}
	text, level = Trend([]float64{3.0, 2.0})
	if level != "fail" || !strings.Contains(text, "↓") {
		t.Fatalf("下降应判 fail，实际 %q %q", text, level)
	}
	if _, level = Trend([]float64{1}); level != "" {
		t.Fatal("单点不应给出趋势")
	}
}

// TestNerdGlyphsHaveIcons 断言 Nerd Font 档位带图标、其它档位不带。
func TestNerdGlyphsHaveIcons(t *testing.T) {
	nerd := theme.New(theme.Dark, theme.TierNerd)
	if !nerd.HasIcons() {
		t.Fatal("Nerd 档位应标记为有图标")
	}
	for name, icon := range map[string]string{
		"dashboard": nerd.Glyphs.Icons.Dashboard,
		"timetable": nerd.Glyphs.Icons.Timetable,
		"scores":    nerd.Glyphs.Icons.Scores,
		"settings":  nerd.Glyphs.Icons.Settings,
		"ai":        nerd.Glyphs.Icons.AI,
	} {
		if icon == "" {
			t.Fatalf("Nerd 档位缺少 %s 图标", name)
		}
	}

	for _, tier := range []theme.Tier{theme.TierASCII, theme.TierUnicode} {
		palette := theme.New(theme.Dark, tier)
		if palette.HasIcons() {
			t.Fatalf("%v 档位不应有图标", tier)
		}
		if palette.Glyphs.Icons.Dashboard != "" {
			t.Fatalf("%v 档位的图标字段应为空", tier)
		}
	}
}

// TestGlyphTiersAreDistinct 断言三档字形确实不同。
func TestGlyphTiersAreDistinct(t *testing.T) {
	ascii := theme.New(theme.Dark, theme.TierASCII).Glyphs
	unicode := theme.New(theme.Dark, theme.TierUnicode).Glyphs
	nerd := theme.New(theme.Dark, theme.TierNerd).Glyphs

	if ascii.Selected == unicode.Selected {
		t.Fatal("ASCII 与 Unicode 的选中标记应不同")
	}
	if unicode.BarFull == ascii.BarFull {
		t.Fatal("ASCII 进度条应是纯 ASCII")
	}
	for _, r := range ascii.BarFull + ascii.BarEmpty + ascii.Selected + ascii.Section + ascii.Focus {
		if r > 127 {
			t.Fatalf("ASCII 档位出现非 ASCII 字符 %q", r)
		}
	}
	if nerd.Selected == unicode.Selected {
		t.Fatal("Nerd 档位应有自己的选中标记")
	}
}

func TestTierFromPrefersASCII(t *testing.T) {
	if got := theme.TierFrom(true, true); got != theme.TierASCII {
		t.Fatalf("同时要求 ASCII 与 Nerd 时应以 ASCII 为准，实际 %v", got)
	}
	if got := theme.TierFrom(false, true); got != theme.TierNerd {
		t.Fatalf("只要求 Nerd 时应为 Nerd，实际 %v", got)
	}
	if got := theme.TierFrom(false, false); got != theme.TierUnicode {
		t.Fatalf("默认应为 Unicode，实际 %v", got)
	}
}
