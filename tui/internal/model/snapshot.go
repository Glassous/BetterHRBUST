package model

import (
	"bytes"
	"encoding/json"
	"sort"
	"sync"
)

// Snapshot 是一次读到内存的 `storage.json` 快照，外加按需解析出的类型化视图。
//
// 设计要点：
//   - 只读。不创建目录、不改名损坏文件、不写回任何内容。
//   - 懒解析。`sync.Once` 保证每个 section 只解析一次；
//     课表页只用到 cells，不必为它解析培养方案。
//   - 单条容错。见 decode.go 的文件注释。
type Snapshot struct {
	Dir         string
	StoragePath string
	// StorageExists 表示 storage.json 是否存在（不存在 != 损坏）。
	StorageExists bool
	// LoadError 非空表示文件存在但读不出来（权限 / 损坏 / 超限），此时视为存储不可用。
	LoadError string
	// StorageSize 是文件字节数，不存在时为 0。
	StorageSize int64

	raw map[string]string

	scoresOnce    sync.Once
	scores        []ScoreRecord
	yearsOnce     sync.Once
	years         []YearOption
	examsOnce     sync.Once
	exams         []ExamRecord
	noticesOnce   sync.Once
	notices       []Notice
	weeksOnce     sync.Once
	noticeWeeks   map[string][]Notice
	coursesOnce   sync.Once
	courses       []CurrentCourse
	planOnce      sync.Once
	plan          Plan
	combineOnce   sync.Once
	combine       Timetable
	baseOnce      sync.Once
	base          Timetable
	profileOnce   sync.Once
	profile       Profile
	syncOnce      sync.Once
	syncMeta      SyncMeta
	classroomOnce sync.Once
	classroom     []string
}

// New 构造快照。raw 为解码后的键值表，可为 nil。
func New(dir, storagePath string, exists bool, loadErr string, size int64, raw map[string]string) *Snapshot {
	if raw == nil {
		raw = map[string]string{}
	}
	return &Snapshot{
		Dir: dir, StoragePath: storagePath, StorageExists: exists,
		LoadError: loadErr, StorageSize: size, raw: raw,
	}
}

// NewFromPairs 供测试使用：直接注入键值，不触碰文件系统。
func NewFromPairs(dir string, pairs map[string]string) *Snapshot {
	copied := make(map[string]string, len(pairs))
	for key, value := range pairs {
		copied[key] = value
	}
	return New(dir, dir+"/storage.json", true, "", 0, copied)
}

// Get 返回某个键的原始字符串。诊断页使用；**凭证键永远不读**。
func (s *Snapshot) Get(key string) (string, bool) {
	if IsSensitiveKey(key) {
		return "", false
	}
	value, ok := s.raw[key]
	return value, ok
}

// HasKey 只判断键是否存在，不暴露内容（凭证键的安全入口）。
func (s *Snapshot) HasKey(key string) bool {
	_, ok := s.raw[key]
	return ok
}

// Keys 返回按键名排序的全部键。
func (s *Snapshot) Keys() []string {
	keys := make([]string, 0, len(s.raw))
	for key := range s.raw {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

// Len 返回键的数量。
func (s *Snapshot) Len() int { return len(s.raw) }

// LoggedIn 只认精确的 "true"，与前端和 Rust 命令行一致。
func (s *Snapshot) LoggedIn() bool { return s.raw[KeySessionFlag] == "true" }

// SessionExpired 只认精确的 "true"。
func (s *Snapshot) SessionExpired() bool { return s.raw[KeySessionExpired] == "true" }

// LastLoginAt 返回上次登录的毫秒时间戳；未记录为 0。
func (s *Snapshot) LastLoginAt() uint64 {
	value, ok := parseFloat(s.raw[KeyLastLogin])
	if !ok || value < 0 {
		return 0
	}
	return uint64(value)
}

// Profile 返回学籍档案（未打码，展示前必须经过 academic.MaskProfile）。
func (s *Snapshot) Profile() Profile {
	s.profileOnce.Do(func() {
		s.profile = DecodeProfile(objectOf(s.raw[KeyProfile]))
	})
	return s.profile
}

// Account 返回账号：优先学号，其次记住的学号。
func (s *Snapshot) Account() string {
	if number := s.Profile().StudentNumber; number != "" {
		return number
	}
	return s.raw[KeySavedNumber]
}

// Scores 返回全部成绩原始记录。
func (s *Snapshot) Scores() []ScoreRecord {
	s.scoresOnce.Do(func() {
		s.scores = decodeList(s.raw[KeyScores], DecodeScore)
	})
	return s.scores
}

// ScoreYears 返回成绩筛选的学年选项。
func (s *Snapshot) ScoreYears() []YearOption {
	s.yearsOnce.Do(func() {
		s.years = decodeList(s.raw[KeyScoreYears], DecodeYearOption)
	})
	return s.years
}

// Exams 返回考试日程。
func (s *Snapshot) Exams() []ExamRecord {
	s.examsOnce.Do(func() {
		s.exams = decodeList(s.raw[KeyExams], DecodeExam)
	})
	return s.exams
}

// Notices 返回当前缓存的公告列表。
func (s *Snapshot) Notices() []Notice {
	s.noticesOnce.Do(func() {
		s.notices = decodeList(s.raw[KeyNotices], DecodeNotice)
	})
	return s.notices
}

// NoticeWeeks 返回按周归档的公告。
//
// 注意空切片的语义：某个周键存在但切片为空，表示「已缓存该周且该周没有公告」，
// 与「未缓存该周」完全不同，调用方必须用第二个返回值区分。
func (s *Snapshot) NoticeWeeks() map[string][]Notice {
	s.weeksOnce.Do(func() {
		s.noticeWeeks = map[string][]Notice{}
		object := objectOf(s.raw[KeyNoticeWeeks])
		if object == nil {
			return
		}
		for week, value := range object {
			list, ok := value.([]any)
			if !ok {
				s.noticeWeeks[week] = []Notice{}
				continue
			}
			notices := make([]Notice, 0, len(list))
			for _, item := range list {
				if child := asMap(item); child != nil {
					notices = append(notices, DecodeNotice(child))
				}
			}
			s.noticeWeeks[week] = notices
		}
	})
	return s.noticeWeeks
}

// CurrentCourses 返回本学期课程名录。
func (s *Snapshot) CurrentCourses() []CurrentCourse {
	s.coursesOnce.Do(func() {
		s.courses = decodeList(s.raw[KeyCurrentCourses], DecodeCurrentCourse)
	})
	return s.courses
}

// Plan 返回培养方案。
func (s *Snapshot) Plan() Plan {
	s.planOnce.Do(func() {
		s.plan = DecodePlan(objectOf(s.raw[KeyPlan]))
	})
	return s.plan
}

// Timetable 返回课表。`base = true` 取小节课表，否则取大节课表。
//
// 大节课表缺失时回退到小节：课时与地点对查询依然有效。
func (s *Snapshot) Timetable(base bool) Timetable {
	if base {
		s.baseOnce.Do(func() {
			s.base = DecodeTimetable(objectOf(s.raw[KeyTimetableBase]))
		})
		return s.base
	}
	s.combineOnce.Do(func() {
		s.combine = DecodeTimetable(objectOf(s.raw[KeyTimetableCombine]))
	})
	if len(s.combine.Cells) > 0 {
		return s.combine
	}
	s.baseOnce.Do(func() {
		s.base = DecodeTimetable(objectOf(s.raw[KeyTimetableBase]))
	})
	if len(s.base.Cells) == 0 {
		return s.combine
	}
	return s.base
}

// SyncMeta 返回上次同步的元信息。
func (s *Snapshot) SyncMeta() SyncMeta {
	s.syncOnce.Do(func() {
		s.syncMeta = DecodeSyncMeta(objectOf(s.raw[KeySyncMeta]))
	})
	return s.syncMeta
}

// ClassroomOptions 返回缓存的空教室筛选项（校区 / 教学楼），仅用于展示。
func (s *Snapshot) ClassroomOptions() []string {
	s.classroomOnce.Do(func() {
		s.classroom = decodeList(s.raw[KeyClassroomOptions], func(object map[string]any) string {
			if label := text(object, "label"); label != "" {
				return label
			}
			return text(object, "name")
		})
	})
	return s.classroom
}

// HasData 与前端 `hasCachedData` 同口径：
// 成绩 / 培养方案 / 课表 / 考试任一非空即算有数据。
// 公告与课程名录单独存在不算——它们不足以支撑概览页。
func (s *Snapshot) HasData() bool {
	return len(s.Scores()) > 0 ||
		len(s.Plan().Groups) > 0 ||
		len(s.Timetable(false).Cells) > 0 ||
		len(s.Exams()) > 0
}

// Counts 返回各表条目数。
func (s *Snapshot) Counts() DataCounts {
	return DataCounts{
		Scores:         len(s.Scores()),
		Exams:          len(s.Exams()),
		TimetableCells: len(s.Timetable(false).Cells),
		PlanGroups:     len(s.Plan().Groups),
		Notices:        len(s.Notices()),
		Courses:        len(s.CurrentCourses()),
	}
}

// ---------- 内部工具 ----------

// objectOf 解析一段 JSON 对象文本；失败或非对象时返回 nil。
func objectOf(raw string) map[string]any {
	text := trimSpace(raw)
	if text == "" {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader([]byte(text)))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil
	}
	return asMap(value)
}

// listOf 解析一段 JSON 数组文本；失败或非数组时返回 nil。
func listOf(raw string) []any {
	text := trimSpace(raw)
	if text == "" {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader([]byte(text)))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil
	}
	list, _ := value.([]any)
	return list
}

// decodeList 逐条解析数组，跳过非对象元素与解析失败的条目。
func decodeList[T any](raw string, decode func(map[string]any) T) []T {
	items := listOf(raw)
	if len(items) == 0 {
		return nil
	}
	out := make([]T, 0, len(items))
	for _, item := range items {
		object := asMap(item)
		if object == nil {
			continue
		}
		out = append(out, decode(object))
	}
	return out
}
