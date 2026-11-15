// Package parity 读取 `shared/parity/*.json` 里的跨端黄金向量。
//
// 这里的 JSON 是**语言中立**的：Web（JS）、桌面端（Rust）与终端界面（Go）
// 都读同一批文件，断言同一组数值。任何一个端改了算法而没同步期望值，
// 它自己的测试就会红。
//
// 文件结构：
//
//	{
//	  "schemaVersion": 1,
//	  "id": "academic/degree-electives",
//	  "kind": "academicStats",
//	  "description": "……",
//	  "reference": ["web/src/services/academic/stats.js"],
//	  "cases": [
//	    {
//	      "name": "……",
//	      "clock": { "today": "2026-10-10", "minuteOfDay": 600 },
//	      "input": { ... },
//	      "expect": [ { "path": "degree.gpa", "equals": 3.07 } ],
//	      "forbid": ["110101199001011234"]
//	    }
//	  ]
//	}
//
// `kind` 决定 `input` 怎么被消费；未知 kind 必须被跳过而不是失败，
// 这样某个端可以先于其他端接入新的用例文件。
package parity

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"strings"
)

// SchemaVersion 是当前支持的用例文件版本。
const SchemaVersion = 1

// Clock 描述用例的时间基准。为零值时使用系统当前时间。
type Clock struct {
	Today            string `json:"today"`
	MinuteOfDay      *int   `json:"minuteOfDay"`
	UTCOffsetMinutes int    `json:"utcOffsetMinutes"`
}

// Expect 是一条断言。
//
// 五种断言互斥，按以下顺序生效：Absent・Null・Equals・Approx・Len・Contains。
type Expect struct {
	Path     string   `json:"path"`
	Equals   any      `json:"equals"`
	Approx   *float64 `json:"approx"`
	Len      *int     `json:"len"`
	Contains string   `json:"contains"`
	Absent   bool     `json:"absent"`
	Null     bool     `json:"null"`
}

// Case 是一条用例。
type Case struct {
	Name   string          `json:"name"`
	Clock  Clock           `json:"clock"`
	Input  json.RawMessage `json:"input"`
	Expect []Expect        `json:"expect"`
	// Forbid 里的字符串一旦出现在结果的 JSON 序列化文本中即判失败，用于隐私断言。
	Forbid []string `json:"forbid"`
}

// File 是一个用例文件。
type File struct {
	SchemaVersion int      `json:"schemaVersion"`
	ID            string   `json:"id"`
	Kind          string   `json:"kind"`
	Description   string   `json:"description"`
	Reference     []string `json:"reference"`
	Cases         []Case   `json:"cases"`
}

// AllKinds 返回当前已定义的全部 kind。
//
// 测试会断言：语料里出现的每个 kind 都能被某个处理器认领，
// 避免拼错 kind 让整个文件被静默跳过。
func AllKinds() []string {
	return []string{
		"academicStats",
		"degreeCourses",
		"dedupe",
		"creditsProgress",
		"courseWeeks",
		"teachingWeek",
		"dayDate",
		"slotTimes",
		"examTime",
		"cellFields",
		"readiness",
		"query",
		"mask",
	}
}

// Dir 返回 `shared/parity` 的绝对路径。
//
// 从包目录向上找到含 `shared/parity` 的目录，因此不受测试工作目录影响。
func Dir() (string, error) {
	current, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for depth := 0; depth < 8; depth++ {
		candidate := filepath.Join(current, "shared", "parity")
		if info, statErr := os.Stat(candidate); statErr == nil && info.IsDir() {
			return candidate, nil
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return "", fmt.Errorf("找不到 shared/parity 目录")
}

// Load 读入全部用例文件，按 id 排序。
func Load() ([]File, error) {
	dir, err := Dir()
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	files := make([]File, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		content, readErr := os.ReadFile(filepath.Join(dir, entry.Name()))
		if readErr != nil {
			return nil, fmt.Errorf("%s: %w", entry.Name(), readErr)
		}
		var file File
		if decodeErr := json.Unmarshal(content, &file); decodeErr != nil {
			return nil, fmt.Errorf("%s: %w", entry.Name(), decodeErr)
		}
		if file.SchemaVersion != SchemaVersion {
			return nil, fmt.Errorf("%s: schemaVersion=%d，期望 %d",
				entry.Name(), file.SchemaVersion, SchemaVersion)
		}
		if file.Kind == "" {
			return nil, fmt.Errorf("%s: 缺少 kind", entry.Name())
		}
		if file.ID == "" {
			file.ID = strings.TrimSuffix(entry.Name(), ".json")
		}
		files = append(files, file)
	}
	sort.Slice(files, func(left, right int) bool { return files[left].ID < files[right].ID })
	return files, nil
}

// DecodeInput 把用例输入解码成通用结构，数字统一为 json.Number。
func DecodeInput(raw json.RawMessage) (any, error) {
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	return value, nil
}

// Normalize 把任意值转成可比较的形式：json.Number → float64，
// 其余保持原样，便于 direct 比较与深比较。
func Normalize(value any) any {
	switch typed := value.(type) {
	case json.Number:
		asFloat, err := typed.Float64()
		if err != nil {
			return typed.String()
		}
		return asFloat
	case map[string]any:
		out := make(map[string]any, len(typed))
		for key, item := range typed {
			out[key] = Normalize(item)
		}
		return out
	case []any:
		out := make([]any, len(typed))
		for index, item := range typed {
			out[index] = Normalize(item)
		}
		return out
	default:
		return value
	}
}

// Resolve 按 `a.b[0].c` 形式在结构里取值。
//
// 返回的 ok 为 false 表示路径不存在（而不是值为 nil）。
func Resolve(root any, path string) (any, bool) {
	if path == "" || path == "." {
		return root, true
	}
	current := root
	for _, segment := range strings.Split(path, ".") {
		name, indices, err := parseSegment(segment)
		if err != nil {
			return nil, false
		}
		if name != "" {
			object, ok := current.(map[string]any)
			if !ok {
				return nil, false
			}
			value, exists := object[name]
			if !exists {
				return nil, false
			}
			current = value
		}
		for _, index := range indices {
			list, ok := current.([]any)
			if !ok || index < 0 || index >= len(list) {
				return nil, false
			}
			current = list[index]
		}
	}
	return current, true
}

// parseSegment 把 `items[0][2]` 拆成 ("items", [0,2])。
func parseSegment(segment string) (string, []int, error) {
	name := segment
	var indices []int
	if open := strings.IndexByte(segment, '['); open >= 0 {
		name = segment[:open]
		rest := segment[open:]
		for rest != "" {
			if rest[0] != '[' {
				return "", nil, fmt.Errorf("路径片段非法：%s", segment)
			}
			close := strings.IndexByte(rest, ']')
			if close < 0 {
				return "", nil, fmt.Errorf("路径片段缺少 ]：%s", segment)
			}
			index, err := strconv.Atoi(rest[1:close])
			if err != nil {
				return "", nil, fmt.Errorf("路径下标不是数字：%s", segment)
			}
			indices = append(indices, index)
			rest = rest[close+1:]
		}
	}
	return name, indices, nil
}

// Check 校验一条断言；返回 nil 表示通过。
func Check(expect Expect, actual any, present bool) error {
	switch {
	case expect.Absent:
		if present {
			return fmt.Errorf("期望字段不存在，实际为 %s", Describe(actual))
		}
		return nil
	case expect.Null:
		if !present || actual != nil {
			return fmt.Errorf("期望 null，实际为 %s", Describe(actual))
		}
		return nil
	}

	if !present {
		return fmt.Errorf("字段缺失（期望 %s）", describeExpect(expect))
	}

	if expect.Len != nil {
		length, ok := lengthOf(actual)
		if !ok {
			return fmt.Errorf("期望长度为 %d，但值不是数组或对象：%s", *expect.Len, Describe(actual))
		}
		if length != *expect.Len {
			return fmt.Errorf("期望长度为 %d，实际 %d", *expect.Len, length)
		}
		return nil
	}

	if expect.Approx != nil {
		number, ok := toFloat(actual)
		if !ok {
			return fmt.Errorf("期望约等于 %v，但值不是数字：%s", *expect.Approx, Describe(actual))
		}
		if math.Abs(number-*expect.Approx) > 1e-9 {
			return fmt.Errorf("期望约等于 %v，实际 %v", *expect.Approx, number)
		}
		return nil
	}

	if expect.Contains != "" {
		text, ok := actual.(string)
		if !ok {
			return fmt.Errorf("期望包含 %q，但值不是字符串：%s", expect.Contains, Describe(actual))
		}
		if !strings.Contains(text, expect.Contains) {
			return fmt.Errorf("期望包含 %q，实际 %q", expect.Contains, text)
		}
		return nil
	}

	if expect.Equals != nil || expect.Path != "" {
		want := Normalize(expect.Equals)
		got := Normalize(actual)
		if !deepEqual(want, got) {
			return fmt.Errorf("期望 %s，实际 %s", Describe(want), Describe(got))
		}
		return nil
	}

	return nil
}

// deepEqual 比较两个已归一化的值，数字按 1e-9 容差。
func deepEqual(left, right any) bool {
	leftNumber, leftOK := left.(float64)
	rightNumber, rightOK := right.(float64)
	if leftOK && rightOK {
		return math.Abs(leftNumber-rightNumber) <= 1e-9
	}
	leftList, leftIsList := left.([]any)
	rightList, rightIsList := right.([]any)
	if leftIsList && rightIsList {
		if len(leftList) != len(rightList) {
			return false
		}
		for index := range leftList {
			if !deepEqual(leftList[index], rightList[index]) {
				return false
			}
		}
		return true
	}
	leftObject, leftIsObject := left.(map[string]any)
	rightObject, rightIsObject := right.(map[string]any)
	if leftIsObject && rightIsObject {
		if len(leftObject) != len(rightObject) {
			return false
		}
		for key, item := range leftObject {
			other, ok := rightObject[key]
			if !ok || !deepEqual(item, other) {
				return false
			}
		}
		return true
	}
	return reflect.DeepEqual(left, right)
}

// lengthOf 返回数组长度或对象键数。
func lengthOf(value any) (int, bool) {
	switch typed := value.(type) {
	case []any:
		return len(typed), true
	case map[string]any:
		return len(typed), true
	default:
		return 0, false
	}
}

// toFloat 把数字类值转成 float64。
func toFloat(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, true
	case float32:
		return float64(typed), true
	case int:
		return float64(typed), true
	case int64:
		return float64(typed), true
	case uint32:
		return float64(typed), true
	case uint64:
		return float64(typed), true
	case json.Number:
		parsed, err := typed.Float64()
		return parsed, err == nil
	default:
		return 0, false
	}
}

// Describe 把值渲染成便于阅读的短文本。
func Describe(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return fmt.Sprintf("%v", value)
	}
	text := string(encoded)
	if len(text) > 120 {
		return text[:117] + "..."
	}
	return text
}

// describeExpect 把断言渲染成短文本。
func describeExpect(expect Expect) string {
	if expect.Approx != nil {
		return fmt.Sprintf("≈%v", *expect.Approx)
	}
	if expect.Len != nil {
		return fmt.Sprintf("长度 %d", *expect.Len)
	}
	if expect.Contains != "" {
		return fmt.Sprintf("包含 %q", expect.Contains)
	}
	return Describe(Normalize(expect.Equals))
}
