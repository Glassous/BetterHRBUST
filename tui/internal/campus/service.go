package campus

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// Service 是终端界面与教务系统之间的门面：
// 会话（共享文件）、HTTP、解析、写回缓存都收敛在这里。
//
// UI 与 `--json` 只依赖本结构，因此登录与同步逻辑只有一份实现。
type Service struct {
	dir     string
	session *Session
	// mu 保护 client / baseURL：设置页可以在运行中切换教务地址，
	// 而同步与登录可能在另一个 goroutine 里取客户端。
	mu      sync.RWMutex
	client  *Client
	baseURL string
	now     func() time.Time
	// writer 把改动合并写回 `storage.json`；未注入时（测试）只返回结果。
	writer func(Patch) (WriteReport, error)
}

// WriteReport 描述一次写回的结果。
type WriteReport struct {
	// Written 是写入或删除的键数。
	Written int
	// StoragePath 是被写入的缓存文件。
	StoragePath string
	// BridgePort 非 0 表示同时推送给了正在运行的图形界面。
	BridgePort int
	// BridgeError 非空表示推送图形界面失败（文件本身已写好）。
	BridgeError string
}

// NewService 构造教务服务。
func NewService(dataDir string, now func() time.Time) *Service {
	if now == nil {
		now = time.Now
	}
	session := OpenSession(dataDir, now)
	return &Service{
		dir:     dataDir,
		session: session,
		client:  NewClient(session),
		baseURL: OfficialBaseURL,
		now:     now,
	}
}

// clientFor 返回当前生效的客户端（切换教务地址后拿到的是新客户端）。
func (s *Service) clientFor() *Client {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.client
}

// BaseURL 返回当前生效的教务地址（以 /academic/ 结尾）。
func (s *Service) BaseURL() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.baseURL == "" {
		return OfficialBaseURL
	}
	return s.baseURL
}

// SetBaseURL 归一化并立即切换目标教务站点。
//
// 只影响后续请求：已在会话文件里的 Cookie 按站点隔离保留，
// 切回原地址时旧登录态仍可用；非法地址返回中文错误且不生效。
func (s *Service) SetBaseURL(raw string) error {
	normalized, err := NormalizeBase(raw)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.baseURL == normalized && s.client != nil {
		return nil
	}
	s.baseURL = normalized
	s.client = NewClientAt(s.session, normalized)
	return nil
}

// SetWriter 注入缓存写回实现（由 cmd 接到 store.Writer）。
func (s *Service) SetWriter(writer func(Patch) (WriteReport, error)) { s.writer = writer }

// DataDir 返回数据目录。
func (s *Service) DataDir() string { return s.dir }

// SessionPath 返回共享会话文件路径。
func (s *Service) SessionPath() string { return s.session.Path() }

// Status 是会话状态快照，供界面展示。
type Status struct {
	// HasSession 表示持有可用的教务 Cookie（不保证教务侧未过期）。
	HasSession bool
	// Source 是会话来源："tui" / "gui"。
	Source string
	// UpdatedAt 是会话文件最后写入时间。
	UpdatedAt time.Time
	// StudentNumber 是会话记录的学号。
	StudentNumber string
	// Path 是会话文件路径。
	Path string
	// LoadError 是会话文件读取问题。
	LoadError string
}

// Status 返回当前会话状态。
func (s *Service) Status() Status {
	return Status{
		HasSession:    s.session.HasCookies(),
		Source:        s.session.Source(),
		UpdatedAt:     s.session.UpdatedAt(),
		StudentNumber: s.session.StudentNumber(),
		Path:          s.session.Path(),
		LoadError:     s.session.LoadError(),
	}
}

// SavedCredential 返回共享缓存里"记住的密码"，供登录表单预填。
//
// 与图形界面同一份数据（`better_hrbust_saved_credential`），
// 因此在图形界面勾选过「记住密码」后，终端不必再输一遍学号。
func (s *Service) SavedCredential(snapshot *model.Snapshot) (username, password string, ok bool) {
	if snapshot == nil {
		return "", "", false
	}
	encoded, exists := snapshot.SavedCredentialBlob()
	if !exists {
		return "", "", false
	}
	var payload struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.Unmarshal([]byte(encoded), &payload); err != nil {
		return "", "", false
	}
	if payload.Username == "" || payload.Password == "" {
		return "", "", false
	}
	masked, err := decodeBase64(payload.Password)
	if err != nil {
		return "", "", false
	}
	return payload.Username, string(xorBytes(masked)), true
}

// SavedNumber 返回共享缓存里记住的学号。
func (s *Service) SavedNumber(snapshot *model.Snapshot) string {
	if snapshot == nil {
		return ""
	}
	value, _ := snapshot.Get(KeySavedNumber)
	return value
}

// write 把改动写回共享缓存。
func (s *Service) write(patch Patch) error {
	if patch.Empty() || s.writer == nil {
		return nil
	}
	_, err := s.writer(patch)
	return err
}

// contextWithTimeout 给单个环节加上超时，避免教务挂起时按钮永远转圈。
func contextWithTimeout(parent context.Context, timeout time.Duration) (context.Context, context.CancelFunc) {
	if timeout <= 0 {
		return context.WithCancel(parent)
	}
	return context.WithTimeout(parent, timeout)
}
