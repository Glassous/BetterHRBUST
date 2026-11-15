package campus

import (
	"regexp"
	"strconv"
	"strings"
)

// 与 `web/src/services/academic/parser.js` 的 decodeEntities 逐条对应。
//
// 教务系统部分页面存在双重编码（源码是 `&amp;lt;`，取文本后得到 `&lt;`），
// 因此这里也循环解码直到结果稳定（最多 3 轮）。

// namedEntities 是常见命名实体映射（键为小写）。
var namedEntities = map[string]string{
	"amp": "&", "lt": "<", "gt": ">", "quot": `"`, "apos": "'", "nbsp": " ",
	"copy": "©", "reg": "®", "trade": "™", "hellip": "…",
	"mdash": "—", "ndash": "–", "middot": "·", "deg": "°",
	"laquo": "«", "raquo": "»",
	"ldquo": "“", "rdquo": "”", "lsquo": "‘", "rsquo": "’",
}

// entityPattern 匹配 `&#xNN;` / `&#NN;` / `&name;`。
var entityPattern = regexp.MustCompile(`&(?:#x([0-9a-fA-F]+)|#([0-9]+)|([a-zA-Z][a-zA-Z0-9]*));`)

// decodeEntities 解码 HTML 实体，最多 3 轮，直到不再变化。
func decodeEntities(text string) string {
	if !strings.Contains(text, "&") {
		return text
	}
	out := text
	for round := 0; round < 3; round++ {
		next := entityPattern.ReplaceAllStringFunc(out, func(raw string) string {
			match := entityPattern.FindStringSubmatch(raw)
			if match == nil {
				return raw
			}
			if match[1] != "" {
				if code, err := strconv.ParseInt(match[1], 16, 32); err == nil {
					return fromCodePoint(code, raw)
				}
				return raw
			}
			if match[2] != "" {
				if code, err := strconv.ParseInt(match[2], 10, 32); err == nil {
					return fromCodePoint(code, raw)
				}
				return raw
			}
			if mapped, ok := namedEntities[strings.ToLower(match[3])]; ok {
				return mapped
			}
			return raw
		})
		if next == out {
			break
		}
		out = next
	}
	return out
}

// fromCodePoint 把码点转字符，非法码点返回原文。
func fromCodePoint(code int64, fallback string) string {
	if code <= 0 || code > 0x10ffff {
		return fallback
	}
	return string(rune(code))
}

// stripHTMLComments 剥离 HTML 注释，防止注释内的单元格导致表格列错位。
func stripHTMLComments(source string) string {
	return commentPattern.ReplaceAllString(source, "")
}

// commentPattern 匹配 HTML 注释。
var commentPattern = regexp.MustCompile(`(?s)<!--.*?-->`)
