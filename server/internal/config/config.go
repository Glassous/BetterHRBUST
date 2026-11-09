// Package config 负责从环境变量（可选叠加 server/.env）加载服务端配置。
//
// 隐私约定：配置中不包含任何学生信息相关字段，服务端也不接受此类输入。
package config

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"log"
	"net/mail"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/Glassous/BetterHRBUST/server/internal/pkg/mailer"
)

const (
	defaultPort       = "9191"
	defaultMongoURI   = "mongodb://127.0.0.1:27017"
	defaultMongoDB    = "betterhrbust"
	defaultJWTTTLHour = 12
	defaultMaxPage    = 100
	defaultSMTPPort   = 587
	// smtpImplicitTLSPort 该端口默认启用隐式 TLS（凭据不经过明文连接）。
	smtpImplicitTLSPort = 465
	// placeholderJWTSecret 是 .env.example 中的占位密钥，命中时提示替换。
	placeholderJWTSecret = "please-change-this-to-a-long-random-secret"
)

// ReplyTemplate 是管理端「快捷回复」的一段预设文案。
// Label 用于按钮展示，Content 为追加到正文输入框的完整文本。
type ReplyTemplate struct {
	Label   string `json:"label"`
	Content string `json:"content"`
}

// DefaultReplyTemplates 内置快捷回复文案；可用 REPLY_TEMPLATES 环境变量整体覆盖。
var DefaultReplyTemplates = []ReplyTemplate{
	{
		Label:   "已收到",
		Content: "您好，感谢您对 BetterHRBUST 的关注！这条反馈我们已经收到并记录，会在后续的迭代中评估处理。",
	},
	{
		Label:   "已修复",
		Content: "您好，您反馈的问题已在最新版本中修复，刷新页面后即可生效。如果仍有异常，欢迎再次反馈给我们。",
	},
	{
		Label:   "排期中",
		Content: "您好，感谢您的建议！该需求已经进入排期，我们会在后续版本中逐步实现，请留意更新说明。",
	},
	{
		Label:   "需要补充信息",
		Content: "您好，为了更准确地定位问题，方便的话请补充一下出现问题的页面、操作步骤与期望结果，我们会尽快跟进。",
	},
}

// Config 汇总服务端运行所需配置。
type Config struct {
	Port          string
	MongoURI      string
	MongoDB       string
	RedisAddr     string
	RedisPassword string
	RedisDB       int
	JWTSecret     string
	JWTTTL        time.Duration
	CORSOrigins   []string
	// TrustedProxies 为空表示不信任任何代理头，直接使用连接来源地址；
	// 容器部署时由反向代理转发，需要显式声明代理网段。
	TrustedProxies []string
	RateLimit      bool
	MaxPageSize    int64

	// ---------- 管理端「回复邮件」所用的 SMTP 参数 ----------
	// SMTPHost 或 SMTPFrom 为空表示邮件回复功能未启用（降级而非启动失败）。
	SMTPHost     string
	SMTPPort     int
	SMTPUsername string
	SMTPPassword string
	// SMTPFrom 发件邮箱地址，必须是 SMTP 服务允许的发件人。
	SMTPFrom string
	// SMTPFromName 发件人显示名前缀，最终显示为「前缀·管理员昵称」。
	SMTPFromName string
	// SMTPUseTLS 为 true 使用隐式 TLS（465）；为 false 时若服务端声明 STARTTLS 则自动升级。
	SMTPUseTLS bool
	// ReplyTemplates 管理端快捷回复预设文案，未配置时使用 DefaultReplyTemplates。
	ReplyTemplates []ReplyTemplate

	// ---------- 「收到新反馈」提醒邮件 ----------
	// NotifyEmail 为新反馈提醒邮件的收件地址（团队公共邮箱可填群组地址）；
	// 为空表示关闭提醒。发信复用上面的 SMTP 参数，SMTP 未配置时提醒同样不可用。
	NotifyEmail string
	// NotifyEnabled 单独控制是否发送提醒，便于保留收件配置的前提下临时关闭。
	NotifyEnabled bool

	// Warnings 收集启动期的降级提示，由调用方决定是否输出（命令行工具可静默忽略）。
	Warnings []string
}

// Load 读取环境变量并返回配置；缺失项使用安全默认值。
func Load() Config {
	loadEnvFile(".env")
	loadEnvFile("server/.env")

	smtpPort := int(envInt("SMTP_PORT", defaultSMTPPort))
	if smtpPort <= 0 || smtpPort > 65535 {
		smtpPort = defaultSMTPPort
	}

	cfg := Config{
		Port:           envString("PORT", defaultPort),
		MongoURI:       envString("MONGO_URI", defaultMongoURI),
		MongoDB:        envString("MONGO_DB", defaultMongoDB),
		RedisAddr:      envString("REDIS_ADDR", ""),
		RedisPassword:  envString("REDIS_PASSWORD", ""),
		RedisDB:        int(envInt("REDIS_DB", 0)),
		JWTSecret:      envString("JWT_SECRET", ""),
		JWTTTL:         time.Duration(envInt("JWT_TTL_HOURS", defaultJWTTTLHour)) * time.Hour,
		CORSOrigins:    envList("CORS_ORIGINS", []string{"http://localhost:5173", "http://localhost:5174", "http://127.0.0.1:5173", "http://127.0.0.1:5174"}),
		TrustedProxies: envList("TRUSTED_PROXIES", nil),
		RateLimit:      envBool("RATE_LIMIT_ENABLED", true),
		MaxPageSize:    envInt("MAX_PAGE_SIZE", defaultMaxPage),

		SMTPHost:       envString("SMTP_HOST", ""),
		SMTPPort:       smtpPort,
		SMTPUsername:   envString("SMTP_USERNAME", ""),
		SMTPPassword:   envString("SMTP_PASSWORD", ""),
		SMTPFrom:       envString("SMTP_FROM", ""),
		SMTPFromName:   envString("SMTP_FROM_NAME", mailer.DefaultFromName),
		SMTPUseTLS:     envBool("SMTP_USE_TLS", smtpPort == smtpImplicitTLSPort),
		ReplyTemplates: parseReplyTemplates(os.Getenv("REPLY_TEMPLATES")),

		NotifyEmail:   normalizeMailbox(os.Getenv("FEEDBACK_NOTIFY_EMAIL")),
		NotifyEnabled: envBool("FEEDBACK_NOTIFY_ENABLED", true),
	}

	if cfg.JWTSecret == "" {
		cfg.JWTSecret = randomSecret()
		cfg.Warnings = append(cfg.Warnings, "未提供 JWT_SECRET，已生成临时密钥（重启后旧登录令牌失效）；生产环境请显式配置")
	} else if cfg.JWTSecret == placeholderJWTSecret {
		cfg.Warnings = append(cfg.Warnings, "JWT_SECRET 仍是示例占位值，对外提供服务前请替换为足够长的随机串")
	}
	if cfg.RedisAddr == "" {
		cfg.RateLimit = false
		cfg.Warnings = append(cfg.Warnings, "未配置 REDIS_ADDR，限流已自动降级为关闭状态")
	}
	if cfg.MaxPageSize < 1 || cfg.MaxPageSize > 500 {
		cfg.MaxPageSize = defaultMaxPage
	}
	if cfg.SMTPHost == "" || cfg.SMTPFrom == "" {
		cfg.Warnings = append(cfg.Warnings, "未完整配置 SMTP_HOST / SMTP_FROM，管理端「回复邮件」与新反馈提醒功能已降级为不可用")
	}
	if cfg.NotifyEmail == "" {
		if raw := strings.TrimSpace(os.Getenv("FEEDBACK_NOTIFY_EMAIL")); raw != "" {
			cfg.Warnings = append(cfg.Warnings, "FEEDBACK_NOTIFY_EMAIL 不是合法邮箱地址，新反馈提醒已关闭")
		}
	} else if cfg.SMTPHost == "" || cfg.SMTPFrom == "" {
		cfg.Warnings = append(cfg.Warnings, "已配置 FEEDBACK_NOTIFY_EMAIL，但 SMTP 未就绪，新反馈提醒无法发送")
	} else if !cfg.NotifyEnabled {
		cfg.Warnings = append(cfg.Warnings, "FEEDBACK_NOTIFY_ENABLED=false，新反馈提醒已关闭")
	}
	return cfg
}

// parseReplyTemplates 解析 REPLY_TEMPLATES：多条文案用 || 分隔，每条为「按钮文案::正文」；
// 省略 :: 时按钮文案自动截取正文前 10 个字。留空或全部非法时回退内置文案。
func parseReplyTemplates(raw string) []ReplyTemplate {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return DefaultReplyTemplates
	}

	templates := make([]ReplyTemplate, 0, 4)
	for _, chunk := range strings.Split(raw, "||") {
		chunk = strings.TrimSpace(chunk)
		if chunk == "" {
			continue
		}
		label, content, hasLabel := strings.Cut(chunk, "::")
		if !hasLabel {
			label, content = "", chunk
		}
		content = strings.TrimSpace(content)
		if content == "" {
			continue
		}
		label = strings.TrimSpace(label)
		if label == "" {
			label = truncateRunes(content, 10)
		}
		templates = append(templates, ReplyTemplate{Label: label, Content: content})
	}
	if len(templates) == 0 {
		return DefaultReplyTemplates
	}
	return templates
}

// normalizeMailbox 校验并规范化单个收件邮箱；允许填写「昵称 <邮箱>」形式，
// 统一取其中的地址部分。非法或为空时返回空串（调用方据此关闭提醒）。
func normalizeMailbox(raw string) string {
	value := strings.TrimSpace(raw)
	if value == "" {
		return ""
	}
	address, err := mail.ParseAddress(value)
	if err != nil || address.Address == "" {
		return ""
	}
	return address.Address
}

// truncateRunes 按字符（而非字节）截断，避免截出半个汉字。
func truncateRunes(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit]) + "…"
}

// loadEnvFile 读取 KEY=VALUE 形式的配置文件；已存在的环境变量优先，不被覆盖。
func loadEnvFile(path string) {
	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.Trim(strings.TrimSpace(value), `"'`)
		if key == "" {
			continue
		}
		if _, exists := os.LookupEnv(key); !exists {
			_ = os.Setenv(key, value)
		}
	}
}

func envString(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int64) int64 {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		log.Printf("[config] %s 取值非法，回退默认值 %d", key, fallback)
		return fallback
	}
	return n
}

func envBool(key string, fallback bool) bool {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return fallback
	}
	return b
}

func envList(key string, fallback []string) []string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	if v == "*" {
		return []string{"*"}
	}
	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if trimmed := strings.TrimSpace(p); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	if len(out) == 0 {
		return fallback
	}
	return out
}

func randomSecret() string {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "betterhrbust-development-secret"
	}
	return hex.EncodeToString(buf)
}
