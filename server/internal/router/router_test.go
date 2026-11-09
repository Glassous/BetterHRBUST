package router_test

import (
	"testing"

	"github.com/Glassous/BetterHRBUST/server/internal/config"
	"github.com/Glassous/BetterHRBUST/server/internal/middleware"
	"github.com/Glassous/BetterHRBUST/server/internal/router"
	"github.com/Glassous/BetterHRBUST/server/internal/service"
)

// 路由注册阶段若出现静态路径与通配路径冲突，Gin 会直接 panic；
// 这里用最小依赖装配一次引擎，确保新增的回复相关路由不会破坏路由树。
func TestNewRegistersReplyRoutes(t *testing.T) {
	const secret = "test-secret"
	deps := router.Deps{
		Config:       config.Config{JWTSecret: secret},
		Feedback:     service.NewFeedbackService(nil, nil, nil, ""),
		Limiter:      middleware.NewRateLimiter(nil, secret, router.FeedbackRules...),
		LoginLimiter: middleware.NewRateLimiter(nil, secret, router.LoginRules...),
	}

	engine := router.New(deps)

	registered := make(map[string]bool)
	for _, route := range engine.Routes() {
		registered[route.Method+" "+route.Path] = true
	}

	want := []string{
		"POST /api/admin/feedback/:id/reply",
		"GET /api/admin/reply-templates",
		"PATCH /api/admin/feedback/:id/read",
	}
	for _, route := range want {
		if !registered[route] {
			t.Fatalf("路由未注册: %s（已注册 %d 条）", route, len(registered))
		}
	}
}
