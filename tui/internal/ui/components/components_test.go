package components

import (
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

func testTheme() theme.Theme { return theme.New(theme.ANSI16, theme.TierASCII) }

func TestWidthCountsCJKAsTwo(t *testing.T) {
	cases := []struct {
		text string
		want int
	}{
		{"abc", 3},
		{"及格", 4},
		{"成绩 90", 7},
		{"", 0},
		{"·", 1},
	}
	for _, item := range cases {
		if got := Width(item.text); got != item.want {
			t.Fatalf("%q 宽度期望 %d，实际 %d", item.text, item.want, got)
		}
	}
}

func TestTruncateNeverExceedsTarget(t *testing.T) {
	cases := []struct {
		text  string
		limit int
	}{
		{"高等数学", 10},
		{"高等数学A(1)", 7},
		{"abcdef", 2},
		{"混合中文andEnglish", 9},
		{"", 5},
	}
	for _, item := range cases {
		got := Truncate(item.text, item.limit)
		if Width(got) > item.limit {
			t.Fatalf("%q 截断到 %d 后宽度 %d", item.text, item.limit, Width(got))
		}
	}
	if !strings.HasSuffix(Truncate("高等数学A(1)", 7), "..") {
		t.Fatal("截断应以 .. 收尾")
	}
	if got := Truncate("abcdef", 2); got != ".." {
		t.Fatalf("极限宽度应返回 ..，实际 %q", got)
	}
}

func TestWrapKeepsWideCharsIntact(t *testing.T) {
	text := "这是一段用于测试折行的中文内容，mixed with english words"
	for _, width := range []int{8, 12, 20, 40} {
		lines := Wrap(text, width)
		for _, line := range lines {
			if got := Width(line); got > width {
				t.Fatalf("宽度 %d 下某行宽 %d：%q", width, got, line)
			}
		}
		joined := strings.Join(lines, "")
		normalized := strings.ReplaceAll(strings.ReplaceAll(text, " ", ""), "\n", "")
		if !strings.Contains(strings.ReplaceAll(joined, " ", ""), normalized[:minInt(len(normalized), 20)]) {
			t.Fatalf("折行后内容丢失：%q", joined)
		}
	}
}

func TestWrapHandlesEmptyAndNewlines(t *testing.T) {
	lines := Wrap("第一行\n\n第三行", 20)
	if len(lines) != 3 {
		t.Fatalf("应保留空行，实际 %d 行：%#v", len(lines), lines)
	}
	if lines[1] != "" {
		t.Fatalf("第二行应为空，实际 %q", lines[1])
	}
}

func TestTableRowsShareWidth(t *testing.T) {
	th := testTheme()
	columns := []Column{
		{Title: "学期", Max: 12},
		{Title: "课程", Flex: true},
		{Title: "学分", Align: Right, Min: 4},
	}
	rows := [][]string{
		{"2025 秋", "高等数学", "3"},
		{"2025 春", "Mixed English Course Name", "2.5"},
	}
	table := NewTable(columns, rows, 0, th, 60)
	out := table.Render()
	widths := []int{}
	for _, line := range strings.Split(out, "\n") {
		widths = append(widths, Width(line))
	}
	for index, width := range widths {
		if width > 60 {
			t.Fatalf("第 %d 行超宽 %d：%q", index+1, width, strings.Split(out, "\n")[index])
		}
	}
	if len(widths) < 4 {
		t.Fatalf("表格应包含表头、分隔线与两行数据，实际 %d 行", len(widths))
	}
}

func TestProgressBarIsExactWidth(t *testing.T) {
	th := testTheme()
	for _, ratio := range []float64{-1, 0, 0.25, 0.5, 1, 2} {
		for _, width := range []int{4, 10, 24} {
			bar := ProgressBar(th, ratio, width)
			if got := Width(bar); got != width {
				t.Fatalf("比例 %v 宽度 %d 的进度条实际宽 %d", ratio, width, got)
			}
		}
	}
}

func TestWindowKeepsCursorVisible(t *testing.T) {
	window := Window{Size: 5}
	total := 100
	window.Clamp(total)
	if window.Cursor != 0 || window.Offset != 0 {
		t.Fatalf("初始状态应为 0/0，实际 %d/%d", window.Cursor, window.Offset)
	}
	for step := 0; step < 20; step++ {
		window.Move(1, total)
		if window.Cursor < window.Offset || window.Cursor >= window.Offset+window.Size {
			t.Fatalf("游标 %d 不在窗口 [%d,%d)", window.Cursor, window.Offset, window.Offset+window.Size)
		}
	}
	window.End(total)
	if window.Cursor != total-1 {
		t.Fatalf("End 应落到最后一项，实际 %d", window.Cursor)
	}
	if window.Cursor >= window.Offset+window.Size {
		t.Fatal("End 后游标应仍在窗口内")
	}
	window.Home(total)
	if window.Cursor != 0 || window.Offset != 0 {
		t.Fatalf("Home 后应为 0/0，实际 %d/%d", window.Cursor, window.Offset)
	}

	// 空列表不应 panic。
	empty := Window{}
	empty.Clamp(0)
	empty.Move(1, 0)
	start, end := empty.Range(0)
	if start != 0 || end != 0 {
		t.Fatalf("空列表区间应为 [0,0)，实际 [%d,%d)", start, end)
	}
}

func TestPaletteFiltersAndSelects(t *testing.T) {
	commands := []Command{
		{Title: "概览", Hint: "今日课程", Key: "1", Enabled: true},
		{Title: "成绩与 GPA", Hint: "筛选与统计", Key: "3", Enabled: true},
		{Title: "设置", Hint: "主题与 AI", Key: ",", Enabled: true},
	}
	palette := NewPalette(commands)
	if len(palette.Matches()) != 3 {
		t.Fatalf("初始应显示全部命令，实际 %d", len(palette.Matches()))
	}

	for _, r := range "成绩" {
		palette.Push(r)
	}
	if len(palette.Matches()) == 0 {
		t.Fatal("中文子序列应能命中")
	}
	if !strings.Contains(palette.Matches()[0].Command.Title, "成绩") {
		t.Fatalf("首选应是成绩页，实际 %q", palette.Matches()[0].Command.Title)
	}

	palette.Reset()
	for _, r := range "gpa" {
		palette.Push(r)
	}
	if len(palette.Matches()) == 0 {
		t.Fatal("副标题也参与匹配")
	}

	palette.Reset()
	for _, r := range "zzzz" {
		palette.Push(r)
	}
	if len(palette.Matches()) != 0 {
		t.Fatal("无命中时应为空")
	}
	if _, ok := palette.Selected(); ok {
		t.Fatal("无命中时不应返回选中项")
	}

	palette.Reset()
	palette.Move(1)
	palette.Move(1)
	palette.Move(-1)
	if _, ok := palette.Selected(); !ok {
		t.Fatal("应能选中")
	}
}

func TestPaletteBackspace(t *testing.T) {
	palette := NewPalette(nil)
	palette.Push('a')
	palette.Push('b')
	palette.Backspace()
	if palette.Query() != "a" {
		t.Fatalf("退格后应为 a，实际 %q", palette.Query())
	}
	palette.Backspace()
	palette.Backspace()
	if palette.Query() != "" {
		t.Fatalf("空输入上退格不应出错，实际 %q", palette.Query())
	}
}

func TestFuzzyScoreRanksContiguousHigher(t *testing.T) {
	// 同一单词内：连续命中（ab）应高于跨度很大的命中（af）。
	contiguous, positions, ok := fuzzyScore("abcdef", "ab")
	if !ok {
		t.Fatal("应能命中")
	}
	if len(positions) != 2 || positions[0] != 0 || positions[1] != 1 {
		t.Fatalf("命中位置应为 [0,1]，实际 %v", positions)
	}
	spread, _, ok := fuzzyScore("abcdef", "af")
	if !ok {
		t.Fatal("跨度命中也应成功")
	}
	if contiguous <= spread {
		t.Fatalf("连续命中应得分更高：%d vs %d", contiguous, spread)
	}
	if _, _, ok := fuzzyScore("abc", "abcd"); ok {
		t.Fatal("模式长于文本不应命中")
	}
	if _, _, ok := fuzzyScore("", "a"); ok {
		t.Fatal("空文本不应命中")
	}
	if _, _, ok := fuzzyScore("abc", ""); !ok {
		t.Fatal("空模式应命中")
	}
}

func TestBadgeAndDivider(t *testing.T) {
	th := testTheme()
	if got := Divider(th, 5); Width(got) != 5 {
		t.Fatalf("分隔线宽度应为 5，实际 %d", Width(got))
	}
	if got := Divider(th, 0); got != "" {
		t.Fatalf("宽度 0 应返回空串，实际 %q", got)
	}
}

func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}
