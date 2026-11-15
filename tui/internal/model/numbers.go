package model

import "strconv"

// parseFloat 解析缓存里的数字文本，允许前后空白。
func parseFloat(raw string) (float64, bool) {
	value, err := strconv.ParseFloat(trimSpace(raw), 64)
	if err != nil {
		return 0, false
	}
	return value, true
}

// parseFloatOrZero 与 parseFloat 相同，但失败时返回 0（对应 Rust 的 `unwrap_or(0.0)`）。
func parseFloatOrZero(raw string) float64 {
	value, _ := parseFloat(raw)
	return value
}
