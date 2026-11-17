package screens

import (
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
)

// 设置页提交教务地址：归一化后写入配置，非法输入只提示、不改配置。
func TestCommitAcademicBaseNormalizesAndValidates(t *testing.T) {
	settings := &Settings{deps: ui.Deps{Config: config.Default()}}

	msg := settings.commitText("教务地址", "jwzx.fiacloud.top")()
	applied, ok := msg.(ui.ApplyConfigMsg)
	if !ok {
		t.Fatalf("合法地址应产生配置变更，实际 %T", msg)
	}
	if got := applied.Config.Academic.BaseURL; got != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("落盘值应为归一化地址，实际 %q", got)
	}
	if applied.Config.EffectiveAcademicBase() != "https://jwzx.fiacloud.top/academic/" {
		t.Fatalf("生效地址不正确：%q", applied.Config.EffectiveAcademicBase())
	}

	// 留空 = 恢复官方默认（删除自定义值，而不是写入官方地址）
	msg = settings.commitText("教务地址", "  ")()
	applied = msg.(ui.ApplyConfigMsg)
	if applied.Config.Academic.BaseURL != "" {
		t.Fatalf("留空应清空自定义地址，实际 %q", applied.Config.Academic.BaseURL)
	}
	if applied.Config.EffectiveAcademicBase() != campus.OfficialBaseURL {
		t.Fatalf("留空后应回到官方默认，实际 %q", applied.Config.EffectiveAcademicBase())
	}

	// 非法地址：只给提示，不产生配置变更
	msg = settings.commitText("教务地址", "ftp://jwzx.example.edu.cn")()
	toast, ok := msg.(ui.ToastMsg)
	if !ok {
		t.Fatalf("非法地址应提示错误，实际 %T", msg)
	}
	if !strings.Contains(toast.Text, "http") {
		t.Fatalf("提示文案应说明协议限制，实际 %q", toast.Text)
	}
}

// 恢复默认走确认框，确认后清空自定义地址与 --base-url 覆盖。
func TestConfirmResetAcademicBaseClearsBoth(t *testing.T) {
	settings := &Settings{deps: ui.Deps{Config: config.Default()}}
	current := settings.deps.Config
	current.Academic.BaseURL = "https://jwzx.fiacloud.top/academic/"
	current.Academic.BaseURLOverride = "https://mirror.example.edu.cn/academic/"

	msg := settings.confirmResetAcademicBase(current)()
	confirm, ok := msg.(ui.ConfirmMsg)
	if !ok {
		t.Fatalf("应弹出确认框，实际 %T", msg)
	}
	applied, ok := confirm.Action().(ui.ApplyConfigMsg)
	if !ok {
		t.Fatalf("确认后应提交配置，实际 %T", confirm.Action())
	}
	if applied.Config.Academic.BaseURL != "" || applied.Config.Academic.BaseURLOverride != "" {
		t.Fatal("恢复默认应同时清空自定义地址与临时覆盖")
	}
	if applied.Config.EffectiveAcademicBase() != campus.OfficialBaseURL {
		t.Fatalf("恢复默认后应使用官方地址，实际 %q", applied.Config.EffectiveAcademicBase())
	}
}
