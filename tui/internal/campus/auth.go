package campus

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// 登录 / 登出。
//
// 与 `web/src/composables/useSession.js` 的 login / logout 对齐：
//   1. 提交 j_acegi_security_check（学号 / 密码 / 验证码）；
//   2. 用 currcourse.jsdo 取上下文（内部学号、学年学期）验证会话真的可用；
//   3. 用 showPersonalInfo.do 取档案，并校验档案学号与输入一致；
//   4. 一次性写入图形界面读的那批缓存键，图形界面打开即已登录。

// LoginRequest 是一次登录所需的输入。
type LoginRequest struct {
	Username string
	Password string
	Captcha  string
	// Remember 为真时把学号与密码（XOR 混淆后）写进共享缓存，供两个界面预填。
	Remember bool
}

// LoginResult 是登录成功的产物。
type LoginResult struct {
	StudentNumber string
	InternalID    string
	Year          string
	Term          string
	Profile       Profile
	// Warnings 是非致命问题（例如校历接口失败）。
	Warnings []string
}

// Captcha 拉取一次验证码图片（PNG / JPEG / GIF 原样字节）。
//
// 不依赖响应头：教务有时不写 Content-Type，有时写 octet-stream，
// 浏览器靠嗅探就能显示，这里同样以文件头为准。
func (s *Service) Captcha(ctx context.Context) ([]byte, error) {
	response, err := s.client.Get(ctx, fmt.Sprintf("getCaptcha.do?_t=%d", time.Now().UnixMilli()))
	if err != nil {
		return nil, err
	}
	if len(response.Body) == 0 {
		return nil, errors.New("验证码接口没有返回内容，请稍后重试")
	}
	if looksLikeImage(response.Body) {
		return response.Body, nil
	}
	if isLoginPage(response.HTML) {
		// 会话被清掉时验证码接口会直接吐登录页
		return nil, errors.New("教务返回了登录页，请稍后重试")
	}
	return nil, errors.New("验证码接口返回的不是图片，请稍后重试")
}

// looksLikeImage 按文件头判断图片格式（PNG / JPEG / GIF）。
func looksLikeImage(body []byte) bool {
	switch {
	case len(body) >= 8 && bytes.HasPrefix(body, []byte("\x89PNG\r\n\x1a\n")):
		return true
	case len(body) >= 3 && body[0] == 0xFF && body[1] == 0xD8 && body[2] == 0xFF:
		return true
	case len(body) >= 6 && (bytes.HasPrefix(body, []byte("GIF87a")) || bytes.HasPrefix(body, []byte("GIF89a"))):
		return true
	default:
		return false
	}
}

// CheckCaptcha 调用教务的前置校验接口；失败不作为阻断条件（与前端一致）。
func (s *Service) CheckCaptcha(ctx context.Context, code string) bool {
	response, err := s.client.PostForm(ctx, "checkCaptcha.do?captchaCode="+url.QueryEscape(code), url.Values{})
	if err != nil {
		return true
	}
	return strings.EqualFold(strings.TrimSpace(response.HTML), "true")
}

// Login 完成一次登录并写回共享缓存。
func (s *Service) Login(ctx context.Context, request LoginRequest) (LoginResult, error) {
	username := strings.TrimSpace(request.Username)
	password := strings.TrimSpace(request.Password)
	captcha := strings.TrimSpace(request.Captcha)
	switch {
	case username == "":
		return LoginResult{}, errors.New("请输入学号")
	case password == "":
		return LoginResult{}, errors.New("请输入密码")
	case captcha == "":
		return LoginResult{}, errors.New("请输入验证码")
	}

	form := url.Values{}
	form.Set("j_username", username)
	form.Set("j_password", password)
	form.Set("j_captcha", captcha)
	response, err := s.client.PostForm(ctx, "j_acegi_security_check", form)
	if err != nil {
		return LoginResult{}, err
	}
	if response.Status >= 400 {
		return LoginResult{}, fmt.Errorf("教务系统登录请求失败（HTTP %d），请稍后重试", response.Status)
	}
	if isLoginPage(response.HTML) || containsAny(visibleText(response.HTML), loginFailureMarkers) {
		return LoginResult{}, errors.New(loginFailureReason(response.HTML))
	}

	contextInfo, err := s.studentContext(ctx)
	if err != nil {
		return LoginResult{}, err
	}
	if contextInfo.StudentID == "" {
		return LoginResult{}, errors.New("教务未返回学生标识，登录未生效，请重试")
	}

	profile, err := s.personalInfo(ctx)
	if err != nil {
		return LoginResult{}, err
	}
	if strings.TrimSpace(profile.StudentNumber) != username {
		_ = s.Logout(ctx)
		return LoginResult{}, errors.New("教务返回的账号与输入学号不一致，请刷新验证码重新登录")
	}
	profile.InternalID = contextInfo.StudentID

	previous := s.session.StudentNumber()
	s.session.SetStudentNumber(username)
	if err := s.session.Save(); err != nil {
		return LoginResult{}, err
	}

	patch := Patch{Set: map[string]string{}}
	patch.Set[KeySessionFlag] = "true"
	patch.Set[KeyLastLogin] = strconv.FormatInt(time.Now().UnixMilli(), 10)
	patch.Set[KeyCurrentCourses] = encodeJSON(contextInfo.Courses)
	patch.Set[KeyProfile] = encodeJSON(profile)
	if request.Remember {
		patch.Set[KeySavedNumber] = username
		if encoded, encodeErr := obfuscateCredential(username, password); encodeErr == nil {
			patch.Set[KeySavedCredential] = encoded
		}
	} else {
		patch.Delete = append(patch.Delete, KeySavedNumber, KeySavedCredential)
	}

	// 换账号时必须清掉旧账号的数据缓存，否则两个账号的成绩会混在一起
	if previous != "" && previous != username {
		patch.Delete = append(patch.Delete, DataKeys...)
	}
	// 登录成功即清除「会话已失效」标记
	patch.Delete = append(patch.Delete, KeySessionExpired)

	if err := s.write(patch); err != nil {
		return LoginResult{}, err
	}

	return LoginResult{
		StudentNumber: username,
		InternalID:    contextInfo.StudentID,
		Year:          contextInfo.Year,
		Term:          contextInfo.Term,
		Profile:       profile,
	}, nil
}

// Logout 结束教务会话，并把本地标记为「会话已失效、可离线查看」。
func (s *Service) Logout(ctx context.Context) error {
	// 服务端登出失败不应阻断本地清理
	_, _ = s.client.Get(ctx, "logout_security_check")

	if err := s.session.Clear(); err != nil {
		return err
	}
	patch := Patch{
		Set:    map[string]string{KeySessionExpired: "true"},
		Delete: []string{KeySessionFlag, KeyLastLogin},
	}
	return s.write(patch)
}

// studentContext 拉取教务上下文；会话失效时返回 ErrSessionExpired。
func (s *Service) studentContext(ctx context.Context) (StudentContext, error) {
	response, err := s.client.Get(ctx, "student/currcourse/currcourse.jsdo")
	if err != nil {
		return StudentContext{}, err
	}
	if isLoginPage(response.HTML) {
		return StudentContext{}, ErrSessionExpired
	}
	return parseStudentContext(response.HTML), nil
}

// personalInfo 拉取学籍档案；会话失效时返回 ErrSessionExpired。
func (s *Service) personalInfo(ctx context.Context) (Profile, error) {
	response, err := s.client.Get(ctx, "showPersonalInfo.do")
	if err != nil {
		return Profile{}, err
	}
	if isLoginPage(response.HTML) {
		return Profile{}, ErrSessionExpired
	}
	return parsePersonalInfo(response.HTML), nil
}

// containsAny 判断文本是否包含任一标记。
func containsAny(text string, markers []string) bool {
	for _, marker := range markers {
		if strings.Contains(text, marker) {
			return true
		}
	}
	return false
}

// encodeJSON 把值编码成前端写入缓存时使用的紧凑 JSON。
func encodeJSON(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "null"
	}
	return string(encoded)
}

// ---------- 「记住密码」的混淆（与 savedCredential.js 完全一致） ----------

// credentialMask 是混淆密钥，只用于避免明文落盘，不承担加密职责。
const credentialMask = "better-hrbust-local-credential-v1"

// obfuscateCredential 把学号密码混淆成 `{"username":…,"password":…}` 字符串。
func obfuscateCredential(username, password string) (string, error) {
	payload := map[string]string{
		"username": username,
		"password": base64.StdEncoding.EncodeToString(xorBytes([]byte(password))),
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

// xorBytes 用固定密钥异或，加解密同一实现。
func xorBytes(input []byte) []byte {
	out := make([]byte, len(input))
	for index := range input {
		out[index] = input[index] ^ credentialMask[index%len(credentialMask)]
	}
	return out
}

// decodeBase64 解码「记住密码」里的密码载荷。
func decodeBase64(encoded string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(encoded)
}
