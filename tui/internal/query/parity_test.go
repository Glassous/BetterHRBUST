package query_test

// 本文件是 `shared/parity/*.json` 的唯一执行者。
//
// 所有语言中立的黄金向量都在这里被消费：算法类用例转调 `internal/academic`，
// 命令类用例转调 `internal/query`。这样「Web / Rust / Go 三端口径一致」
// 只需要维护一份 JSON，而不是三份测试。
//
// 新增 kind 时必须同时更新 `internal/parity` 的 AllKinds 与下面的 handlers，
// 否则 TestParityKindsAreCovered 会失败。

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/parity"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// handler 接收一条用例的输入，返回待断言的结果。
type handler func(t *testing.T, c parity.Case) (any, error)

func TestParity(t *testing.T) {
	files, err := parity.Load()
	if err != nil {
		t.Fatalf("读取 parity 语料失败：%v", err)
	}
	if len(files) == 0 {
		t.Fatal("shared/parity 下没有任何用例文件")
	}

	handlers := buildHandlers()
	for _, file := range files {
		run, ok := handlers[file.Kind]
		if !ok {
			t.Fatalf("%s：kind=%q 没有处理器（已知 kind：%s）",
				file.ID, file.Kind, strings.Join(parity.AllKinds(), "、"))
		}
		file := file
		t.Run(file.ID, func(t *testing.T) {
			for _, item := range file.Cases {
				item := item
				t.Run(item.Name, func(t *testing.T) {
					result, err := run(t, item)
					if err != nil {
						t.Fatalf("执行失败：%v", err)
					}
					encoded, err := json.Marshal(result)
					if err != nil {
						t.Fatalf("结果无法序列化：%v", err)
					}
					decoded, err := decodeAny(encoded)
					if err != nil {
						t.Fatalf("结果无法解码：%v", err)
					}
					for _, expect := range item.Expect {
						actual, present := parity.Resolve(decoded, expect.Path)
						if err := parity.Check(expect, actual, present); err != nil {
							t.Fatalf("%s：%v", expect.Path, err)
						}
					}
					for _, forbidden := range item.Forbid {
						if strings.Contains(string(encoded), forbidden) {
							t.Fatalf("结果中出现了禁止的字符串 %q（隐私断言失败）", forbidden)
						}
					}
				})
			}
		})
	}
}

// TestParityKindsAreCovered 断言语料里的每个 kind 都有处理器，避免拼错 kind 后静默跳过。
func TestParityKindsAreCovered(t *testing.T) {
	files, err := parity.Load()
	if err != nil {
		t.Fatalf("读取 parity 语料失败：%v", err)
	}
	handlers := buildHandlers()
	seen := map[string]bool{}
	for _, file := range files {
		seen[file.Kind] = true
		if _, ok := handlers[file.Kind]; !ok {
			t.Errorf("%s 使用了未实现的 kind：%s", file.ID, file.Kind)
		}
	}
	for _, kind := range parity.AllKinds() {
		if !seen[kind] {
			t.Errorf("kind %q 已在 AllKinds 声明，但没有任何用例文件使用它", kind)
		}
		if _, ok := handlers[kind]; !ok {
			t.Errorf("kind %q 没有处理器", kind)
		}
	}
}

// TestParityFixturesAreParseable 校验夹具本身是合法的 storage.json。
func TestParityFixturesAreParseable(t *testing.T) {
	dir, err := parity.Dir()
	if err != nil {
		t.Fatalf("%v", err)
	}
	entries, err := os.ReadDir(filepath.Join(dir, "fixtures"))
	if err != nil {
		t.Fatalf("读取 fixtures 目录失败：%v", err)
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		path := filepath.Join(dir, "fixtures", entry.Name())
		content, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%s：%v", path, err)
		}
		var raw map[string]string
		if err := json.Unmarshal(content, &raw); err != nil {
			t.Fatalf("%s 不是 map[string]string：%v", path, err)
		}
		if len(raw) == 0 {
			t.Fatalf("%s 是空文件", path)
		}
	}
}

// ---------- 处理器 ----------

func buildHandlers() map[string]handler {
	return map[string]handler{
		"academicStats":   handleAcademicStats,
		"degreeCourses":   handleDegreeCourses,
		"dedupe":          handleDedupe,
		"creditsProgress": handleCreditsProgress,
		"courseWeeks":     handleCourseWeeks,
		"teachingWeek":    handleTeachingWeek,
		"dayDate":         handleDayDate,
		"slotTimes":       handleSlotTimes,
		"examTime":        handleExamTime,
		"cellFields":      handleCellFields,
		"readiness":       handleReadiness,
		"query":           handleQuery,
		"mask":            handleMask,
	}
}

func handleAcademicStats(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	return academic.BuildAcademicStats(scoresOf(input)), nil
}

func handleDegreeCourses(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	courses := academic.DegreeCourses(scoresOf(input))
	ids := make([]string, 0, len(courses))
	for _, course := range courses {
		ids = append(ids, course.CourseID)
	}
	return map[string]any{"ids": ids, "scores": courses}, nil
}

func handleDedupe(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	deduped := academic.Dedupe(scoresOf(input))
	courses := make([]query.CourseEntry, 0, len(deduped))
	for _, record := range deduped {
		courses = append(courses, query.CourseEntry{
			Record:      record,
			IsRetake:    record.IsRetake,
			RecordCount: record.RecordCount,
		})
	}
	return map[string]any{"courses": courses}, nil
}

func handleCreditsProgress(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	groups := model.DecodeList(arrayOf(input, "groups"), model.DecodePlanGroup)
	var total *float64
	if raw, ok := input["totalRequiredCredits"].(json.Number); ok {
		value, err := raw.Float64()
		if err != nil {
			return nil, fmt.Errorf("totalRequiredCredits 不是数字：%w", err)
		}
		total = &value
	}
	requiredOnly := false
	if raw, ok := input["requiredOnly"].(bool); ok {
		requiredOnly = raw
	}
	return academic.ComputeCreditsProgress(scoresOf(input), groups, total, requiredOnly), nil
}

func handleCourseWeeks(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	result := map[string]any{}
	if raw, ok := input["expression"].(string); ok {
		if week, ok := intOf(input, "week"); ok {
			result["active"] = academic.IsCourseActiveInWeek(raw, week)
		}
		if maxWeek, ok := intOf(input, "maxWeek"); ok {
			result["expanded"] = academic.ExpandCourseWeeks(raw, maxWeek)
		}
	}
	return result, nil
}

func handleTeachingWeek(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	raw, _ := input["today"].(string)
	date, ok := academic.ParseISODate(raw)
	if !ok {
		return nil, fmt.Errorf("today 不是合法日期：%q", raw)
	}
	result := map[string]any{"teachingWeeks": academic.TeachingWeeks(date)}
	if week, has := academic.TeachingWeekOn(date); has {
		result["week"] = week
	} else {
		result["week"] = nil
	}
	if semester, has := academic.SemesterOn(date); has {
		result["semesterLabel"] = semester.Label
	} else {
		result["semesterLabel"] = nil
	}
	return result, nil
}

func handleDayDate(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	currentWeek, _ := intOf(input, "currentWeek")
	selectedWeek, _ := intOf(input, "selectedWeek")
	day, _ := intOf(input, "day")
	raw, _ := input["today"].(string)
	date, ok := academic.ParseISODate(raw)
	if !ok {
		return nil, fmt.Errorf("today 不是合法日期：%q", raw)
	}
	return map[string]any{
		"label": academic.TimetableDayDate(currentWeek, selectedWeek, day, date),
	}, nil
}

func handleSlotTimes(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	period, _ := intOf(input, "period")
	result := map[string]any{
		"time":  academic.CombineSlotTime(period),
		"start": academic.CombineSlotStartTime(period),
	}
	if minutes, ok := academic.CombineSlotEndMinutes(period); ok {
		result["endMinutes"] = minutes
	} else {
		result["endMinutes"] = nil
	}
	if minutesOfDay, ok := intOf(input, "minutesOfDay"); ok {
		result["ended"] = academic.HasCombineSlotEnded(period, minutesOfDay)
	}
	return result, nil
}

func handleExamTime(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	raw, _ := input["text"].(string)
	parsed, ok := academic.ParseExamTime(raw)
	if !ok {
		return map[string]any{"ok": false, "date": nil, "start": nil, "end": nil}, nil
	}
	result := map[string]any{"ok": true, "date": parsed.Date.String()}
	if parsed.HasStart {
		result["start"] = clockText(parsed.StartHour, parsed.StartMinute)
	} else {
		result["start"] = nil
	}
	if parsed.HasEnd {
		result["end"] = clockText(parsed.EndHour, parsed.EndMinute)
	} else {
		result["end"] = nil
	}
	return result, nil
}

func handleCellFields(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	lines := make([]string, 0, 6)
	for _, item := range arrayOf(input, "lines") {
		text, _ := item.(string)
		lines = append(lines, text)
	}
	fields := academic.SplitCourseCellFields(lines)
	return map[string]any{
		"location":  fields.Location,
		"teacher":   fields.Teacher,
		"weeks":     fields.Weeks,
		"hoursType": fields.HoursType,
	}, nil
}

func handleReadiness(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	snapshot := snapshotOf(t, input)
	readiness := store.Evaluate(snapshot)
	return map[string]any{
		"kind":      readiness.Code(),
		"offline":   readiness.Offline,
		"exitCode":  readiness.ExitCode(),
		"errorCode": readiness.ErrorCode(),
		"message":   readiness.Message,
	}, nil
}

func handleMask(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}
	raw, _ := input["text"].(string)
	head, _ := intOf(input, "keepHead")
	tail, _ := intOf(input, "keepTail")
	return map[string]any{"masked": academic.MaskedField(raw, head, tail)}, nil
}

func handleQuery(t *testing.T, c parity.Case) (any, error) {
	input, err := decodeInput(c)
	if err != nil {
		return nil, err
	}

	storage := input
	if fixture, ok := input["fixture"].(string); ok {
		storage = map[string]any{"storage": loadFixture(t, fixture)}
	}

	snapshot := snapshotOf(t, storage)

	clock := query.SystemClock()
	if c.Clock.Today != "" {
		date, ok := academic.ParseISODate(c.Clock.Today)
		if !ok {
			return nil, fmt.Errorf("clock.today 不是合法日期：%q", c.Clock.Today)
		}
		clock = query.Clock{Today: date, Location: fixedLocation(c.Clock.UTCOffsetMinutes)}
	}
	if c.Clock.MinuteOfDay != nil {
		clock.MinuteOfDay = *c.Clock.MinuteOfDay
	}
	if raw, ok := input["today"].(string); ok {
		date, okDate := academic.ParseISODate(raw)
		if !okDate {
			return nil, fmt.Errorf("today 不是合法日期：%q", raw)
		}
		clock.Today = date
	}
	if minutes, ok := intOf(input, "minuteOfDay"); ok {
		clock.MinuteOfDay = minutes
	}
	// 固定生成时刻，让 generatedAt 之类的字段也可断言。
	reference := academic.Date{Year: 2026, Month: 11, Day: 13}
	if c.Clock.MinuteOfDay != nil {
		reference = clock.Today
	}
	service := query.New(snapshot, clock, "test")
	service.GeneratedAt = time.Date(reference.Year, time.Month(reference.Month), reference.Day, 12, 0, 0, 0, clock.Location)

	command, _ := input["command"].(string)
	options, err := optionsOf(input)
	if err != nil {
		return nil, err
	}

	var data any
	switch command {
	case "status":
		data = service.Status()
	case "summary":
		data = service.Summary(options)
	case "timetable":
		data = service.Timetable(options)
	case "scores":
		data = service.Scores(options)
	case "exams":
		data = service.Exams(options)
	case "plan":
		data = service.Plan(options)
	case "notices":
		data = service.Notices(options)
	case "profile":
		data = service.Profile()
	case "courses":
		data = service.Courses(options)
	case "doctor":
		data = service.Doctor()
	case "check":
		data = service.Check(options)
	default:
		return nil, fmt.Errorf("未知 command：%q", command)
	}

	readiness := service.Readiness()
	return map[string]any{
		"readiness": readiness.Code(),
		"offline":   readiness.Offline,
		"data":      data,
	}, nil
}

// ---------- 工具 ----------

func decodeInput(c parity.Case) (map[string]any, error) {
	raw, err := parity.DecodeInput(c.Input)
	if err != nil {
		return nil, err
	}
	object, ok := raw.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("用例 input 必须是对象")
	}
	return object, nil
}

func scoresOf(input map[string]any) []model.ScoreRecord {
	return model.DecodeList(arrayOf(input, "scores"), model.DecodeScore)
}

func arrayOf(input map[string]any, key string) []any {
	list, _ := input[key].([]any)
	return list
}

func intOf(input map[string]any, key string) (int, bool) {
	raw, ok := input[key].(json.Number)
	if !ok {
		return 0, false
	}
	value, err := raw.Int64()
	if err != nil {
		return 0, false
	}
	return int(value), true
}

func clockText(hour, minute int) string {
	return fmt.Sprintf("%02d:%02d", hour, minute)
}

// snapshotOf 从用例输入构造缓存快照。
func snapshotOf(t *testing.T, input map[string]any) *model.Snapshot {
	t.Helper()
	raw := map[string]string{}
	if storage, ok := input["storage"].(map[string]any); ok {
		for key, value := range storage {
			if text, okText := value.(string); okText {
				raw[key] = text
			}
		}
	}
	loadError := ""
	if text, ok := input["loadError"].(string); ok {
		loadError = text
	}
	return model.New("/parity", "/parity/storage.json", len(raw) > 0, loadError, 0, raw)
}

// loadFixture 读入 `shared/parity/fixtures/*.json` 并转成 storage 键值表。
func loadFixture(t *testing.T, name string) map[string]any {
	t.Helper()
	dir, err := parity.Dir()
	if err != nil {
		t.Fatalf("%v", err)
	}
	content, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(name)))
	if err != nil {
		t.Fatalf("读取夹具 %s 失败：%v", name, err)
	}
	var raw map[string]string
	if err := json.Unmarshal(content, &raw); err != nil {
		t.Fatalf("夹具 %s 不是 map[string]string：%v", name, err)
	}
	object := make(map[string]any, len(raw))
	for key, value := range raw {
		object[key] = value
	}
	return object
}

// optionsOf 把用例里的 options 转成查询参数。
func optionsOf(input map[string]any) (query.Options, error) {
	options := query.Options{}
	raw, ok := input["options"].(map[string]any)
	if !ok {
		return options, nil
	}

	var err error
	setInt := func(key string, target **int) {
		if value, ok := intOf(raw, key); ok {
			copied := value
			*target = &copied
		}
	}
	setInt("week", &options.Week)
	setInt("day", &options.Day)
	setInt("days", &options.Days)
	setInt("limit", &options.Limit)

	if value, ok := raw["today"].(bool); ok {
		options.Today = value
	}
	if value, ok := raw["base"].(bool); ok {
		options.Base = value
	}
	if value, ok := raw["failed"].(bool); ok {
		options.Failed = value
	}
	if value, ok := raw["merged"].(bool); ok {
		options.Merged = value
	}
	if value, ok := raw["all"].(bool); ok {
		options.All = value
	}
	if value, ok := raw["strict"].(bool); ok {
		options.Strict = value
	}
	if value, ok := raw["course"].(string); ok {
		options.Course = value
	}
	if value, ok := raw["query"].(string); ok {
		options.Query = value
	}
	if value, ok := raw["group"].(string); ok {
		options.Group = value
	}
	if value, ok := raw["date"].(string); ok {
		date, okDate := academic.ParseISODate(value)
		if !okDate {
			return options, fmt.Errorf("options.date 不是合法日期：%q", value)
		}
		options.Date = &date
	}
	if value, ok := raw["property"].(string); ok {
		property, okProperty := academic.PropertyFilter(value)
		if !okProperty {
			return options, fmt.Errorf("options.property 非法：%q", value)
		}
		options.Property = &property
	}
	return options, err
}

// decodeAny 把 JSON 解码为通用结构，数字统一为 json.Number。
func decodeAny(raw []byte) (any, error) {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, err
	}
	return value, nil
}

// fixedLocation 按分钟偏移构造时区。
func fixedLocation(offsetMinutes int) *time.Location {
	if offsetMinutes == 0 {
		return time.UTC
	}
	return time.FixedZone("parity", offsetMinutes*60)
}
