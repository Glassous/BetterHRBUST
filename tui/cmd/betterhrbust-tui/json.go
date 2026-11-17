package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
	"github.com/Glassous/BetterHRBUST/tui/internal/version"
)

// versionLine 返回版本号文本。
func versionLine() string { return version.String() }

// emitJSON 把结构化结果写到标准输出。
//
// 编码失败时退化成一条错误对象：脚本拿到不可解析的输出比拿到错误更糟。
func emitJSON(payload any) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(payload); err != nil {
		fmt.Fprintf(os.Stdout, "{\"ok\":false,\"error\":{\"code\":\"SERIALIZE\",\"message\":%q}}\n",
			err.Error())
	}
}

// runJSON 执行非交互命令。
//
// 输出结构与退出码与 `BetterHRBUSTCLI` 完全一致：
// 成功是 Envelope，失败是 {command, ok:false, error:{code,message}}。
func runJSON(flags Flags, dataDir string, settings config.Config) int {
	snapshot := store.ReadSnapshot(dataDir)
	clock := query.SystemClock()
	service := query.New(snapshot, clock, version.String())
	readiness := service.Readiness()

	command := flags.command
	// sync / login 不依赖既有缓存：它们是"把缓存做出来"的那一步
	switch command {
	case "sync":
		return runSyncCommand(service, dataDir, settings)
	case "login":
		emitJSON(map[string]any{
			"command": "login",
			"ok":      false,
			"error": map[string]string{
				"code": "LOGIN_REQUIRES_TTY",
				"message": "登录需要输入学号、密码与图片验证码，只能在交互界面完成：" +
					"启动 BetterHRBUSTTUI 后按 9 打开「登录与同步」。已登录时可用 --json sync 直接同步。",
			},
		})
		return store.ExitError
	}

	if dataCommands[command] && !readiness.OK() {
		emitJSON(service.Failure(command, readiness.ErrorCode(), readiness.Message))
		return readiness.ExitCode()
	}

	var data any
	switch command {
	case "status":
		data = service.Status()
	case "summary":
		data = service.Summary(flags.options)
	case "timetable":
		data = service.Timetable(flags.options)
	case "scores":
		data = service.Scores(flags.options)
	case "exams":
		data = service.Exams(flags.options)
	case "plan":
		data = service.Plan(flags.options)
	case "notices":
		data = service.Notices(flags.options)
	case "profile":
		data = service.Profile()
	case "courses":
		data = service.Courses(flags.options)
	case "doctor":
		data = service.Doctor()
	case "check":
		result := service.Check(flags.options)
		emitJSON(service.Envelope(command, result))
		return exitCodeFor(result, flags.options.Strict)
	case "version":
		emitJSON(map[string]any{"command": "version", "ok": true, "version": version.String()})
		return store.ExitOK
	default:
		emitJSON(service.Failure(command, "UNKNOWN_COMMAND", "未知命令："+command))
		return store.ExitError
	}

	emitJSON(service.Envelope(command, data))
	return store.ExitOK
}
