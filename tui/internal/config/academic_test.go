package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
)

func TestEffectiveAcademicBasePriority(t *testing.T) {
	// 默认：官方地址
	if got := Default().EffectiveAcademicBase(); got != campus.OfficialBaseURL {
		t.Fatalf("默认应为官方地址，实际 %q", got)
	}

	// 配置文件里的地址
	settings := Default()
	settings.Academic.BaseURL = "https://jwzx.fiacloud.top/homepage/index.do"
	if got := settings.EffectiveAcademicBase(); got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("应使用配置地址，实际 %q", got)
	}

	// --base-url 临时覆盖优先于配置文件
	settings.Academic.BaseURLOverride = "https://mirror.example.edu.cn"
	if got := settings.EffectiveAcademicBase(); got != "https://mirror.example.edu.cn/academic/" {
		t.Fatalf("命令行覆盖应优先，实际 %q", got)
	}

	// 手工改坏的地址不能把请求链路带偏
	broken := Default()
	broken.Academic.BaseURL = "ftp://jwzx.example.edu.cn"
	if got := broken.EffectiveAcademicBase(); got != campus.OfficialBaseURL {
		t.Fatalf("非法地址应回退官方默认，实际 %q", got)
	}
}

func TestSaveNeverPersistsCommandLineOverride(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	settings := Default()
	settings.Path = path
	settings.Academic.BaseURL = "https://jwzx.fiacloud.top/academic/"
	settings.Academic.BaseURLOverride = "https://mirror.example.edu.cn"
	if err := settings.Save(); err != nil {
		t.Fatalf("保存失败：%v", err)
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取配置文件失败：%v", err)
	}
	var payload map[string]any
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatalf("配置文件应为合法 JSON：%v", err)
	}
	academic, ok := payload["academic"].(map[string]any)
	if !ok {
		t.Fatal("配置文件应包含 academic 段")
	}
	if academic["base_url"] != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("academic.base_url 未写入：%v", academic)
	}
	if _, exists := academic["base_url_override"]; exists {
		t.Fatal("--base-url 的临时覆盖不能落盘")
	}
	if strings.Contains(string(raw), "mirror.example.edu.cn") {
		t.Fatal("临时覆盖不应出现在配置文件里")
	}

	// 重新加载：只有落盘的地址生效
	reloaded := Load(path)
	if got := reloaded.EffectiveAcademicBase(); got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("重新加载后应沿用落盘地址，实际 %q", got)
	}
}

func TestOverlayDesktopAcademicBase(t *testing.T) {
	dir := t.TempDir()
	// 桌面端未配置 → 终端保持不变
	if _, applied := OverlayDesktopAcademicBase(Default(), dir); applied {
		t.Fatal("桌面端没有配置时不应覆盖")
	}

	// 桌面端 storage.json 写了自定义地址 → 终端只读复用
	storage := map[string]string{
		desktopAcademicBaseKey: "https://jwzx.fiacloud.top/homepage/index.do",
	}
	encoded, _ := json.Marshal(storage)
	if err := os.WriteFile(filepath.Join(dir, desktopStorageFile), encoded, 0o600); err != nil {
		t.Fatalf("写入 storage.json 失败：%v", err)
	}
	merged, applied := OverlayDesktopAcademicBase(Default(), dir)
	if !applied {
		t.Fatal("桌面端配置应被复用")
	}
	if got := merged.EffectiveAcademicBase(); got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("覆盖后的地址不正确：%q", got)
	}

	// 终端自己配过 → 以终端为准
	own := Default()
	own.Academic.BaseURL = "https://own.example.edu.cn/academic/"
	kept, applied := OverlayDesktopAcademicBase(own, dir)
	if applied {
		t.Fatal("终端自己的配置不应被桌面端覆盖")
	}
	if got := kept.EffectiveAcademicBase(); got != "https://own.example.edu.cn/academic/" {
		t.Fatalf("终端配置被改写：%q", got)
	}
}

func TestOverlayDesktopAcademicBaseIgnoresBrokenFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, desktopStorageFile), []byte("{ not json"), 0o600); err != nil {
		t.Fatalf("写入损坏文件失败：%v", err)
	}
	if _, applied := OverlayDesktopAcademicBase(Default(), dir); applied {
		t.Fatal("损坏的 storage.json 不应影响教务地址")
	}
}
