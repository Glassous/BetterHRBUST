package main

// 本文件是命令行的端到端测试：直接调用 run()，因此覆盖参数解析、
// 退出码、`--json` 结构与隐私边界——这些是脚本与 AI 工具真正依赖的契约。

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Glassous/BetterHRBUST/tui/internal/parity"
)

// capture 运行 run 并捕获标准输出。
func capture(t *testing.T, args ...string) (string, int) {
	t.Helper()
	original := os.Stdout
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("创建管道失败：%v", err)
	}
	os.Stdout = writer

	done := make(chan string, 1)
	go func() {
		content, _ := io.ReadAll(reader)
		done <- string(content)
	}()

	code := run(args)

	writer.Close()
	os.Stdout = original
	output := <-done
	reader.Close()
	return output, code
}

// fixtureDir 把共享夹具复制成可用的数据目录。
func fixtureDir(t *testing.T) string {
	t.Helper()
	dir, err := parity.Dir()
	if err != nil {
		t.Fatalf("定位共享目录失败：%v", err)
	}
	content, err := os.ReadFile(filepath.Join(dir, "fixtures", "storage-full.json"))
	if err != nil {
		t.Fatalf("读取夹具失败：%v", err)
	}
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "storage.json"), content, 0o600); err != nil {
		t.Fatalf("写入夹具失败：%v", err)
	}
	return root
}

// emptyDir 返回一个空的临时目录。
func emptyDir(t *testing.T) string { return t.TempDir() }

// decode 解析 JSON 输出。
func decode(t *testing.T, output string) map[string]any {
	t.Helper()
	decoder := json.NewDecoder(strings.NewReader(output))
	decoder.UseNumber()
	var value map[string]any
	if err := decoder.Decode(&value); err != nil {
		t.Fatalf("输出不是合法 JSON：%v\n%s", err, output)
	}
	return value
}

func TestVersionFlag(t *testing.T) {
	output, code := capture(t, "--version")
	if code != 0 {
		t.Fatalf("退出码应为 0，实际 %d", code)
	}
	if !strings.Contains(output, "BetterHRBUSTTUI") {
		t.Fatalf("版本输出异常：%q", output)
	}

	output, code = capture(t, "--version", "--json")
	if code != 0 {
		t.Fatalf("退出码应为 0，实际 %d", code)
	}
	payload := decode(t, output)
	if payload["ok"] != true || payload["version"] == nil {
		t.Fatalf("version 结构不符：%v", payload)
	}
}

func TestHelpOutput(t *testing.T) {
	output, code := capture(t, "--help")
	if code != 0 {
		t.Fatalf("退出码应为 0，实际 %d", code)
	}
	for _, want := range []string{"用法", "命令", "退出码", "ctrl+p", "--json"} {
		if !strings.Contains(output, want) {
			t.Fatalf("帮助缺少 %q", want)
		}
	}
}

func TestJSONStatusOnFixture(t *testing.T) {
	dir := fixtureDir(t)
	output, code := capture(t, "--json", "status", "--data-dir", dir)
	if code != 0 {
		t.Fatalf("退出码应为 0，实际 %d\n%s", code, output)
	}
	payload := decode(t, output)

	// envelope 的键必须稳定：脚本与 AI 按这些键取值。
	for _, key := range []string{
		"command", "ok", "generatedAt", "loggedIn", "offline",
		"account", "lastLoginAt", "lastSyncAt", "lastSyncText", "syncError", "data",
	} {
		if _, ok := payload[key]; !ok {
			t.Fatalf("envelope 缺少键 %q：%s", key, output)
		}
	}
	data, _ := payload["data"].(map[string]any)
	if data["readiness"] != "ready" {
		t.Fatalf("就绪状态应为 ready，实际 %v", data["readiness"])
	}
	counts, _ := data["counts"].(map[string]any)
	if counts["scores"] != json.Number("3") {
		t.Fatalf("成绩条数应为 3，实际 %v", counts["scores"])
	}
}

func TestJSONCommandsSucceed(t *testing.T) {
	dir := fixtureDir(t)
	commands := []string{
		"status", "summary", "timetable", "scores", "exams",
		"plan", "notices", "profile", "courses", "doctor", "check",
	}
	for _, command := range commands {
		command := command
		t.Run(command, func(t *testing.T) {
			output, code := capture(t, "--json", command, "--data-dir", dir)
			if code != 0 {
				t.Fatalf("%s 退出码应为 0，实际 %d\n%s", command, code, output)
			}
			payload := decode(t, output)
			if payload["ok"] != true {
				t.Fatalf("%s 应成功：%s", command, output)
			}
			if _, ok := payload["data"]; !ok {
				t.Fatalf("%s 缺少 data", command)
			}
		})
	}
}

func TestJSONSummaryValues(t *testing.T) {
	dir := fixtureDir(t)
	output, code := capture(t, "--json", "summary", "--data-dir", dir, "--date", "2026-11-13")
	if code != 0 {
		t.Fatalf("退出码应为 0，实际 %d\n%s", code, output)
	}
	data := decode(t, output)["data"].(map[string]any)
	stats := data["stats"].(map[string]any)

	if stats["gpa"] != json.Number("2.4") {
		t.Fatalf("GPA 应为 2.4，实际 %v", stats["gpa"])
	}
	if stats["weightedAvg"] != json.Number("74") {
		t.Fatalf("加权平均应为 74，实际 %v", stats["weightedAvg"])
	}
	if stats["earnedCredits"] != json.Number("3") {
		t.Fatalf("已获学分应为 3，实际 %v", stats["earnedCredits"])
	}
	degree := stats["degree"].(map[string]any)
	if degree["requiredCredits"] != json.Number("7") {
		t.Fatalf("学位课学分应为 7，实际 %v", degree["requiredCredits"])
	}
	if degree["gpa"] != json.Number("3") {
		t.Fatalf("学位绩点应为 3，实际 %v", degree["gpa"])
	}
	if data["date"] != "2026-11-13" {
		t.Fatalf("基准日期应为 2026-11-13，实际 %v", data["date"])
	}
	semester := data["semester"].(map[string]any)
	if semester["teachingWeek"] != json.Number("11") {
		t.Fatalf("教学周应为 11，实际 %v", semester["teachingWeek"])
	}
	courses := data["todayCourses"].([]any)
	if len(courses) != 1 {
		t.Fatalf("当天应有 1 门课，实际 %d", len(courses))
	}
}

func TestJSONPrivacyBoundary(t *testing.T) {
	dir := fixtureDir(t)
	// 夹具里包含明文的证件号、手机号、邮箱与凭证键。
	commands := []string{"status", "summary", "profile", "doctor", "check", "scores", "plan"}
	for _, command := range commands {
		command := command
		t.Run(command, func(t *testing.T) {
			output, _ := capture(t, "--json", command, "--data-dir", dir)
			for _, forbidden := range []string{
				"110101199001011234",
				"13800001111",
				"zhangsan@example.com",
				"b2JmdXNjYXRlZA==",
			} {
				if strings.Contains(output, forbidden) {
					t.Fatalf("%s 泄露了 %q", command, forbidden)
				}
			}
		})
	}
}

func TestExitCodeNeedLogin(t *testing.T) {
	dir := emptyDir(t)
	output, code := capture(t, "--json", "scores", "--data-dir", dir)
	if code != 2 {
		t.Fatalf("退出码应为 2，实际 %d\n%s", code, output)
	}
	payload := decode(t, output)
	if payload["ok"] != false {
		t.Fatal("应是失败结构")
	}
	errorBody := payload["error"].(map[string]any)
	if errorBody["code"] != "NEED_GUI_LOGIN" {
		t.Fatalf("错误码应为 NEED_GUI_LOGIN，实际 %v", errorBody["code"])
	}
	message, _ := errorBody["message"].(string)
	if !strings.Contains(message, "图形界面") || !strings.Contains(message, "验证码") {
		t.Fatalf("提示应引导去图形界面：%s", message)
	}
}

func TestExitCodeNoData(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "storage.json"),
		[]byte(`{"better_hrbust_has_session":"true"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	output, code := capture(t, "--json", "scores", "--data-dir", dir)
	if code != 3 {
		t.Fatalf("退出码应为 3，实际 %d\n%s", code, output)
	}
	payload := decode(t, output)
	if payload["error"].(map[string]any)["code"] != "NO_LOCAL_DATA" {
		t.Fatalf("错误码应为 NO_LOCAL_DATA：%s", output)
	}
	if !strings.Contains(output, "刷新") {
		t.Fatal("提示应引导刷新")
	}
}

func TestCorruptStorageIsNotModified(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "storage.json")
	if err := os.WriteFile(path, []byte("{ not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, code := capture(t, "--json", "scores", "--data-dir", dir)
	if code != 3 {
		t.Fatalf("退出码应为 3，实际 %d", code)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("不得删除或改名原文件：%v", err)
	}
	if _, err := os.Stat(path + ".corrupt"); err == nil {
		t.Fatal("不得创建 .corrupt 备份")
	}
}

func TestExitCodeCheckStrict(t *testing.T) {
	// 未登录但有数据：会话项是 warn，非 strict 成功，strict 返回 1。
	dir := fixtureDir(t)
	content, err := os.ReadFile(filepath.Join(dir, "storage.json"))
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]string
	if err := json.Unmarshal(content, &raw); err != nil {
		t.Fatal(err)
	}
	delete(raw, "better_hrbust_has_session")
	patched, _ := json.Marshal(raw)
	if err := os.WriteFile(filepath.Join(dir, "storage.json"), patched, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, code := capture(t, "--json", "check", "--data-dir", dir); code != 0 {
		t.Fatalf("非严格模式应成功，实际 %d", code)
	}
	output, code := capture(t, "--json", "check", "--strict", "--data-dir", dir)
	if code != 1 {
		t.Fatalf("严格模式遇到 warn 应返回 1，实际 %d\n%s", code, output)
	}
}

func TestUnknownCommandAndBadFlags(t *testing.T) {
	cases := [][]string{
		{"--json", "no-such-command"},
		{"--json", "scores", "--week", "99"},
		{"--json", "scores", "--week", "abc"},
		{"--json", "scores", "--property", "乱填"},
		{"--json", "scores", "--date", "2026-13-01"},
		{"--json", "scores", "--nope"},
		{"--json"},
	}
	for _, args := range cases {
		args := args
		t.Run(strings.Join(args, " "), func(t *testing.T) {
			if _, code := capture(t, args...); code != 1 {
				t.Fatalf("参数错误应返回 1，实际 %d（%v）", code, args)
			}
		})
	}
}

func TestChineseAliases(t *testing.T) {
	dir := fixtureDir(t)
	output, code := capture(t, "--json", "成绩", "--data-dir", dir)
	if code != 0 {
		t.Fatalf("中文命令别名应可用，退出码 %d\n%s", code, output)
	}
	if !strings.HasPrefix(strings.TrimSpace(output), "{") {
		t.Fatal("应输出 JSON")
	}
}

func TestNoTTYRefusesInteractiveMode(t *testing.T) {
	// 测试进程的标准输出是管道，因此非 --json 模式应给出提示并返回 1。
	_, code := capture(t, "--data-dir", fixtureDir(t))
	if code != 1 {
		t.Fatalf("非 TTY 下应拒绝启动界面，实际退出码 %d", code)
	}
}

func TestParseFlagsRoundTrip(t *testing.T) {
	flags, err := parseFlags([]string{
		"--json", "scores", "--data-dir", "/tmp/x", "--week=12",
		"--property", "必修", "--failed", "--limit", "5", "--date", "2026-11-13",
	})
	if err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if !flags.json || flags.command != "scores" || flags.dataDir != "/tmp/x" {
		t.Fatalf("基础字段不符：%+v", flags)
	}
	if flags.options.Week == nil || *flags.options.Week != 12 {
		t.Fatal("--week=12 应被解析")
	}
	if flags.options.Property == nil || flags.options.Property.Label() != "必修" {
		t.Fatal("--property 应被解析")
	}
	if !flags.options.Failed {
		t.Fatal("--failed 应被解析")
	}
	if flags.options.Limit == nil || *flags.options.Limit != 5 {
		t.Fatal("--limit 应被解析")
	}
	if flags.options.Date == nil || flags.options.Date.String() != "2026-11-13" {
		t.Fatal("--date 应被解析")
	}
}

func TestParseFlagsWithoutCommandStartsTUI(t *testing.T) {
	flags, err := parseFlags(nil)
	if err != nil {
		t.Fatalf("空参数不应报错：%v", err)
	}
	if flags.help {
		t.Fatal("无参数不应进入帮助，而应启动交互界面")
	}
	if flags.command != "" {
		t.Fatalf("无参数时不应有命令，实际 %q", flags.command)
	}
}
