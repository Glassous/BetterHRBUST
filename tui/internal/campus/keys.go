package campus

import "github.com/Glassous/BetterHRBUST/tui/internal/model"

// 同步写回时使用的缓存键。
//
// 键名唯一来源仍是 `internal/model`（与前端 CACHE_KEYS 逐字一致），
// 这里只是给同步路径一个短别名，避免每个写入点都写 model. 前缀。
const (
	KeySessionFlag      = model.KeySessionFlag
	KeySessionExpired   = model.KeySessionExpired
	KeyLastLogin        = model.KeyLastLogin
	KeyProfile          = model.KeyProfile
	KeySavedNumber      = model.KeySavedNumber
	KeySavedCredential  = model.KeySavedCredential
	KeySyncMeta         = model.KeySyncMeta
	KeyScores           = model.KeyScores
	KeyScoreYears       = model.KeyScoreYears
	KeyPlan             = model.KeyPlan
	KeyTimetableCombine = model.KeyTimetableCombine
	KeyTimetableBase    = model.KeyTimetableBase
	KeyExams            = model.KeyExams
	KeyNotices          = model.KeyNotices
	KeyNoticeWeeks      = model.KeyNoticeWeeks
	KeyCurrentCourses   = model.KeyCurrentCourses
)

// DataKeys 是需要随「换账号 / 清缓存」一起删除的业务数据键。
var DataKeys = []string{
	KeySyncMeta, KeyScores, KeyScoreYears, KeyPlan,
	KeyTimetableCombine, KeyTimetableBase, KeyExams,
	KeyNotices, KeyNoticeWeeks, KeyCurrentCourses,
}

// Patch 是一次对共享缓存的修改：Set 写入、Delete 删除。
//
// 之所以不是直接给 `map[string]string`：登出、换账号需要**删除**键，
// 而"写入空字符串"与"删除"在前端语义里并不等价。
type Patch struct {
	Set    map[string]string
	Delete []string
}

// Keys 返回本次修改涉及的全部键（诊断与日志用）。
func (p Patch) Keys() []string {
	keys := make([]string, 0, len(p.Set)+len(p.Delete))
	for key := range p.Set {
		keys = append(keys, key)
	}
	keys = append(keys, p.Delete...)
	return keys
}

// Empty 表示没有任何改动。
func (p Patch) Empty() bool { return len(p.Set) == 0 && len(p.Delete) == 0 }
