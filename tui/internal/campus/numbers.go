package campus

import (
	"regexp"
	"strconv"
	"strings"
)

// 前端解析器里的数值来自 `parseFloat` / `parseInt`，
// 教务页面经常把数字和中文写在一起（"3.5学分"、"16学时"）。
// Go 的 strconv 要求整串都是数字，因此这里按 JS 语义取前缀。

// floatPrefixPattern 匹配 JS parseFloat 接受的数字前缀。
var floatPrefixPattern = regexp.MustCompile(`^[\s]*[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?`)

// intPrefixPattern 匹配 JS parseInt(…, 10) 接受的数字前缀。
var intPrefixPattern = regexp.MustCompile(`^\s*[+-]?\d+`)

// jsParseFloat 等价于 JS 的 parseFloat，无法解析时返回 0。
func jsParseFloat(text string) float64 {
	value, _ := jsParseFloatOK(text)
	return value
}

// jsParseFloatOK 返回解析结果与是否成功（对应 NaN 判定）。
func jsParseFloatOK(text string) (float64, bool) {
	match := floatPrefixPattern.FindString(text)
	if match == "" {
		return 0, false
	}
	value, err := strconv.ParseFloat(strings.TrimSpace(match), 64)
	if err != nil {
		return 0, false
	}
	return value, true
}

// jsParseInt 等价于 JS 的 parseInt(text, 10)，无法解析时返回 0。
func jsParseInt(text string) int {
	value, _ := jsParseIntOK(text)
	return value
}

// jsParseIntOK 返回解析结果与是否成功。
func jsParseIntOK(text string) (int, bool) {
	match := intPrefixPattern.FindString(text)
	if match == "" {
		return 0, false
	}
	value, err := strconv.Atoi(strings.TrimSpace(match))
	if err != nil {
		return 0, false
	}
	return value, true
}

// truncateRunes 截取前 n 个字符（对应 JS 的 slice(0, n)）。
func truncateRunes(text string, limit int) string {
	runes := []rune(text)
	if len(runes) <= limit {
		return text
	}
	return string(runes[:limit])
}

// SemesterInfo 是校历学期的最小投影。
//
// 单独定义而不是直接引用 `internal/academic`：`campus` 只做网络与解析，
// 校历语义由调用方（cmd / UI）注入，测试里可以直接给固定学期。
type SemesterInfo struct {
	Label         string
	TeachingWeeks int
}
