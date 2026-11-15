// Package theme 提供终端界面的配色与字形。
//
// 三套主题（深色 / 浅色 / 16 色兼容）× 三档字形（ASCII / Unicode / Nerd Font）。
// 两者正交：主题决定颜色，字形档位决定边框、图标与进度条字符。
//
// 字形档位是**显式选择**而不是自动探测：没有任何可靠办法判断终端装没装
// Nerd Font，猜错的结果是满屏豆腐块。默认 Unicode；`--ascii` 与
// `--nerd-font` 由用户明确指定。
package theme

import (
	"image/color"

	"charm.land/lipgloss/v2"
)

// Name 是主题名。
type Name string

const (
	// Dark 是默认深色主题。
	Dark Name = "dark"
	// Light 是浅色主题。
	Light Name = "light"
	// ANSI16 是 16 色兼容主题。
	ANSI16 Name = "ansi16"
)

// Tier 是字形档位。
type Tier int

const (
	// TierASCII 只用 ASCII：`> - OK X # .`，任何字体都不会出豆腐块。
	TierASCII Tier = iota
	// TierUnicode 用常见几何符号（`▸ ▪ ✓ █ ░`），绝大多数等宽字体都有。
	TierUnicode
	// TierNerd 在 Unicode 之上叠加 Nerd Font 图标（Font Awesome 私有区）。
	TierNerd
)

// String 返回档位名。
func (t Tier) String() string {
	switch t {
	case TierASCII:
		return "ascii"
	case TierNerd:
		return "nerd"
	default:
		return "unicode"
	}
}

// ParseTier 解析档位名；未知值回退 Unicode。
func ParseTier(text string) Tier {
	switch text {
	case "ascii":
		return TierASCII
	case "nerd", "nerd-font", "nerdfont":
		return TierNerd
	default:
		return TierUnicode
	}
}

// TierFrom 由两个开关推出档位。`ascii` 优先：显式要求纯 ASCII 时不上图标。
func TierFrom(ascii, nerd bool) Tier {
	if ascii {
		return TierASCII
	}
	if nerd {
		return TierNerd
	}
	return TierUnicode
}

// Icons 是 Nerd Font 图标集。
//
// 非 Nerd 档位下全部为空串，渲染层据此退回纯文字标题——
// 图标是增强而非必需，缺了只是少一层装饰。
type Icons struct {
	Dashboard string
	Timetable string
	Scores    string
	Exams     string
	Plan      string
	Notices   string
	Profile   string
	Courses   string
	Doctor    string
	Settings  string
	AI        string

	Search   string
	Chevron  string
	Warning  string
	Info     string
	Spark    string
	Clock    string
	MapPin   string
	Teacher  string
	Star     string
	Trend    string
	Calendar string
	Check    string
	Cross    string
}

// nerdIcons 是 Font Awesome 4 码位（Nerd Fonts 固定收录）。
func nerdIcons() Icons {
	return Icons{
		Dashboard: "\uf0e4", // tachometer
		Timetable: "\uf073", // calendar
		Scores:    "\uf080", // bar-chart
		Exams:     "\uf017", // clock-o
		Plan:      "\uf19d", // graduation-cap
		Notices:   "\uf0f3", // bell
		Profile:   "\uf007", // user
		Courses:   "\uf02d", // book
		Doctor:    "\uf21e", // heartbeat
		Settings:  "\uf013", // cog
		AI:        "\uf0d0", // magic

		Search:   "\uf002", // search
		Chevron:  "\uf054", // chevron-right
		Warning:  "\uf071", // exclamation-triangle
		Info:     "\uf05a", // info-circle
		Spark:    "\uf1e0", // share-alt（用作趋势）
		Clock:    "\uf017",
		MapPin:   "\uf041", // map-marker
		Teacher:  "\uf0f0", // user-md
		Star:     "\uf005", // star
		Trend:    "\uf201", // line-chart
		Calendar: "\uf073",
		Check:    "\uf00c",
		Cross:    "\uf00d",
	}
}

// emptyIcons 表示该档位不使用图标。
func emptyIcons() Icons { return Icons{} }

// Glyphs 是与编码/字体相关的图形字符。
type Glyphs struct {
	// Border 是面板边框。
	Border lipgloss.Border
	// Selected 标记当前选中项。
	Selected string
	// Bullet 是列表项目符号。
	Bullet string
	// Check 表示成功。
	Check string
	// Cross 表示失败。
	Cross string
	// Arrow 表示"进入下一层"。
	Arrow string
	// Ellipsis 是截断标记。
	Ellipsis string
	// BarFull / BarEmpty 是进度条填充字符。
	BarFull  string
	BarEmpty string
	// Divider 是分隔线字符。
	Divider string
	// Section 是区块标题前缀。
	Section string
	// Cursor 是输入光标。
	Cursor string
	// Prompt 是输入提示符。
	Prompt string
	// Spark 是迷你折线图的八个高度档。
	Spark [8]string
	// Focus 标记当前获得焦点的面板。
	Focus string
	// Icons 是 Nerd Font 图标（其它档位为空）。
	Icons Icons
}

// unicodeGlyphs 是默认字形。
func unicodeGlyphs() Glyphs {
	return Glyphs{
		Border:   lipgloss.RoundedBorder(),
		Selected: "▸",
		Bullet:   "·",
		Check:    "✓",
		Cross:    "✗",
		Arrow:    "→",
		Ellipsis: "…",
		BarFull:  "█",
		BarEmpty: "░",
		Divider:  "─",
		Section:  "▎",
		Cursor:   "▏",
		Prompt:   "›",
		Spark:    [8]string{"▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"},
		Focus:    "●",
		Icons:    emptyIcons(),
	}
}

// asciiBorder 是真正只用 ASCII 的边框。
//
// 注意：lipgloss.NormalBorder() 用的是 `─│┌┐└┘`，那是 Unicode 制表符，
// 不是 ASCII。真正连制表符都不支持的终端上必须用 `- | +`。
func asciiBorder() lipgloss.Border {
	return lipgloss.Border{
		Top:          "-",
		Bottom:       "-",
		Left:         "|",
		Right:        "|",
		TopLeft:      "+",
		TopRight:     "+",
		BottomLeft:   "+",
		BottomRight:  "+",
		MiddleLeft:   "+",
		MiddleRight:  "+",
		Middle:       "+",
		MiddleTop:    "+",
		MiddleBottom: "+",
	}
}

// asciiGlyphs 是完全 ASCII 的字形，供 `--ascii` 与严格的 7 位终端使用。
func asciiGlyphs() Glyphs {
	return Glyphs{
		Border:   asciiBorder(),
		Selected: ">",
		Bullet:   "-",
		Check:    "OK",
		Cross:    "X",
		Arrow:    ">",
		Ellipsis: "..",
		BarFull:  "#",
		BarEmpty: ".",
		Divider:  "-",
		Section:  "|",
		Cursor:   "|",
		Prompt:   ">",
		Spark:    [8]string{".", ":", "-", "=", "+", "*", "%", "#"},
		Focus:    "*",
		Icons:    emptyIcons(),
	}
}

// nerdGlyphs 在 Unicode 基础上加图标与更精细的进度条。
func nerdGlyphs() Glyphs {
	glyphs := unicodeGlyphs()
	glyphs.Icons = nerdIcons()
	glyphs.Selected = "\uf0da" // caret-right
	glyphs.Focus = "\uf111"    // circle
	glyphs.Spark = [8]string{"▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"}
	return glyphs
}

// themeGlyphs 按档位返回字形。
func themeGlyphs(tier Tier) Glyphs {
	switch tier {
	case TierASCII:
		return asciiGlyphs()
	case TierNerd:
		return nerdGlyphs()
	default:
		return unicodeGlyphs()
	}
}

// Theme 是一套配色 + 字形。
type Theme struct {
	Name   Name
	Tier   Tier
	Glyphs Glyphs
	// Dark 表示是否为深色背景（决定是否需要更高对比度）。
	Dark bool
	// ANSIOnly 表示只使用 16 色。
	ANSIOnly bool
	// Plain 为真时不输出任何 ANSI 属性（含加粗），用于 NO_COLOR 场景。
	Plain bool

	Primary color.Color
	Accent  color.Color
	Text    color.Color
	Muted   color.Color
	Subtle  color.Color
	Border  color.Color
	Surface color.Color
	Success color.Color
	Warning color.Color
	Danger  color.Color
	Info    color.Color
}

// New 按名称与字形档位返回主题；未知名称回退深色。
func New(name Name, tier Tier) Theme {
	var theme Theme
	switch name {
	case Light:
		theme = lightTheme()
	case ANSI16:
		theme = ansi16Theme()
	default:
		theme = darkTheme()
	}
	theme.Tier = tier
	theme.Glyphs = themeGlyphs(tier)
	return theme
}

// darkTheme 是东京夜配色，对比度足够又不过分刺眼。
func darkTheme() Theme {
	return Theme{
		Name:    Dark,
		Glyphs:  unicodeGlyphs(),
		Dark:    true,
		Primary: lipgloss.Color("#7aa2f7"),
		Accent:  lipgloss.Color("#bb9af7"),
		Text:    lipgloss.Color("#c0caf5"),
		Muted:   lipgloss.Color("#9aa5ce"),
		Subtle:  lipgloss.Color("#565f89"),
		Border:  lipgloss.Color("#3b4261"),
		Surface: lipgloss.Color("#1f2335"),
		Success: lipgloss.Color("#9ece6a"),
		Warning: lipgloss.Color("#e0af68"),
		Danger:  lipgloss.Color("#f7768e"),
		Info:    lipgloss.Color("#7dcfff"),
	}
}

// lightTheme 面向浅色背景终端。
func lightTheme() Theme {
	return Theme{
		Name:    Light,
		Glyphs:  unicodeGlyphs(),
		Primary: lipgloss.Color("#3b5bdb"),
		Accent:  lipgloss.Color("#7048e8"),
		Text:    lipgloss.Color("#1f2933"),
		Muted:   lipgloss.Color("#4b5563"),
		Subtle:  lipgloss.Color("#9ca3af"),
		Border:  lipgloss.Color("#cbd5e1"),
		Surface: lipgloss.Color("#f1f3f5"),
		Success: lipgloss.Color("#2f9e44"),
		Warning: lipgloss.Color("#e8590c"),
		Danger:  lipgloss.Color("#c92a2a"),
		Info:    lipgloss.Color("#1971c2"),
	}
}

// ansi16Theme 只用 0–15 号 ANSI 色。
func ansi16Theme() Theme {
	return Theme{
		Name:     ANSI16,
		Glyphs:   unicodeGlyphs(),
		ANSIOnly: true,
		Primary:  lipgloss.Color("4"),
		Accent:   lipgloss.Color("5"),
		Text:     lipgloss.Color("7"),
		Muted:    lipgloss.Color("8"),
		Subtle:   lipgloss.Color("8"),
		Border:   lipgloss.Color("8"),
		Surface:  nil,
		Success:  lipgloss.Color("2"),
		Warning:  lipgloss.Color("3"),
		Danger:   lipgloss.Color("1"),
		Info:     lipgloss.Color("6"),
	}
}

// Plain 返回无色主题，供 `NO_COLOR` / `TERM=dumb` 使用。
//
// 颜色字段全部为 nil，且**不加粗**：`NO_COLOR` 的语义是"不要任何装饰"，
// 只去掉颜色而保留 `\x1b[1m` 在某些日志里仍然是噪声。
func Plain() Theme {
	theme := ansi16Theme()
	theme.Name = "plain"
	theme.ANSIOnly = true
	theme.Plain = true
	theme.Tier = TierASCII
	theme.Glyphs = asciiGlyphs()
	theme.Primary = nil
	theme.Accent = nil
	theme.Text = nil
	theme.Muted = nil
	theme.Subtle = nil
	theme.Border = nil
	theme.Surface = nil
	theme.Success = nil
	theme.Warning = nil
	theme.Danger = nil
	theme.Info = nil
	return theme
}

// Title 是页面标题样式。
func (t Theme) Title() lipgloss.Style {
	return t.decorate(lipgloss.NewStyle().Foreground(t.Primary))
}

// Heading 是区块标题样式。
func (t Theme) Heading() lipgloss.Style {
	return t.decorate(lipgloss.NewStyle().Foreground(t.Accent))
}

// decorate 在非 Plain 主题下加上加粗。
func (t Theme) decorate(style lipgloss.Style) lipgloss.Style {
	if t.Plain {
		return style
	}
	return style.Bold(true)
}

// Body 是正文样式。
func (t Theme) Body() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Text)
}

// MutedStyle 是次要文字样式。
func (t Theme) MutedStyle() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Muted)
}

// SubtleStyle 是更弱的提示文字样式。
func (t Theme) SubtleStyle() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Subtle)
}

// Label 是字段名样式。
func (t Theme) Label() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Muted)
}

// Value 是字段值样式。
func (t Theme) Value() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Text)
}

// Panel 是带边框的面板样式。
func (t Theme) Panel() lipgloss.Style {
	return lipgloss.NewStyle().
		Border(t.Glyphs.Border).
		BorderForeground(t.Border)
}

// PanelTitle 是面板标题样式。
func (t Theme) PanelTitle() lipgloss.Style {
	return t.decorate(lipgloss.NewStyle().Foreground(t.Primary))
}

// SelectedRow 是选中行样式。
func (t Theme) SelectedRow() lipgloss.Style {
	return t.decorate(lipgloss.NewStyle().Foreground(t.Primary))
}

// DimRow 是未选中但可读的列表行样式。
func (t Theme) DimRow() lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.Muted)
}

// LevelColor 按语义级别返回颜色。
func (t Theme) LevelColor(level string) color.Color {
	switch level {
	case "ok", "success":
		return t.Success
	case "warn", "warning":
		return t.Warning
	case "fail", "danger", "error":
		return t.Danger
	case "info":
		return t.Info
	case "muted":
		return t.Subtle
	default:
		return t.Text
	}
}

// LevelColorStyle 按语义级别返回文字样式。
func (t Theme) LevelColorStyle(level string) lipgloss.Style {
	return lipgloss.NewStyle().Foreground(t.LevelColor(level))
}

// Icon 返回图标符号；该档位没有图标时返回空串。
//
// 调用方应当把空串当成"没有图标"，不要补空格——补了会让纯文字档位
// 的标题整体右移一格。
func (t Theme) Icon(icon string) string { return icon }

// HasIcons 表示当前档位是否会渲染图标。
func (t Theme) HasIcons() bool { return t.Tier == TierNerd }
