package service

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestNormalizeInputTrimsAndKeepsAnonymous(t *testing.T) {
	feedback, err := NormalizeInput(FeedbackSubmitInput{
		Content: "  课程表在周次切换后偶尔会闪一下  ",
		Contact: "  student@example.com  ",
	})
	if err != nil {
		t.Fatalf("期望校验通过，实际错误: %v", err)
	}
	if feedback.Content != "课程表在周次切换后偶尔会闪一下" {
		t.Fatalf("正文未被去除首尾空白: %q", feedback.Content)
	}
	if feedback.Contact != "student@example.com" {
		t.Fatalf("联系方式未被去除首尾空白: %q", feedback.Contact)
	}
	if !feedback.Anonymous {
		t.Fatal("未开启实名时应标记为匿名")
	}
	if feedback.Signature != "" {
		t.Fatalf("匿名提交不应保存姓名: %q", feedback.Signature)
	}
}

func TestNormalizeInputRealName(t *testing.T) {
	feedback, err := NormalizeInput(FeedbackSubmitInput{
		Content:   "建议给成绩页增加导出 Excel 的入口",
		Signature: " 张三 ",
		Anonymous: false,
	})
	if err != nil {
		t.Fatalf("期望校验通过，实际错误: %v", err)
	}
	if feedback.Signature != "张三" {
		t.Fatalf("姓名未被规范化: %q", feedback.Signature)
	}
	if feedback.Anonymous {
		t.Fatal("开启实名后不应标记为匿名")
	}
}

// 即使客户端只发送姓名而未声明实名，也不能出现「有姓名却标记匿名」的矛盾状态。
func TestNormalizeInputSignatureWithoutFlagFallsBackToAnonymous(t *testing.T) {
	feedback, err := NormalizeInput(FeedbackSubmitInput{Content: "反馈内容测试文本", Anonymous: true})
	if err != nil {
		t.Fatalf("期望校验通过，实际错误: %v", err)
	}
	if !feedback.Anonymous {
		t.Fatal("无姓名时必须是匿名")
	}
}

func TestNormalizeInputRejectsTooShort(t *testing.T) {
	_, err := NormalizeInput(FeedbackSubmitInput{Content: " 短 "})
	if !errors.Is(err, ErrContentTooShort) {
		t.Fatalf("期望 ErrContentTooShort，实际: %v", err)
	}
}

func TestNormalizeInputRejectsTooLong(t *testing.T) {
	_, err := NormalizeInput(FeedbackSubmitInput{Content: strings.Repeat("哈", MaxContentLength+1)})
	if !errors.Is(err, ErrContentTooLong) {
		t.Fatalf("期望 ErrContentTooLong，实际: %v", err)
	}
}

func TestNormalizeInputRejectsLongContact(t *testing.T) {
	_, err := NormalizeInput(FeedbackSubmitInput{
		Content: "反馈正文内容合规",
		Contact: strings.Repeat("a", MaxContactLength+1),
	})
	if !errors.Is(err, ErrContactTooLong) {
		t.Fatalf("期望 ErrContactTooLong，实际: %v", err)
	}
}

func TestNormalizeInputRejectsLongSignature(t *testing.T) {
	_, err := NormalizeInput(FeedbackSubmitInput{
		Content:   "反馈正文内容合规",
		Signature: strings.Repeat("名", MaxSignatureLength+1),
	})
	if !errors.Is(err, ErrSignatureTooLong) {
		t.Fatalf("期望 ErrSignatureTooLong，实际: %v", err)
	}
}

// 控制字符必须被剔除，防止不可见字符绕过长度校验或污染管理端展示。
// ---------- 新反馈提醒邮件 ----------

// syncDispatch 让提醒邮件在测试中同步发送，便于直接断言。
func syncDispatch(task func(ctx context.Context)) { task(context.Background()) }

func TestSubmitSendsNotifyMailWithContent(t *testing.T) {
	store := &fakeStore{}
	sender := &fakeSender{enabled: true}
	svc := newFeedbackService(store, sender, nil, "inbox@example.com")
	svc.dispatch = syncDispatch

	feedback, err := svc.Submit(context.Background(), FeedbackSubmitInput{
		Content: "  成绩页导出按钮点了没有反应  ",
		Contact: " 邮箱：student@example.com  ",
	})
	if err != nil {
		t.Fatalf("期望提交成功，实际错误: %v", err)
	}
	if len(store.created) != 1 {
		t.Fatalf("期望写入 1 条反馈，实际 %d 条", len(store.created))
	}
	if len(sender.sent) != 1 {
		t.Fatalf("期望发送 1 封提醒邮件，实际 %d 封", len(sender.sent))
	}

	mail := sender.sent[0]
	if mail.To != "inbox@example.com" {
		t.Fatalf("提醒邮件应发往配置的邮箱，实际: %q", mail.To)
	}
	if mail.Subject != notifySubject {
		t.Fatalf("提醒邮件标题不符合预期: %q", mail.Subject)
	}
	for _, want := range []string{
		"成绩页导出按钮点了没有反应",
		"student@example.com",
		feedback.ID.Hex(),
		"匿名",
		"提交时间",
		"联系方式",
	} {
		if !strings.Contains(mail.Body, want) {
			t.Fatalf("提醒邮件正文应包含 %q，实际正文:\n%s", want, mail.Body)
		}
	}
	if mail.Operator != "" {
		t.Fatalf("系统提醒不应伪装成管理员发送，实际 operator: %q", mail.Operator)
	}
}

// 实名反馈需要在提醒邮件中体现署名，匿名反馈则不应泄露姓名。
func TestNotifyMailReflectsSignature(t *testing.T) {
	cases := []struct {
		name      string
		input     FeedbackSubmitInput
		want      string
		notWanted string
	}{
		{
			name:      "实名",
			input:     FeedbackSubmitInput{Content: "希望增加课表导出功能", Signature: "张三", Anonymous: false},
			want:      "张三（实名）",
			notWanted: "提交方式：匿名",
		},
		{
			name:      "匿名",
			input:     FeedbackSubmitInput{Content: "希望增加课表导出功能", Signature: "张三", Anonymous: true},
			want:      "提交方式：匿名",
			notWanted: "张三（实名）",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			sender := &fakeSender{enabled: true}
			svc := newFeedbackService(&fakeStore{}, sender, nil, "inbox@example.com")
			svc.dispatch = syncDispatch

			if _, err := svc.Submit(context.Background(), tc.input); err != nil {
				t.Fatalf("期望提交成功，实际错误: %v", err)
			}
			if len(sender.sent) != 1 {
				t.Fatalf("期望发送 1 封提醒邮件，实际 %d 封", len(sender.sent))
			}
			if !strings.Contains(sender.sent[0].Body, tc.want) {
				t.Fatalf("正文应包含 %q，实际:\n%s", tc.want, sender.sent[0].Body)
			}
			if strings.Contains(sender.sent[0].Body, tc.notWanted) {
				t.Fatalf("正文不应包含 %q，实际:\n%s", tc.notWanted, sender.sent[0].Body)
			}
		})
	}
}

// 未配置收件邮箱时不应产生任何发信行为。
func TestSubmitWithoutNotifyEmailSendsNothing(t *testing.T) {
	sender := &fakeSender{enabled: true}
	svc := newFeedbackService(&fakeStore{}, sender, nil, "")
	svc.dispatch = syncDispatch

	if _, err := svc.Submit(context.Background(), FeedbackSubmitInput{Content: "反馈正文内容合规"}); err != nil {
		t.Fatalf("期望提交成功，实际错误: %v", err)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("未配置收件邮箱时不应发信，实际 %d 封", len(sender.sent))
	}
}

// 提醒只是附带能力：SMTP 未配置或发信失败都不能影响提交结果。
func TestSubmitNotificationFailureIsNotFatal(t *testing.T) {
	t.Run("发信失败", func(t *testing.T) {
		sender := &fakeSender{enabled: true, sendErr: errors.New("SMTP 认证失败")}
		svc := newFeedbackService(&fakeStore{}, sender, nil, "inbox@example.com")
		svc.dispatch = syncDispatch

		if _, err := svc.Submit(context.Background(), FeedbackSubmitInput{Content: "反馈正文内容合规"}); err != nil {
			t.Fatalf("提醒发送失败不应影响提交，实际错误: %v", err)
		}
	})

	t.Run("未配置 SMTP", func(t *testing.T) {
		svc := newFeedbackService(&fakeStore{}, nil, nil, "inbox@example.com")
		svc.dispatch = syncDispatch

		if _, err := svc.Submit(context.Background(), FeedbackSubmitInput{Content: "反馈正文内容合规"}); err != nil {
			t.Fatalf("未配置 SMTP 时提交仍应成功，实际错误: %v", err)
		}
	})
}

// 落库失败时不应发出「有新反馈」的提醒。
func TestSubmitCreateFailureSkipsNotification(t *testing.T) {
	sender := &fakeSender{enabled: true}
	store := &fakeStore{createErr: errors.New("mongo 不可用")}
	svc := newFeedbackService(store, sender, nil, "inbox@example.com")
	svc.dispatch = syncDispatch

	if _, err := svc.Submit(context.Background(), FeedbackSubmitInput{Content: "反馈正文内容合规"}); err == nil {
		t.Fatal("落库失败应返回错误")
	}
	if len(sender.sent) != 0 {
		t.Fatalf("落库失败不应发信，实际 %d 封", len(sender.sent))
	}
}

func TestSanitizeTextStripsControlCharacters(t *testing.T) {
	got := sanitizeText("正常\x00文本\x07带\r\n换行")
	if strings.ContainsAny(got, "\x00\x07\r") {
		t.Fatalf("控制字符未被剔除: %q", got)
	}
	if !strings.Contains(got, "\n") {
		t.Fatalf("换行符应被保留: %q", got)
	}
}
