package query

// Envelope 是 `--json` 成功输出的统一结构。
//
// 字段名与 `BetterHRBUSTCLI` 逐字一致：脚本与 AI 工具按这些键取值，
// 新增字段只能追加，不能重命名或删除。
type Envelope struct {
	Command      string `json:"command"`
	OK           bool   `json:"ok"`
	GeneratedAt  string `json:"generatedAt"`
	LoggedIn     bool   `json:"loggedIn"`
	Offline      bool   `json:"offline"`
	Account      string `json:"account"`
	LastLoginAt  uint64 `json:"lastLoginAt"`
	LastSyncAt   uint64 `json:"lastSyncAt"`
	LastSyncText string `json:"lastSyncText"`
	SyncError    string `json:"syncError"`
	Data         any    `json:"data"`
}

// ErrorBody 是失败结构里的错误详情。
type ErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Failure 是 `--json` 失败输出。
type Failure struct {
	Command string    `json:"command"`
	OK      bool      `json:"ok"`
	Error   ErrorBody `json:"error"`
}

// Envelope 组装成功输出。
func (s *Service) Envelope(command string, data any) Envelope {
	meta := s.Snapshot.SyncMeta()
	return Envelope{
		Command:      command,
		OK:           true,
		GeneratedAt:  s.GeneratedAtText(),
		LoggedIn:     s.LoggedIn(),
		Offline:      s.Offline(),
		Account:      s.Account(),
		LastLoginAt:  s.Snapshot.LastLoginAt(),
		LastSyncAt:   meta.LastSyncAt,
		LastSyncText: s.LastSyncText(),
		SyncError:    meta.LastError,
		Data:         data,
	}
}

// Failure 组装失败输出。
func (s *Service) Failure(command, code, message string) Failure {
	return Failure{Command: command, OK: false, Error: ErrorBody{Code: code, Message: message}}
}
