package campus

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// 会话桥接文件。
//
// 三端（图形界面 / 命令行 / 终端界面）共用一份教务会话，文件位于
// 数据目录下的 academic-session.json：
//
//   - 终端界面登录后写；图形界面的本地反代在收到 Set-Cookie 时也写；
//   - 终端界面启动时读进 Cookie Jar；图形界面的反代在请求缺 Cookie 时补上。
//
// 这样"在终端登录一次，图形界面直接可用"，反之亦然，真正做到无感。
// 文件里只有教务系统签发的会话 Cookie，权限 0600。
const (
	// SessionFileName 是会话桥接文件名。
	SessionFileName = "academic-session.json"
	// SessionVersion 是文件结构版本。
	SessionVersion = 1
	// SessionOrigin 是会话所属的教务系统 origin。
	SessionOrigin = "http://jwzx.hrbust.edu.cn"
)

// StoredCookie 是一个序列化后的 Cookie。
type StoredCookie struct {
	Name  string `json:"name"`
	Value string `json:"value"`
	Path  string `json:"path,omitempty"`
	// ExpiresAt 是毫秒时间戳；0 表示会话 Cookie。
	ExpiresAt int64 `json:"expiresAt,omitempty"`
	// Host 是 Cookie 归属的教务站点主机名（空 = 官方站点，兼容旧文件）。
	// 切换自定义教务地址后，官方站点的 Cookie 不会被发给第三方站点。
	Host string `json:"host,omitempty"`
}

// jarHost 归一化 Cookie 归属主机：空值按官方站点处理。
func jarHost(host string) string {
	trimmed := strings.ToLower(strings.TrimSpace(host))
	if trimmed == "" {
		return OfficialHost
	}
	return trimmed
}

// jarKey 是内存 Cookie 表的键：不同站点的同名 Cookie（都是 JSESSIONID）
// 必须互不覆盖，否则切回原地址时会串用会话。
func jarKey(host, name string) string {
	return jarHost(host) + "\x1f" + name
}

// sessionFile 是会话文件的磁盘结构。
type sessionFile struct {
	Version       int            `json:"version"`
	Origin        string         `json:"origin"`
	Source        string         `json:"source"`
	UpdatedAt     int64          `json:"updatedAt"`
	StudentNumber string         `json:"studentNumber,omitempty"`
	Cookies       []StoredCookie `json:"cookies"`
}

// Session 是跨端共享的教务会话（Cookie Jar + 桥接文件）。
type Session struct {
	path  string
	now   func() time.Time
	mu    sync.Mutex
	jar   map[string]StoredCookie
	src   string
	num   string
	stamp time.Time
	dirty bool
	err   string
}

// OpenSession 读入数据目录下的会话文件；文件缺失或损坏都不会报错，
// 只是退化成"没有会话"（损坏原因可通过 Status 展示）。
func OpenSession(dir string, now func() time.Time) *Session {
	if now == nil {
		now = time.Now
	}
	session := &Session{
		path: filepath.Join(dir, SessionFileName),
		now:  now,
		jar:  map[string]StoredCookie{},
	}
	session.reload()
	return session
}

// Path 返回会话文件路径。
func (s *Session) Path() string { return s.path }

// LoadError 返回读取会话文件时的问题（空串表示正常）。
func (s *Session) LoadError() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.err
}

// Source 返回会话来源（"tui" / "gui"），未知为空串。
func (s *Session) Source() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.src
}

// StudentNumber 返回会话记录里的学号。
func (s *Session) StudentNumber() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.num
}

// UpdatedAt 返回会话最后写入时间。
func (s *Session) UpdatedAt() time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stamp
}

// HasCookies 表示当前是否持有可用的教务会话 Cookie。
func (s *Session) HasCookies() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.validLocked()
}

// Snapshot 返回当前非过期的 Cookie 快照。
func (s *Session) Snapshot() []StoredCookie {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]StoredCookie, 0, len(s.jar))
	for _, cookie := range s.jar {
		out = append(out, cookie)
	}
	return out
}

// ApplyCookies 把外部（图形界面反代）拿到的 Cookie 并入会话。
func (s *Session) ApplyCookies(cookies []StoredCookie) {
	s.mu.Lock()
	for _, cookie := range cookies {
		if cookie.Name == "" {
			continue
		}
		s.jar[jarKey(cookie.Host, cookie.Name)] = cookie
		s.dirty = true
	}
	s.mu.Unlock()
}

// SetStudentNumber 记录当前登录学号（写进桥接文件，供图形界面预填）。
func (s *Session) SetStudentNumber(number string) {
	s.mu.Lock()
	if s.num != number {
		s.num = number
		s.dirty = true
	}
	s.mu.Unlock()
}

// Clear 结束会话：清空 Cookie 并删除桥接文件。
func (s *Session) Clear() error {
	s.mu.Lock()
	s.jar = map[string]StoredCookie{}
	s.src = ""
	s.num = ""
	s.stamp = time.Time{}
	s.dirty = false
	s.err = ""
	s.mu.Unlock()
	err := os.Remove(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// Refresh 在文件被其他端（图形界面）更新时重新读入。
//
// 每次请求前调用：只是 stat + 必要时重读，代价极小，
// 换来"图形界面刚登录，终端立刻可用"。
func (s *Session) Refresh() {
	info, err := os.Stat(s.path)
	if err != nil {
		return
	}
	s.mu.Lock()
	stale := info.ModTime().After(s.stamp)
	s.mu.Unlock()
	if stale {
		s.reload()
	}
}

// Save 把当前会话写回桥接文件（原子替换，权限 0600）。
//
// 没有变化时不写盘：一次同步会打十来个请求，绝大多数响应不带 Set-Cookie，
// 没必要每次都重写同一份文件。
func (s *Session) Save() error {
	s.mu.Lock()
	if !s.dirty {
		s.mu.Unlock()
		return nil
	}
	s.src = "tui"
	s.stamp = s.now()
	payload := sessionFile{
		Version:       SessionVersion,
		Origin:        SessionOrigin,
		Source:        "tui",
		UpdatedAt:     s.stamp.UnixMilli(),
		StudentNumber: s.num,
		Cookies:       make([]StoredCookie, 0, len(s.jar)),
	}
	for _, cookie := range s.jar {
		payload.Cookies = append(payload.Cookies, cookie)
	}
	s.dirty = false
	s.mu.Unlock()

	encoded, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return err
	}
	encoded = append(encoded, '\n')
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, encoded, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// reload 重新读盘。
func (s *Session) reload() {
	s.mu.Lock()
	defer s.mu.Unlock()

	raw, err := os.ReadFile(s.path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			s.err = fmt.Sprintf("无法读取会话文件（%v）", err)
		}
		return
	}
	if info, statErr := os.Stat(s.path); statErr == nil {
		s.stamp = info.ModTime()
	}

	var payload sessionFile
	if err := json.Unmarshal(raw, &payload); err != nil {
		s.err = fmt.Sprintf("会话文件格式不正确（%v），已按未登录处理", err)
		return
	}
	s.err = ""
	s.src = payload.Source
	s.num = payload.StudentNumber
	jar := make(map[string]StoredCookie, len(payload.Cookies))
	expired := s.now().UnixMilli()
	for _, cookie := range payload.Cookies {
		if cookie.Name == "" || cookie.Value == "" {
			continue
		}
		if cookie.ExpiresAt > 0 && cookie.ExpiresAt <= expired {
			continue
		}
		jar[jarKey(cookie.Host, cookie.Name)] = cookie
	}
	s.jar = jar
}

// validLocked 判断是否持有会话 Cookie（调用方需持锁）。
func (s *Session) validLocked() bool {
	for _, cookie := range s.jar {
		if cookie.ExpiresAt > 0 && cookie.ExpiresAt <= s.now().UnixMilli() {
			continue
		}
		return true
	}
	return false
}

// ---------- http.CookieJar 实现（只服务单一 origin，不需要完整 RFC 6265） ----------

// SetCookies 实现 http.CookieJar。
func (s *Session) SetCookies(u *url.URL, cookies []*http.Cookie) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	host := jarHost(hostOf(u))
	for _, cookie := range cookies {
		if cookie.Name == "" {
			continue
		}
		key := jarKey(host, cookie.Name)
		// 过期 / 删除语义：MaxAge < 0 或 Expires 已过即移除
		if cookie.MaxAge < 0 || (!cookie.Expires.IsZero() && cookie.Expires.Before(now)) {
			delete(s.jar, key)
			s.dirty = true
			continue
		}
		stored := StoredCookie{Name: cookie.Name, Value: cookie.Value, Path: cookie.Path, Host: host}
		if !cookie.Expires.IsZero() {
			stored.ExpiresAt = cookie.Expires.UnixMilli()
		}
		s.jar[key] = stored
		s.dirty = true
	}
}

// Cookies 实现 http.CookieJar：只返回归属当前请求站点的 Cookie。
func (s *Session) Cookies(u *url.URL) []*http.Cookie {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UnixMilli()
	host := jarHost(hostOf(u))
	out := make([]*http.Cookie, 0, len(s.jar))
	for _, cookie := range s.jar {
		if cookie.ExpiresAt > 0 && cookie.ExpiresAt <= now {
			continue
		}
		if jarHost(cookie.Host) != host {
			continue
		}
		item := &http.Cookie{Name: cookie.Name, Value: cookie.Value, Path: cookie.Path}
		if cookie.ExpiresAt > 0 {
			item.Expires = time.UnixMilli(cookie.ExpiresAt)
		}
		out = append(out, item)
	}
	return out
}

// hostOf 取 URL 的主机名；u 为 nil 时按官方站点处理。
func hostOf(u *url.URL) string {
	if u == nil {
		return OfficialHost
	}
	return u.Hostname()
}

// CookieHeader 返回可直接用于 Cookie 请求头的文本（为空表示没有会话）。
func (s *Session) CookieHeader() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UnixMilli()
	parts := make([]string, 0, len(s.jar))
	for _, cookie := range s.jar {
		if cookie.ExpiresAt > 0 && cookie.ExpiresAt <= now {
			continue
		}
		parts = append(parts, cookie.Name+"="+cookie.Value)
	}
	return strings.Join(parts, "; ")
}
