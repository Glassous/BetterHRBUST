package query

import (
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// StatusData 是 `status` 载荷：登录态、数据量与上次同步时间。
type StatusData struct {
	Readiness      string           `json:"readiness"`
	Label          string           `json:"label"`
	LoggedIn       bool             `json:"loggedIn"`
	SessionExpired bool             `json:"sessionExpired"`
	HasData        bool             `json:"hasData"`
	Offline        bool             `json:"offline"`
	Account        string           `json:"account"`
	DataDir        string           `json:"dataDir"`
	StoragePath    string           `json:"storagePath"`
	StorageExists  bool             `json:"storageExists"`
	StorageSize    int64            `json:"storageSize"`
	Counts         model.DataCounts `json:"counts"`
	LastLoginAt    uint64           `json:"lastLoginAt"`
	LastSyncAt     uint64           `json:"lastSyncAt"`
	LastSyncText   string           `json:"lastSyncText"`
	LastSyncError  string           `json:"lastSyncError"`
	GeneratedAt    string           `json:"generatedAt"`
	Version        string           `json:"version"`
}

// Status 组装状态数据。`status` 永远不失败——它本身就是在报告状态。
func (s *Service) Status() StatusData {
	readiness := s.Readiness()
	meta := s.Snapshot.SyncMeta()
	return StatusData{
		Readiness:      readiness.Code(),
		Label:          readiness.Label(),
		LoggedIn:       s.LoggedIn(),
		SessionExpired: s.Snapshot.SessionExpired(),
		HasData:        s.Snapshot.HasData(),
		Offline:        readiness.Offline,
		Account:        s.Account(),
		DataDir:        s.Snapshot.Dir,
		StoragePath:    s.Snapshot.StoragePath,
		StorageExists:  s.Snapshot.StorageExists,
		StorageSize:    s.Snapshot.StorageSize,
		Counts:         s.Snapshot.Counts(),
		LastLoginAt:    s.Snapshot.LastLoginAt(),
		LastSyncAt:     meta.LastSyncAt,
		LastSyncText:   s.LastSyncText(),
		LastSyncError:  meta.LastError,
		GeneratedAt:    s.GeneratedAtText(),
		Version:        s.Version,
	}
}

// KeyReport 是单个缓存键的诊断信息。
type KeyReport struct {
	Key string `json:"key"`
	// Present 表示键存在。
	Present bool `json:"present"`
	// Bytes 是值的字节数；凭证键也会显示长度，但绝不显示内容。
	Bytes int `json:"bytes"`
	// Sensitive 为真表示内容永不展示。
	Sensitive bool `json:"sensitive"`
}

// DoctorData 是 `doctor` 载荷：路径、可读性、缓存键。
type DoctorData struct {
	DataDir       string           `json:"dataDir"`
	StoragePath   string           `json:"storagePath"`
	StorageExists bool             `json:"storageExists"`
	StorageSize   int64            `json:"storageSize"`
	LoadError     string           `json:"loadError"`
	Readiness     string           `json:"readiness"`
	ReadinessNote string           `json:"readinessNote"`
	Keys          []KeyReport      `json:"keys"`
	Missing       []string         `json:"missing"`
	Sidecars      []string         `json:"sidecars"`
	Counts        model.DataCounts `json:"counts"`
	CalendarOK    bool             `json:"calendarOk"`
	CalendarError string           `json:"calendarError"`
	Version       string           `json:"version"`
}

// Doctor 组装诊断数据。
//
// 只报告、不修复：不创建目录，不重命名损坏文件，不清理残留文件。
func (s *Service) Doctor() DoctorData {
	readiness := s.Readiness()
	keys := make([]KeyReport, 0, len(model.AllKeys()))
	missing := make([]string, 0, 4)
	for _, key := range model.AllKeys() {
		sensitive := model.IsSensitiveKey(key)
		report := KeyReport{Key: key, Sensitive: sensitive}
		// 凭证键只判断存在性，不取值。
		if s.Snapshot.HasKey(key) {
			report.Present = true
			if !sensitive {
				if value, ok := s.Snapshot.Get(key); ok {
					report.Bytes = len(value)
				}
			}
		} else {
			missing = append(missing, key)
		}
		keys = append(keys, report)
	}

	_, calendarErr := academic.LoadCalendar()

	return DoctorData{
		DataDir:       s.Snapshot.Dir,
		StoragePath:   s.Snapshot.StoragePath,
		StorageExists: s.Snapshot.StorageExists,
		StorageSize:   s.Snapshot.StorageSize,
		LoadError:     s.Snapshot.LoadError,
		Readiness:     readiness.Code(),
		ReadinessNote: readiness.Message,
		Keys:          keys,
		Missing:       missing,
		Sidecars:      store.SidecarFiles(s.Snapshot.Dir),
		Counts:        s.Snapshot.Counts(),
		CalendarOK:    calendarErr == nil,
		CalendarError: errorText(calendarErr),
		Version:       s.Version,
	}
}

// CheckItem 是一条检查项。
type CheckItem struct {
	ID     string `json:"id"`
	Level  string `json:"level"`
	Title  string `json:"title"`
	Detail string `json:"detail"`
	Hint   string `json:"hint"`
}

// CheckSummary 是检查项计数。
type CheckSummary struct {
	OK   int `json:"ok"`
	Warn int `json:"warn"`
	Fail int `json:"fail"`
}

// CheckData 是 `check` 载荷。
type CheckData struct {
	Checks  []CheckItem  `json:"checks"`
	Summary CheckSummary `json:"summary"`
	Strict  bool         `json:"strict"`
}

// Check 执行数据完整性与会话检查。
//
// 有 fail 项时调用方应以退出码 3 结束；`--strict` 下 warn 项返回 1。
func (s *Service) Check(options Options) CheckData {
	items := make([]CheckItem, 0, 8)

	// 1. 存储文件
	switch {
	case s.Snapshot.LoadError != "":
		items = append(items, CheckItem{
			ID: "storage", Level: "fail", Title: "本地数据文件不可用",
			Detail: s.Snapshot.LoadError,
			Hint:   "打开 BetterHRBUST 图形界面刷新一次；若仍失败，检查文件权限",
		})
	case !s.Snapshot.StorageExists:
		items = append(items, CheckItem{
			ID: "storage", Level: "fail", Title: "未找到本地数据文件",
			Detail: s.Snapshot.StoragePath,
			Hint:   "先打开图形界面登录一次，缓存会写入该路径",
		})
	default:
		items = append(items, CheckItem{
			ID: "storage", Level: "ok", Title: "本地数据文件可读",
			Detail: s.Snapshot.StoragePath + "（" + humanBytes(s.Snapshot.StorageSize) + "）",
		})
	}

	// 2. 登录态
	if s.LoggedIn() {
		items = append(items, CheckItem{
			ID: "session", Level: "ok", Title: "会话有效", Detail: "缓存记录显示已登录",
		})
	} else {
		level := "warn"
		if !s.Snapshot.HasData() {
			level = "fail"
		}
		items = append(items, CheckItem{
			ID: "session", Level: level, Title: "会话已失效",
			Detail: "当前只有本地缓存，无法获取新数据",
			Hint:   "需要最新数据时到图形界面重新登录并刷新",
		})
	}

	// 3. 数据完整性
	counts := s.Snapshot.Counts()
	if !s.Snapshot.HasData() {
		items = append(items, CheckItem{
			ID: "data", Level: "fail", Title: "缓存为空",
			Detail: "成绩、课表、考试、培养方案均无内容",
			Hint:   "在图形界面顶栏点击「刷新」同步一次",
		})
	} else {
		items = append(items, CheckItem{
			ID: "data", Level: "ok", Title: "缓存有数据",
			Detail: "成绩 " + itoa(counts.Scores) + " 条 · 课表 " + itoa(counts.TimetableCells) +
				" 格 · 考试 " + itoa(counts.Exams) + " 场 · 培养方案 " + itoa(counts.PlanGroups) + " 组",
		})
	}

	// 4. 上次同步失败
	meta := s.Snapshot.SyncMeta()
	if strings.TrimSpace(meta.LastError) != "" {
		items = append(items, CheckItem{
			ID: "sync", Level: "warn", Title: "上次同步报错",
			Detail: meta.LastError,
			Hint:   "到图形界面手动刷新，确认错误是否已消失",
		})
	} else {
		items = append(items, CheckItem{
			ID: "sync", Level: "ok", Title: "上次同步无报错", Detail: "同步时间 " + s.LastSyncText(),
		})
	}

	// 5. 数据新鲜度
	if meta.LastSyncAt > 0 {
		age := s.GeneratedAt.UnixMilli() - int64(meta.LastSyncAt)
		if age > 30*24*60*60*1000 {
			items = append(items, CheckItem{
				ID: "fresh", Level: "warn", Title: "缓存已超过 30 天",
				Detail: "上次同步：" + s.LastSyncText(),
				Hint:   "成绩与课表可能已变化，建议到图形界面刷新",
			})
		} else {
			items = append(items, CheckItem{
				ID: "fresh", Level: "ok", Title: "缓存较新", Detail: "上次同步：" + s.LastSyncText(),
			})
		}
	}

	// 6. 成绩可读性
	scores := s.Snapshot.Scores()
	if len(scores) > 0 {
		unreadable := 0
		for _, record := range scores {
			if academic.ParseScoreValue(record.Score).Estimated == nil && !record.Pending {
				unreadable++
			}
		}
		if unreadable > 0 {
			items = append(items, CheckItem{
				ID: "scores", Level: "warn", Title: "有成绩无法折算绩点",
				Detail: itoa(unreadable) + " 条记录的成绩文本不在对照表内（如「免修」）",
				Hint:   "这些记录不计入 GPA 分母，属预期行为",
			})
		} else {
			items = append(items, CheckItem{
				ID: "scores", Level: "ok", Title: "成绩全部可折算", Detail: itoa(len(scores)) + " 条记录",
			})
		}
	}

	// 7. 校历
	if _, err := academic.LoadCalendar(); err != nil {
		items = append(items, CheckItem{
			ID: "calendar", Level: "fail", Title: "内嵌校历无法解析",
			Detail: errorText(err), Hint: "这是构建问题，请反馈",
		})
	} else {
		items = append(items, CheckItem{
			ID: "calendar", Level: "ok", Title: "校历可用",
			Detail: s.calendarSummary(),
		})
	}

	summary := CheckSummary{}
	for _, item := range items {
		switch item.Level {
		case "ok":
			summary.OK++
		case "warn":
			summary.Warn++
		default:
			summary.Fail++
		}
	}
	return CheckData{Checks: items, Summary: summary, Strict: options.Strict}
}

// calendarSummary 返回校历覆盖范围。
func (s *Service) calendarSummary() string {
	calendar, err := academic.LoadCalendar()
	if err != nil || len(calendar.Semesters) == 0 {
		return "未收录学期"
	}
	parts := make([]string, 0, len(calendar.Semesters))
	for _, semester := range calendar.Semesters {
		parts = append(parts, semester.Label+"（"+itoa(semester.TeachingWeeks)+" 周）")
	}
	return strings.Join(parts, " · ")
}

// errorText 把 error 转成字符串，nil 返回空串。
func errorText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// humanBytes 把字节数转成人类可读形式。
func humanBytes(size int64) string {
	const unit = 1024.0
	value := float64(size)
	switch {
	case value < unit:
		return itoa(int(size)) + " B"
	case value < unit*unit:
		return trimFloat(value/unit) + " KB"
	default:
		return trimFloat(value/(unit*unit)) + " MB"
	}
}

// trimFloat 保留一位小数。
func trimFloat(value float64) string {
	scaled := int(value*10 + 0.5)
	whole := scaled / 10
	fraction := scaled % 10
	return itoa(whole) + "." + itoa(fraction)
}
