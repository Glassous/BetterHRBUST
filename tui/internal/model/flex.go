package model

import "encoding/json"

// 本文件把 Rust 命令行的 `de_string / de_f64 / de_u32 / de_bool / de_opt_f64 / de_u64`
// 逐一移植到 Go。这些函数刻意**不对称**：
//
//   - 字符串字段接受数字与布尔（数字按其 JSON 原文，"90" 仍是 "90"）；
//   - 浮点字段接受字符串与布尔（"3.5" → 3.5，true → 1）；
//   - 整数字段**不接受布尔**（Rust 侧 `de_u32` 没有 bool 分支）；
//   - 布尔字段只把 "true" / "1" 当作真。
//
// 这种不对称是历史行为，缓存里已经存在按此规则写入的数据，
// 因此这里照抄而不"顺手统一"。

// asMap 把任意值当作对象；不是对象时返回 nil。
func asMap(value any) map[string]any {
	if object, ok := value.(map[string]any); ok {
		return object
	}
	return nil
}

// text 等价于 Rust 的 `de_string`。
func text(object map[string]any, key string) string {
	switch value := object[key].(type) {
	case nil:
		return ""
	case string:
		return value
	case bool:
		if value {
			return "true"
		}
		return "false"
	case json.Number:
		// 数字保持 JSON 原文：90 仍是 "90"，92.5 仍是 "92.5"。
		return value.String()
	default:
		return ""
	}
}

// number 等价于 Rust 的 `de_f64`。
func number(object map[string]any, key string) float64 {
	switch value := object[key].(type) {
	case json.Number:
		parsed, err := value.Float64()
		if err != nil {
			return 0
		}
		return parsed
	case string:
		return parseFloatOrZero(value)
	case bool:
		if value {
			return 1
		}
		return 0
	default:
		return 0
	}
}

// optNumber 等价于 Rust 的 `de_opt_f64`：无法解析时区分"没有"而不是"零"。
func optNumber(object map[string]any, key string) *float64 {
	switch value := object[key].(type) {
	case json.Number:
		parsed, err := value.Float64()
		if err != nil {
			return nil
		}
		return &parsed
	case string:
		parsed, ok := parseFloat(value)
		if !ok {
			return nil
		}
		return &parsed
	default:
		return nil
	}
}

// count 等价于 Rust 的 `de_u32`：只收数字与字符串，不收布尔。
func count(object map[string]any, key string) int {
	switch value := object[key].(type) {
	case json.Number:
		parsed, err := value.Int64()
		if err != nil {
			// JSON 里的 3.0 是浮点，serde 的 as_u64 同样返回 None。
			if asFloat, floatErr := value.Float64(); floatErr == nil && asFloat == float64(int64(asFloat)) {
				return int(asFloat)
			}
			return 0
		}
		if parsed < 0 {
			return 0
		}
		return int(min(parsed, int64(^uint32(0))))
	case string:
		parsed, ok := parseFloat(value)
		if !ok || parsed < 0 {
			return 0
		}
		return int(parsed)
	default:
		return 0
	}
}

// flag 等价于 Rust 的 `de_bool`。
func flag(object map[string]any, key string) bool {
	switch value := object[key].(type) {
	case bool:
		return value
	case json.Number:
		parsed, err := value.Float64()
		return err == nil && parsed != 0
	case string:
		return value == "true" || value == "1"
	default:
		return false
	}
}

// stamp 等价于 Rust 的 `de_u64`，用于毫秒时间戳。
func stamp(object map[string]any, key string) uint64 {
	switch value := object[key].(type) {
	case json.Number:
		parsed, err := value.Int64()
		if err != nil || parsed < 0 {
			return 0
		}
		return uint64(parsed)
	case string:
		parsed, ok := parseFloat(value)
		if !ok || parsed < 0 {
			return 0
		}
		return uint64(parsed)
	default:
		return 0
	}
}

// texts 读取字符串数组；非字符串元素按 text 规则转换后保留。
func texts(object map[string]any, key string) []string {
	list, ok := object[key].([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(list))
	for _, item := range list {
		out = append(out, text(map[string]any{"v": item}, "v"))
	}
	return out
}

// objects 读取对象数组，跳过非对象元素。
func objects(object map[string]any, key string) []map[string]any {
	list, ok := object[key].([]any)
	if !ok {
		return nil
	}
	out := make([]map[string]any, 0, len(list))
	for _, item := range list {
		if child := asMap(item); child != nil {
			out = append(out, child)
		}
	}
	return out
}
