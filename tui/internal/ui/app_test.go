package ui_test

import (
	"encoding/json"
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

// newTestApp 构造一个使用夹具数据的完整外壳。
func newTestApp(t *testing.T, width, height int) *ui.App {
	t.Helper()
	ui.ToastTTL = time.Millisecond
	ui.CacheWatchInterval = time.Millisecond
	return ui.NewApp(testDeps(t), screens.Registry(), ui.ScreenDashboard)
}

// testDeps 与 screens 包里的夹具保持同一套数据。
func testDeps(t *testing.T) ui.Deps {
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
		t.Fatalf("夹具格式错误：%v", err)
	}

	clock := query.Clock{
		Today:       academic.Date{Year: 2026, Month: 11, Day: 13},
		MinuteOfDay: 600,
		Location:    time.FixedZone("CST", 8*3600),
	}
	snapshot := model.New("/fixture", "/fixture/storage.json", true, "", int64(len(content)), raw)
	service := query.New(snapshot, clock, "test")
	service.GeneratedAt = time.Date(2026, 11, 13, 12, 0, 0, 0, clock.Location)

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
		Readiness:     service.Readiness(),
		SyncText:      service.LastSyncText(),
		Week:          11,
		SemesterLabel: "2026 秋",
		StaleDays:     1,
		Emit:          func(tea.Msg) {},
		NewChat:       screens.ChatFactory(),
		SaveConfig:    func(config.Config) error { return nil },
		Reload:        func(previous ui.Deps) ui.Deps { return previous },
	}
}

// pump 执行一个命令及其批次子命令，把产生的消息全部回灌给外壳。
func pump(t *testing.T, app *ui.App, cmd tea.Cmd, depth int) {
	t.Helper()
	if cmd == nil || depth > 3 {
		return
	}
	msg := cmd()
	switch typed := msg.(type) {
	case nil:
		return
	case tea.BatchMsg:
		for _, sub := range typed {
			pump(t, app, sub, depth+1)
		}
	case tea.QuitMsg:
		return
	default:
		_, next := app.Update(msg)
		pump(t, app, next, depth+1)
	}
}

// boot 完成"启动 → 首次尺寸 → 首屏数据加载"。
func boot(t *testing.T, width, height int) *ui.App {
	t.Helper()
	app := newTestApp(t, width, height)
	pump(t, app, app.Init(), 0)
	_, cmd := app.Update(tea.WindowSizeMsg{Width: width, Height: height})
	pump(t, app, cmd, 0)
	return app
}

// screenText 返回当前渲染文本。
func screenText(app *ui.App) string { return app.View().Content }

func TestAppRendersExactHeight(t *testing.T) {
	for _, size := range []struct{ width, height int }{
		{120, 40}, {80, 24}, {72, 20}, {200, 60},
	} {
		app := boot(t, size.width, size.height)
		view := screenText(app)
		lines := strings.Split(view, "\n")
		if len(lines) > size.height {
			t.Fatalf("%dx%d：渲染了 %d 行，超过终端高度", size.width, size.height, len(lines))
		}
		for index, line := range lines {
			if got := components.Width(line); got > size.width {
				t.Fatalf("%dx%d：第 %d 行宽 %d：%q", size.width, size.height, index+1, got, line)
			}
		}
	}
}

func TestAppShowsDataOnDashboard(t *testing.T) {
	app := boot(t, 120, 40)
	view := screenText(app)
	for _, want := range []string{"BetterHRBUST", "张三", "平均学分绩点", "软件体系结构与设计模式", "高等数学"} {
		if !strings.Contains(view, want) {
			t.Fatalf("概览页缺少 %q", want)
		}
	}
}

func TestAppNavigatesWithDigits(t *testing.T) {
	app := boot(t, 120, 40)
	cases := []struct {
		key  string
		want string
	}{
		{"2", "第 11 周"},
		{"3", "学业指标"},
		{"4", "考试安排"},
		{"5", "学分进度"},
		{"6", "公告"},
		{"7", "学籍概览"},
		{"8", "本学期课程"},
		{"0", "数据健康"},
		{",", "设置"},
		{"1", "今日课程"},
	}
	for _, item := range cases {
		_, cmd := app.Update(keyPress(item.key))
		pump(t, app, cmd, 0)
		view := screenText(app)
		if !strings.Contains(view, item.want) {
			t.Fatalf("按 %q 后应看到 %q，实际：\n%s", item.key, item.want, view)
		}
	}
}

func TestAppMinSizeGuard(t *testing.T) {
	app := boot(t, 60, 15)
	view := screenText(app)
	if !strings.Contains(view, "终端窗口过小") {
		t.Fatalf("小尺寸应显示守卫文案，实际：\n%s", view)
	}
	// 守卫文案本身也不能超宽。
	for _, line := range strings.Split(view, "\n") {
		if components.Width(line) > 60 {
			t.Fatalf("守卫文案超宽：%q", line)
		}
	}
	if len(strings.Split(view, "\n")) > 15 {
		t.Fatal("守卫文案不应超出终端高度")
	}
}

func TestAppCommandPalette(t *testing.T) {
	app := boot(t, 120, 40)

	app.Update(keyPress("ctrl+p"))
	view := screenText(app)
	// 用面板输入框的占位文案作标记：页脚里也有「命令面板」四个字，不能用作判据。
	if !strings.Contains(view, "输入命令或页面名") {
		t.Fatalf("应打开命令面板，实际：\n%s", view)
	}

	// 输入中文筛选。
	for _, r := range "成绩" {
		app.Update(tea.KeyPressMsg(tea.Key{Text: string(r), Code: r}))
	}
	view = screenText(app)
	if !strings.Contains(view, "成绩与 GPA") {
		t.Fatalf("筛选后应显示成绩页，实际：\n%s", view)
	}

	// 回车跳转。
	_, cmd := app.Update(keyPress("enter"))
	pump(t, app, cmd, 0)
	if view := screenText(app); !strings.Contains(view, "平均学分绩点") {
		t.Fatalf("选中后应跳到成绩页，实际：\n%s", view)
	}

	// esc 关闭面板。
	app.Update(keyPress("ctrl+p"))
	app.Update(keyPress("esc"))
	if view := screenText(app); strings.Contains(view, "输入命令或页面名") {
		t.Fatal("esc 应关闭命令面板")
	}
}

func TestAppHelpOverlay(t *testing.T) {
	app := boot(t, 120, 40)
	app.Update(keyPress("?"))
	view := screenText(app)
	if !strings.Contains(view, "按 esc 或 ? 关闭") {
		t.Fatalf("应打开帮助浮层，实际：\n%s", view)
	}
	if !strings.Contains(view, "命令面板") || !strings.Contains(view, "退出") {
		t.Fatal("帮助浮层应列出全局按键")
	}
	app.Update(keyPress("esc"))
	if view := screenText(app); strings.Contains(view, "按 esc 或 ? 关闭") {
		t.Fatal("esc 应关闭帮助浮层")
	}
}

func TestAppThemeCyclePersists(t *testing.T) {
	app := boot(t, 120, 40)
	saved := config.Config{}
	deps := testDeps(t)
	deps.SaveConfig = func(updated config.Config) error {
		saved = updated
		return nil
	}
	ui.CacheWatchInterval = time.Millisecond
	app = ui.NewApp(deps, screens.Registry(), ui.ScreenDashboard)
	pump(t, app, app.Init(), 0)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	_, cmd := app.Update(keyPress("ctrl+t"))
	pump(t, app, cmd, 0)
	if saved.UI.Theme == "" {
		t.Fatal("换主题应写入配置文件")
	}
	if saved.UI.Theme != config.ThemeDark {
		t.Fatalf("从 16 色应切到深色，实际 %q", saved.UI.Theme)
	}
}

func TestAppDrawerToggle(t *testing.T) {
	app := boot(t, 140, 40)
	_, cmd := app.Update(keyPress("ctrl+j"))
	pump(t, app, cmd, 0)
	view := screenText(app)
	if !strings.Contains(view, "尚未就绪") {
		t.Fatalf("应打开 AI 抽屉（未配置时显示引导），实际：\n%s", view)
	}
	if !strings.Contains(view, "AI 对话") {
		t.Fatal("未配置时应显示配置引导")
	}
	app.Update(keyPress("esc"))
	if view := screenText(app); strings.Contains(view, "尚未就绪") {
		t.Fatal("esc 应关闭抽屉")
	}
}

func TestAppReloadRebuildsScreens(t *testing.T) {
	app := boot(t, 120, 40)
	app.Update(keyPress("3"))

	reloaded := false
	deps := testDeps(t)
	deps.Reload = func(previous ui.Deps) ui.Deps {
		reloaded = true
		return previous
	}
	ui.CacheWatchInterval = time.Millisecond
	app = ui.NewApp(deps, screens.Registry(), ui.ScreenDashboard)
	pump(t, app, app.Init(), 0)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	// 提示条要留到断言之后：boot 会把 ToastTTL 调成 1ms，这里改回来。
	ui.ToastTTL = time.Minute
	ui.CacheWatchInterval = time.Hour
	_, cmd := app.Update(keyPress("ctrl+r"))
	if cmd == nil {
		t.Fatal("ctrl+r 应产生重读命令")
	}
	// 只执行重读本身；随后返回的批次里还有一个提示条计时器，
	// 执行它会把提示条立刻清掉，这里刻意不跑。
	_, follow := app.Update(cmd())
	_ = follow
	_ = cmd
	if !reloaded {
		t.Fatal("ctrl+r 应触发重新读盘")
	}
	view := screenText(app)
	if !strings.Contains(view, "已重新读取") {
		t.Fatalf("应提示已重新读取，实际：\n%s", view)
	}
}

func TestAppConfirmsBeforeEnablingAI(t *testing.T) {
	app := boot(t, 120, 44)
	app.Update(keyPress(","))

	// 设置页第 6 项是「AI 对话」（教务地址两行在它之前）。
	for index := 0; index < 5; index++ {
		app.Update(keyPress("down"))
	}
	_, cmd := app.Update(keyPress("enter"))
	pump(t, app, cmd, 0)

	view := screenText(app)
	if !strings.Contains(view, "确认操作") {
		t.Fatalf("打开 AI 应弹确认框，实际：\n%s", view)
	}
	if !strings.Contains(view, "会发送到") {
		t.Fatal("确认框应说明数据会出网")
	}
	app.Update(keyPress("n"))
	if view := screenText(app); strings.Contains(view, "确认操作") {
		t.Fatal("n 应取消")
	}
}

// TestAppDigitsTypeIntoLoginForm 守住"输学号被当成切页"这个问题：
// 在「登录与同步」页敲数字必须落进输入框，页面不能跳走。
func TestAppDigitsTypeIntoLoginForm(t *testing.T) {
	app := boot(t, 120, 40)
	_, cmd := app.Update(keyPress("9"))
	pump(t, app, cmd, 0)
	if view := screenText(app); !strings.Contains(view, "登录教务系统") {
		t.Fatalf("按 9 应停在登录与同步页：\n%s", view)
	}

	for _, digit := range []string{"2", "3", "0", "4"} {
		_, cmd = app.Update(keyPress(digit))
		pump(t, app, cmd, 0)
	}
	view := screenText(app)
	if !strings.Contains(view, "登录教务系统") {
		t.Fatalf("数字键不应把页面切走：\n%s", view)
	}
	if !strings.Contains(view, "2304") {
		t.Fatalf("数字应写入输入框：\n%s", view)
	}

	// 表单里的 q 是字符，不是退出命令
	if _, quit := app.Update(keyPress("q")); quit != nil {
		if _, isQuit := quit().(tea.QuitMsg); isQuit {
			t.Fatal("登录表单里按 q 不应退出程序")
		}
	}

	// 出口仍然存在：数字跳页会入返回栈，esc 能回到上一页
	app.Update(keyPress("esc"))
	pump(t, app, nil, 0)
	if view := screenText(app); !strings.Contains(view, "今日课程") {
		t.Fatalf("esc 应返回概览页：\n%s", view)
	}
}

func TestAppQOnlyQuitsOutsideInput(t *testing.T) {
	app := boot(t, 120, 40)
	// 概览页没有输入框，q 应退出。
	_, cmd := app.Update(keyPress("q"))
	if cmd == nil {
		t.Fatal("q 应产生退出命令")
	}
	if _, ok := cmd().(tea.QuitMsg); !ok {
		t.Fatal("q 应触发 tea.Quit")
	}
}

func TestAppEscapeBackNavigation(t *testing.T) {
	app := boot(t, 120, 40)
	// 命令面板跳转会把原页面压栈。
	_, cmd := app.Update(ui.NavigateMsg{To: ui.ScreenScores, Push: true})
	pump(t, app, cmd, 0)
	if view := screenText(app); !strings.Contains(view, "平均学分绩点") {
		t.Fatal("应跳到成绩页")
	}
	_, cmd = app.Update(keyPress("esc"))
	pump(t, app, cmd, 0)
	if view := screenText(app); !strings.Contains(view, "今日课程") {
		t.Fatalf("esc 应回到概览，实际：\n%s", view)
	}
}

func TestAppNoColorThemeHasNoEscapes(t *testing.T) {
	deps := testDeps(t)
	deps.Themes = ui.NewThemeState(theme.Plain())
	deps.Config.UI.ASCII = true
	app := ui.NewApp(deps, screens.Registry(), ui.ScreenDashboard)
	pump(t, app, app.Init(), 0)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})
	pump(t, app, app.Init(), 1)
	view := screenText(app)
	if strings.Contains(view, "\x1b[") {
		t.Fatalf("无色主题不应输出 ANSI 转义：%q", view[:minInt(len(view), 80)])
	}
}

// keyPress 构造一个按键消息。
func keyPress(text string) tea.KeyPressMsg {
	switch text {
	case "esc":
		return tea.KeyPressMsg(tea.Key{Code: tea.KeyEsc})
	case "enter":
		return tea.KeyPressMsg(tea.Key{Code: tea.KeyEnter})
	case "up":
		return tea.KeyPressMsg(tea.Key{Code: tea.KeyUp})
	case "down":
		return tea.KeyPressMsg(tea.Key{Code: tea.KeyDown})
	case "ctrl+p":
		return tea.KeyPressMsg(tea.Key{Code: 'p', Mod: tea.ModCtrl})
	case "ctrl+j":
		return tea.KeyPressMsg(tea.Key{Code: 'j', Mod: tea.ModCtrl})
	case "ctrl+r":
		return tea.KeyPressMsg(tea.Key{Code: 'r', Mod: tea.ModCtrl})
	case "ctrl+t":
		return tea.KeyPressMsg(tea.Key{Code: 't', Mod: tea.ModCtrl})
	case "ctrl+n":
		return tea.KeyPressMsg(tea.Key{Code: 'n', Mod: tea.ModCtrl})
	default:
		runes := []rune(text)
		return tea.KeyPressMsg(tea.Key{Code: runes[0], Text: text})
	}
}

func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}

// TestAppNotifiesOnExternalCacheChange 验证外部刷新后只提示、不自动换数据。
func TestAppNotifiesOnExternalCacheChange(t *testing.T) {
	ui.ToastTTL = time.Minute
	ui.CacheWatchInterval = time.Hour

	deps := testDeps(t)
	snapshot := model.New("/w", "/w/storage.json", true, "", 0, map[string]string{})
	deps.Service = query.New(snapshot, deps.Clock, "test")
	app := ui.NewApp(deps, screens.Registry(), ui.ScreenDashboard)
	app.Update(tea.WindowSizeMsg{Width: 120, Height: 40})

	base := time.Date(2026, 11, 13, 12, 0, 0, 0, time.UTC)
	// 第一次上报只记录基准时间，不提示。
	app.Update(ui.CacheChangedMsg{ModTime: base})
	if view := screenText(app); strings.Contains(view, "检测到本地缓存已更新") {
		t.Fatal("首次记录基准时间不应提示")
	}
	// 时间不变的重复上报也不提示。
	app.Update(ui.CacheChangedMsg{ModTime: base})
	if view := screenText(app); strings.Contains(view, "检测到本地缓存已更新") {
		t.Fatal("修改时间未变不应提示")
	}
	// 修改时间推进后应提示，且不自动重读。
	app.Update(ui.CacheChangedMsg{ModTime: base.Add(time.Minute)})
	if view := screenText(app); !strings.Contains(view, "检测到本地缓存已更新") {
		t.Fatal("外部刷新后应提示用户按 ctrl+r")
	}
}
