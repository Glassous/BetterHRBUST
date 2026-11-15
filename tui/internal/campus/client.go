package campus

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
)

// 教务在线 HTTP 客户端。
//
// 与 `web/src/services/academic/client.js` 对齐的三件事：
//   - 编码自适应：响应头声明 GBK 就按 GBK 解，否则先试 UTF-8、失败再回退 GBK；
//   - 会话失效判定：返回登录页即视为会话失效（`ErrSessionExpired`）；
//   - 失败原因文案：与图形界面登录页展示的同一批中文提示。

// BaseURL 是教务系统的 /academic 根。
const BaseURL = "http://jwzx.hrbust.edu.cn/academic/"

// MaxBodyBytes 是单个响应的读取上限（教务页面最大约数百 KB）。
const MaxBodyBytes = 8 << 20

// RequestTimeout 是单个请求的整体超时。
const RequestTimeout = 30 * time.Second

// userAgent 与桌面端 WebView 保持一致，避免被教务当成未知客户端。
const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 BetterHRBUST-TUI"

// ErrSessionExpired 表示教务返回了登录页，需要重新登录。
var ErrSessionExpired = errors.New("教务会话已失效或未登录")

// Client 是一个绑定了共享会话的 HTTP 客户端。
type Client struct {
	session *Session
	http    *http.Client
	base    *url.URL
}

// NewClient 构造客户端。所有请求都会带上共享会话的 Cookie。
func NewClient(session *Session) *Client { return NewClientAt(session, BaseURL) }

// NewClientAt 指定 base（以 /academic/ 结尾）构造客户端，供测试指向本地假服务。
func NewClientAt(session *Session, baseURL string) *Client {
	base, err := url.Parse(baseURL)
	if err != nil {
		base = &url.URL{Scheme: "http", Host: "jwzx.hrbust.edu.cn", Path: "/academic/"}
	}
	return &Client{
		session: session,
		http: &http.Client{
			Timeout: RequestTimeout,
			Jar:     session,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 8 {
					return http.ErrUseLastResponse
				}
				return nil
			},
		},
		base: base,
	}
}

// Response 是一次请求的结果。
type Response struct {
	Status      int
	ContentType string
	HTML        string
	Body        []byte
	URL         string
}

// Get 发起一次 GET。
func (c *Client) Get(ctx context.Context, path string) (*Response, error) {
	return c.doWithHeader(ctx, http.MethodGet, path, nil, nil)
}

// PostForm 发起一次表单 POST。
func (c *Client) PostForm(ctx context.Context, path string, form url.Values) (*Response, error) {
	body := form.Encode()
	header := http.Header{}
	header.Set("Content-Type", "application/x-www-form-urlencoded")
	return c.doWithHeader(ctx, http.MethodPost, path, strings.NewReader(body), header)
}

// doWithHeader 是请求的公共实现。
func (c *Client) doWithHeader(
	ctx context.Context, method, path string, body io.Reader, header http.Header,
) (*Response, error) {
	// 图形界面可能刚刚登录：每次请求前检查会话文件有没有更新
	c.session.Refresh()

	target := c.resolve(path)
	request, err := http.NewRequestWithContext(ctx, method, target.String(), body)
	if err != nil {
		return nil, fmt.Errorf("请求构造失败：%w", err)
	}
	request.Header.Set("Accept",
		"text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
	request.Header.Set("Accept-Language", "zh-CN,zh;q=0.9")
	request.Header.Set("User-Agent", userAgent)
	request.Header.Set("X-Requested-With", "XMLHttpRequest")
	request.Header.Set("Cache-Control", "no-cache")
	request.Header.Set("Referer", c.base.String())
	for name, values := range header {
		for _, value := range values {
			request.Header.Set(name, value)
		}
	}

	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("网络连接失败：%w", err)
	}
	defer func() { _ = response.Body.Close() }()

	raw, err := io.ReadAll(io.LimitReader(response.Body, MaxBodyBytes))
	if err != nil {
		return nil, fmt.Errorf("读取教务响应失败：%w", err)
	}
	// 教务下发的 Cookie 已由 Jar 收集，落盘后图形界面可直接复用
	if err := c.session.Save(); err != nil {
		return nil, fmt.Errorf("保存会话失败：%w", err)
	}

	contentType := response.Header.Get("Content-Type")
	return &Response{
		Status:      response.StatusCode,
		ContentType: contentType,
		HTML:        decodeBody(raw, contentType),
		Body:        raw,
		URL:         response.Request.URL.String(),
	}, nil
}

// resolve 把教务路径拼成绝对 URL。
//
// 与前端 resolveUrl 一致：无论传入什么形式（含带域名的绝对地址），
// 一律剥掉域名后挂到教务 /academic/ 下，避免被浏览器/客户端升级到 HTTPS。
func (c *Client) resolve(path string) *url.URL {
	clean := strings.TrimSpace(path)
	lower := strings.ToLower(clean)
	for _, prefix := range []string{"https://jwzx.hrbust.edu.cn", "http://jwzx.hrbust.edu.cn"} {
		if strings.HasPrefix(lower, prefix) {
			clean = clean[len(prefix):]
			lower = strings.ToLower(clean)
			break
		}
	}
	clean = strings.TrimLeft(clean, "/")
	clean = strings.TrimPrefix(clean, "academic/")

	// 查询串必须单独放进 RawQuery：直接塞进 Path 会被转义成 %3F，
	// 服务端拿到的路径就多出一段，教务只会回 404。
	pathPart := clean
	queryPart := ""
	if index := strings.IndexByte(clean, '?'); index >= 0 {
		pathPart = clean[:index]
		queryPart = clean[index+1:]
	}

	target := *c.base
	target.Path = "/academic/" + pathPart
	target.RawQuery = queryPart
	return &target
}

// decodeBody 按响应头与内容自适应解码。
func decodeBody(raw []byte, contentType string) string {
	lower := strings.ToLower(contentType)
	declaredGBK := strings.Contains(lower, "gbk") ||
		strings.Contains(lower, "gb2312") ||
		strings.Contains(lower, "gb18030")

	if declaredGBK {
		if text, err := decodeGBK(raw); err == nil {
			return text
		}
	}
	if utf8.Valid(raw) {
		return string(raw)
	}
	if text, err := decodeGBK(raw); err == nil {
		return text
	}
	return string(raw)
}

// decodeGBK 把 GBK 字节解码成 UTF-8 文本。
func decodeGBK(raw []byte) (string, error) {
	decoded, err := simplifiedchinese.GBK.NewDecoder().Bytes(raw)
	if err != nil {
		return "", err
	}
	return string(decoded), nil
}

// ---------- 登录页与会话判定 ----------

// commentBlockPattern 匹配 HTML 注释。
var commentBlockPattern = regexp.MustCompile(`(?s)<!--.*?-->`)

// scriptBlockPattern 匹配 script 标签及其内容。
var scriptBlockPattern = regexp.MustCompile(`(?is)<script\b[^>]*>.*?</script>`)

// tagAnyPattern 匹配任意标签。
var tagAnyPattern = regexp.MustCompile(`<[^>]*>`)

// loginFormPattern 匹配登录表单。
var loginFormPattern = regexp.MustCompile(`(?i)<form\b[^>]*\baction\s*=\s*["'][^"']*j_acegi_security_check`)

// captchaInputPattern 匹配验证码输入框。
var captchaInputPattern = regexp.MustCompile(`(?i)<input\b[^>]*\bname\s*=\s*["']j_captcha["']`)

// isLoginPage 判断响应是否为登录页（与 client.js 的 isLoginPage 一致）。
func isLoginPage(source string) bool {
	if source == "" {
		return false
	}
	content := commentBlockPattern.ReplaceAllString(source, "")
	content = scriptBlockPattern.ReplaceAllString(content, "")
	return loginFormPattern.MatchString(content) || captchaInputPattern.MatchString(content)
}

// loginFailureMarkers 是登录失败页面的特征串。
var loginFailureMarkers = []string{
	"badCredentials", "密码错误", "用户名不存在", "验证码错误", "Bad credentials", "用户不存在",
}

// visibleText 去掉注释 / 脚本 / 样式与标签，只留可读文本。
func visibleText(source string) string {
	text := commentBlockPattern.ReplaceAllString(source, " ")
	text = scriptBlockPattern.ReplaceAllString(text, " ")
	text = styleBlockPattern.ReplaceAllString(text, " ")
	text = tagAnyPattern.ReplaceAllString(text, " ")
	return strings.Join(strings.Fields(text), " ")
}

// styleBlockPattern 匹配 style 标签及其内容。
var styleBlockPattern = regexp.MustCompile(`(?is)<style\b[^>]*>.*?</style>`)

// 登录失败原因的模式（顺序与 client.js 的 parseLoginFailureReason 一致）。
var (
	lockedPattern      = regexp.MustCompile(`锁定|已冻结|已禁用|accountLocked`)
	noSuchUserPattern  = regexp.MustCompile(`用户不存在|学号不存在|用户名不存在`)
	badCaptchaPattern  = regexp.MustCompile(`验证码\s*(?:输入|校验|验证)?\s*(?:错误|已?过期|失效|不正确|无效)|captcha\s*(invalid|incorrect|expired)`)
	badPasswordPattern = regexp.MustCompile(`密码\s*(?:输入)?\s*(?:错误|不正确|无效)|badCredentials|Bad credentials`)
)

// loginFailureReason 从失败页提取中文原因。
func loginFailureReason(source string) string {
	text := visibleText(source)
	switch {
	case lockedPattern.MatchString(text):
		return "账号已被系统锁定，请稍后再试"
	case noSuchUserPattern.MatchString(text):
		return "该学号不存在"
	case badCaptchaPattern.MatchString(text):
		return "验证码错误或已过期，请刷新重试"
	case badPasswordPattern.MatchString(text):
		return "学号或密码错误"
	default:
		return "登录失败，请检查学号与密码"
	}
}
