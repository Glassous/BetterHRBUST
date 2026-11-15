package config

import (
	"os"
	"path/filepath"
	"testing"
)

func envOf(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}

func TestDefaultIsUsableButDisabled(t *testing.T) {
	settings := Default()
	if settings.LLM.Enabled || settings.LLM.Consent {
		t.Fatal("默认必须关闭 AI，且未同意出网")
	}
	if ready, reason := settings.Ready(envOf(nil)); ready {
		t.Fatal("默认不应可用")
	} else if reason == "" {
		t.Fatal("不可用时必须给出原因")
	}
	if settings.UI.Theme != ThemeAuto {
		t.Fatalf("默认主题应为 auto，实际 %q", settings.UI.Theme)
	}
}

func TestLoadMissingFileReturnsDefaults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "config.json")
	settings := Load(path)
	if settings.LoadWarning != "" {
		t.Fatalf("文件不存在不应告警：%s", settings.LoadWarning)
	}
	if settings.Path != path {
		t.Fatalf("应记录路径，实际 %q", settings.Path)
	}
	if settings.LLM.MaxToolRounds != 8 {
		t.Fatalf("应使用默认轮次，实际 %d", settings.LLM.MaxToolRounds)
	}
}

func TestLoadCorruptFileKeepsOriginals(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	original := []byte(`{"llm": {"api_key": "secret-value"`)
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}

	settings := Load(path)
	if settings.LoadWarning == "" {
		t.Fatal("损坏配置应给出告警")
	}
	// 原文件必须原样保留：里面可能有用户的 API Key。
	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("不得删除配置文件：%v", err)
	}
	if string(current) != string(original) {
		t.Fatal("不得覆盖损坏的配置文件")
	}
	if settings.LLM.MaxToolRounds != 8 {
		t.Fatal("损坏时应回退默认值")
	}
}

func TestSaveIsPrivateAndAtomic(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sub", "config.json")
	settings := Default()
	settings.Path = path
	settings.LLM.APIKey = "sk-test"
	settings.UI.Theme = ThemeLight

	if err := settings.Save(); err != nil {
		t.Fatalf("保存失败：%v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("文件应存在：%v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Fatalf("配置权限应为 0600，实际 %o", mode)
	}
	if _, err := os.Stat(path + ".tmp"); err == nil {
		t.Fatal("不应残留临时文件")
	}

	reloaded := Load(path)
	if reloaded.UI.Theme != ThemeLight {
		t.Fatalf("往返后主题应保留，实际 %q", reloaded.UI.Theme)
	}
	if reloaded.LLM.APIKey != "sk-test" {
		t.Fatal("往返后 API Key 应保留")
	}
}

func TestResolvedAPIKeyPrefersConfigThenEnv(t *testing.T) {
	settings := Default()
	settings.LLM.APIKey = ""
	settings.LLM.APIKeyEnv = "TEST_KEY"

	if got := settings.ResolvedAPIKey(envOf(map[string]string{"TEST_KEY": "from-env"})); got != "from-env" {
		t.Fatalf("应回退环境变量，实际 %q", got)
	}
	settings.LLM.APIKey = "from-config"
	if got := settings.ResolvedAPIKey(envOf(map[string]string{"TEST_KEY": "from-env"})); got != "from-config" {
		t.Fatalf("配置文件应优先，实际 %q", got)
	}
	if got := settings.ResolvedAPIKey(nil); got != "from-config" {
		t.Fatalf("getenv 为 nil 时不应崩溃，实际 %q", got)
	}
}

func TestReadyRequiresThreeSwitches(t *testing.T) {
	settings := Default()
	settings.LLM.APIKey = "sk-test"
	env := envOf(map[string]string{"BETTERHRBUST_LLM_KEY": "sk-env"})

	if ready, reason := settings.Ready(env); ready || reason == "" {
		t.Fatal("未启用时不应可用")
	}

	settings.LLM.Enabled = true
	if ready, reason := settings.Ready(env); ready || reason == "" {
		t.Fatal("未同意出网时不应可用")
	}

	settings.LLM.Consent = true
	if ready, _ := settings.Ready(env); !ready {
		t.Fatal("三个开关齐全时应可用")
	}

	settings.LLM.APIKey = ""
	if ready, _ := settings.Ready(envOf(nil)); ready {
		t.Fatal("缺少密钥时不应可用")
	}
	if ready, _ := settings.Ready(env); !ready {
		t.Fatal("环境变量提供密钥时应可用")
	}
}

func TestProviderHost(t *testing.T) {
	cases := []struct {
		url  string
		want string
	}{
		{"https://api.deepseek.com/v1", "api.deepseek.com"},
		{"http://127.0.0.1:11434/v1", "127.0.0.1:11434"},
		{"https://open.bigmodel.cn/api/paas/v4", "open.bigmodel.cn"},
		{"", "未配置"},
	}
	for _, item := range cases {
		settings := Default()
		settings.LLM.BaseURL = item.url
		if got := settings.ProviderHost(); got != item.want {
			t.Fatalf("%q 的主机期望 %q，实际 %q", item.url, item.want, got)
		}
	}
}

func TestRedactedHidesKey(t *testing.T) {
	settings := Default()
	settings.LLM.APIKey = "sk-very-secret"
	redacted := settings.Redacted()
	if redacted.LLM.APIKey == "sk-very-secret" {
		t.Fatal("脱敏后不应保留原值")
	}
	if redacted.LLM.APIKey == "" {
		t.Fatal("脱敏后应说明已设置")
	}
	if settings.LLM.APIKey != "sk-very-secret" {
		t.Fatal("脱敏不应修改原值")
	}
}

func TestNormalizedFixesInvalidValues(t *testing.T) {
	settings := Default()
	settings.UI.Theme = "neon"
	settings.LLM.MaxToolRounds = 999
	settings.LLM.TimeoutSecond = 1
	settings.LLM.Temperature = 9
	fixed := settings.Normalized()

	if fixed.UI.Theme != ThemeAuto {
		t.Fatalf("非法主题应回退 auto，实际 %q", fixed.UI.Theme)
	}
	if fixed.LLM.MaxToolRounds != 8 {
		t.Fatalf("越界轮次应回退 8，实际 %d", fixed.LLM.MaxToolRounds)
	}
	if fixed.LLM.TimeoutSecond != 120 {
		t.Fatalf("越界超时应回退 120，实际 %d", fixed.LLM.TimeoutSecond)
	}
	if fixed.LLM.Temperature != 0.3 {
		t.Fatalf("越界温度应回退 0.3，实际 %v", fixed.LLM.Temperature)
	}
}

func TestMergeKeepsDefaultsForMissingFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	// 只写一个字段，其余应保持默认。
	if err := os.WriteFile(path, []byte(`{"llm":{"model":"my-model"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	settings := Load(path)
	if settings.LLM.Model != "my-model" {
		t.Fatalf("应读取已写的模型，实际 %q", settings.LLM.Model)
	}
	if settings.LLM.BaseURL != "https://api.deepseek.com/v1" {
		t.Fatalf("未写的字段应保持默认，实际 %q", settings.LLM.BaseURL)
	}
	if settings.LLM.MaxToolRounds != 8 {
		t.Fatalf("未写的轮次应保持默认，实际 %d", settings.LLM.MaxToolRounds)
	}
}
