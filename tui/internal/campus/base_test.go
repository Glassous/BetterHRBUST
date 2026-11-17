package campus

import (
	"net/http"
	"net/url"
	"testing"
	"time"
)

func newTestClient(t *testing.T, baseURL string) *Client {
	t.Helper()
	return NewClientAt(OpenSession(t.TempDir(), time.Now), baseURL)
}

func TestNormalizeBaseDefaultsToOfficial(t *testing.T) {
	for _, raw := range []string{"", "   "} {
		got, err := NormalizeBase(raw)
		if err != nil {
			t.Fatalf("空值不应报错：%v", err)
		}
		if got != OfficialBaseURL {
			t.Fatalf("空值应回退官方默认，实际 %q", got)
		}
	}
}

func TestNormalizeBaseAcceptsPastedHomepage(t *testing.T) {
	cases := map[string]string{
		"https://jwzx.fiacloud.top/homepage/index.do": "https://jwzx.fiacloud.top/academic/",
		"jwzx.fiacloud.top":                           "https://jwzx.fiacloud.top/academic/",
		"jwzx.hrbust.edu.cn":                          "http://jwzx.hrbust.edu.cn/academic/",
		"http://10.20.30.40:8080/academic/x.jsdo":     "http://10.20.30.40:8080/academic/",
		"  https://mirror.example.edu.cn/  ":          "https://mirror.example.edu.cn/academic/",
	}
	for raw, want := range cases {
		got, err := NormalizeBase(raw)
		if err != nil {
			t.Fatalf("%q 解析失败：%v", raw, err)
		}
		if got != want {
			t.Fatalf("%q 归一化结果应为 %q，实际 %q", raw, want, got)
		}
	}
}

func TestNormalizeBaseRejectsInvalid(t *testing.T) {
	for _, raw := range []string{
		"ftp://jwzx.example.edu.cn",
		"http://user:pass@jwzx.example.edu.cn",
		"https://",
	} {
		if _, err := NormalizeBase(raw); err == nil {
			t.Fatalf("%q 应当被拒绝", raw)
		}
	}
}

// 自定义 base 下，教务返回的绝对地址（当前域名或官方域名）都要剥掉 origin 重挂，
// 否则请求会跑到别的站点。
func TestResolveUsesCustomBase(t *testing.T) {
	client := newTestClient(t, "https://jwzx.fiacloud.top/academic/")
	cases := map[string]string{
		"student/x.jsdo":                                  "https://jwzx.fiacloud.top/academic/student/x.jsdo",
		"/academic/student/x.jsdo":                        "https://jwzx.fiacloud.top/academic/student/x.jsdo",
		"https://jwzx.fiacloud.top/academic/student/x.do": "https://jwzx.fiacloud.top/academic/student/x.do",
		"http://jwzx.hrbust.edu.cn/academic/student/x.do": "https://jwzx.fiacloud.top/academic/student/x.do",
	}
	for path, want := range cases {
		if got := client.resolve(path).String(); got != want {
			t.Fatalf("resolve(%q) = %q，期望 %q", path, got, want)
		}
	}
}

// 默认地址时行为与改造前完全一致（官方 HTTP + /academic/ 前缀）。
func TestResolveKeepsOfficialDefault(t *testing.T) {
	client := newTestClient(t, OfficialBaseURL)
	got := client.resolve("https://jwzx.hrbust.edu.cn/academic/student/x.jsdo?a=1")
	want, _ := url.Parse("http://jwzx.hrbust.edu.cn/academic/student/x.jsdo?a=1")
	if got.String() != want.String() {
		t.Fatalf("默认地址解析应保持不变，实际 %q", got.String())
	}
}

// 服务切换地址后立即换客户端，非法地址保持原状。
func TestServiceSetBaseURL(t *testing.T) {
	service := NewService(t.TempDir(), time.Now)
	if got := service.BaseURL(); got != OfficialBaseURL {
		t.Fatalf("初始应为官方地址，实际 %q", got)
	}
	if err := service.SetBaseURL("jwzx.fiacloud.top"); err != nil {
		t.Fatalf("切换失败：%v", err)
	}
	if got := service.BaseURL(); got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("切换后的地址不正确：%q", got)
	}
	if err := service.SetBaseURL("ftp://bad"); err == nil {
		t.Fatal("非法地址应返回错误")
	}
	if got := service.BaseURL(); got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("非法地址不应改变生效地址：%q", got)
	}
	if err := service.SetBaseURL(""); err != nil {
		t.Fatalf("恢复默认失败：%v", err)
	}
	if got := service.BaseURL(); got != OfficialBaseURL {
		t.Fatalf("恢复默认后的地址不正确：%q", got)
	}
}

// 不同站点的同名 Cookie 互不串用：切换地址不会把官方会话发给第三方站点。
func TestSessionCookiesAreIsolatedPerHost(t *testing.T) {
	session := OpenSession(t.TempDir(), time.Now)
	official, _ := url.Parse(OfficialBaseURL)
	custom, _ := url.Parse("https://jwzx.fiacloud.top/academic/")

	session.SetCookies(official, []*http.Cookie{{Name: "JSESSIONID", Value: "official"}})
	session.SetCookies(custom, []*http.Cookie{{Name: "JSESSIONID", Value: "mirror"}})

	officialCookies := session.Cookies(official)
	if len(officialCookies) != 1 || officialCookies[0].Value != "official" {
		t.Fatalf("官方站点 Cookie 不正确：%+v", officialCookies)
	}
	customCookies := session.Cookies(custom)
	if len(customCookies) != 1 || customCookies[0].Value != "mirror" {
		t.Fatalf("自定义站点 Cookie 不正确：%+v", customCookies)
	}

	// 旧文件没有 host 字段：按官方站点归属，不会被带到自定义站点
	legacy := OpenSession(t.TempDir(), time.Now)
	legacy.jar[jarKey("", "JSESSIONID")] = StoredCookie{Name: "JSESSIONID", Value: "legacy"}
	if len(legacy.Cookies(custom)) != 0 {
		t.Fatal("官方会话不应被发送到自定义站点")
	}
	if got := legacy.Cookies(official); len(got) != 1 || got[0].Value != "legacy" {
		t.Fatalf("旧会话应按官方站点归属：%+v", got)
	}
}
