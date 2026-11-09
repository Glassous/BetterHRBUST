package handler_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/Glassous/BetterHRBUST/server/internal/handler"
	"github.com/Glassous/BetterHRBUST/server/internal/service"
)

// 管理端回复区依赖该接口下发的三个字段：快捷文案、发信能力、发件人显示名前缀。
// 这里锁定字段名，避免服务端改名后前端静默降级。
func TestReplyTemplatesResponseShape(t *testing.T) {
	gin.SetMode(gin.TestMode)

	// 未配置 SMTP 的服务：仓储可为 nil，这两个方法不访问仓储
	feedback := service.NewFeedbackService(nil, nil, nil, "")
	engine := gin.New()
	engine.GET("/reply-templates", handler.NewAdminFeedbackHandler(feedback).ReplyTemplates)

	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/reply-templates", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("期望 200，实际 %d", recorder.Code)
	}

	var payload struct {
		Code int `json:"code"`
		Data struct {
			Templates   []struct{ Label, Content string } `json:"templates"`
			MailEnabled bool                              `json:"mailEnabled"`
			FromName    *string                           `json:"fromName"`
		} `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if payload.Code != 0 {
		t.Fatalf("期望业务码 0，实际 %d", payload.Code)
	}
	if len(payload.Data.Templates) == 0 {
		t.Fatal("快捷回复文案不应为空（未配置时应回退内置文案）")
	}
	if payload.Data.MailEnabled {
		t.Fatal("未配置 SMTP 时 mailEnabled 应为 false")
	}
	if payload.Data.FromName == nil {
		t.Fatal("响应必须包含 fromName 字段（前端据此展示真实发件人）")
	}
	if *payload.Data.FromName != "" {
		t.Fatalf("未配置 SMTP 时 fromName 应为空串，实际: %q", *payload.Data.FromName)
	}
}
