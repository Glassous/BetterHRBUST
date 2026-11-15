package components

import (
	"strings"
	"time"

	"charm.land/bubbles/v2/key"
	"charm.land/lipgloss/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// State 描述一个页面的加载状态。
type State struct {
	// Loading 为真时显示加载提示，忽略 Empty / Err。
	Loading bool
	// Err 非空时显示错误态。
	Err error
	// Empty 为真且 Err 为空时显示空态。
	Empty bool
	// EmptyText 是空态文案；不同页面给出不同原因。
	EmptyText string
}

// Active 表示当前需要显示占位而不是内容。
func (s State) Active() bool { return s.Loading || s.Err != nil || s.Empty }

// Render 渲染占位内容并返回是否已接管整页。
func (s State) Render(th theme.Theme, width, height int, loadingText string) (string, bool) {
	if !s.Active() {
		return "", false
	}
	var text string
	switch {
	case s.Loading:
		if loadingText == "" {
			loadingText = "正在读取本地缓存…"
		}
		text = th.MutedStyle().Render(loadingText)
	case s.Err != nil:
		text = th.LevelColorStyle("fail").Render(th.Glyphs.Cross+" "+s.Err.Error()) + "\n\n" +
			th.SubtleStyle().Render("提示：按 ctrl+r 重读缓存；若仍失败，到图形界面刷新一次")
	default:
		if s.EmptyText == "" {
			s.EmptyText = "暂无数据"
		}
		text = th.SubtleStyle().Render(s.EmptyText)
	}
	return lipgloss.Place(width, height, lipgloss.Center, lipgloss.Center, text), true
}

// Toast 是一条会自动消失的提示。
type Toast struct {
	Seq   int
	Text  string
	Level string
	// Expire 之后不再显示。
	Expire time.Time
}

// Visible 判断是否仍未过期。
func (t Toast) Visible(now time.Time) bool { return t.Seq > 0 && now.Before(t.Expire) }

// Render 渲染提示条。
func (t Toast) Render(th theme.Theme) string {
	if t.Text == "" {
		return ""
	}
	glyph := th.Glyphs.Check
	if t.Level == "fail" || t.Level == "error" {
		glyph = th.Glyphs.Cross
	}
	style := lipgloss.NewStyle().Foreground(th.LevelColor(t.Level)).Bold(true)
	return style.Render(glyph + " " + t.Text)
}

// Banner 是状态栏下方的提示条。
type Banner struct {
	Text  string
	Level string
}

// Render 渲染提示条；空文本返回空串。
func (b Banner) Render(th theme.Theme, width int) string {
	if strings.TrimSpace(b.Text) == "" {
		return ""
	}
	style := lipgloss.NewStyle().Foreground(th.LevelColor(b.Level))
	prefix := "  "
	if b.Level == "warn" {
		prefix = "  ! "
	} else if b.Level == "fail" {
		prefix = "  " + th.Glyphs.Cross + " "
	}
	return style.Render(Truncate(prefix+b.Text, width))
}

// StatusBar 是顶部状态栏。
type StatusBar struct {
	Title  string
	Left   []string
	Center []string
	Right  []string
}

// Render 渲染状态栏：左右两端对齐，中间按可用宽度裁剪。
func (s StatusBar) Render(th theme.Theme, width int) string {
	if width <= 0 {
		return ""
	}
	left := strings.Join(s.Left, "  ")
	right := strings.Join(s.Right, "  ")
	center := strings.Join(s.Center, "  ")

	title := th.Title().Render(s.Title)
	leftLine := title
	if left != "" {
		leftLine = title + th.SubtleStyle().Render(" · ") + th.Body().Render(left)
	}
	rightLine := th.MutedStyle().Render(right)

	gap := width - Width(leftLine) - Width(rightLine)
	if gap < 1 {
		return Truncate(leftLine, width)
	}
	if center != "" {
		centerLine := th.MutedStyle().Render(center)
		centerGap := gap - Width(centerLine)
		if centerGap >= 2 {
			before := centerGap / 2
			return leftLine + strings.Repeat(" ", before) + centerLine +
				strings.Repeat(" ", centerGap-before) + rightLine
		}
	}
	return leftLine + strings.Repeat(" ", gap) + rightLine
}

// Footer 是底部按键提示。
type Footer struct {
	Hints []string
	Note  string
}

// Render 渲染页脚：按键提示左对齐，备注右对齐。
func (f Footer) Render(th theme.Theme, width int) string {
	left := ""
	if len(f.Hints) > 0 {
		parts := make([]string, 0, len(f.Hints))
		for _, hint := range f.Hints {
			parts = append(parts, th.SubtleStyle().Render(hint))
		}
		left = strings.Join(parts, th.SubtleStyle().Render("  ·  "))
	}
	note := th.SubtleStyle().Render(f.Note)
	gap := width - Width(left) - Width(note)
	if gap < 1 {
		return Truncate(left, width)
	}
	return left + strings.Repeat(" ", gap) + note
}

// Divider 渲染一条与宽度等长的分隔线。
func Divider(th theme.Theme, width int) string {
	if width <= 0 {
		return ""
	}
	return th.SubtleStyle().Render(strings.Repeat(th.Glyphs.Divider, width))
}

// HelpOverlay 把按键绑定渲染成浮层内容。
type HelpOverlay struct {
	Sections []HelpSection
	Width    int
	Height   int
}

// HelpSection 是帮助浮层里的一组按键。
type HelpSection struct {
	Title    string
	Bindings []key.Binding
}

// Render 渲染帮助浮层（不含外框，由调用方加边框）。
func (h HelpOverlay) Render(th theme.Theme) string {
	lines := make([]string, 0, 32)
	for _, section := range h.Sections {
		if len(section.Bindings) == 0 {
			continue
		}
		lines = append(lines, th.Heading().Render(section.Title))
		for _, binding := range section.Bindings {
			if !binding.Enabled() {
				continue
			}
			lines = append(lines, "  "+th.Title().Render(PadRight(binding.Help().Key, 11))+
				th.MutedStyle().Render(binding.Help().Desc))
		}
		lines = append(lines, "")
	}
	lines = append(lines, th.SubtleStyle().Render("按 esc 或 ? 关闭"))
	return strings.Join(lines, "\n")
}
