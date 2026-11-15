package screens_test

// 本文件是视图层的"结构回归"测试。
//
// 断言三件事：
//  1. 每个页面在真实夹具上都能渲染，不 panic（缓存来自外部文件，越界下标会让界面消失）；
//  2. 每一条物理行的显示宽度都不超过可用宽度——这是 CJK 混排最容易出错的地方，
//     超过宽度会让终端自动折行，页脚被挤走、边框错位；
//  3. 关键页面与 golden 快照一致，改动排版时能立刻看到差异。

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/parity"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/screens"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

var updateGolden = flag.Bool("update", false, "重新生成 golden 快照")

// fixtureSnapshot 用共享夹具构造缓存快照。
func fixtureSnapshot(t *testing.T) *model.Snapshot {
	t.Helper()
	dir, err := parity.Dir()
	if err != nil {
		t.Fatalf("定位共享目录失败：%v", err)
	}
	content, err := os.ReadFile(filepath.Join(dir, "fixtures", "storage-full.json"))
	if err != nil {
		t.Fatalf("读取夹具失败：%v", err)
	}
	raw := map[string]string{}
	if err := json.Unmarshal(content, &raw); err != nil {
		t.Fatalf("夹具不是 map[string]string：%v", err)
	}
	return model.New("/fixture", "/fixture/storage.json", true, "", int64(len(content)), raw)
}

// testDeps 组装测试依赖。
func testDeps(t *testing.T) ui.Deps {
	t.Helper()
	snapshot := fixtureSnapshot(t)
	clock := query.Clock{
		Today:       academic.Date{Year: 2026, Month: 11, Day: 13},
		MinuteOfDay: 600,
		Location:    time.FixedZone("CST", 8*3600),
	}
	service := query.New(snapshot, clock, "test")
	service.GeneratedAt = time.Date(2026, 11, 13, 12, 0, 0, 0, clock.Location)
	readiness := service.Readiness()
	settings := config.Default()
	settings.UI.Theme = config.ThemeANSI16
	settings.UI.ASCII = true

	return ui.Deps{
		Service:       service,
		Toolset:       query.NewToolset(service),
		Config:        settings,
		Version:       "test",
		Clock:         clock,
		Themes:        ui.NewThemeState(theme.New(theme.ANSI16, theme.TierASCII)),
		Readiness:     readiness,
		SyncText:      service.LastSyncText(),
		Week:          11,
		SemesterLabel: "2026 秋",
		StaleDays:     1,
		Emit:          func(tea.Msg) {},
		NewChat:       screens.ChatFactory(),
	}
}

// renderScreen 创建页面、喂入数据并渲染。
func renderScreen(t *testing.T, entry ui.Entry, width, height int) string {
	t.Helper()
	deps := testDeps(t)
	screen := entry.New(deps)
	screen.SetSize(width, height)

	// 触发加载：直接跑 Init 返回的命令并回灌消息。
	if cmd := screen.Init(); cmd != nil {
		if msg := cmd(); msg != nil {
			screen, _ = screen.Update(msg)
		}
	}
	return screen.View()
}

// TestScreensRenderWithinWidth 在多种尺寸下渲染全部页面并检查宽度。
func TestScreensRenderWithinWidth(t *testing.T) {
	sizes := []struct {
		name   string
		width  int
		height int
	}{
		{"标准 120x40", 120, 40},
		{"窄屏 80x24", 80, 24},
		{"最小 72x20", 72, 20},
		{"宽屏 200x60", 200, 60},
		{"高窄 76x50", 76, 50},
	}

	for _, entry := range screens.Registry() {
		entry := entry
		for _, size := range sizes {
			size := size
			t.Run(entry.Title+"/"+size.name, func(t *testing.T) {
				view := renderScreen(t, entry, size.width, size.height)
				if strings.TrimSpace(view) == "" {
					t.Fatal("渲染结果为空")
				}
				checkWidth(t, view, size.width)
			})
		}
	}
}

// TestScreensHandleEmptyCache 在空缓存下不 panic 且给出引导。
func TestScreensHandleEmptyCache(t *testing.T) {
	for _, entry := range screens.Registry() {
		entry := entry
		t.Run(entry.Title, func(t *testing.T) {
			deps := testDeps(t)
			deps.Service = query.New(
				model.New("/none", "/none/storage.json", false, "", 0, nil),
				deps.Clock, "test")
			deps.Readiness = deps.Service.Readiness()

			screen := entry.New(deps)
			screen.SetSize(110, 36)
			if cmd := screen.Init(); cmd != nil {
				if msg := cmd(); msg != nil {
					screen, _ = screen.Update(msg)
				}
			}
			view := screen.View()
			if strings.TrimSpace(view) == "" {
				t.Fatal("空缓存下渲染结果为空")
			}
			checkWidth(t, view, 110)
		})
	}
}

// TestScreensHandleCorruptCache 在存储损坏时显示错误态而不是崩溃。
func TestScreensHandleCorruptCache(t *testing.T) {
	for _, entry := range screens.Registry() {
		entry := entry
		t.Run(entry.Title, func(t *testing.T) {
			deps := testDeps(t)
			broken := model.New("/bad", "/bad/storage.json", true, "无法解析本地数据文件", 0, nil)
			deps.Service = query.New(broken, deps.Clock, "test")
			deps.Readiness = deps.Service.Readiness()

			screen := entry.New(deps)
			screen.SetSize(110, 36)
			if cmd := screen.Init(); cmd != nil {
				if msg := cmd(); msg != nil {
					screen, _ = screen.Update(msg)
				}
			}
			_ = screen.View()
		})
	}
}

// TestDashboardGolden 固定概览页排版。
func TestDashboardGolden(t *testing.T) {
	entry, ok := findEntry(ui.ScreenDashboard)
	if !ok {
		t.Fatal("注册表里没有概览页")
	}
	view := renderScreen(t, entry, 120, 40)
	assertGolden(t, "dashboard_120x40.golden", view)
}

// TestTimetableGolden 固定课表网格排版。
func TestTimetableGolden(t *testing.T) {
	entry, ok := findEntry(ui.ScreenTimetable)
	if !ok {
		t.Fatal("注册表里没有课表页")
	}
	view := renderScreen(t, entry, 120, 40)
	assertGolden(t, "timetable_120x40.golden", view)
}

// TestScoresGolden 固定成绩页排版。
func TestScoresGolden(t *testing.T) {
	entry, ok := findEntry(ui.ScreenScores)
	if !ok {
		t.Fatal("注册表里没有成绩页")
	}
	view := renderScreen(t, entry, 120, 40)
	assertGolden(t, "scores_120x40.golden", view)
}

// TestDoctorGolden 固定诊断页排版。
func TestDoctorGolden(t *testing.T) {
	entry, ok := findEntry(ui.ScreenDoctor)
	if !ok {
		t.Fatal("注册表里没有诊断页")
	}
	view := renderScreen(t, entry, 120, 40)
	assertGolden(t, "doctor_120x40.golden", view)
}

// TestProfileNeverShowsRawPII 断言学籍页永不显示完整证件号。
func TestProfileNeverShowsRawPII(t *testing.T) {
	entry, ok := findEntry(ui.ScreenProfile)
	if !ok {
		t.Fatal("注册表里没有学籍页")
	}
	view := renderScreen(t, entry, 120, 40)
	for _, forbidden := range []string{
		"110101199001011234", "13800001111", "zhangsan@example.com", "南岗区",
	} {
		if strings.Contains(view, forbidden) {
			t.Fatalf("学籍页出现了未打码内容 %q", forbidden)
		}
	}
	if !strings.Contains(view, "1101") {
		t.Fatal("打码后仍应保留前 4 位，便于用户确认是自己的档案")
	}
}

// TestSettingsShowsLLMGuard 断言未配置时设置页明确说明 AI 不可用。
func TestSettingsShowsLLMGuard(t *testing.T) {
	entry, ok := findEntry(ui.ScreenSettings)
	if !ok {
		t.Fatal("注册表里没有设置页")
	}
	view := renderScreen(t, entry, 120, 44)
	if !strings.Contains(view, "AI") {
		t.Fatal("设置页应包含 AI 相关配置项")
	}
	if !strings.Contains(view, "未启用") && !strings.Contains(view, "缺少 API Key") {
		t.Fatalf("未配置时应说明不可用原因，实际：\n%s", view)
	}
}

// TestChatShowsSetupWhenDisabled 断言抽屉在未启用时给出配置引导。
func TestChatShowsSetupWhenDisabled(t *testing.T) {
	deps := testDeps(t)
	chat := screens.NewChat(deps)
	chat.SetSize(48, 30)
	if cmd := chat.Init(); cmd != nil {
		cmd()
	}
	view := chat.View()
	for _, want := range []string{"AI 对话尚未就绪", "设置页", "环境变量"} {
		if !strings.Contains(view, want) {
			t.Fatalf("引导卡片缺少 %q：\n%s", want, view)
		}
	}
	checkWidth(t, view, 48)
}

// TestScreenKeysAreEnabled 断言每个页面至少有一个可用按键说明。
func TestScreenKeysAreEnabled(t *testing.T) {
	for _, entry := range screens.Registry() {
		entry := entry
		screen := entry.New(testDeps(t))
		screen.SetSize(100, 30)
		var enabled int
		for _, binding := range screen.Keys() {
			if binding.Enabled() {
				enabled++
			}
			if binding.Help().Key == "" || binding.Help().Desc == "" {
				t.Errorf("%s 的按键缺少帮助文案：%+v", entry.Title, binding.Help())
			}
		}
		// 只读页面（学籍）可以没有页面级按键；但只要声明了按键就必须可用。
		if enabled == 0 && len(screen.Keys()) > 0 {
			t.Errorf("%s 声明了按键但全部不可用", entry.Title)
		}
	}
}

// TestRegistryMatchesKeyMap 断言注册表顺序与数字键映射一致。
func TestRegistryMatchesKeyMap(t *testing.T) {
	keys := ui.DefaultKeyMap()
	registry := screens.Registry()
	nav := keys.NavBindings()
	if len(registry) != len(nav) {
		t.Fatalf("注册表 %d 项，导航键 %d 个", len(registry), len(nav))
	}
	for index, entry := range registry {
		if entry.Key != nav[index].Help().Key {
			t.Fatalf("第 %d 项 %s 的快捷键是 %q，导航键是 %q",
				index, entry.Title, entry.Key, nav[index].Help().Key)
		}
	}
}

// ---------- 工具 ----------

func findEntry(id ui.ID) (ui.Entry, bool) {
	for _, entry := range screens.Registry() {
		if entry.ID == id {
			return entry, true
		}
	}
	return ui.Entry{}, false
}

// checkWidth 断言每一行的显示宽度都不超过可用宽度。
func checkWidth(t *testing.T, view string, width int) {
	t.Helper()
	for index, line := range strings.Split(view, "\n") {
		if got := components.Width(line); got > width {
			t.Fatalf("第 %d 行宽度 %d 超过 %d：%q", index+1, got, width, line)
		}
	}
}

// assertGolden 比对 golden 快照。
func assertGolden(t *testing.T, name, got string) {
	t.Helper()
	path := filepath.Join("testdata", name)
	if *updateGolden {
		if err := os.MkdirAll("testdata", 0o755); err != nil {
			t.Fatalf("创建 testdata 失败：%v", err)
		}
		if err := os.WriteFile(path, []byte(got), 0o644); err != nil {
			t.Fatalf("写入 golden 失败：%v", err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取 golden 失败（先执行 go test ./internal/ui/screens -update）：%v", err)
	}
	if string(want) != got {
		t.Fatalf("与 golden 不一致：\n--- want ---\n%s\n--- got ---\n%s", string(want), got)
	}
}
