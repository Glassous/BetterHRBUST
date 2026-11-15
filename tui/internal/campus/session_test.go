package campus

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSessionRoundTripThroughSharedFile(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 11, 15, 9, 30, 0, 0, time.Local)

	session := OpenSession(dir, func() time.Time { return now })
	if session.HasCookies() {
		t.Fatal("空目录不应有会话")
	}

	target, _ := url.Parse(SessionOrigin + "/academic/")
	session.SetCookies(target, []*http.Cookie{{Name: "JSESSIONID", Value: "abc", Path: "/academic"}})
	session.SetStudentNumber("2304060322")
	if err := session.Save(); err != nil {
		t.Fatalf("保存会话失败：%v", err)
	}

	path := filepath.Join(dir, SessionFileName)
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("会话文件应被创建：%v", err)
	}
	if info.Size() == 0 {
		t.Fatal("会话文件不应为空")
	}

	// 另一端的进程（图形界面）读同一份文件：来源与学号都要能拿到
	reopened := OpenSession(dir, func() time.Time { return now })
	if !reopened.HasCookies() {
		t.Fatal("重新打开应读到会话")
	}
	if reopened.StudentNumber() != "2304060322" {
		t.Fatalf("学号未持久化：%q", reopened.StudentNumber())
	}
	if header := reopened.CookieHeader(); header != "JSESSIONID=abc" {
		t.Fatalf("Cookie 头拼装错误：%q", header)
	}
}

func TestSessionDropsExpiredCookies(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 11, 15, 9, 30, 0, 0, time.Local)

	payload := sessionFile{
		Version:   SessionVersion,
		Origin:    SessionOrigin,
		Source:    "gui",
		UpdatedAt: now.UnixMilli(),
		Cookies: []StoredCookie{
			{Name: "JSESSIONID", Value: "fresh", Path: "/academic"},
			{Name: "stale", Value: "x", ExpiresAt: now.Add(-time.Hour).UnixMilli()},
		},
	}
	encoded, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, SessionFileName), encoded, 0o600); err != nil {
		t.Fatal(err)
	}

	session := OpenSession(dir, func() time.Time { return now })
	if !session.HasCookies() {
		t.Fatal("未过期的 Cookie 应保留")
	}
	if header := session.CookieHeader(); header != "JSESSIONID=fresh" {
		t.Fatalf("过期 Cookie 必须被剔除：%q", header)
	}
	if session.Source() != "gui" {
		t.Fatalf("来源应保留：%q", session.Source())
	}
}

func TestSessionClearRemovesFile(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	session := OpenSession(dir, func() time.Time { return now })
	target, _ := url.Parse(SessionOrigin + "/academic/")
	session.SetCookies(target, []*http.Cookie{{Name: "JSESSIONID", Value: "abc"}})
	if err := session.Save(); err != nil {
		t.Fatal(err)
	}
	if err := session.Clear(); err != nil {
		t.Fatalf("清空会话失败：%v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, SessionFileName)); !os.IsNotExist(err) {
		t.Fatalf("会话文件应被删除，实际 err=%v", err)
	}
	if session.HasCookies() {
		t.Fatal("清空后不应再有会话")
	}
}

func TestSessionToleratesCorruptFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, SessionFileName), []byte("{ not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	session := OpenSession(dir, time.Now)
	if session.HasCookies() {
		t.Fatal("损坏的会话文件应按未登录处理")
	}
	if session.LoadError() == "" {
		t.Fatal("应记录损坏原因，便于诊断页展示")
	}
}

func TestCredentialObfuscationMatchesFrontendFormat(t *testing.T) {
	encoded, err := obfuscateCredential("2304060322", "secret-pw")
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.Unmarshal([]byte(encoded), &payload); err != nil {
		t.Fatalf("混淆结果必须是 JSON：%v", err)
	}
	if payload.Username != "2304060322" {
		t.Fatalf("学号未写入：%q", payload.Username)
	}
	raw, err := decodeBase64(payload.Password)
	if err != nil {
		t.Fatalf("密码必须是 Base64：%v", err)
	}
	if plain := string(xorBytes(raw)); plain != "secret-pw" {
		t.Fatalf("解密结果错误：%q", plain)
	}
	if payload.Password == "secret-pw" {
		t.Fatal("密码不能以明文落盘")
	}
}
