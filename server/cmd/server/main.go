// Command server 启动 BetterHRBUST 服务端。
//
// 服务端只承载「意见反馈」与「管理端账号」两类数据，
// 不代理教务在线请求，也不保存任何学生信息。
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os/signal"
	"syscall"
	"time"

	"github.com/Glassous/BetterHRBUST/server/internal/config"
	"github.com/Glassous/BetterHRBUST/server/internal/database"
	"github.com/Glassous/BetterHRBUST/server/internal/middleware"
	"github.com/Glassous/BetterHRBUST/server/internal/pkg/mailer"
	"github.com/Glassous/BetterHRBUST/server/internal/repository"
	"github.com/Glassous/BetterHRBUST/server/internal/router"
	"github.com/Glassous/BetterHRBUST/server/internal/service"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("[betterhrbust] ")

	cfg := config.Load()
	for _, warning := range cfg.Warnings {
		log.Printf("[config] %s", warning)
	}

	startupCtx, cancelStartup := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancelStartup()

	mongoClient, err := database.ConnectMongo(startupCtx, cfg.MongoURI)
	if err != nil {
		log.Fatalf("连接 MongoDB 失败: %v", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = mongoClient.Disconnect(shutdownCtx)
	}()

	db := mongoClient.Database(cfg.MongoDB)
	if err := database.EnsureIndexes(startupCtx, db); err != nil {
		log.Fatalf("初始化集合索引失败: %v", err)
	}

	redisClient, err := database.ConnectRedis(startupCtx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB)
	if err != nil {
		log.Printf("连接 Redis 失败，限流功能不可用（服务继续启动）: %v", err)
		redisClient = nil
	}
	if redisClient != nil {
		defer func() { _ = redisClient.Close() }()
	}

	feedbackRepo := repository.NewFeedbackRepository(db)
	adminRepo := repository.NewAdminRepository(db)

	// SMTP 未配置时返回 nil，管理端「回复邮件」自动降级为不可用
	mailClient := mailer.New(mailer.Config{
		Host:     cfg.SMTPHost,
		Port:     cfg.SMTPPort,
		Username: cfg.SMTPUsername,
		Password: cfg.SMTPPassword,
		From:     cfg.SMTPFrom,
		FromName: cfg.SMTPFromName,
		UseTLS:   cfg.SMTPUseTLS,
	})

	// 未配置收件邮箱或显式关闭时为空串，服务端不会发提醒邮件
	notifyEmail := ""
	if cfg.NotifyEnabled {
		notifyEmail = cfg.NotifyEmail
	}

	feedbackService := service.NewFeedbackService(feedbackRepo, mailClient, cfg.ReplyTemplates, notifyEmail)
	adminService := service.NewAdminService(adminRepo, cfg.JWTSecret, cfg.JWTTTL)

	limiter := middleware.NewRateLimiter(redisClient, cfg.JWTSecret, router.FeedbackRules...)
	loginLimiter := middleware.NewRateLimiter(redisClient, cfg.JWTSecret, router.LoginRules...)
	if !cfg.RateLimit {
		limiter = middleware.NewRateLimiter(nil, cfg.JWTSecret)
		loginLimiter = middleware.NewRateLimiter(nil, cfg.JWTSecret)
	}

	engine := router.New(router.Deps{
		Config:       cfg,
		Feedback:     feedbackService,
		Admins:       adminService,
		AdminRepo:    adminRepo,
		Limiter:      limiter,
		LoginLimiter: loginLimiter,
		HealthCheck: func(ctx context.Context) error {
			return mongoClient.Ping(ctx, nil)
		},
	})

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           engine,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		log.Printf("服务已启动，监听 :%s（限流: %v，数据库: %s，邮件回复: %v，新反馈提醒: %v）",
			cfg.Port, limiter.Enabled(), cfg.MongoDB, mailClient.Enabled(), notifyEmail != "")
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("HTTP 服务异常退出: %v", err)
		}
	}()

	<-ctx.Done()
	log.Println("收到退出信号，正在关闭服务…")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("优雅关闭超时: %v", err)
	}
	log.Println("服务已停止")
}
