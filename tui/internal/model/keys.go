// Package model 定义桌面端写入本地缓存的领域模型与宽松反序列化。
//
// 数据来源与图形界面、命令行完全一致：`storage.json` 是一个
// `map[string]string`，其中每个值本身又是前端 `JSON.stringify` 后的字符串。
// 结构体字段名与前端缓存逐字对应（camelCase），本包不做任何业务计算。
//
// 与 Rust 命令行（`desktop-tauri/src-tauri/src/cli/store.rs`）的差异只有一处，
// 且是有意为之：Rust 用 `from_str::<Vec<T>>` 反序列化整张列表，任一条记录
// 字段类型异常就会清空整张表；这里改为逐条解析，坏记录只丢自己。
// 一条脏数据不该让成绩页整页空白。
package model

// 存储键（与前端 `web/src/composables/useAcademicData.js` 的 CACHE_KEYS 一致）。
const (
	KeySessionFlag    = "better_hrbust_has_session"
	KeySessionExpired = "better_hrbust_session_expired"
	KeyLastLogin      = "better_hrbust_last_login_at"
	KeyProfile        = "better_hrbust_cached_profile"
	KeySavedNumber    = "saved_student_number"
	// KeySavedCredential 保存的账号密码（XOR + Base64 混淆）。
	// 终端界面**只判断存在性**，永不读取、输出或转发其内容。
	KeySavedCredential  = "better_hrbust_saved_credential"
	KeySyncMeta         = "better_hrbust_cache_sync_meta"
	KeyScores           = "better_hrbust_cache_scores"
	KeyScoreYears       = "better_hrbust_cache_score_years"
	KeyPlan             = "better_hrbust_cache_program_plan"
	KeyTimetableCombine = "better_hrbust_cache_timetable"
	KeyTimetableBase    = "better_hrbust_cache_timetable_base"
	KeyExams            = "better_hrbust_cache_exams"
	KeyNotices          = "better_hrbust_cache_notices"
	KeyNoticeWeeks      = "better_hrbust_cache_notices_weeks"
	KeyCurrentCourses   = "better_hrbust_cache_current_courses"
	// KeyClassroomOptions 空教室的可选校区/教学楼，前端会写；终端只做只读展示。
	KeyClassroomOptions = "better_hrbust_cache_classroom_options"
)

// IsSensitiveKey 判断键是否属于凭证类：诊断页只报告存在性与长度。
func IsSensitiveKey(key string) bool {
	return key == KeySavedCredential
}

// AllKeys 返回全部已知缓存键，供诊断页按固定顺序展示。
func AllKeys() []string {
	return []string{
		KeySessionFlag, KeySessionExpired, KeyLastLogin,
		KeyProfile, KeySavedNumber, KeySavedCredential,
		KeySyncMeta, KeyScores, KeyScoreYears, KeyPlan,
		KeyTimetableCombine, KeyTimetableBase, KeyExams,
		KeyNotices, KeyNoticeWeeks, KeyCurrentCourses, KeyClassroomOptions,
	}
}
