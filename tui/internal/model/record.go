package model

// 本文件是缓存结构的 Go 版本，字段与前端缓存逐字对应（camelCase）。
// 结构体只用于承载数据与序列化输出，业务计算一律在 internal/academic。

// ScoreRecord 是一条成绩记录。同一门课可能因补考/重修/多学期修读出现多条。
type ScoreRecord struct {
	Year       string `json:"year"`
	Term       string `json:"term"`
	CourseID   string `json:"courseId"`
	CourseName string `json:"courseName"`
	CourseSeq  string `json:"courseSeq"`
	// CourseGroup 是课组名，学位绩点的 A–E 类判定依赖它。
	CourseGroup string `json:"courseGroup"`
	// Score 是总评原文：可能是数字、五级制等级，或「缓考」等无结论文本。
	Score    string  `json:"score"`
	Credit   float64 `json:"credit"`
	Hours    float64 `json:"hours"`
	Property string  `json:"property"`
	Remark   string  `json:"remark"`
	ExamType string  `json:"examType"`
	PassMark string  `json:"passMark"`
	Passed   bool    `json:"passed"`
	Pending  bool    `json:"pending"`

	// 以下两字段由 academic.Dedupe 计算，不属于缓存内容，
	// 因此不参与序列化——与 Rust 侧的 `#[serde(skip)]` 一致。
	// 需要输出时由 query.CourseEntry 包装成 `{record, isRetake, recordCount}`。
	IsRetake    bool `json:"-"`
	RecordCount int  `json:"-"`
}

// TimetableCell 是课程表上的一个格子。
type TimetableCell struct {
	ID           string `json:"id"`
	CourseID     string `json:"courseId"`
	Day          int    `json:"day"`
	SectionIndex int    `json:"sectionIndex"`
	SectionLabel string `json:"sectionLabel"`
	CourseName   string `json:"courseName"`
	CourseSeq    string `json:"courseSeq"`
	Location     string `json:"location"`
	Teacher      string `json:"teacher"`
	Weeks        string `json:"weeks"`
	HoursType    string `json:"hoursType"`
	// RawLines 是单元格未拆解的原始行；旧缓存可能只有它没有上面几个字段。
	RawLines []string `json:"rawLines,omitempty"`
}

// UnarrangedCourse 是「未安排时间」的课程（课程设计、实习等）。
type UnarrangedCourse struct {
	CourseID   string `json:"courseId"`
	CourseName string `json:"courseName"`
	CourseSeq  string `json:"courseSeq"`
	Teacher    string `json:"teacher"`
	MergeClass string `json:"mergeClass"`
	Weeks      string `json:"weeks"`
	Day        string `json:"day"`
	Location   string `json:"location"`
}

// Timetable 是一张课程表：格子 + 未安排课程。
type Timetable struct {
	Cells      []TimetableCell    `json:"cells"`
	Unarranged []UnarrangedCourse `json:"unarranged"`
}

// ExamRecord 是一场考试。
type ExamRecord struct {
	CourseID   string `json:"courseId"`
	CourseName string `json:"courseName"`
	// Time 是原文时间串，格式不固定，由 academic.ParseExamTime 解析。
	Time     string `json:"time"`
	Location string `json:"location"`
	Property string `json:"property"`
}

// Notice 是一条教学公告。Date 恒为空——来源本身没有日期字段，不臆造。
type Notice struct {
	Title   string `json:"title"`
	Content string `json:"content"`
	Date    string `json:"date"`
}

// PlanCourse 是培养方案里的候选课程。
type PlanCourse struct {
	Code     string  `json:"code"`
	Name     string  `json:"name"`
	Credit   float64 `json:"credit"`
	Hours    float64 `json:"hours"`
	Property string  `json:"property"`
}

// PlanGroup 是一个课组（学科基础课、专业限选……）。
type PlanGroup struct {
	ID              string       `json:"id"`
	Name            string       `json:"name"`
	Property        string       `json:"property"`
	RequiredCredits float64      `json:"requiredCredits"`
	RequiredCourses int          `json:"requiredCourses"`
	EarnedCredits   float64      `json:"earnedCredits"`
	PassedCourses   int          `json:"passedCourses"`
	Courses         []PlanCourse `json:"courses"`
}

// Plan 是整份培养方案。
type Plan struct {
	Groups               []PlanGroup `json:"groups"`
	TotalRequiredCredits *float64    `json:"totalRequiredCredits"`
}

// CurrentCourse 是本学期修读的课程名录条目。
type CurrentCourse struct {
	CourseID   string  `json:"courseId"`
	CourseSeq  string  `json:"courseSeq"`
	CourseName string  `json:"courseName"`
	Teacher    string  `json:"teacher"`
	Credit     float64 `json:"credit"`
	Hours      float64 `json:"hours"`
}

// ProfileChange 是一条学籍异动记录。
type ProfileChange struct {
	Kind   string `json:"type"`
	Date   string `json:"date"`
	Reason string `json:"reason"`
	Remark string `json:"remark"`
}

// Profile 是学籍档案。证件号、手机号、邮箱、地址在展示前必须打码。
type Profile struct {
	StudentNumber string          `json:"studentNumber"`
	InternalID    string          `json:"internalId"`
	RealName      string          `json:"realName"`
	College       string          `json:"college"`
	Major         string          `json:"major"`
	Direction     string          `json:"direction"`
	Grade         string          `json:"grade"`
	ClassName     string          `json:"className"`
	StudentType   string          `json:"studentType"`
	Status        string          `json:"status"`
	IDCard        string          `json:"idCard"`
	Email         string          `json:"email"`
	Phone         string          `json:"phone"`
	Address       string          `json:"address"`
	PostalCode    string          `json:"postalCode"`
	PhotoURL      string          `json:"photoUrl"`
	Changes       []ProfileChange `json:"changes"`
}

// SyncMeta 是上次同步的元信息。
type SyncMeta struct {
	LastSyncAt   uint64 `json:"lastSyncAt"`
	LastSyncDate string `json:"lastSyncDate"`
	LastError    string `json:"lastError"`
}

// YearOption 是成绩筛选器里的学年选项。
type YearOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// DataCounts 是各缓存表的条目数。
type DataCounts struct {
	Scores         int `json:"scores"`
	Exams          int `json:"exams"`
	TimetableCells int `json:"timetableCells"`
	PlanGroups     int `json:"planGroups"`
	Notices        int `json:"notices"`
	Courses        int `json:"courses"`
}
