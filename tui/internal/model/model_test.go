package model

import (
	"encoding/json"
	"testing"
)

func TestDecodeScoreToleratesTypeDrift(t *testing.T) {
	object := map[string]any{
		"courseId":   "C1",
		"courseName": "线性代数",
		"score":      jsonNumber("92.5"),
		"credit":     "3.5",
		"hours":      "56",
		"passed":     "true",
		"pending":    jsonNumber("0"),
		"property":   "必修",
	}
	record := DecodeScore(object)

	if record.Score != "92.5" {
		t.Fatalf("数字成绩应转成字符串，实际 %q", record.Score)
	}
	if record.Credit != 3.5 || record.Hours != 56 {
		t.Fatalf("字符串学分应转成数字，实际 %v / %v", record.Credit, record.Hours)
	}
	if !record.Passed || record.Pending {
		t.Fatalf("鲁尔字段应容错，实际 passed=%v pending=%v", record.Passed, record.Pending)
	}
}

func TestDecodeScoreDefaultsOnMissingFields(t *testing.T) {
	record := DecodeScore(map[string]any{})
	if record.CourseName != "" || record.Credit != 0 || record.Passed {
		t.Fatalf("缺失字段应退化为零值：%+v", record)
	}
}

func TestDecodeCountRejectsBool(t *testing.T) {
	// Rust 的 de_u32 不接受布尔，这里保持一致。
	object := map[string]any{"day": true, "sectionIndex": "3"}
	cell := DecodeTimetableCell(object)
	if cell.Day != 0 {
		t.Fatalf("布尔不应被当成整数，实际 %d", cell.Day)
	}
	if cell.SectionIndex != 3 {
		t.Fatalf("字符串整数应被接受，实际 %d", cell.SectionIndex)
	}
}

func TestDecodeNoticeDropsDate(t *testing.T) {
	notice := DecodeNotice(map[string]any{"title": "通知", "content": "正文", "date": "2026-01-01"})
	if notice.Date != "" {
		t.Fatalf("公告日期必须清空，实际 %q", notice.Date)
	}
}

func TestSnapshotLazilyDecodes(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeySessionFlag: "true",
		KeyScores:      `[{"courseId":"A","courseName":"甲","score":"90","credit":3,"property":"必修","passed":true}]`,
		KeyExams:       `[{"courseId":"A","courseName":"甲","time":"2026-01-05"}]`,
	})

	if !snapshot.LoggedIn() {
		t.Fatal("应识别登录态")
	}
	if got := len(snapshot.Scores()); got != 1 {
		t.Fatalf("成绩条数应为 1，实际 %d", got)
	}
	if got := len(snapshot.Exams()); got != 1 {
		t.Fatalf("考试条数应为 1，实际 %d", got)
	}
	if !snapshot.HasData() {
		t.Fatal("有成绩即算有数据")
	}
	// 再取一次应命中缓存，不重复解析。
	if got := len(snapshot.Scores()); got != 1 {
		t.Fatalf("二次读取条数应为 1，实际 %d", got)
	}
}

func TestSnapshotIgnoresCorruptSection(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeyScores: `{not json`,
		KeyExams:  `[{"courseName":"甲"}]`,
	})
	if got := len(snapshot.Scores()); got != 0 {
		t.Fatalf("损坏的成绩段应退化为空，实际 %d", got)
	}
	if got := len(snapshot.Exams()); got != 1 {
		t.Fatalf("其他段不应受影响，实际 %d", got)
	}
}

func TestSnapshotDropsOnlyBadRecord(t *testing.T) {
	// 一条记录是字符串而不是对象：只丢这一条，其余保留。
	snapshot := NewFromPairs("/x", map[string]string{
		KeyScores: `["坏记录", {"courseId":"A","courseName":"甲"}, {"courseId":"B","courseName":"乙"}]`,
	})
	if got := len(snapshot.Scores()); got != 2 {
		t.Fatalf("应保留 2 条有效记录，实际 %d", got)
	}
}

func TestSnapshotNeverExposesCredential(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeySavedCredential: "c2VjcmV0",
		KeyScores:          "[]",
	})
	if _, ok := snapshot.Get(KeySavedCredential); ok {
		t.Fatal("凭证键不得通过 Get 读取")
	}
	if !snapshot.HasKey(KeySavedCredential) {
		t.Fatal("但仍应能判断存在性")
	}
	if !IsSensitiveKey(KeySavedCredential) {
		t.Fatal("凭证键应被标记为敏感")
	}
}

func TestTimetableFallsBackToBase(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeyTimetableBase: `{"cells":[{"courseName":"甲","day":1}]}`,
	})
	if got := len(snapshot.Timetable(false).Cells); got != 1 {
		t.Fatalf("大节缺失时应回退小节，实际 %d", got)
	}
}

func TestNoticeWeeksKeepsEmptySlice(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeyNoticeWeeks: `{"6":[],"7":[{"title":"有公告"}]}`,
	})
	weeks := snapshot.NoticeWeeks()
	if _, ok := weeks["6"]; !ok {
		t.Fatal("空数组周必须保留键：它表示「已缓存且该周没有公告」")
	}
	if got := len(weeks["6"]); got != 0 {
		t.Fatalf("第 6 周应为空，实际 %d", got)
	}
	if got := len(weeks["7"]); got != 1 {
		t.Fatalf("第 7 周应有 1 条，实际 %d", got)
	}
}

func TestCountsAndKeys(t *testing.T) {
	snapshot := NewFromPairs("/x", map[string]string{
		KeyScores: `[{"courseId":"A"},{"courseId":"B"}]`,
		KeyExams:  `[{"courseId":"A"}]`,
		KeyPlan:   `{"groups":[{"id":"1"}]}`,
	})
	counts := snapshot.Counts()
	if counts.Scores != 2 || counts.Exams != 1 || counts.PlanGroups != 1 {
		t.Fatalf("计数不符：%+v", counts)
	}
	if snapshot.Len() != 3 {
		t.Fatalf("键数应为 3，实际 %d", snapshot.Len())
	}
	keys := snapshot.Keys()
	if len(keys) != 3 || keys[0] > keys[1] {
		t.Fatalf("键应排序返回：%v", keys)
	}
}

func TestTrimSpace(t *testing.T) {
	if got := trimSpace("  a \n"); got != "a" {
		t.Fatalf("应去掉首尾空白，实际 %q", got)
	}
}

// jsonNumber 构造 json.Number，模拟 UseNumber 解码后的值。
func jsonNumber(text string) any { return json.Number(text) }
