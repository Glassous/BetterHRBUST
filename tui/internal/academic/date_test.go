package academic

import "testing"

// TestDateRoundTrips 校验公历换算的往返一致性（含闰年与世纪边界）。
func TestDateRoundTrips(t *testing.T) {
	cases := []Date{
		{Year: 1970, Month: 1, Day: 1},
		{Year: 2000, Month: 2, Day: 29},
		{Year: 2024, Month: 2, Day: 29},
		{Year: 2026, Month: 10, Day: 10},
		{Year: 2027, Month: 1, Day: 10},
		{Year: 2027, Month: 3, Day: 1},
		{Year: 1969, Month: 12, Day: 31},
		{Year: 1900, Month: 3, Day: 1},
	}
	for _, date := range cases {
		restored := FromDayNumber(date.DayNumber())
		if restored != date {
			t.Fatalf("%v 往返后变成 %v", date, restored)
		}
	}
}

// TestDayNumberAnchors 校验已知锚点，防止实现被"顺手改成"别的年月日基准。
func TestDayNumberAnchors(t *testing.T) {
	cases := []struct {
		date Date
		want int64
	}{
		{Date{Year: 1970, Month: 1, Day: 1}, 0},
		{Date{Year: 1970, Month: 1, Day: 2}, 1},
		{Date{Year: 1969, Month: 12, Day: 31}, -1},
		{Date{Year: 2000, Month: 1, Day: 1}, 10957},
		{Date{Year: 2026, Month: 10, Day: 10}, 20736},
		{Date{Year: 2026, Month: 11, Day: 13}, 20770},
	}
	for _, item := range cases {
		if got := item.date.DayNumber(); got != item.want {
			t.Fatalf("%v 的天编号期望 %d，实际 %d", item.date, item.want, got)
		}
	}
}

// TestWeekdayAnchors 校验 ISO 星期：1 = 周一 … 7 = 周日。
func TestWeekdayAnchors(t *testing.T) {
	cases := []struct {
		date Date
		want int
	}{
		{Date{Year: 1970, Month: 1, Day: 1}, 4},   // 周四
		{Date{Year: 2026, Month: 10, Day: 10}, 6}, // 周六
		{Date{Year: 2026, Month: 11, Day: 13}, 5}, // 周五
		{Date{Year: 2026, Month: 8, Day: 31}, 1},  // 2026 秋学期第一天，周一
		{Date{Year: 2027, Month: 3, Day: 1}, 1},   // 2027 春学期第一天，周一
	}
	for _, item := range cases {
		if got := item.date.Weekday(); got != item.want {
			t.Fatalf("%v 的星期期望 %d，实际 %d", item.date, item.want, got)
		}
	}
}

// TestAddDaysCrossesMonthAndYear 校验跨月跨年。
func TestAddDaysCrossesMonthAndYear(t *testing.T) {
	if got := (Date{Year: 2026, Month: 12, Day: 31}).AddDays(1); got != (Date{Year: 2027, Month: 1, Day: 1}) {
		t.Fatalf("跨年失败：%v", got)
	}
	if got := (Date{Year: 2024, Month: 2, Day: 28}).AddDays(1); got != (Date{Year: 2024, Month: 2, Day: 29}) {
		t.Fatalf("闰年 2 月失败：%v", got)
	}
}

// TestParseISODateRejectsInvalid 校验非法日期不会被当成有效值。
func TestParseISODateRejectsInvalid(t *testing.T) {
	for _, raw := range []string{"", "2026", "2026-13-01", "2026-01-32", "abc-def-gh", "2026/01/01"} {
		if _, ok := ParseISODate(raw); ok {
			t.Fatalf("%q 不应被解析为有效日期", raw)
		}
	}
	for _, raw := range []string{"2026-01-01", "2026-1-1", "2027-03-01"} {
		if _, ok := ParseISODate(raw); !ok {
			t.Fatalf("%q 应被解析为有效日期", raw)
		}
	}
}

// TestSemesterSortKeyOrdersSpringBeforeAutumn 验证学期排序口径。
func TestSemesterSortKeyOrdersSpringBeforeAutumn(t *testing.T) {
	if !(SemesterSortKey("2024", "春") < SemesterSortKey("2024", "秋")) {
		t.Fatal("同学年内应先春后秋")
	}
	if !(SemesterSortKey("2023", "秋") < SemesterSortKey("2024", "春")) {
		t.Fatal("跨学年应按年份升序")
	}
	if !(SemesterSortKey("2024", "1") < SemesterSortKey("2024", "2")) {
		t.Fatal("数字学期 1 应排在 2 之前")
	}
	if SemesterSortKey("", "") != 3 {
		t.Fatalf("空学年空学期应落到 rank 3，实际 %d", SemesterSortKey("", ""))
	}
}

// TestScoreValueTable 逐条钉住五级制与百分制折算。
func TestScoreValueTable(t *testing.T) {
	cases := []struct {
		raw    string
		point  float64
		hasNum bool
	}{
		{"优秀", 4.5, false},
		{"优", 4.5, false},
		{"良好", 3.5, false},
		{"良", 3.5, false},
		{"中等", 2.5, false},
		{"中", 2.5, false},
		{"及格", 1.5, false},
		{"合格", 1.5, false},
		{"及格线", 1.5, false},
		{"不及格", 0, false},
		{"不合格", 0, false},
		{"免修", 0, false},
		{"缓考", 0, false},
		{"", 0, false},
		{"59", 0, true},
		{"60", 1.0, true},
		{"90", 4.0, true},
		{"100", 5.0, true},
		{"105", 5.0, true},
	}
	for _, item := range cases {
		value := ParseScoreValue(item.raw)
		if value.GradePoint != item.point {
			t.Fatalf("%q 绩点期望 %v，实际 %v", item.raw, item.point, value.GradePoint)
		}
		if (value.Numeric != nil) != item.hasNum {
			t.Fatalf("%q 的 numeric 存在性不符（期望 %v）", item.raw, item.hasNum)
		}
	}
}
