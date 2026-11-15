package screens

import (
	"image"
	"image/color"
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// renderSyncDeps 构造最小依赖：本页不依赖本地缓存，但需要主题。
func renderSyncDeps(tier theme.Tier) ui.Deps {
	return ui.Deps{
		Config: config.Default(),
		Themes: ui.NewThemeState(theme.New(theme.Dark, tier)),
	}
}

// TestSyncScreenRendersWithoutSyncService 保证在缺少同步能力（测试环境）时
// 页面仍然可渲染，并给出"不可用"的说明，而不是空白或 panic。
func TestSyncScreenRendersWithoutSyncService(t *testing.T) {
	for _, size := range [][2]int{{120, 40}, {80, 24}, {72, 20}} {
		screen := NewSync(renderSyncDeps(theme.TierUnicode))
		screen.SetSize(size[0], size[1])
		view := screen.View()
		if strings.TrimSpace(view) == "" {
			t.Fatalf("%dx%d 渲染为空", size[0], size[1])
		}
		if !strings.Contains(view, "登录") {
			t.Fatalf("%dx%d 应展示登录表单：%q", size[0], size[1], view)
		}
	}
}

// TestSyncScreenShowsCaptchaImage 验证验证码会被画进视图（含真彩色转义），
// 无色主题下则退化为说明文字——这是终端能否独立登录的关键。
func TestSyncScreenShowsCaptchaImage(t *testing.T) {
	screen := NewSync(renderSyncDeps(theme.TierUnicode)).(*Sync)
	screen.SetSize(120, 40)
	screen.captcha = testImage()

	view := screen.View()
	if !strings.Contains(view, "\x1b[38;2;") {
		t.Fatal("验证码应以真彩色半块字符渲染")
	}

	plain := NewSync(renderSyncDeps(theme.TierASCII)).(*Sync)
	plain.deps.Themes = ui.NewThemeState(theme.Plain())
	plain.SetSize(120, 40)
	plain.captcha = testImage()
	plainView := plain.View()
	if strings.Contains(plainView, "\x1b[38;2;") {
		t.Fatal("无色主题下不应输出真彩色转义")
	}
	if !strings.Contains(plainView, "验证码") {
		t.Fatal("无色主题下应给出验证码不可显示的说明")
	}
}

// testImage 生成一张 12×8 的测试图（左半黑右半白，便于检查渲染宽度）。
func testImage() image.Image {
	img := image.NewRGBA(image.Rect(0, 0, 12, 8))
	for y := 0; y < 8; y++ {
		for x := 0; x < 12; x++ {
			if x < 6 {
				img.Set(x, y, color.RGBA{A: 255})
				continue
			}
			img.Set(x, y, color.RGBA{R: 255, G: 255, B: 255, A: 255})
		}
	}
	return img
}
