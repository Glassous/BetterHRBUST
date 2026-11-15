package store

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// fakeEnv 构造可预测的环境，让三个平台的路径解析都能在 Linux CI 上断言。
func fakeEnv(goos string, values map[string]string, home string) Env {
	return Env{
		GOOS:   goos,
		Getenv: func(key string) string { return values[key] },
		Home:   func() string { return home },
	}
}

func TestResolveDataDirPerPlatform(t *testing.T) {
	cases := []struct {
		name      string
		goos      string
		env       map[string]string
		home      string
		explicit  string
		want      string
		wantCause DirSource
	}{
		{
			name: "显式路径优先于一切", goos: "windows",
			env:  map[string]string{"APPDATA": `C:\Users\me\AppData\Roaming`, "BETTERHRBUST_DATA_DIR": `D:\other`},
			home: `C:\Users\me`, explicit: `E:\custom`,
			want: `E:\custom`, wantCause: SourceFlag,
		},
		{
			name: "环境变量次之", goos: "linux",
			env:  map[string]string{"BETTERHRBUST_DATA_DIR": "/data/better"},
			home: "/home/me",
			want: "/data/better", wantCause: SourceEnv,
		},
		{
			name: "Windows 用 APPDATA", goos: "windows",
			env:  map[string]string{"APPDATA": `C:\Users\me\AppData\Roaming`},
			home: `C:\Users\me`,
			want: `C:\Users\me\AppData\Roaming\edu.hrbust.betterhrbust`, wantCause: SourceAppData,
		},
		{
			name: "Windows 缺 APPDATA 时用主目录", goos: "windows",
			env:  map[string]string{},
			home: `C:\Users\me`,
			want: `C:\Users\me\AppData\Roaming\edu.hrbust.betterhrbust`, wantCause: SourceHome,
		},
		{
			name: "Windows 不读 XDG_DATA_HOME", goos: "windows",
			env:  map[string]string{"XDG_DATA_HOME": `D:\xdg`},
			home: `C:\Users\me`,
			want: `C:\Users\me\AppData\Roaming\edu.hrbust.betterhrbust`, wantCause: SourceHome,
		},
		{
			name: "macOS 用 Application Support", goos: "darwin",
			env:  map[string]string{},
			home: "/Users/me",
			want: "/Users/me/Library/Application Support/edu.hrbust.betterhrbust", wantCause: SourceHome,
		},
		{
			name: "Linux 用 XDG_DATA_HOME", goos: "linux",
			env:  map[string]string{"XDG_DATA_HOME": "/xdg/data"},
			home: "/home/me",
			want: "/xdg/data/edu.hrbust.betterhrbust", wantCause: SourceXDG,
		},
		{
			name: "Linux 相对 XDG_DATA_HOME 被忽略", goos: "linux",
			env:  map[string]string{"XDG_DATA_HOME": "relative/path"},
			home: "/home/me",
			want: "/home/me/.local/share/edu.hrbust.betterhrbust", wantCause: SourceHome,
		},
		{
			name: "Linux 无 XDG 时用 .local/share", goos: "linux",
			env:  map[string]string{},
			home: "/home/me",
			want: "/home/me/.local/share/edu.hrbust.betterhrbust", wantCause: SourceHome,
		},
		{
			name: "全都没有时兜底当前目录", goos: "linux",
			env:  map[string]string{},
			home: "",
			want: "betterhrbust-data", wantCause: SourceCwd,
		},
	}

	for _, item := range cases {
		item := item
		t.Run(item.name, func(t *testing.T) {
			got, cause := ResolveDataDir(item.explicit, fakeEnv(item.goos, item.env, item.home))
			if got != item.want {
				t.Fatalf("路径期望 %q，实际 %q", item.want, got)
			}
			if cause != item.wantCause {
				t.Fatalf("来源期望 %q，实际 %q", item.wantCause, cause)
			}
		})
	}
}

func TestResolveConfigPath(t *testing.T) {
	cases := []struct {
		name string
		goos string
		env  map[string]string
		home string
		want string
	}{
		{
			name: "显式覆盖", goos: "linux",
			env:  map[string]string{"BETTERHRBUST_TUI_CONFIG": "/tmp/tui.json"},
			home: "/home/me", want: "/tmp/tui.json",
		},
		{
			name: "Windows APPDATA", goos: "windows",
			env:  map[string]string{"APPDATA": `C:\Users\me\AppData\Roaming`},
			home: `C:\Users\me`,
			want: `C:\Users\me\AppData\Roaming\betterhrbust-tui\config.json`,
		},
		{
			name: "macOS Application Support", goos: "darwin",
			env:  map[string]string{},
			home: "/Users/me",
			want: "/Users/me/Library/Application Support/betterhrbust-tui/config.json",
		},
		{
			name: "Linux XDG_CONFIG_HOME", goos: "linux",
			env:  map[string]string{"XDG_CONFIG_HOME": "/xdg/config"},
			home: "/home/me", want: "/xdg/config/betterhrbust-tui/config.json",
		},
		{
			name: "Linux ~/.config", goos: "linux",
			env:  map[string]string{},
			home: "/home/me", want: "/home/me/.config/betterhrbust-tui/config.json",
		},
	}
	for _, item := range cases {
		item := item
		t.Run(item.name, func(t *testing.T) {
			got, _ := ResolveConfigPath(fakeEnv(item.goos, item.env, item.home))
			if got != item.want {
				t.Fatalf("配置路径期望 %q，实际 %q", item.want, got)
			}
		})
	}
}

func TestReadSnapshot(t *testing.T) {
	t.Run("文件不存在返回空快照且不报错", func(t *testing.T) {
		dir := t.TempDir()
		snapshot := ReadSnapshot(dir)
		if snapshot.LoadError != "" {
			t.Fatalf("不应记录错误：%s", snapshot.LoadError)
		}
		if snapshot.StorageExists {
			t.Fatal("不应判定文件存在")
		}
		if snapshot.Len() != 0 {
			t.Fatalf("应为空快照，实际 %d 个键", snapshot.Len())
		}
	})

	t.Run("BOM 与空文件都视为空快照", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "storage.json")
		if err := os.WriteFile(path, []byte("\ufeff  \n"), 0o600); err != nil {
			t.Fatal(err)
		}
		snapshot := ReadSnapshot(dir)
		if snapshot.LoadError != "" {
			t.Fatalf("BOM 空文件不应报错：%s", snapshot.LoadError)
		}
		if snapshot.Len() != 0 {
			t.Fatalf("应为空快照，实际 %d 个键", snapshot.Len())
		}
	})

	t.Run("正常读取", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "storage.json")
		content := `{"better_hrbust_has_session":"true","better_hrbust_cache_scores":"[]"}`
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		snapshot := ReadSnapshot(dir)
		if snapshot.LoadError != "" {
			t.Fatalf("不应报错：%s", snapshot.LoadError)
		}
		if !snapshot.StorageExists {
			t.Fatal("应判定文件存在")
		}
		if !snapshot.LoggedIn() {
			t.Fatal("应识别登录态")
		}
	})

	t.Run("损坏文件只报错，不改名不删除", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "storage.json")
		if err := os.WriteFile(path, []byte("{ not json"), 0o600); err != nil {
			t.Fatal(err)
		}
		snapshot := ReadSnapshot(dir)
		if snapshot.LoadError == "" {
			t.Fatal("应记录解析错误")
		}
		if !strings.Contains(snapshot.LoadError, "刷新") {
			t.Fatalf("错误文案应引导用户刷新：%s", snapshot.LoadError)
		}
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("不得删除或改名原文件：%v", err)
		}
		if _, err := os.Stat(path + ".corrupt"); err == nil {
			t.Fatal("不得创建 .corrupt 备份（那是图形界面的职责）")
		}
	})

	t.Run("超限文件被拒绝解析", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "storage.json")
		big := make([]byte, MaxStorageBytes+1)
		for index := range big {
			big[index] = ' '
		}
		if err := os.WriteFile(path, big, 0o600); err != nil {
			t.Fatal(err)
		}
		snapshot := ReadSnapshot(dir)
		if !strings.Contains(snapshot.LoadError, "过大") {
			t.Fatalf("应提示文件过大，实际：%q", snapshot.LoadError)
		}
	})
}

func TestReadiness(t *testing.T) {
	cases := []struct {
		name      string
		raw       map[string]string
		loadError string
		wantKind  Kind
		wantExit  int
		contain   string
	}{
		{
			name: "未登录无数据", raw: map[string]string{},
			wantKind: KindNeedLogin, wantExit: ExitNeedLogin, contain: "图形界面",
		},
		{
			name: "未登录有考试", raw: map[string]string{
				"better_hrbust_cache_exams": `[{"courseName":"高等数学"}]`,
			},
			wantKind: KindReady, wantExit: ExitOK,
		},
		{
			name: "已登录有课表", raw: map[string]string{
				"better_hrbust_has_session":     "true",
				"better_hrbust_cache_timetable": `{"cells":[{"day":1}]}`,
			},
			wantKind: KindReady, wantExit: ExitOK,
		},
		{
			name: "已登录无数据", raw: map[string]string{"better_hrbust_has_session": "true"},
			wantKind: KindNoData, wantExit: ExitNoData, contain: "刷新",
		},
		{
			name: "存储损坏", raw: map[string]string{}, loadError: "无法解析本地数据文件",
			wantKind: KindNoData, wantExit: ExitNoData, contain: "无法解析",
		},
	}

	for _, item := range cases {
		item := item
		t.Run(item.name, func(t *testing.T) {
			snapshot := newSnapshot(item.raw, item.loadError)
			readiness := Evaluate(snapshot)
			if readiness.Kind != item.wantKind {
				t.Fatalf("状态期望 %v，实际 %v", item.wantKind, readiness.Kind)
			}
			if readiness.ExitCode() != item.wantExit {
				t.Fatalf("退出码期望 %d，实际 %d", item.wantExit, readiness.ExitCode())
			}
			if item.contain != "" && !strings.Contains(readiness.Message, item.contain) {
				t.Fatalf("提示应包含 %q，实际 %q", item.contain, readiness.Message)
			}
		})
	}
}

func TestIsAbsFor(t *testing.T) {
	if !isAbsFor("windows", `C:\Users`) {
		t.Fatal(`C:\Users 应为绝对路径`)
	}
	if isAbsFor("windows", `Users\me`) {
		t.Fatal(`Users\me 不是绝对路径`)
	}
	if !isAbsFor("linux", "/home/me") {
		t.Fatal("/home/me 应为绝对路径")
	}
	if isAbsFor("linux", "home/me") {
		t.Fatal("home/me 不是绝对路径")
	}
}

// newSnapshot 构造测试快照。
func newSnapshot(raw map[string]string, loadError string) *model.Snapshot {
	return model.New("/test", "/test/storage.json", len(raw) > 0, loadError, 0, raw)
}
