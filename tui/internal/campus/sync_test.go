package campus

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// 端到端用例：用本地假服务扮演教务系统，跑一次「登录 + 全量同步」，
// 断言写回的键、结构与图形界面读到的一致。
//
// 这里覆盖的是真实 bug 的高发区：路径拼装、GBK 判定、Cookie 传递、
// 会话失效判定、以及"哪些键在什么情况下写/不写"。

const fakeLoginPage = `<html><body><form action="/academic/j_acegi_security_check">
<input name="j_username"><input name="j_captcha"></form></body></html>`

// fakeCampus 构造一个最小可用的教务系统。
func fakeCampus(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()

	writeHTML := func(w http.ResponseWriter, body string) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(body))
	}
	requireSession := func(w http.ResponseWriter, r *http.Request) bool {
		if cookie, err := r.Cookie("JSESSIONID"); err != nil || cookie.Value != "ok" {
			writeHTML(w, fakeLoginPage)
			return false
		}
		return true
	}

	mux.HandleFunc("/academic/getCaptcha.do", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("fake-image"))
	})
	mux.HandleFunc("/academic/checkCaptcha.do", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("true"))
	})
	mux.HandleFunc("/academic/j_acegi_security_check", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Errorf("登录表单解析失败：%v", err)
		}
		if r.PostForm.Get("j_captcha") != "1234" {
			writeHTML(w, `<html><body><div>验证码错误</div></body></html>`)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "JSESSIONID", Value: "ok", Path: "/academic"})
		writeHTML(w, `<html><body>登录成功</body></html>`)
	})
	mux.HandleFunc("/academic/student/currcourse/currcourse.jsdo", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, contextHTML)
	})
	mux.HandleFunc("/academic/showPersonalInfo.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, profileHTML)
	})
	mux.HandleFunc("/academic/manager/score/studentOwnScore.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, scoresHTML)
	})
	mux.HandleFunc("/academic/manager/studyschedule/studentSelfSchedule.jsdo", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, `<html><body><a href="studentScheduleShowByTerm.do?studentId=12345">x</a></body></html>`)
	})
	mux.HandleFunc("/academic/manager/studyschedule/studentScheduleShowByTerm.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, planHTML)
	})
	mux.HandleFunc("/academic/manager/examstu/studentQueryAllExam.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, examsHTML)
	})
	mux.HandleFunc("/academic/calendarinfo/viewCalendarInfo.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, calendarHTML)
	})
	mux.HandleFunc("/academic/manager/coursearrange/showTimetable.do", func(w http.ResponseWriter, r *http.Request) {
		if !requireSession(w, r) {
			return
		}
		writeHTML(w, timetableHTML)
	})

	return httptest.NewServer(mux)
}

// patchRecorder 记录写回内容，替代真实的 storage.json。
type patchRecorder struct {
	mu   sync.Mutex
	data map[string]string
}

func newPatchRecorder() *patchRecorder {
	return &patchRecorder{data: map[string]string{}}
}

func (r *patchRecorder) apply(patch Patch) (WriteReport, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for key, value := range patch.Set {
		r.data[key] = value
	}
	for _, key := range patch.Delete {
		delete(r.data, key)
	}
	return WriteReport{Written: len(patch.Set) + len(patch.Delete)}, nil
}

func (r *patchRecorder) get(key string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.data[key]
}

func TestLoginAndSyncEndToEnd(t *testing.T) {
	server := fakeCampus(t)
	defer server.Close()

	dir := t.TempDir()
	now := time.Date(2026, 11, 15, 10, 0, 0, 0, time.Local)
	service := NewService(dir, func() time.Time { return now })
	service.client = NewClientAt(service.session, server.URL+"/academic/")

	recorder := newPatchRecorder()
	service.SetWriter(recorder.apply)

	// 验证码错误：必须把教务的中文原因透出来，且不写入任何缓存
	if _, err := service.Login(context.Background(), LoginRequest{
		Username: "2304060322", Password: "pw", Captcha: "0000", Remember: true,
	}); err == nil || !strings.Contains(err.Error(), "验证码") {
		t.Fatalf("验证码错误应返回可读原因，实际：%v", err)
	}
	if recorder.get(KeySessionFlag) != "" {
		t.Fatal("登录失败不应写入登录标记")
	}

	result, err := service.Login(context.Background(), LoginRequest{
		Username: "2304060322", Password: "pw", Captcha: "1234", Remember: true,
	})
	if err != nil {
		t.Fatalf("登录失败：%v", err)
	}
	if result.InternalID != "12345" || result.Year != "2025" || result.Term != "2" {
		t.Fatalf("登录返回的上下文不对：%+v", result)
	}
	if recorder.get(KeySessionFlag) != "true" {
		t.Fatal("登录成功必须写登录标记（图形界面据此判定已登录）")
	}
	if recorder.get(KeySavedNumber) != "2304060322" {
		t.Fatal("记住密码应写入学号")
	}
	if recorder.get(KeySavedCredential) == "" {
		t.Fatal("记住密码应写入混淆后的凭据")
	}
	if recorder.get(KeySessionExpired) != "" {
		t.Fatal("登录成功必须清掉会话失效标记")
	}
	if !service.Status().HasSession {
		t.Fatal("登录后本地应持有会话 Cookie")
	}

	syncResult, err := service.Sync(context.Background(), SyncOptions{Account: "2304060322"})
	if err != nil {
		t.Fatalf("同步失败：%v", err)
	}
	if len(syncResult.Failures) != 0 {
		t.Fatalf("不应有失败项：%v", syncResult.Failures)
	}
	if syncResult.Written == 0 {
		t.Fatal("同步应写回缓存")
	}

	// 结构与图形界面一致：按缓存键反序列化后逐字段核对
	var scores []ScoreEntry
	if err := json.Unmarshal([]byte(recorder.get(KeyScores)), &scores); err != nil {
		t.Fatalf("成绩缓存不是合法数组：%v", err)
	}
	if len(scores) != 3 || scores[0].CourseName != "高等数学" {
		t.Fatalf("成绩缓存内容错误：%+v", scores)
	}

	var exams []ExamEntry
	if err := json.Unmarshal([]byte(recorder.get(KeyExams)), &exams); err != nil || len(exams) != 1 {
		t.Fatalf("考试缓存错误：%v %+v", err, exams)
	}

	var combine Timetable
	if err := json.Unmarshal([]byte(recorder.get(KeyTimetableCombine)), &combine); err != nil {
		t.Fatalf("课表缓存错误：%v", err)
	}
	if len(combine.Cells) != 2 || combine.Cells[0].Location != "教1-101" {
		t.Fatalf("课表缓存内容错误：%+v", combine)
	}

	var plan Plan
	if err := json.Unmarshal([]byte(recorder.get(KeyPlan)), &plan); err != nil || len(plan.Groups) != 2 {
		t.Fatalf("培养方案缓存错误：%v %+v", err, plan)
	}

	var profile Profile
	if err := json.Unmarshal([]byte(recorder.get(KeyProfile)), &profile); err != nil {
		t.Fatalf("档案缓存错误：%v", err)
	}
	if profile.InternalID != "12345" {
		t.Fatalf("档案应带上内部学号（图形界面依赖它请求课表）：%+v", profile)
	}

	var courses []CurrentCourse
	if err := json.Unmarshal([]byte(recorder.get(KeyCurrentCourses)), &courses); err != nil || len(courses) != 1 {
		t.Fatalf("课程名录缓存错误：%v %+v", err, courses)
	}

	var meta struct {
		LastSyncAt   int64  `json:"lastSyncAt"`
		LastSyncDate string `json:"lastSyncDate"`
		LastError    string `json:"lastError"`
	}
	if err := json.Unmarshal([]byte(recorder.get(KeySyncMeta)), &meta); err != nil {
		t.Fatalf("同步元信息错误：%v", err)
	}
	if meta.LastSyncDate != "2026-11-15" || meta.LastSyncAt != now.UnixMilli() {
		t.Fatalf("同步元信息内容错误：%+v", meta)
	}
	if meta.LastError != "" {
		t.Fatalf("无失败项时 lastError 应为空：%q", meta.LastError)
	}
}

func TestSyncMarksOfflineWhenSessionExpired(t *testing.T) {
	server := fakeCampus(t)
	defer server.Close()

	dir := t.TempDir()
	service := NewService(dir, time.Now)
	service.client = NewClientAt(service.session, server.URL+"/academic/")

	recorder := newPatchRecorder()
	service.SetWriter(recorder.apply)

	// 没有会话：直接拒绝，且不产生任何写入
	if _, err := service.Sync(context.Background(), SyncOptions{}); err == nil {
		t.Fatal("没有会话时同步应失败")
	}
	if len(recorder.data) != 0 {
		t.Fatalf("没有会话时不应写缓存：%#v", recorder.data)
	}

	// 有 Cookie 但教务已判定失效（响应是登录页）
	if err := writeSessionCookie(service, "expired"); err != nil {
		t.Fatal(err)
	}
	recorder.data[KeySessionFlag] = "true"

	result, err := service.Sync(context.Background(), SyncOptions{})
	if !result.Expired {
		t.Fatalf("应判定为会话失效：%+v", result)
	}
	if err == nil {
		t.Fatal("会话失效应返回错误")
	}
	if recorder.get(KeySessionFlag) != "" {
		t.Fatal("会话失效必须清除登录标记（图形界面据此进入离线模式）")
	}
	if recorder.get(KeySessionExpired) != "true" {
		t.Fatal("会话失效必须写入失效标记")
	}
}

// writeSessionCookie 直接往会话里塞一个 Cookie（绕过登录流程）。
func writeSessionCookie(service *Service, value string) error {
	service.session.SetCookies(nil, []*http.Cookie{
		{Name: "JSESSIONID", Value: value, Path: "/academic"},
	})
	return service.session.Save()
}
