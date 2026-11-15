// Command BetterHRBUSTTUI 是 BetterHRBUST 的终端界面。
//
// 两种运行方式：
//
//	BetterHRBUSTTUI                          启动交互界面（需要 TTY）
//	BetterHRBUSTTUI --json <命令> [选项]      非交互输出，供脚本与 AI 工具使用
//
// `--json` 的结构与退出码与 `BetterHRBUSTCLI` 完全一致，
// 因此在没有桌面端二进制的 Linux / macOS 上它可以直接顶替。
package main

import (
	"fmt"
	"os"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/screens"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
	"github.com/Glassous/BetterHRBUST/tui/internal/version"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

// run 是真正的入口，返回进程退出码。
func run(argv []string) int {
	flags, err := parseFlags(argv)
	if err != nil {
		fmt.Fprintln(os.Stderr, "参数错误："+err.Error())
		fmt.Fprintln(os.Stderr, "执行 BetterHRBUSTTUI --help 查看用法。")
		return store.ExitError
	}
	if flags.help {
		printHelp()
		return store.ExitOK
	}
	if flags.showVersion {
		if flags.json {
			emitJSON(map[string]any{"command": "version", "ok": true, "version": version.String()})
			return store.ExitOK
		}
		fmt.Printf("BetterHRBUSTTUI %s\n", version.String())
		return store.ExitOK
	}

	env := store.SystemEnv()
	dataDir, source := store.ResolveDataDir(flags.dataDir, env)
	configPath, _ := store.ResolveConfigPath(env)
	settings := config.Load(configPath).Normalized()

	if flags.json {
		return runJSON(flags, dataDir, settings)
	}
	return runTUI(flags, settings, dataDir, source)
}

// runTUI 启动交互界面。
func runTUI(flags Flags, settings config.Config, dataDir string, source store.DirSource) int {
	if !isInteractive() {
		fmt.Fprintln(os.Stderr, "当前不是交互式终端，无法启动界面。")
		fmt.Fprintln(os.Stderr, "请用 --json <命令> 获取机器可读输出，例如：")
		fmt.Fprintln(os.Stderr, "  BetterHRBUSTTUI --json summary")
		return store.ExitError
	}

	themes := ui.NewThemeState(resolveTheme(settings, flags))
	// emitter 在 Program 创建后才可用；先放一个空实现，创建后替换。
	var program *tea.Program
	emit := func(msg tea.Msg) {
		if program != nil {
			program.Send(msg)
		}
	}

	deps := buildDeps(settings, dataDir, themes, flags, emit)
	initial := ui.ID(ui.ScreenDashboard)
	if flags.screen != "" {
		initial = ui.ID(flags.screen)
	}

	program = tea.NewProgram(ui.NewApp(deps, screens.Registry(), initial))
	if _, err := program.Run(); err != nil {
		fmt.Fprintln(os.Stderr, "界面异常退出："+err.Error())
		return store.ExitError
	}
	return store.ExitOK
}

// resolveTheme 依据命令行与配置选定主题。
func resolveTheme(settings config.Config, flags Flags) theme.Theme {
	if flags.noColor || os.Getenv("NO_COLOR") != "" || os.Getenv("TERM") == "dumb" {
		return theme.Plain()
	}
	name := settings.UI.Theme
	if flags.theme != "" {
		name = flags.theme
	}
	tier := theme.TierFrom(settings.UI.ASCII || flags.ascii, settings.UI.NerdFont || flags.nerdFont)
	switch name {
	case config.ThemeDark:
		return theme.New(theme.Dark, tier)
	case config.ThemeLight:
		return theme.New(theme.Light, tier)
	case config.ThemeANSI16:
		return theme.New(theme.ANSI16, tier)
	default:
		return theme.New(theme.Dark, tier)
	}
}

// buildDeps 组装一次完整的依赖集合。
//
// reload 会重新调用本函数：重新读盘后教学周、同步时间、就绪状态都可能变化，
// 返回一整套新依赖比逐字段回填安全得多。
func buildDeps(
	settings config.Config,
	dataDir string,
	themes *ui.ThemeState,
	flags Flags,
	emit func(tea.Msg),
) ui.Deps {
	clock := query.SystemClock()
	snapshot := store.ReadSnapshot(dataDir)
	service := query.New(snapshot, clock, version.String())
	toolset := query.NewToolset(service)
	readiness := service.Readiness()

	week, _ := academic.TeachingWeekOn(clock.Today)
	semesterLabel := ""
	if semester, ok := academic.SemesterOn(clock.Today); ok {
		semesterLabel = semester.Label
	}

	deps := ui.Deps{
		Service:       service,
		Toolset:       toolset,
		Config:        settings,
		Version:       version.String(),
		Clock:         clock,
		Themes:        themes,
		Readiness:     readiness,
		SyncText:      service.LastSyncText(),
		Week:          week,
		SemesterLabel: semesterLabel,
		StaleDays:     staleDays(snapshot),
		Emit:          emit,
		NewChat:       screens.ChatFactory(),
	}
	deps.SaveConfig = func(updated config.Config) error {
		return updated.Normalized().Save()
	}
	deps.Reload = func(previous ui.Deps) ui.Deps {
		return buildDeps(previous.Config, dataDir, previous.Themes, flags, previous.Emit)
	}
	return deps
}

// staleDays 返回缓存距今的天数；无法判断时返回 0。
func staleDays(snapshot *model.Snapshot) int {
	meta := snapshot.SyncMeta()
	if meta.LastSyncAt == 0 {
		return 0
	}
	elapsed := time.Since(time.UnixMilli(int64(meta.LastSyncAt)))
	if elapsed < 0 {
		return 0
	}
	return int(elapsed.Hours() / 24)
}

// isInteractive 判断标准输出是否为终端。
//
// 不用第三方 isatty：`ModeCharDevice` 在 Unix 与 Windows 控制台上都成立，
// 而在管道、重定向与 CI 日志里都不成立，正好是我们要的判断。
func isInteractive() bool {
	info, err := os.Stdout.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}
