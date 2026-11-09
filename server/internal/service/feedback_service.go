// Package service 承载业务规则与输入校验。
package service

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/Glassous/BetterHRBUST/server/internal/config"
	"github.com/Glassous/BetterHRBUST/server/internal/model"
	"github.com/Glassous/BetterHRBUST/server/internal/pkg/mailer"
	"github.com/Glassous/BetterHRBUST/server/internal/repository"
)

// 输入长度限制（按字符数，非字节数）。
const (
	MinContentLength   = 5
	MaxContentLength   = 2000
	MaxContactLength   = 128
	MaxSignatureLength = 64

	// 回复邮件长度限制。
	MinReplyBodyLength    = 2
	MaxReplyBodyLength    = 4000
	MaxReplySubjectLength = 120
)

// 反馈提交相关的业务错误。
var (
	ErrContentTooShort  = errors.New("content too short")
	ErrContentTooLong   = errors.New("content too long")
	ErrContactTooLong   = errors.New("contact too long")
	ErrSignatureTooLong = errors.New("signature too long")
)

// 回复邮件相关的业务错误。
var (
	// ErrMailNotConfigured 服务端未配置 SMTP，无法发送回复。
	ErrMailNotConfigured = errors.New("mail service not configured")
	// ErrNoReplyTarget 既未指定收件邮箱，联系方式中也没有可识别的邮箱。
	ErrNoReplyTarget = errors.New("no reachable email in contact")
	// ErrInvalidReplyTarget 指定的收件邮箱格式不合法。
	ErrInvalidReplyTarget = errors.New("invalid reply target")
	// ErrSubjectRequired 邮件标题为空。
	ErrSubjectRequired = errors.New("reply subject required")
	// ErrSubjectTooLong 邮件标题超长。
	ErrSubjectTooLong = errors.New("reply subject too long")
	// ErrReplyBodyTooShort 邮件正文过短。
	ErrReplyBodyTooShort = errors.New("reply body too short")
	// ErrReplyBodyTooLong 邮件正文超长。
	ErrReplyBodyTooLong = errors.New("reply body too long")
	// ErrReplySendFailed 邮件投递失败（SMTP 侧错误），回复不会落库。
	ErrReplySendFailed = errors.New("send reply mail failed")
)

// emailPattern 从自由文本中提取邮箱候选，最终结果再交由 net/mail 校验。
// 联系方式是用户随手填写的文本（如「邮箱 a@b.com，也可加微信…」），因此只提取其中一段。
var emailPattern = regexp.MustCompile(`[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}`)

// ExtractEmail 从联系方式文本中提取首个合法邮箱；没有则返回空串。
func ExtractEmail(contact string) string {
	for _, candidate := range emailPattern.FindAllString(contact, -1) {
		candidate = strings.Trim(candidate, ".-")
		address, err := mail.ParseAddress(candidate)
		if err != nil || address.Address == "" {
			continue
		}
		return address.Address
	}
	return ""
}

// resolveReplyTarget 决定回复邮件的收件人：优先使用管理员显式填写的邮箱，
// 留空时回退到从联系方式中提取的结果。允许填写「昵称 <邮箱>」形式，统一取其中的地址。
func resolveReplyTarget(explicit, contact string) (string, error) {
	value := strings.TrimSpace(explicit)
	if value == "" {
		extracted := ExtractEmail(contact)
		if extracted == "" {
			return "", ErrNoReplyTarget
		}
		return extracted, nil
	}
	extracted := ExtractEmail(value)
	if extracted == "" {
		return "", ErrInvalidReplyTarget
	}
	return extracted, nil
}

// ReplyInput 是管理端回复邮件的输入。
// To 为可选的收件邮箱：留空时自动从反馈的联系方式中提取，
// 未识别到邮箱的反馈允许管理员手工填写。
type ReplyInput struct {
	To      string `json:"to"`
	Subject string `json:"subject"`
	Body    string `json:"body"`
}

// SendError 包装底层 SMTP 错误：errors.Is(err, ErrReplySendFailed) 成立，
// 同时 Error() 只暴露可读的失败原因，便于直接展示给管理员。
type SendError struct {
	Reason error
}

func (e *SendError) Error() string {
	if e.Reason == nil {
		return ErrReplySendFailed.Error()
	}
	return e.Reason.Error()
}

func (e *SendError) Is(target error) bool {
	return target == ErrReplySendFailed || errors.Is(e.Reason, target)
}

// FeedbackSubmitInput 是反馈接口接受的全部字段。
//
// 隐私约定：这里刻意只保留四个字段，服务端不会读取或存储任何其他客户端字段
// （例如学号、账号、Cookie、设备信息），从结构上杜绝学生信息落库。
type FeedbackSubmitInput struct {
	Content string `json:"content"`
	Contact string `json:"contact"`
	// Signature 与 Anonymous 共同表达实名开关：
	// 前端在用户开启「实名发布」时置 Anonymous=false 并附带姓名；
	// 关闭或未填写姓名时一律按匿名处理。
	Signature string `json:"signature"`
	Anonymous bool   `json:"anonymous"`
}

// feedbackStore 抽象反馈仓储，便于用假实现覆盖回复链路的各条分支。
type feedbackStore interface {
	Create(ctx context.Context, feedback *model.Feedback) error
	List(ctx context.Context, filter repository.FeedbackFilter) ([]model.Feedback, int64, error)
	GetByID(ctx context.Context, id string) (*model.Feedback, error)
	SetRead(ctx context.Context, id string, isRead bool) (*model.Feedback, error)
	Delete(ctx context.Context, id string) error
	UnreadCount(ctx context.Context) (int64, error)
	AppendReply(ctx context.Context, id string, reply model.FeedbackReply) (model.FeedbackReply, error)
}

// mailSender 抽象发信能力，生产环境由 *mailer.Mailer 实现。
type mailSender interface {
	Enabled() bool
	// FromName 返回配置的发件人显示名前缀，用于在管理端预览真实发件人。
	FromName() string
	Send(ctx context.Context, to, subject, body, operator string) error
}

// FeedbackService 意见反馈业务逻辑。
type FeedbackService struct {
	repo feedbackStore
	// mailer 为 nil 时表示未配置 SMTP，回复功能降级为不可用。
	mailer    mailSender
	templates []config.ReplyTemplate
	// notifyEmail 是新反馈提醒邮件的收件地址；为空表示不发送提醒。
	notifyEmail string
	// dispatch 决定提醒邮件的执行方式：生产环境异步发送，测试中可替换为同步执行。
	dispatch func(task func(ctx context.Context))
}

// NewFeedbackService 构造反馈服务；mailClient 允许为 nil（未配置 SMTP）。
// notifyEmail 为新反馈提醒的收件邮箱，留空表示关闭提醒。
func NewFeedbackService(repo feedbackStore, mailClient *mailer.Mailer, templates []config.ReplyTemplate, notifyEmail string) *FeedbackService {
	if mailClient == nil {
		// 显式落入 nil 接口：避免接口持有 nil 指针后在调用发信时 panic。
		return newFeedbackService(repo, nil, templates, notifyEmail)
	}
	return newFeedbackService(repo, mailClient, templates, notifyEmail)
}

// newFeedbackService 内部构造入口，便于测试注入自定义发信器。
func newFeedbackService(repo feedbackStore, sender mailSender, templates []config.ReplyTemplate, notifyEmail string) *FeedbackService {
	if len(templates) == 0 {
		templates = config.DefaultReplyTemplates
	}
	svc := &FeedbackService{
		repo:        repo,
		templates:   templates,
		notifyEmail: strings.TrimSpace(notifyEmail),
		dispatch:    asyncDispatch,
	}
	if sender != nil && sender.Enabled() {
		svc.mailer = sender
	}
	return svc
}

// MailEnabled 报告服务端是否具备发信能力。
func (s *FeedbackService) MailEnabled() bool { return s.mailer != nil && s.mailer.Enabled() }

// SenderName 返回发件人显示名前缀；未配置 SMTP 时为空串。
// 管理端据此展示「前缀·管理员昵称」的真实发件人，避免与配置不一致。
func (s *FeedbackService) SenderName() string {
	if s.mailer == nil {
		return ""
	}
	return s.mailer.FromName()
}

// ReplyTemplates 返回快捷回复文案副本，调用方可安全修改。
func (s *FeedbackService) ReplyTemplates() []config.ReplyTemplate {
	out := make([]config.ReplyTemplate, len(s.templates))
	copy(out, s.templates)
	return out
}

// NormalizeInput 校验并规范化反馈输入（纯函数，便于单测）。
// 返回值中的 anonymous 由调用方按表单开关语义决定，这里只在无姓名时强制为匿名。
func NormalizeInput(input FeedbackSubmitInput) (*model.Feedback, error) {
	content := sanitizeText(input.Content)
	length := utf8.RuneCountInString(content)
	if length < MinContentLength {
		return nil, ErrContentTooShort
	}
	if length > MaxContentLength {
		return nil, ErrContentTooLong
	}

	contact := sanitizeText(input.Contact)
	if utf8.RuneCountInString(contact) > MaxContactLength {
		return nil, ErrContactTooLong
	}

	// 姓名仅在用户主动开启「实名发布」时才会随请求到达，
	// 此处仅作为本条反馈的附属文本保存，不与任何账号产生关联。
	signature := sanitizeText(input.Signature)
	if utf8.RuneCountInString(signature) > MaxSignatureLength {
		return nil, ErrSignatureTooLong
	}

	return &model.Feedback{
		Content:   content,
		Contact:   contact,
		Signature: signature,
		Anonymous: input.Anonymous || signature == "",
	}, nil
}

// Submit 校验并写入一条反馈，落库成功后向配置邮箱发送新反馈提醒。
func (s *FeedbackService) Submit(ctx context.Context, input FeedbackSubmitInput) (*model.Feedback, error) {
	feedback, err := NormalizeInput(input)
	if err != nil {
		return nil, err
	}
	if err := s.repo.Create(ctx, feedback); err != nil {
		return nil, err
	}
	// 提醒邮件属于附带能力：发送失败只记日志，不影响本次提交的结果。
	s.notify(feedback)
	return feedback, nil
}

// List 管理端分页查询。返回的每条记录都会带上从联系方式中提取的邮箱。
func (s *FeedbackService) List(ctx context.Context, filter repository.FeedbackFilter) ([]model.Feedback, int64, error) {
	if filter.ReadState != repository.ReadStateRead && filter.ReadState != repository.ReadStateUnread {
		filter.ReadState = repository.ReadStateAll
	}
	filter.Keyword = strings.TrimSpace(filter.Keyword)

	items, total, err := s.repo.List(ctx, filter)
	if err != nil {
		return nil, 0, err
	}
	for i := range items {
		items[i].Email = ExtractEmail(items[i].Contact)
	}
	return items, total, nil
}

// Get 查询单条反馈详情（含回复历史）。
func (s *FeedbackService) Get(ctx context.Context, id string) (*model.Feedback, error) {
	feedback, err := s.repo.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	feedback.Email = ExtractEmail(feedback.Contact)
	return feedback, nil
}

// Reply 校验回复内容 → 通过 SMTP 投递 → 落库记录。
//
// 顺序上先发信后落库：发信失败时不会留下「已回复」的假记录，
// 管理员可在修正问题后重试（重复发送由人工判断，服务端不做去重）。
func (s *FeedbackService) Reply(ctx context.Context, id string, input ReplyInput, operator *model.Admin) (*model.Feedback, error) {
	feedback, err := s.repo.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}

	to, err := resolveReplyTarget(input.To, feedback.Contact)
	if err != nil {
		return nil, err
	}
	if !s.MailEnabled() {
		return nil, ErrMailNotConfigured
	}

	subject := sanitizeText(input.Subject)
	if subject == "" {
		return nil, ErrSubjectRequired
	}
	if utf8.RuneCountInString(subject) > MaxReplySubjectLength {
		return nil, ErrSubjectTooLong
	}

	body := sanitizeText(input.Body)
	bodyLength := utf8.RuneCountInString(body)
	if bodyLength < MinReplyBodyLength {
		return nil, ErrReplyBodyTooShort
	}
	if bodyLength > MaxReplyBodyLength {
		return nil, ErrReplyBodyTooLong
	}

	operatorID, operatorName := "", ""
	if operator != nil {
		operatorID = operator.ID.Hex()
		operatorName = operator.Nickname
		if operatorName == "" {
			operatorName = operator.Username
		}
	}

	if err := s.mailer.Send(ctx, to, subject, body, operatorName); err != nil {
		return nil, &SendError{Reason: err}
	}

	if _, err := s.repo.AppendReply(ctx, id, model.FeedbackReply{
		Subject:   subject,
		Body:      body,
		To:        to,
		AdminID:   operatorID,
		AdminName: operatorName,
	}); err != nil {
		return nil, err
	}
	return s.Get(ctx, id)
}

// SetRead 标记已读 / 未读。
func (s *FeedbackService) SetRead(ctx context.Context, id string, isRead bool) (*model.Feedback, error) {
	return s.repo.SetRead(ctx, id, isRead)
}

// Delete 永久删除（调用方必须已校验超级管理员身份）。
func (s *FeedbackService) Delete(ctx context.Context, id string) error {
	return s.repo.Delete(ctx, id)
}

// UnreadCount 未读数量。
func (s *FeedbackService) UnreadCount(ctx context.Context) (int64, error) {
	return s.repo.UnreadCount(ctx)
}

// 新反馈提醒邮件相关常量。
const (
	// notifySubject 提醒邮件标题，固定文案便于在邮箱侧按规则归集。
	notifySubject = "【BetterHRBUST】收到一条新的意见反馈"
	// notifyTimeout 单封提醒邮件的发送上限，避免慢 SMTP 长期占用后台 goroutine。
	notifyTimeout = 30 * time.Second
)

// asyncDispatch 在后台 goroutine 中执行发信任务：
// SMTP 交互最长可达十几秒，不能阻塞前端的反馈提交响应。
func asyncDispatch(task func(ctx context.Context)) {
	go func() {
		defer func() {
			// 后台 goroutine 中的 panic 会直接终止进程，这里兜底记录。
			if r := recover(); r != nil {
				log.Printf("[feedback] 新反馈提醒发送异常: %v", r)
			}
		}()
		ctx, cancel := context.WithTimeout(context.Background(), notifyTimeout)
		defer cancel()
		task(ctx)
	}()
}

// notify 向配置邮箱投递新反馈提醒；未配置收件邮箱或 SMTP 不可用时静默跳过。
func (s *FeedbackService) notify(feedback *model.Feedback) {
	if feedback == nil || s.notifyEmail == "" || !s.MailEnabled() {
		return
	}
	subject, body := buildNotifyMessage(feedback)
	dispatch := s.dispatch
	if dispatch == nil {
		dispatch = asyncDispatch
	}
	dispatch(func(ctx context.Context) {
		// 日志中不打印反馈正文，避免用户内容散落到日志系统。
		if err := s.mailer.Send(ctx, s.notifyEmail, subject, body, ""); err != nil {
			log.Printf("[feedback] 新反馈提醒发送失败: %v", err)
		}
	})
}

// buildNotifyMessage 组装提醒邮件的标题与正文。
// 正文只搬运用户主动提交的字段（内容、联系方式、署名），不附加任何推断信息。
func buildNotifyMessage(feedback *model.Feedback) (string, string) {
	author := "匿名"
	if !feedback.Anonymous && feedback.Signature != "" {
		author = feedback.Signature + "（实名）"
	}
	contact := feedback.Contact
	if contact == "" {
		contact = "未填写"
	}

	var builder strings.Builder
	builder.WriteString("收到一条新的意见反馈，请登录管理端查看并处理。\r\n\r\n")
	builder.WriteString("---------- 反馈信息 ----------\r\n")
	fmt.Fprintf(&builder, "提交时间：%s\r\n", feedback.CreatedAt.Local().Format("2006-01-02 15:04:05"))
	if !feedback.ID.IsZero() {
		fmt.Fprintf(&builder, "反馈编号：%s\r\n", feedback.ID.Hex())
	}
	fmt.Fprintf(&builder, "提交方式：%s\r\n", author)
	fmt.Fprintf(&builder, "联系方式：%s\r\n", contact)
	builder.WriteString("\r\n---------- 反馈内容 ----------\r\n")
	builder.WriteString(feedback.Content)
	builder.WriteString("\r\n---------- 反馈内容结束 ----------\r\n\r\n")
	builder.WriteString("本邮件由 BetterHRBUST 服务端在收到反馈时自动发送，无需回复。\r\n")
	return notifySubject, builder.String()
}

// sanitizeText 去除首尾空白并剔除控制字符（保留换行与制表符），
// 避免不可见字符绕过长度校验或污染管理端展示。
func sanitizeText(raw string) string {
	trimmed := strings.TrimSpace(raw)
	var builder strings.Builder
	builder.Grow(len(trimmed))
	for _, r := range trimmed {
		if r == '\n' || r == '\t' {
			builder.WriteRune(r)
			continue
		}
		if unicode.IsControl(r) {
			continue
		}
		builder.WriteRune(r)
	}
	return strings.TrimSpace(builder.String())
}
