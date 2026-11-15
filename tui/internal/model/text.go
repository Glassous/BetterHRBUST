package model

import "strings"

// trimSpace 是 strings.TrimSpace 的薄封装，集中出口便于单测覆盖不可见字符。
func trimSpace(raw string) string {
	return strings.TrimSpace(raw)
}
