// Package store 是终端界面唯一的磁盘 I/O 层：数据目录解析与 `storage.json` 只读快照。
//
// 与 Rust 命令行不同，这里从第一版就把 Windows / macOS / Linux 三条路径一次做对。
// Rust 主干只认 `%APPDATA%`，在 Unix 上会静默落到 `./betterhrbust-data`，
// 与图形界面真正写入的目录不一致；终端界面不应重复这个问题。
package store

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// DirSource 说明数据目录是从哪一级解析出来的，诊断页会展示。
type DirSource string

const (
	// SourceFlag 表示来自 `--data-dir`。
	SourceFlag DirSource = "--data-dir"
	// SourceEnv 表示来自 `BETTERHRBUST_DATA_DIR`。
	SourceEnv DirSource = "BETTERHRBUST_DATA_DIR"
	// SourceAppData 表示来自 `%APPDATA%`（Windows）。
	SourceAppData DirSource = "%APPDATA%"
	// SourceXDG 表示来自 `$XDG_DATA_HOME` 或 `$XDG_CONFIG_HOME`。
	SourceXDG DirSource = "XDG"
	// SourceHome 表示来自用户主目录下的约定路径。
	SourceHome DirSource = "~"
	// SourceCwd 表示兜底的当前目录相对路径。
	SourceCwd DirSource = "."
)

// Env 是可注入的环境，让路径解析能在任意平台上被单测覆盖。
type Env struct {
	// GOOS 取 "windows" / "darwin" / "linux"。
	GOOS string
	// Getenv 读环境变量；缺失返回空串。
	Getenv func(string) string
	// Home 返回用户主目录；失败返回空串。
	Home func() string
}

// SystemEnv 返回当前进程的真实环境。
func SystemEnv() Env {
	return Env{
		GOOS:   runtime.GOOS,
		Getenv: os.Getenv,
		Home: func() string {
			home, err := os.UserHomeDir()
			if err != nil {
				return ""
			}
			return home
		},
	}
}

const (
	appDirName    = "edu.hrbust.betterhrbust"
	tuiConfigName = "betterhrbust-tui"
)

// ResolveDataDir 解析数据目录。优先级：
//
//  1. `--data-dir`
//  2. `BETTERHRBUST_DATA_DIR`
//  3. Windows：`%APPDATA%\edu.hrbust.betterhrbust`
//  4. macOS：`~/Library/Application Support/edu.hrbust.betterhrbust`
//  5. Linux/BSD：`$XDG_DATA_HOME/edu.hrbust.betterhrbust`（必须绝对路径）
//  6. `~/.local/share/edu.hrbust.betterhrbust`
//  7. 兜底 `./betterhrbust-data`
//
// Windows 上刻意**不读** `XDG_DATA_HOME`：Git Bash / MSYS 会注入该变量，
// 照它找目录会指向一个图形界面从未写过的位置。
func ResolveDataDir(explicit string, env Env) (string, DirSource) {
	if strings.TrimSpace(explicit) != "" {
		return explicit, SourceFlag
	}
	if value := strings.TrimSpace(env.Getenv("BETTERHRBUST_DATA_DIR")); value != "" {
		return value, SourceEnv
	}

	home := env.Home()
	switch env.GOOS {
	case "windows":
		if appdata := strings.TrimSpace(env.Getenv("APPDATA")); appdata != "" {
			return joinFor(env.GOOS, appdata, appDirName), SourceAppData
		}
		if home != "" {
			return joinFor(env.GOOS, home, "AppData", "Roaming", appDirName), SourceHome
		}
	case "darwin":
		if home != "" {
			return joinFor(env.GOOS, home, "Library", "Application Support", appDirName), SourceHome
		}
	default:
		if xdg := strings.TrimSpace(env.Getenv("XDG_DATA_HOME")); xdg != "" && isAbsFor(env.GOOS, xdg) {
			return joinFor(env.GOOS, xdg, appDirName), SourceXDG
		}
		if home != "" {
			return joinFor(env.GOOS, home, ".local", "share", appDirName), SourceHome
		}
	}
	return joinFor(env.GOOS, ".", "betterhrbust-data"), SourceCwd
}

// ResolveConfigPath 解析终端界面自己的配置文件路径。
//
// 刻意**不放进**应用数据目录：那是图形界面拥有的目录，其内容会被
// `storage.json` 的原子写与损坏备份逻辑影响。终端界面只写自己的配置。
//
// 顺序：`BETTERHRBUST_TUI_CONFIG` > Windows/macOS 的应用支持目录 >
// `$XDG_CONFIG_HOME` > `~/.config` > `./betterhrbust-tui.json`。
func ResolveConfigPath(env Env) (string, DirSource) {
	if value := strings.TrimSpace(env.Getenv("BETTERHRBUST_TUI_CONFIG")); value != "" {
		return value, SourceEnv
	}
	home := env.Home()
	switch env.GOOS {
	case "windows":
		if appdata := strings.TrimSpace(env.Getenv("APPDATA")); appdata != "" {
			return joinFor(env.GOOS, appdata, tuiConfigName, "config.json"), SourceAppData
		}
		if home != "" {
			return joinFor(env.GOOS, home, "AppData", "Roaming", tuiConfigName, "config.json"), SourceHome
		}
	case "darwin":
		if home != "" {
			return joinFor(env.GOOS, home, "Library", "Application Support", tuiConfigName, "config.json"), SourceHome
		}
	default:
		if xdg := strings.TrimSpace(env.Getenv("XDG_CONFIG_HOME")); xdg != "" && isAbsFor(env.GOOS, xdg) {
			return joinFor(env.GOOS, xdg, tuiConfigName, "config.json"), SourceXDG
		}
		if home != "" {
			return joinFor(env.GOOS, home, ".config", tuiConfigName, "config.json"), SourceHome
		}
	}
	return joinFor(env.GOOS, ".", tuiConfigName+".json"), SourceCwd
}

// joinFor 按**目标平台**的分隔符拼接路径。
//
// 不能直接用 filepath.Join：那样在 Linux CI 上永远得不到 `C:\Users\...`，
// Windows 分支的路径解析就无法被单测覆盖。
func joinFor(goos string, parts ...string) string {
	separator := "/"
	if goos == "windows" {
		separator = `\`
	}
	cleaned := make([]string, 0, len(parts))
	for _, part := range parts {
		if part == "" {
			continue
		}
		cleaned = append(cleaned, strings.TrimSuffix(part, separator))
	}
	joined := strings.Join(cleaned, separator)
	if goos == "windows" {
		// 统一盘符后的相对写法，例如 `C:\Users\me` 保持不变。
		return joined
	}
	return filepath.Clean(joined)
}

// isAbsFor 按目标平台判断绝对路径。
func isAbsFor(goos, path string) bool {
	if goos == "windows" {
		if strings.HasPrefix(path, `\\`) {
			return true
		}
		return len(path) >= 2 && path[1] == ':' && isLetter(path[0])
	}
	return strings.HasPrefix(path, "/")
}

func isLetter(b byte) bool {
	return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
}
