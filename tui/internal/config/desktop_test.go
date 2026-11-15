package config

import (
	"os"
	"path/filepath"
	"testing"
)

// writeDesktop 写一份桌面端 ai-config.json（字段名与 Rust 侧 AiConfig 一致）
func writeDesktop(t *testing.T, dir, payload string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, desktopAIConfigFile), []byte(payload), 0o600); err != nil {
		t.Fatalf("写入桌面配置失败：%v", err)
	}
}

func TestOverlayDesktopAIAdoptsEnabledConfig(t *testing.T) {
	dir := t.TempDir()
	writeDesktop(t, dir, `{
		"enabled": true,
		"consent": true,
		"base_url": "https://api.deepseek.com/v1",
		"model": "deepseek-flash",
		"api_key": "sk-desktop",
		"temperature": 0.3,
		"max_tool_rounds": 8,
		"timeout_seconds": 120,
		"send_profile": false
	}`)

	settings := Default()
	settings.LLM.APIKey = ""
	merged, ok := OverlayDesktopAI(settings, dir)
	if !ok {
		t.Fatal("桌面配置已启用，应当覆盖成功")
	}
	if merged.LLM.APIKey != "sk-desktop" {
		t.Fatalf("API Key 未同步：%q", merged.LLM.APIKey)
	}
	if !merged.LLM.Enabled || !merged.LLM.Consent {
		t.Fatal("桌面已同意的开关应当继承，否则终端还要再配置一次")
	}
	if merged.LLM.BaseURL != "https://api.deepseek.com/v1" {
		t.Fatalf("接口地址未同步：%q", merged.LLM.BaseURL)
	}
}

func TestOverlayDesktopAIKeepsOwnConfigWhenDisabled(t *testing.T) {
	dir := t.TempDir()
	writeDesktop(t, dir, `{"enabled": false, "api_key": "sk-desktop"}`)

	settings := Default()
	settings.LLM.APIKey = "sk-tui"
	merged, ok := OverlayDesktopAI(settings, dir)
	if ok {
		t.Fatal("桌面配置未启用时不应覆盖")
	}
	if merged.LLM.APIKey != "sk-tui" {
		t.Fatalf("终端自己的 key 被改动：%q", merged.LLM.APIKey)
	}
}

func TestOverlayDesktopAIIgnoresMissingOrCorruptFile(t *testing.T) {
	settings := Default()
	settings.LLM.APIKey = "sk-tui"

	if _, ok := OverlayDesktopAI(settings, t.TempDir()); ok {
		t.Fatal("文件缺失时应返回 false")
	}

	dir := t.TempDir()
	writeDesktop(t, dir, `{ not json`)
	merged, ok := OverlayDesktopAI(settings, dir)
	if ok {
		t.Fatal("损坏文件不应覆盖")
	}
	if merged.LLM.APIKey != "sk-tui" {
		t.Fatalf("损坏文件不应影响原配置：%q", merged.LLM.APIKey)
	}

	// 空目录参数：直接返回原配置，不 panic
	if _, ok := OverlayDesktopAI(settings, ""); ok {
		t.Fatal("空目录应返回 false")
	}
}

func TestOverlayDesktopAIPreservesAPIKeyEnv(t *testing.T) {
	dir := t.TempDir()
	writeDesktop(t, dir, `{"enabled": true, "consent": true, "api_key": "sk-desktop"}`)

	settings := Default()
	settings.LLM.APIKeyEnv = "MY_CUSTOM_KEY"
	merged, ok := OverlayDesktopAI(settings, dir)
	if !ok {
		t.Fatal("应当覆盖成功")
	}
	if merged.LLM.APIKeyEnv != "MY_CUSTOM_KEY" {
		t.Fatalf("桌面端没有该字段，应保留终端既有值：%q", merged.LLM.APIKeyEnv)
	}
}
