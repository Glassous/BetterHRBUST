package academic

import (
	"os"
	"path/filepath"
	"testing"
)

// TestEmbeddedCalendarMatchesShared 守住内嵌校历副本与 `shared/school-calendar.json` 一致。
//
// Go 的 go:embed 不能引用父目录，因此 `internal/academic/school-calendar.json`
// 是副本。副本一旦漂移，终端界面算出的教学周就会与 Web / Android 不同——
// 这类错误在真机上极难发现，必须靠测试挡住。
func TestEmbeddedCalendarMatchesShared(t *testing.T) {
	shared, err := findSharedCalendar()
	if err != nil {
		t.Fatalf("%v", err)
	}
	want, err := os.ReadFile(shared)
	if err != nil {
		t.Fatalf("读取 %s 失败：%v", shared, err)
	}
	if string(want) != string(calendarJSON) {
		t.Fatalf("内嵌校历与 %s 不一致：请把 shared/school-calendar.json 复制到 "+
			"tui/internal/academic/school-calendar.json", shared)
	}
}

// findSharedCalendar 从测试工作目录向上定位共享校历。
func findSharedCalendar() (string, error) {
	current, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for depth := 0; depth < 8; depth++ {
		candidate := filepath.Join(current, "shared", "school-calendar.json")
		if _, statErr := os.Stat(candidate); statErr == nil {
			return candidate, nil
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return "", os.ErrNotExist
}

// TestCalendarHasExpectedSemesters 校验校历内容本身没被改坏。
func TestCalendarHasExpectedSemesters(t *testing.T) {
	calendar, err := LoadCalendar()
	if err != nil {
		t.Fatalf("解析校历失败：%v", err)
	}
	if len(calendar.Semesters) < 2 {
		t.Fatalf("校历至少应包含两个学期，实际 %d", len(calendar.Semesters))
	}
	for _, semester := range calendar.Semesters {
		if semester.Label == "" || semester.StartDate == "" || semester.TeachingEndDate == "" {
			t.Fatalf("学期字段不完整：%+v", semester)
		}
		if _, ok := ParseISODate(semester.StartDate); !ok {
			t.Fatalf("学期 %s 的 startDate 非法：%s", semester.Label, semester.StartDate)
		}
		if semester.TeachingWeeks <= 0 {
			t.Fatalf("学期 %s 的教学周数非法：%d", semester.Label, semester.TeachingWeeks)
		}
	}
}
