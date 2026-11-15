// Package config 管理终端界面自己的配置文件（主题、界面偏好、LLM 设置）。
//
// 刻意与 `storage.json` 分开：
//   - `storage.json` 是图形界面拥有的缓存，终端界面只读；
//   - 主题键 `better_hrbust_theme` 属于 Web 客户端，改写它会同时破坏
//     只读契约并与图形界面的原子写竞争。
//
// 配置文件权限 0600、目录 0700：里面可能存有 API Key。
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Version 是配置文件结构版本。
const Version = 1

// Theme 取值。
const (
	ThemeAuto   = "auto"
	ThemeDark   = "dark"
	ThemeLight  = "light"
	ThemeANSI16 = "ansi16"
)

// UISettings 是界面偏好。
type UISettings struct {
	Theme string `json:"theme"`
	ASCII bool   `json:"ascii"`
	// NerdFont 打开后标题与导航会加 Nerd Font 图标。
	//
	// 不自动探测：判断终端有没有装字体是不可能的，猜错就是满屏豆腐块。
	NerdFont bool `json:"nerd_font"`
	Sidebar  bool `json:"sidebar"`
}

// LLMSettings 是 AI 对话配置。
type LLMSettings struct {
	// Enabled 是功能开关；Consent 是出网同意。两者都为真才允许调用。
	Enabled bool `json:"enabled"`
	Consent bool `json:"consent"`
	// BaseURL 指向 OpenAI 兼容服务的 `/v1` 根，例如 https://api.deepseek.com/v1。
	BaseURL string `json:"base_url"`
	Model   string `json:"model"`
	// APIKey 明文保存在本机配置文件（0600）。留空时退回 APIKeyEnv。
	APIKey string `json:"api_key"`
	// APIKeyEnv 是存放密钥的环境变量名。
	APIKeyEnv     string  `json:"api_key_env"`
	Temperature   float64 `json:"temperature"`
	MaxToolRounds int     `json:"max_tool_rounds"`
	TimeoutSecond int     `json:"timeout_seconds"`
	// SendProfile 控制是否把（已打码的）学籍放进上下文。
	SendProfile bool `json:"send_profile"`
}

// Config 是配置文件结构。
type Config struct {
	Version int         `json:"version"`
	UI      UISettings  `json:"ui"`
	LLM     LLMSettings `json:"llm"`
	// Path 是配置文件的绝对路径，不写入文件。
	Path string `json:"-"`
	// LoadWarning 记录加载时的问题（损坏、字段非法），供设置页展示。
	LoadWarning string `json:"-"`
}

// Default 返回内置默认值。
func Default() Config {
	return Config{
		Version: Version,
		UI: UISettings{
			Theme:    ThemeAuto,
			ASCII:    false,
			NerdFont: false,
			Sidebar:  true,
		},
		LLM: LLMSettings{
			Enabled:       false,
			Consent:       false,
			BaseURL:       "https://api.deepseek.com/v1",
			Model:         "deepseek-chat",
			APIKey:        "",
			APIKeyEnv:     "BETTERHRBUST_LLM_KEY",
			Temperature:   0.3,
			MaxToolRounds: 8,
			TimeoutSecond: 120,
			SendProfile:   false,
		},
	}
}

// Load 读取配置文件。
//
// 文件不存在时返回默认值且不报错（首次运行）。文件损坏时返回默认值，
// 把原因写进 LoadWarning，并**保留原文件**供用户手动修复——配置里可能有密钥，
// 静默覆盖会让用户彻底丢失它。
func Load(path string) Config {
	config := Default()
	config.Path = path

	content, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			config.LoadWarning = fmt.Sprintf("无法读取配置文件（%v），已使用默认设置", err)
		}
		return config
	}

	var loaded Config
	if err := json.Unmarshal(content, &loaded); err != nil {
		config.LoadWarning = fmt.Sprintf("配置文件格式错误（%v），已使用默认设置；原文件已保留", err)
		return config
	}

	config = merge(config, loaded)
	config.Path = path
	config.LoadWarning = config.Validate()
	return config
}

// merge 把已加载的值覆盖到默认值上，缺失字段保持默认。
func merge(base, loaded Config) Config {
	if loaded.Version > 0 {
		base.Version = loaded.Version
	}
	if loaded.UI.Theme != "" {
		base.UI.Theme = loaded.UI.Theme
	}
	// 布尔字段无法区分"未设置"与"设为 false"，因此整体替换而非逐字段合并。
	base.UI.ASCII = loaded.UI.ASCII
	base.UI.NerdFont = loaded.UI.NerdFont
	base.UI.Sidebar = loaded.UI.Sidebar

	llm := loaded.LLM
	if llm.BaseURL != "" {
		base.LLM.BaseURL = llm.BaseURL
	}
	if llm.Model != "" {
		base.LLM.Model = llm.Model
	}
	if llm.APIKeyEnv != "" {
		base.LLM.APIKeyEnv = llm.APIKeyEnv
	}
	if llm.Temperature > 0 {
		base.LLM.Temperature = llm.Temperature
	}
	if llm.MaxToolRounds > 0 {
		base.LLM.MaxToolRounds = llm.MaxToolRounds
	}
	if llm.TimeoutSecond > 0 {
		base.LLM.TimeoutSecond = llm.TimeoutSecond
	}
	base.LLM.Enabled = llm.Enabled
	base.LLM.Consent = llm.Consent
	base.LLM.SendProfile = llm.SendProfile
	base.LLM.APIKey = llm.APIKey
	return base
}

// Validate 修正非法取值并返回提示文本。
func (c Config) Validate() string {
	var problems []string
	switch c.UI.Theme {
	case ThemeAuto, ThemeDark, ThemeLight, ThemeANSI16:
	default:
		problems = append(problems, "主题取值非法，已回退 auto")
	}
	if c.LLM.MaxToolRounds < 1 || c.LLM.MaxToolRounds > 32 {
		problems = append(problems, "max_tool_rounds 超出 1..32，已按 8 处理")
	}
	if c.LLM.TimeoutSecond < 5 || c.LLM.TimeoutSecond > 1800 {
		problems = append(problems, "timeout_seconds 超出 5..1800，已按 120 处理")
	}
	if c.LLM.Temperature < 0 || c.LLM.Temperature > 2 {
		problems = append(problems, "temperature 超出 0..2，已按 0.3 处理")
	}
	if len(problems) == 0 {
		return ""
	}
	return strings.Join(problems, "；")
}

// Normalized 返回修正了非法取值的副本。
func (c Config) Normalized() Config {
	out := c
	switch out.UI.Theme {
	case ThemeAuto, ThemeDark, ThemeLight, ThemeANSI16:
	default:
		out.UI.Theme = ThemeAuto
	}
	if out.LLM.MaxToolRounds < 1 || out.LLM.MaxToolRounds > 32 {
		out.LLM.MaxToolRounds = 8
	}
	if out.LLM.TimeoutSecond < 5 || out.LLM.TimeoutSecond > 1800 {
		out.LLM.TimeoutSecond = 120
	}
	if out.LLM.Temperature < 0 || out.LLM.Temperature > 2 {
		out.LLM.Temperature = 0.3
	}
	if out.LLM.APIKeyEnv == "" {
		out.LLM.APIKeyEnv = "BETTERHRBUST_LLM_KEY"
	}
	return out
}

// Save 写入配置文件（0600，目录 0700）。
//
// 先写临时文件再 rename，避免写入过程被打断留下半截配置。
func (c Config) Save() error {
	if c.Path == "" {
		return errors.New("配置路径为空")
	}
	directory := filepath.Dir(c.Path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("创建配置目录失败：%w", err)
	}

	payload := struct {
		Version int         `json:"version"`
		UI      UISettings  `json:"ui"`
		LLM     LLMSettings `json:"llm"`
	}{Version: Version, UI: c.UI, LLM: c.LLM}

	encoded, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return fmt.Errorf("序列化配置失败：%w", err)
	}
	encoded = append(encoded, '\n')

	temporary := c.Path + ".tmp"
	if err := os.WriteFile(temporary, encoded, 0o600); err != nil {
		return fmt.Errorf("写入配置失败：%w", err)
	}
	if err := os.Chmod(temporary, 0o600); err != nil {
		return fmt.Errorf("设置配置权限失败：%w", err)
	}
	if err := os.Rename(temporary, c.Path); err != nil {
		return fmt.Errorf("替换配置失败：%w", err)
	}
	return nil
}

// ResolvedAPIKey 返回实际使用的密钥：优先配置文件，其次环境变量。
func (c Config) ResolvedAPIKey(getenv func(string) string) string {
	if strings.TrimSpace(c.LLM.APIKey) != "" {
		return strings.TrimSpace(c.LLM.APIKey)
	}
	name := strings.TrimSpace(c.LLM.APIKeyEnv)
	if name == "" || getenv == nil {
		return ""
	}
	return strings.TrimSpace(getenv(name))
}

// Ready 判断 LLM 功能是否可用，并给出不可用的原因。
//
// 三重条件缺一不可：功能开关、出网同意、可用密钥。任何一个缺失都只影响
// 对话抽屉本身，不影响其他页面。
func (c Config) Ready(getenv func(string) string) (bool, string) {
	if !c.LLM.Enabled {
		return false, "AI 对话未启用：在设置页（按 ,）打开开关"
	}
	if !c.LLM.Consent {
		return false, "尚未确认数据出网：在设置页勾选同意后才会发送任何内容"
	}
	if strings.TrimSpace(c.LLM.BaseURL) == "" {
		return false, "未配置 base_url"
	}
	if strings.TrimSpace(c.LLM.Model) == "" {
		return false, "未配置模型名"
	}
	if c.ResolvedAPIKey(getenv) == "" {
		return false, "缺少 API Key：填写 llm.api_key 或设置环境变量 " +
			nonEmpty(c.LLM.APIKeyEnv, "BETTERHRBUST_LLM_KEY")
	}
	return true, ""
}

// Redacted 返回隐藏了密钥的副本，供设置页展示与日志输出。
func (c Config) Redacted() Config {
	out := c
	if out.LLM.APIKey != "" {
		out.LLM.APIKey = "已设置（已隐藏）"
	}
	return out
}

// ProviderHost 返回 base_url 的主机名，用于出网提示。
func (c Config) ProviderHost() string {
	url := strings.TrimSpace(c.LLM.BaseURL)
	if url == "" {
		return "未配置"
	}
	trimmed := url
	for _, prefix := range []string{"https://", "http://"} {
		if strings.HasPrefix(trimmed, prefix) {
			trimmed = strings.TrimPrefix(trimmed, prefix)
			break
		}
	}
	if index := strings.IndexAny(trimmed, "/?#"); index >= 0 {
		trimmed = trimmed[:index]
	}
	return trimmed
}

// nonEmpty 返回第一个非空值。
func nonEmpty(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

// ParseBool 把设置页的开关文本转成布尔。
func ParseBool(raw string, fallback bool) bool {
	value, err := strconv.ParseBool(strings.TrimSpace(raw))
	if err != nil {
		return fallback
	}
	return value
}
