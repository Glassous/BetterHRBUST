package academic

import (
	"fmt"
	"strings"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// 本文件是展示层的文本工具：显示宽度、截断、打码、时间格式化。
//
// 为什么不用 lipgloss.Width：`internal/query` 要产出与 Rust 命令行
// 逐字一致的文本字段（学籍打码后截断到 40 列），这里必须与 Rust 的
// `render::truncate` 用同一套算法。UI 层自己用 Lip Gloss 排版。

// CharWidth 返回单个字符占用的终端列数。
func CharWidth(r rune) int {
	code := int(r)
	switch {
	case code == 0,
		code == 0x200B, code == 0x200C, code == 0x200D, code == 0xFEFF,
		code >= 0x0300 && code <= 0x036F,
		code >= 0xFE00 && code <= 0xFE0F:
		return 0
	}
	wide := (code >= 0x1100 && code <= 0x115F) ||
		(code >= 0x2E80 && code <= 0x303E) ||
		(code >= 0x3041 && code <= 0x33FF) ||
		(code >= 0x3400 && code <= 0x4DBF) ||
		(code >= 0x4E00 && code <= 0x9FFF) ||
		(code >= 0xA000 && code <= 0xA4CF) ||
		(code >= 0xA960 && code <= 0xA97F) ||
		(code >= 0xAC00 && code <= 0xD7A3) ||
		(code >= 0xF900 && code <= 0xFAFF) ||
		(code >= 0xFE10 && code <= 0xFE19) ||
		(code >= 0xFE30 && code <= 0xFE6F) ||
		(code >= 0xFF00 && code <= 0xFF60) ||
		(code >= 0xFFE0 && code <= 0xFFE6) ||
		(code >= 0x1F300 && code <= 0x1F64F) ||
		(code >= 0x1F900 && code <= 0x1F9FF) ||
		(code >= 0x20000 && code <= 0x2FFFD) ||
		(code >= 0x30000 && code <= 0x3FFFD)
	if wide {
		return 2
	}
	return 1
}

// DisplayWidth 返回字符串在终端中的显示宽度。
func DisplayWidth(text string) int {
	width := 0
	for _, r := range text {
		width += CharWidth(r)
	}
	return width
}

// Truncate 按显示宽度截断，超出部分以 `..` 收尾。
//
// 不用省略号：`…` 是「歧义宽度」字符，部分终端算 1 列、部分算 2 列，
// 会让后续列错位。
func Truncate(text string, maxWidth int) string {
	if DisplayWidth(text) <= maxWidth {
		return text
	}
	if maxWidth <= 2 {
		return strings.Repeat(".", maxWidth)
	}
	var builder strings.Builder
	width := 0
	for _, r := range text {
		w := CharWidth(r)
		if width+w > maxWidth-2 {
			break
		}
		builder.WriteRune(r)
		width += w
	}
	builder.WriteString("..")
	return builder.String()
}

// CollapseWhitespace 把连续空白折叠成单个空格，并去掉首尾空白。
func CollapseWhitespace(text string) string {
	return strings.Join(strings.Fields(text), " ")
}

// MaskMiddle 保留头尾若干字符，中间以 `*` 填充。
//
// 证件号、手机号不应完整落屏：截图、日志、AI 上下文都会外泄。
func MaskMiddle(text string, keepHead, keepTail int) string {
	runes := []rune(text)
	if len(runes) <= keepHead+keepTail {
		return strings.Repeat("*", len(runes))
	}
	var builder strings.Builder
	builder.WriteString(string(runes[:keepHead]))
	builder.WriteString(strings.Repeat("*", len(runes)-keepHead-keepTail))
	if keepTail > 0 {
		builder.WriteString(string(runes[len(runes)-keepTail:]))
	}
	return builder.String()
}

// MaskedField 折叠空白 → 打码 → 截断到 40 列。空值返回空串。
func MaskedField(text string, keepHead, keepTail int) string {
	clean := CollapseWhitespace(text)
	if clean == "" {
		return ""
	}
	return Truncate(MaskMiddle(clean, keepHead, keepTail), 40)
}

// PlainField 折叠空白 → 截断到 40 列。空值返回「未记录」。
func PlainField(text string) string {
	clean := CollapseWhitespace(text)
	if clean == "" {
		return "未记录"
	}
	return Truncate(clean, 40)
}

// MaskedProfileFields 描述学籍档案里哪些字段需要打码、保留几位。
var MaskedProfileFields = []struct {
	Field    string
	KeepHead int
	KeepTail int
}{
	{"idCard", 4, 2},
	{"phone", 3, 2},
	{"email", 2, 0},
	{"address", 6, 0},
}

// MaskProfile 返回打码后的学籍档案副本，并折叠全部文本字段的空白。
//
// 这是**唯一**允许把学籍交给外部（LLM、剪贴板、日志）的入口：
// `internal/query` 的 profile 命令与 `internal/llm` 的工具都只使用它的返回值。
func MaskProfile(profile model.Profile) model.Profile {
	masked := profile
	masked.IDCard = MaskedField(profile.IDCard, 4, 2)
	masked.Phone = MaskedField(profile.Phone, 3, 2)
	masked.Email = MaskedField(profile.Email, 2, 0)
	masked.Address = MaskedField(profile.Address, 6, 0)

	masked.RealName = CollapseWhitespace(profile.RealName)
	masked.StudentNumber = CollapseWhitespace(profile.StudentNumber)
	masked.College = CollapseWhitespace(profile.College)
	masked.Major = CollapseWhitespace(profile.Major)
	masked.Direction = CollapseWhitespace(profile.Direction)
	masked.Grade = CollapseWhitespace(profile.Grade)
	masked.ClassName = CollapseWhitespace(profile.ClassName)
	masked.StudentType = CollapseWhitespace(profile.StudentType)
	masked.Status = CollapseWhitespace(profile.Status)
	masked.PostalCode = CollapseWhitespace(profile.PostalCode)
	// 照片地址与内部 ID 与展示无关，交出去只会增加暴露面。
	masked.PhotoURL = ""
	masked.InternalID = ""
	return masked
}

// FormatTimestamp 把毫秒时间戳格式化为 `2006-01-02 15:04`。
//
// 用显式 loc 而不是 time.Local：测试与 AI 工具都要求可复现。
func FormatTimestamp(milliseconds int64, loc *time.Location) string {
	if milliseconds <= 0 {
		return ""
	}
	instant := time.UnixMilli(milliseconds).In(loc)
	return instant.Format("2006-01-02 15:04")
}

// Now 返回当前日期与当天已过分钟数。整个 academic 包只有这里读系统时钟，
// 调用方（`internal/query`）把它作为可注入的依赖暴露出去。
func Now() (Date, int) {
	instant := time.Now()
	return Date{Year: instant.Year(), Month: int(instant.Month()), Day: instant.Day()},
		instant.Hour()*60 + instant.Minute()
}

// FormatCredits 把学分格式化为「3」「3.5」这类紧凑文本。
func FormatCredits(value float64) string {
	return formatNumber(value)
}

// FormatScore 返回成绩原文；空字符串显示为「未出分」。
func FormatScore(score string) string {
	text := strings.TrimSpace(score)
	if text == "" {
		return "未出分"
	}
	return text
}

// FormatGPA 把绩点格式化为两位小数，供表格对齐使用。
func FormatGPA(value float64) string {
	return fmt.Sprintf("%.2f", value)
}

// WeekLabel 返回「第 6 周」这类标签。
func WeekLabel(week int) string {
	if week <= 0 {
		return "未知周"
	}
	return fmt.Sprintf("第 %d 周", week)
}
