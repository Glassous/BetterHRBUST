package screens

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// renderSyncDeps 构造最小依赖：本页不依赖本地缓存，但需要主题。
func renderSyncDeps(tier theme.Tier) ui.Deps {
	return ui.Deps{
		Config: config.Default(),
		Themes: ui.NewThemeState(theme.New(theme.Dark, tier)),
	}
}

// renderSyncDepsWithService 追加一个真实的同步服务（数据目录指向临时目录）。
// 它不会联网：这些用例只驱动键盘，不执行返回的 tea.Cmd。
func renderSyncDepsWithService(t *testing.T, tier theme.Tier) ui.Deps {
	t.Helper()
	deps := renderSyncDeps(tier)
	deps.Sync = campus.NewService(t.TempDir(), time.Now)
	return deps
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

// encodeTestImage 把测试图编码成真实图片字节。
func encodeTestImage(t *testing.T, format string) []byte {
	t.Helper()
	var buffer bytes.Buffer
	var err error
	switch format {
	case "jpeg":
		err = jpeg.Encode(&buffer, testImage(), nil)
	default:
		err = png.Encode(&buffer, testImage())
	}
	if err != nil {
		t.Fatalf("编码测试图失败：%v", err)
	}
	return buffer.Bytes()
}

// TestCaptchaEventDecodesRealImages 是最关键的一条：
// 教务返回的是 JPEG（部分学期是 PNG），解码器没注册就会显示成
// "验证码图片无法识别"——用户会以为终端根本不能登录。
func TestCaptchaEventDecodesRealImages(t *testing.T) {
	for _, format := range []string{"png", "jpeg"} {
		screen := NewSync(renderSyncDeps(theme.TierUnicode)).(*Sync)
		screen.SetSize(120, 40)

		updated, _ := screen.Update(syncEventMsg{kind: "captcha", image: encodeTestImage(t, format)})
		sync := updated.(*Sync)

		if sync.captcha == nil {
			t.Fatalf("%s 验证码应解码成功，实际提示：%q", format, sync.captchaHint)
		}
		if len(sync.captchaRaw) == 0 {
			t.Fatalf("%s 验证码原始字节应保留（o 键要用）", format)
		}
		if sync.captchaFormat != format {
			t.Fatalf("格式识别错误：期望 %s，实际 %s", format, sync.captchaFormat)
		}
		if view := sync.View(); !strings.Contains(view, "\x1b[38;2;") {
			t.Fatalf("%s 验证码应渲染进视图", format)
		}
	}
}

// TestSyncFormAcceptsDigitsDirectly 是这次交互改造的核心用例：
// 打开页面后直接敲学号，字符必须进第一个字段（不再要求先按 enter）。
func TestSyncFormAcceptsDigitsDirectly(t *testing.T) {
	screen := NewSync(renderSyncDeps(theme.TierUnicode)).(*Sync)
	screen.SetSize(120, 40)

	for _, digit := range []string{"2", "3", "0", "4", "0", "6"} {
		screen.Update(keyPressMsg(digit))
	}
	if got := screen.fields[0].value; got != "230406" {
		t.Fatalf("学号字段应收到全部数字，实际 %q", got)
	}
	if screen.focus != 0 {
		t.Fatalf("输入过程中不应切换焦点，实际焦点 %d", screen.focus)
	}
}

// TestSyncEnterWalksFieldsThenValidates 验证回车依次走完三个字段，
// 最后一步不是发请求而是先校验必填项（避免无意义的网络调用）。
func TestSyncEnterWalksFieldsThenValidates(t *testing.T) {
	screen := NewSync(renderSyncDepsWithService(t, theme.TierUnicode)).(*Sync)
	screen.SetSize(120, 40)

	screen.Update(keyPressMsg("1"))
	screen.Update(keyPressMsg("enter"))
	if screen.focus != 1 {
		t.Fatalf("回车应移到密码字段，实际 %d", screen.focus)
	}
	screen.Update(keyPressMsg("enter"))
	if screen.focus != 2 {
		t.Fatalf("回车应移到验证码字段，实际 %d", screen.focus)
	}
	screen.Update(keyPressMsg("enter"))
	if screen.focus != 1 || !strings.Contains(screen.notice, "请先填写密码") {
		t.Fatalf("缺密码时应回到该字段并提示，实际焦点 %d 提示 %q", screen.focus, screen.notice)
	}
}

// TestSyncCaptchaUsesFullWidthAndZoom 保证验证码用整页宽度渲染，
// 且 ctrl+g 放大真的变宽（这是"分辨率不够"的直接对策）。
func TestSyncCaptchaUsesFullWidthAndZoom(t *testing.T) {
	screen := NewSync(renderSyncDeps(theme.TierUnicode)).(*Sync)
	screen.SetSize(120, 40)
	screen.captcha = testImage() // 12×8

	th := screen.deps.Themes.Get()
	base := screen.captchaLines(th, 100, 28)
	if len(base) != 4 {
		t.Fatalf("12×8 在 1× 下应渲染 4 行，实际 %d", len(base))
	}
	baseWidth := components.Width(strings.TrimLeft(base[0], " "))

	screen.Update(ctrlKey('g'))
	if screen.zoom != 2 {
		t.Fatalf("ctrl+g 应切到 2×，实际 %d", screen.zoom)
	}
	zoomed := screen.captchaLines(th, 100, 28)
	zoomedWidth := components.Width(strings.TrimLeft(zoomed[0], " "))
	if zoomedWidth <= baseWidth {
		t.Fatalf("放大后应更宽：%d → %d", baseWidth, zoomedWidth)
	}
	if len(zoomed) != 8 {
		t.Fatalf("12×8 在 2× 下应渲染 8 行，实际 %d", len(zoomed))
	}
}

// keyPressMsg 构造单字符按键。
func keyPressMsg(text string) tea.KeyPressMsg {
	runes := []rune(text)
	return tea.KeyPressMsg(tea.Key{Code: runes[0], Text: text})
}

// ctrlKey 构造 ctrl 组合键。
func ctrlKey(code rune) tea.KeyPressMsg {
	return tea.KeyPressMsg(tea.Key{Code: code, Mod: tea.ModCtrl})
}

// TestSaveCaptchaUsesRealExtension 保证交给看图器的文件扩展名正确，
// 否则 Windows 会拿默认程序（可能是文本编辑器）去打开二进制。
func TestSaveCaptchaUsesRealExtension(t *testing.T) {
	screen := NewSync(renderSyncDeps(theme.TierUnicode)).(*Sync)
	screen.SetSize(120, 40)
	screen.Update(syncEventMsg{kind: "captcha", image: encodeTestImage(t, "jpeg")})

	path, err := screen.saveCaptcha()
	if err != nil {
		t.Fatalf("保存验证码失败：%v", err)
	}
	defer os.Remove(path)

	if filepath.Ext(path) != ".jpg" {
		t.Fatalf("扩展名应为 .jpg，实际 %s", filepath.Ext(path))
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取落盘验证码失败：%v", err)
	}
	if !bytes.Equal(content, screen.captchaRaw) {
		t.Fatal("落盘内容必须是原始字节")
	}
}
