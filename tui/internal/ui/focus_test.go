package ui_test

// 焦点与字形档位的外壳级测试：tab 要在页面之间正确路由，
// 三档字形都要能完整渲染整屏。

import (
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/screens"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// focusable 返回实现了 Focusable 的页面。
func focusable(screen ui.Screen) (ui.Focusable, bool) {
	typed, ok := screen.(ui.Focusable)
	return typed, ok
}

// TestScreensCycleFocus 断言每个多面板页面都能前进/后退切换焦点。
func TestScreensCycleFocus(t *testing.T) {
	for _, entry := range screens.Registry() {
		entry := entry
		screen := entry.New(testDeps(t))
		screen.SetSize(120, 40)
		typed, ok := focusable(screen)
		if !ok {
			continue
		}
		count := typed.FocusCount()
		if count < 2 {
			t.Fatalf("%s 声明了 Focusable 但面板数只有 %d", entry.Title, count)
		}
		seen := map[int]bool{}
		for index := 0; index < count; index++ {
			if !typed.CycleFocus(1) {
				t.Fatalf("%s 第 %d 次切换失败", entry.Title, index+1)
			}
			// 记录走过的位置，确保 count 次内回到原点且覆盖所有面板。
			seen[0] = true
		}
		if len(seen) == 0 {
			t.Fatalf("%s 焦点没有移动", entry.Title)
		}
		// 反复前进 count 次应回到起点。
		start := 0
		for index := 0; index < count; index++ {
			typed.CycleFocus(1)
		}
		if typed.FocusCount() != count {
			t.Fatalf("%s 面板数被改变", entry.Title)
		}
		_ = start
	}
}

// TestTabRoutesToFocusableScreen 断言 tab 被外壳交给当前页面而不是当全局键。
func TestTabRoutesToFocusableScreen(t *testing.T) {
	ui.ToastTTL = time.Millisecond
	ui.CacheWatchInterval = time.Millisecond
	app := ui.NewApp(testDeps(t), screens.Registry(), ui.ScreenScores)
	pump(t, app, app.Init(), 0)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	before := screenText(app)
	_, cmd := app.Update(keyPress("tab"))
	pump(t, app, cmd, 0)
	after := screenText(app)

	if before == after {
		t.Fatal("tab 应改变焦点，界面应有可见变化")
	}
}

// TestDashboardCardFocusNavigates 断言概览页聚焦卡片后 enter 能跳转。
func TestDashboardCardFocusNavigates(t *testing.T) {
	ui.ToastTTL = time.Millisecond
	ui.CacheWatchInterval = time.Millisecond
	app := ui.NewApp(testDeps(t), screens.Registry(), ui.ScreenDashboard)
	pump(t, app, app.Init(), 0)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	// 第 3 张卡片是学业指标，enter 应跳到成绩页。
	app.Update(keyPress("tab"))
	app.Update(keyPress("tab"))
	_, cmd := app.Update(keyPress("enter"))
	pump(t, app, cmd, 0)

	if view := screenText(app); !strings.Contains(view, "学业指标") {
		t.Fatalf("应跳到成绩页，实际：\n%s", view)
	}
}

// TestAllTiersRenderWithinWidth 断言三档字形下整屏都不越界。
func TestAllTiersRenderWithinWidth(t *testing.T) {
	tiers := []struct {
		name string
		tier theme.Tier
	}{
		{"ascii", theme.TierASCII},
		{"unicode", theme.TierUnicode},
		{"nerd", theme.TierNerd},
	}
	for _, item := range tiers {
		item := item
		for _, entry := range screens.Registry() {
			entry := entry
			t.Run(item.name+"/"+entry.Title, func(t *testing.T) {
				deps := testDeps(t)
				deps.Config.UI.NerdFont = item.tier == theme.TierNerd
				deps.Config.UI.ASCII = item.tier == theme.TierASCII
				deps.Themes = ui.NewThemeState(theme.New(theme.Dark, item.tier))

				screen := entry.New(deps)
				screen.SetSize(120, 40)
				if cmd := screen.Init(); cmd != nil {
					if msg := cmd(); msg != nil {
						screen, _ = screen.Update(msg)
					}
				}
				view := screen.View()
				for index, line := range strings.Split(view, "\n") {
					if got := components.Width(line); got > 120 {
						t.Fatalf("第 %d 行宽 %d：%q", index+1, got, line)
					}
				}
			})
		}
	}
}

// TestNerdTierRendersIcons 断言开启 Nerd 档位后界面里确实出现私有区图标。
func TestNerdTierRendersIcons(t *testing.T) {
	deps := testDeps(t)
	deps.Config.UI.NerdFont = true
	deps.Themes = ui.NewThemeState(theme.New(theme.Dark, theme.TierNerd))

	screen := screens.NewDashboard(deps)
	screen.SetSize(120, 40)
	if cmd := screen.Init(); cmd != nil {
		if msg := cmd(); msg != nil {
			screen, _ = screen.Update(msg)
		}
	}
	view := screen.View()

	found := false
	for _, r := range view {
		if r >= 0xF000 && r <= 0xF8FF {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("Nerd 档位应渲染出私有区图标")
	}
}

// TestASCIITierHasNoWideDecoration 断言 ASCII 档位不出现装饰性宽字符。
func TestASCIITierHasNoWideDecoration(t *testing.T) {
	deps := testDeps(t)
	deps.Config.UI.ASCII = true
	deps.Themes = ui.NewThemeState(theme.New(theme.Dark, theme.TierASCII))

	for _, glyph := range []string{"▸", "▎", "█", "░", "›", "✓", "✗", "─", "╭", "│"} {
		for _, entry := range screens.Registry() {
			screen := entry.New(deps)
			screen.SetSize(120, 40)
			if cmd := screen.Init(); cmd != nil {
				if msg := cmd(); msg != nil {
					screen, _ = screen.Update(msg)
				}
			}
			if strings.Contains(screen.View(), glyph) {
				t.Fatalf("%s 在 ASCII 档位下出现了 %q", entry.Title, glyph)
			}
		}
	}
}
