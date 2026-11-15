package components

import (
	"strings"

	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// Command 是命令面板里的一项。
type Command struct {
	// Title 是主标题，参与模糊匹配。
	Title string
	// Hint 是副标题，也参与匹配（例如"3"、"成绩"）。
	Hint string
	// Key 是右侧显示的快捷键提示。
	Key string
	// Enabled 为假时置灰且不可执行。
	Enabled bool
	// Run 返回要发回主循环的消息。
	Run func() any
}

// Palette 是命令面板的状态。
type Palette struct {
	all      []Command
	matches  []Match
	query    string
	cursor   int
	maxWidth int
}

// Match 是一条命中结果。
type Match struct {
	Command Command
	Score   int
	// Positions 是命中的字符下标，用于高亮。
	Positions []int
}

// NewPalette 构造命令面板。
func NewPalette(commands []Command) *Palette {
	palette := &Palette{all: commands}
	palette.refilter()
	return palette
}

// SetCommands 替换命令列表并重新过滤。
func (p *Palette) SetCommands(commands []Command) {
	p.all = commands
	p.refilter()
}

// SetWidth 设置最大宽度。
func (p *Palette) SetWidth(width int) { p.maxWidth = width }

// Query 返回当前输入。
func (p *Palette) Query() string { return p.query }

// Reset 清空输入与选中。
func (p *Palette) Reset() {
	p.query = ""
	p.cursor = 0
	p.refilter()
}

// Matches 返回当前命中项。
func (p *Palette) Matches() []Match { return p.matches }

// Cursor 返回当前选中下标。
func (p *Palette) Cursor() int { return p.cursor }

// Push 追加一个字符。
func (p *Palette) Push(r rune) {
	p.query += string(r)
	p.cursor = 0
	p.refilter()
}

// Backspace 删除最后一个字符。
func (p *Palette) Backspace() {
	runes := []rune(p.query)
	if len(runes) == 0 {
		return
	}
	p.query = string(runes[:len(runes)-1])
	p.cursor = 0
	p.refilter()
}

// Move 上下移动选中项。
func (p *Palette) Move(delta int) {
	if len(p.matches) == 0 {
		return
	}
	p.cursor = (p.cursor + delta + len(p.matches)) % len(p.matches)
}

// Selected 返回当前选中的命令。
func (p *Palette) Selected() (Command, bool) {
	if p.cursor < 0 || p.cursor >= len(p.matches) {
		return Command{}, false
	}
	return p.matches[p.cursor].Command, true
}

// refilter 依据当前输入重新计算命中与排序。
func (p *Palette) refilter() {
	if strings.TrimSpace(p.query) == "" {
		p.matches = make([]Match, 0, len(p.all))
		for _, command := range p.all {
			p.matches = append(p.matches, Match{Command: command})
		}
		if p.cursor >= len(p.matches) {
			p.cursor = 0
		}
		return
	}

	type scored struct {
		match Match
		order int
	}
	hits := make([]scored, 0, len(p.all))
	for index, command := range p.all {
		score, positions, ok := fuzzyScore(command.Title, p.query)
		if !ok {
			// 主标题没命中时退而匹配副标题与快捷键。
			score, positions, ok = fuzzyScore(command.Hint+" "+command.Key, p.query)
		}
		if !ok {
			continue
		}
		hits = append(hits, scored{
			match: Match{Command: command, Score: score, Positions: positions},
			order: index,
		})
	}
	// 分数高者在前；同分保持注册顺序，避免"输入一样却每次顺序不同"。
	for outer := 1; outer < len(hits); outer++ {
		for inner := outer; inner > 0; inner-- {
			left, right := hits[inner-1], hits[inner]
			better := right.match.Score > left.match.Score ||
				(right.match.Score == left.match.Score && right.order < left.order)
			if !better {
				break
			}
			hits[inner-1], hits[inner] = hits[inner], hits[inner-1]
		}
	}
	p.matches = make([]Match, 0, len(hits))
	for _, hit := range hits {
		p.matches = append(p.matches, hit.match)
	}
	if p.cursor >= len(p.matches) {
		p.cursor = 0
	}
}

// fuzzyScore 做大小写无关的子序列匹配，返回分数与命中位置。
//
// 打分规则简单但有效：连续命中加分、词首命中加分、越靠前加分。
// 目标不是"最好的模糊搜索"，而是"输入 jw 能找到'成绩'"这类可用体验。
func fuzzyScore(text, pattern string) (int, []int, bool) {
	if pattern == "" {
		return 0, nil, true
	}
	haystack := []rune(strings.ToLower(text))
	needle := []rune(strings.ToLower(pattern))

	score := 0
	positions := make([]int, 0, len(needle))
	cursor := 0
	lastHit := -2
	for _, want := range needle {
		found := -1
		for index := cursor; index < len(haystack); index++ {
			if haystack[index] == want {
				found = index
				break
			}
		}
		if found < 0 {
			return 0, nil, false
		}
		score += 10
		if found == lastHit+1 {
			score += 6
		}
		if found == 0 || haystack[found-1] == ' ' || haystack[found-1] == '-' || haystack[found-1] == '/' {
			score += 8
		}
		score -= found / 4
		positions = append(positions, found)
		lastHit = found
		cursor = found + 1
	}
	return score, positions, true
}

// Render 渲染命令面板（不含外框）。
func (p *Palette) Render(th theme.Theme, height int) string {
	lines := make([]string, 0, height)
	prompt := th.Title().Render(th.Glyphs.Prompt+" ") + th.Body().Render(p.query)
	if p.query == "" {
		prompt = th.Title().Render(th.Glyphs.Prompt+" ") + th.SubtleStyle().Render("输入命令或页面名…")
	}
	lines = append(lines, prompt)
	lines = append(lines, "")

	if len(p.matches) == 0 {
		lines = append(lines, th.SubtleStyle().Render("没有匹配项"))
		return strings.Join(lines, "\n")
	}

	visible := height - 3
	if visible < 1 {
		visible = 1
	}
	start := 0
	if p.cursor >= visible {
		start = p.cursor - visible + 1
	}
	end := start + visible
	if end > len(p.matches) {
		end = len(p.matches)
	}

	for index := start; index < end; index++ {
		match := p.matches[index]
		row := match.Command.Title
		if match.Command.Hint != "" {
			row += "  " + match.Command.Hint
		}
		keyText := match.Command.Key
		if p.maxWidth > 0 && keyText != "" {
			gap := p.maxWidth - Width(row) - Width(keyText) - 4
			if gap > 0 {
				row += strings.Repeat(" ", gap)
			}
		}
		if index == p.cursor {
			lines = append(lines, th.SelectedRow().Render(th.Glyphs.Selected+" ")+
				th.Title().Render(row)+th.SubtleStyle().Render(keyText))
			continue
		}
		style := th.Body()
		if !match.Command.Enabled {
			style = th.SubtleStyle()
		}
		lines = append(lines, "  "+style.Render(row)+th.SubtleStyle().Render(keyText))
	}
	if len(p.matches) > visible {
		lines = append(lines, th.SubtleStyle().Render(
			"  "+itoa(start+1)+"-"+itoa(end)+" / "+itoa(len(p.matches))))
	}
	return strings.Join(lines, "\n")
}

// itoa 把整数转成字符串。
func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	digits := make([]byte, 0, 4)
	for value > 0 {
		digits = append([]byte{byte('0' + value%10)}, digits...)
		value /= 10
	}
	if negative {
		return "-" + string(digits)
	}
	return string(digits)
}

// Box 给内容套一个带边框的面板，并按可用空间裁剪。
func Box(th theme.Theme, title, body string, width, height int) string {
	if width < 8 {
		width = 8
	}
	inner := width - 4
	lines := strings.Split(body, "\n")
	if height > 2 && len(lines) > height-2 {
		lines = lines[:height-2]
	}
	trimmed := make([]string, 0, len(lines))
	for _, line := range lines {
		trimmed = append(trimmed, Truncate(line, inner))
	}
	panel := th.Panel()
	if title != "" {
		panel = panel.Padding(0, 1)
	}
	content := strings.Join(trimmed, "\n")
	if title != "" {
		content = th.PanelTitle().Render(title) + "\n" + content
	}
	return panel.Width(inner).Render(content)
}

// Place 把内容居中放到给定区域内。
func Place(content string, width, height int) string {
	return lipgloss.Place(width, height, lipgloss.Center, lipgloss.Center, content)
}
