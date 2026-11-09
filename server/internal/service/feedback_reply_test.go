package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"

	"github.com/Glassous/BetterHRBUST/server/internal/config"
	"github.com/Glassous/BetterHRBUST/server/internal/model"
	"github.com/Glassous/BetterHRBUST/server/internal/repository"
)

func TestExtractEmail(t *testing.T) {
	cases := []struct {
		name    string
		contact string
		want    string
	}{
		{"纯邮箱", "student@example.com", "student@example.com"},
		{"带空白", "  student@example.com  ", "student@example.com"},
		{"夹在中文里", "邮箱：student@example.com，也可以加微信", "student@example.com"},
		{"教育网多级域名", "联系我 stu2024@stu.hrbust.edu.cn 谢谢", "stu2024@stu.hrbust.edu.cn"},
		{"加号标签", "user.name+tag@sub.example.co.uk", "user.name+tag@sub.example.co.uk"},
		{"取第一个", "a@b.com 或 c@d.cn", "a@b.com"},
		{"无邮箱", "QQ 1234567", ""},
		{"缺顶级域", "someone@localhost", ""},
		{"仅有 @ 符号", "@zhang", ""},
		{"空值", "", ""},
		{"邮箱后跟中文标点", "邮箱 a@b.com。", "a@b.com"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := ExtractEmail(tc.contact); got != tc.want {
				t.Fatalf("ExtractEmail(%q) = %q，期望 %q", tc.contact, got, tc.want)
			}
		})
	}
}

// ---------- 测试替身 ----------

type sentMail struct {
	To       string
	Subject  string
	Body     string
	Operator string
}

type fakeSender struct {
	enabled  bool
	fromName string
	sendErr  error
	sent     []sentMail
}

func (f *fakeSender) Enabled() bool { return f.enabled }

func (f *fakeSender) FromName() string {
	if f.fromName == "" {
		return "BetterHRBUST"
	}
	return f.fromName
}

func (f *fakeSender) Send(_ context.Context, to, subject, body, operator string) error {
	if f.sendErr != nil {
		return f.sendErr
	}
	f.sent = append(f.sent, sentMail{To: to, Subject: subject, Body: body, Operator: operator})
	return nil
}

type fakeStore struct {
	item      *model.Feedback
	getErr    error
	createErr error
	appendErr error
	created   []*model.Feedback
	appended  []model.FeedbackReply
}

func (f *fakeStore) Create(_ context.Context, feedback *model.Feedback) error {
	if f.createErr != nil {
		return f.createErr
	}
	// 与真实仓储保持一致：回填主键与创建时间，供提醒邮件引用。
	feedback.ID = primitive.NewObjectID()
	feedback.CreatedAt = time.Now().UTC()
	f.created = append(f.created, feedback)
	f.item = feedback
	return nil
}
func (f *fakeStore) List(context.Context, repository.FeedbackFilter) ([]model.Feedback, int64, error) {
	return nil, 0, nil
}
func (f *fakeStore) GetByID(context.Context, string) (*model.Feedback, error) {
	if f.getErr != nil {
		return nil, f.getErr
	}
	return f.item, nil
}
func (f *fakeStore) SetRead(context.Context, string, bool) (*model.Feedback, error) {
	return f.item, nil
}
func (f *fakeStore) Delete(context.Context, string) error       { return nil }
func (f *fakeStore) UnreadCount(context.Context) (int64, error) { return 0, nil }

func (f *fakeStore) AppendReply(_ context.Context, _ string, reply model.FeedbackReply) (model.FeedbackReply, error) {
	if f.appendErr != nil {
		return model.FeedbackReply{}, f.appendErr
	}
	reply.ID = primitive.NewObjectID()
	reply.SentAt = time.Now().UTC()
	f.appended = append(f.appended, reply)
	if f.item != nil {
		f.item.Replies = append(f.item.Replies, reply)
	}
	return reply, nil
}

func newStoredFeedback(contact string) *model.Feedback {
	return &model.Feedback{
		ID:        primitive.NewObjectID(),
		Content:   "希望成绩页支持导出，谢谢",
		Contact:   contact,
		CreatedAt: time.Now().UTC(),
	}
}

func testOperator() *model.Admin {
	return &model.Admin{ID: primitive.NewObjectID(), Username: "reviewer", Nickname: "审核员小张"}
}

// ---------- Reply 分支覆盖 ----------

func TestReplySendsMailAndStoresRecord(t *testing.T) {
	store := &fakeStore{item: newStoredFeedback("邮箱：student@example.com，谢谢")}
	sender := &fakeSender{enabled: true}
	svc := newFeedbackService(store, sender, nil, "")

	got, err := svc.Reply(context.Background(), "any", ReplyInput{
		Subject: "  关于成绩导出的建议  ",
		Body:    "  您好，导出功能已排期。\n感谢反馈！  ",
	}, testOperator())
	if err != nil {
		t.Fatalf("期望发送成功，实际错误: %v", err)
	}

	if len(sender.sent) != 1 {
		t.Fatalf("期望发送 1 封邮件，实际 %d 封", len(sender.sent))
	}
	mail := sender.sent[0]
	if mail.To != "student@example.com" {
		t.Fatalf("收件人应为提取到的邮箱，实际: %q", mail.To)
	}
	if mail.Subject != "关于成绩导出的建议" {
		t.Fatalf("标题未去除首尾空白: %q", mail.Subject)
	}
	if !strings.Contains(mail.Body, "\n") {
		t.Fatalf("正文应保留换行: %q", mail.Body)
	}
	if mail.Operator != "审核员小张" {
		t.Fatalf("发件人昵称应为管理员昵称，实际: %q", mail.Operator)
	}

	if len(store.appended) != 1 {
		t.Fatalf("期望落库 1 条回复，实际 %d 条", len(store.appended))
	}
	record := store.appended[0]
	if record.To != "student@example.com" || record.AdminName != "审核员小张" {
		t.Fatalf("回复记录字段不正确: %+v", record)
	}
	if record.Subject != "关于成绩导出的建议" {
		t.Fatalf("回复记录标题不正确: %q", record.Subject)
	}
	if len(got.Replies) != 1 {
		t.Fatalf("返回的详情应包含刚写入的回复，实际 %d 条", len(got.Replies))
	}
	if got.Email != "student@example.com" {
		t.Fatalf("详情应填充提取出的邮箱，实际: %q", got.Email)
	}
}

// 收件人解析：显式填写的邮箱优先，留空时回退到联系方式；两者都没有才拒绝发送。
func TestReplyRecipientResolution(t *testing.T) {
	cases := []struct {
		name     string
		contact  string
		explicit string
		wantTo   string
		wantErr  error
	}{
		{"联系方式中含邮箱", "邮箱：student@example.com", "", "student@example.com", nil},
		{"显式填写优先于联系方式", "邮箱：student@example.com", "teacher@example.com", "teacher@example.com", nil},
		{"联系方式无邮箱时手填", "QQ 1234567", "teacher@example.com", "teacher@example.com", nil},
		{"显式填写带昵称", "QQ 1234567", "张老师 <teacher@example.com>", "teacher@example.com", nil},
		{"显式填写非法", "QQ 1234567", "not-an-email", "", ErrInvalidReplyTarget},
		{"显式填写缺顶级域", "QQ 1234567", "someone@localhost", "", ErrInvalidReplyTarget},
		{"两者都没有", "QQ 1234567", "   ", "", ErrNoReplyTarget},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := &fakeStore{item: newStoredFeedback(tc.contact)}
			sender := &fakeSender{enabled: true}
			svc := newFeedbackService(store, sender, nil, "")

			_, err := svc.Reply(context.Background(), "any", ReplyInput{
				To:      tc.explicit,
				Subject: "关于您反馈的问题",
				Body:    "您好，问题已处理。",
			}, testOperator())
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("期望错误 %v，实际: %v", tc.wantErr, err)
			}
			if tc.wantErr != nil {
				if len(sender.sent) != 0 || len(store.appended) != 0 {
					t.Fatal("校验失败时不应发信或落库")
				}
				return
			}
			if len(sender.sent) != 1 || sender.sent[0].To != tc.wantTo {
				t.Fatalf("实际发信收件人应为 %q，实际: %+v", tc.wantTo, sender.sent)
			}
			if len(store.appended) != 1 || store.appended[0].To != tc.wantTo {
				t.Fatalf("回复记录收件人应为 %q，实际: %+v", tc.wantTo, store.appended)
			}
		})
	}
}

func TestReplyRejectsContactWithoutEmail(t *testing.T) {
	store := &fakeStore{item: newStoredFeedback("QQ 1234567")}
	sender := &fakeSender{enabled: true}
	svc := newFeedbackService(store, sender, nil, "")

	_, err := svc.Reply(context.Background(), "any", ReplyInput{Subject: "标题", Body: "正文内容"}, nil)
	if !errors.Is(err, ErrNoReplyTarget) {
		t.Fatalf("期望 ErrNoReplyTarget，实际: %v", err)
	}
	if len(sender.sent) != 0 {
		t.Fatal("无法识别邮箱时不应尝试发信")
	}
}

func TestReplyWithoutMailerConfigured(t *testing.T) {
	store := &fakeStore{item: newStoredFeedback("student@example.com")}
	svc := newFeedbackService(store, nil, nil, "")

	_, err := svc.Reply(context.Background(), "any", ReplyInput{Subject: "标题", Body: "正文内容"}, nil)
	if !errors.Is(err, ErrMailNotConfigured) {
		t.Fatalf("期望 ErrMailNotConfigured，实际: %v", err)
	}
}

func TestReplyValidationErrors(t *testing.T) {
	cases := []struct {
		name    string
		input   ReplyInput
		wantErr error
	}{
		{"标题为空", ReplyInput{Subject: "   ", Body: "正文内容"}, ErrSubjectRequired},
		{"标题超长", ReplyInput{Subject: strings.Repeat("题", MaxReplySubjectLength+1), Body: "正文内容"}, ErrSubjectTooLong},
		{"正文为空", ReplyInput{Subject: "标题", Body: "  "}, ErrReplyBodyTooShort},
		{"正文超长", ReplyInput{Subject: "标题", Body: strings.Repeat("文", MaxReplyBodyLength+1)}, ErrReplyBodyTooLong},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := &fakeStore{item: newStoredFeedback("student@example.com")}
			sender := &fakeSender{enabled: true}
			svc := newFeedbackService(store, sender, nil, "")

			_, err := svc.Reply(context.Background(), "any", tc.input, nil)
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("期望 %v，实际: %v", tc.wantErr, err)
			}
			if len(sender.sent) != 0 || len(store.appended) != 0 {
				t.Fatal("校验失败时不应发信或落库")
			}
		})
	}
}

// 发信失败时必须保留可读原因，且不能留下「已回复」的假记录。
func TestReplySendFailureLeavesNoRecord(t *testing.T) {
	store := &fakeStore{item: newStoredFeedback("student@example.com")}
	sender := &fakeSender{enabled: true, sendErr: errors.New("SMTP 认证失败")}
	svc := newFeedbackService(store, sender, nil, "")

	_, err := svc.Reply(context.Background(), "any", ReplyInput{Subject: "标题", Body: "正文内容"}, nil)
	if !errors.Is(err, ErrReplySendFailed) {
		t.Fatalf("期望 ErrReplySendFailed，实际: %v", err)
	}
	if err.Error() != "SMTP 认证失败" {
		t.Fatalf("错误信息应直接展示底层原因，实际: %q", err.Error())
	}
	if len(store.appended) != 0 {
		t.Fatal("发信失败不应写入回复记录")
	}
}

func TestReplyMissingFeedback(t *testing.T) {
	store := &fakeStore{getErr: repository.ErrNotFound}
	svc := newFeedbackService(store, &fakeSender{enabled: true}, nil, "")

	_, err := svc.Reply(context.Background(), "missing", ReplyInput{Subject: "标题", Body: "正文内容"}, nil)
	if !errors.Is(err, repository.ErrNotFound) {
		t.Fatalf("期望 repository.ErrNotFound，实际: %v", err)
	}
}

func TestReplyTemplatesFallbackAndIsolation(t *testing.T) {
	svc := newFeedbackService(&fakeStore{}, nil, nil, "")
	templates := svc.ReplyTemplates()
	if len(templates) != len(config.DefaultReplyTemplates) {
		t.Fatalf("未配置模板时应回退内置文案，实际 %d 条", len(templates))
	}

	templates[0].Content = "被外部修改"
	if svc.ReplyTemplates()[0].Content == "被外部修改" {
		t.Fatal("ReplyTemplates 应返回副本，外部修改不应影响服务内部状态")
	}

	custom := []config.ReplyTemplate{{Label: "自定义", Content: "自定义正文"}}
	if got := newFeedbackService(&fakeStore{}, nil, custom, "").ReplyTemplates(); len(got) != 1 || got[0].Label != "自定义" {
		t.Fatalf("显式传入的模板应优先生效，实际: %+v", got)
	}
}

// 管理端需要展示真实发件人前缀，未配置邮件时为空串（前端据此降级展示收件人）。
func TestSenderNameReflectsMailer(t *testing.T) {
	svc := newFeedbackService(&fakeStore{}, &fakeSender{enabled: true, fromName: "BetterHRBUST"}, nil, "")
	if got := svc.SenderName(); got != "BetterHRBUST" {
		t.Fatalf("SenderName 应为配置的前缀，实际: %q", got)
	}
	if got := newFeedbackService(&fakeStore{}, nil, nil, "").SenderName(); got != "" {
		t.Fatalf("未配置邮件时 SenderName 应为空串，实际: %q", got)
	}
}
