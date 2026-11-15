package main

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// Flags 是命令行参数。
type Flags struct {
	// 全局
	json        bool
	help        bool
	showVersion bool
	dataDir     string
	theme       string
	noColor     bool
	ascii       bool
	nerdFont    bool
	screen      string

	// 命令
	command string
	options query.Options
	// year / term 只用于展示与筛选提示，不影响统计口径。
	year string
	term string
}

// 命令别名（与 BetterHRBUSTCLI 保持一致，便于脚本迁移）。
var commandAliases = map[string]string{
	"status": "status", "状态": "status",
	"summary": "summary", "dashboard": "summary", "overview": "summary", "概览": "summary",
	"timetable": "timetable", "schedule": "timetable", "课表": "timetable", "课程表": "timetable",
	"scores": "scores", "score": "scores", "成绩": "scores",
	"exams": "exams", "exam": "exams", "考试": "exams",
	"plan": "plan", "program": "plan", "培养方案": "plan",
	"notices": "notices", "notice": "notices", "公告": "notices",
	"profile": "profile", "学籍": "profile", "档案": "profile",
	"courses": "courses", "course": "courses", "课程": "courses",
	"doctor": "doctor", "诊断": "doctor",
	"check": "check", "selfcheck": "check", "自检": "check", "检查": "check",
	"help": "help", "帮助": "help",
	"version": "version", "版本": "version",
}

// dataCommands 是需要本地数据才能执行的命令。
var dataCommands = map[string]bool{
	"summary": true, "timetable": true, "scores": true, "exams": true,
	"plan": true, "notices": true, "profile": true, "courses": true,
}

// parseFlags 解析命令行。
//
// 手写解析器而不是引入 flag 包：需要同时支持 `--k=v`、`--k v`、
// 中文命令别名，以及"命令出现在任意位置"，用 flag 包反而更啰嗦。
func parseFlags(argv []string) (Flags, error) {
	flags := Flags{}

	positional := make([]string, 0, 2)
	index := 0
	for index < len(argv) {
		arg := argv[index]
		if arg == "" {
			index++
			continue
		}
		if !strings.HasPrefix(arg, "-") {
			positional = append(positional, arg)
			index++
			continue
		}

		name := arg
		inline := ""
		if position := strings.Index(arg, "="); position > 0 {
			name = arg[:position]
			inline = arg[position+1:]
		}

		switch name {
		case "--json":
			flags.json = true
		case "--help", "-h":
			flags.help = true
		case "--version", "-V":
			flags.showVersion = true
		case "--no-color":
			flags.noColor = true
		case "--ascii":
			flags.ascii = true
		case "--nerd-font", "--nerdfont":
			flags.nerdFont = true
		case "--today":
			flags.options.Today = true
		case "--base":
			flags.options.Base = true
		case "--failed":
			flags.options.Failed = true
		case "--merged":
			flags.options.Merged = true
		case "--all":
			flags.options.All = true
		case "--strict":
			flags.options.Strict = true
		case "--data-dir":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.dataDir = value
			index = next
		case "--theme":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.theme = value
			index = next
		case "--screen":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.screen = value
			index = next
		case "--week":
			value, next, err := intOption(argv, index, inline, name, 1, 26)
			if err != nil {
				return flags, err
			}
			flags.options.Week = &value
			index = next
		case "--day":
			value, next, err := intOption(argv, index, inline, name, 1, 7)
			if err != nil {
				return flags, err
			}
			flags.options.Day = &value
			index = next
		case "--days":
			value, next, err := intOption(argv, index, inline, name, 1, 3650)
			if err != nil {
				return flags, err
			}
			flags.options.Days = &value
			index = next
		case "--limit":
			value, next, err := intOption(argv, index, inline, name, 1, 100000)
			if err != nil {
				return flags, err
			}
			flags.options.Limit = &value
			index = next
		case "--date":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			date, ok := academic.ParseISODate(value)
			if !ok {
				return flags, fmt.Errorf("--date 需要 YYYY-MM-DD 格式的合法日期")
			}
			flags.options.Date = &date
			index = next
		case "--course":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.options.Course = value
			index = next
		case "--query":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.options.Query = value
			index = next
		case "--group":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.options.Group = value
			index = next
		case "--year":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.year = value
			index = next
		case "--term":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			flags.term = value
			index = next
		case "--property":
			value, next, err := optionValue(argv, index, inline, name)
			if err != nil {
				return flags, err
			}
			property, ok := academic.PropertyFilter(value)
			if !ok {
				return flags, fmt.Errorf("--property 只能是 必修 / 限选 / 任选 / 其他")
			}
			flags.options.Property = &property
			index = next
		default:
			return flags, fmt.Errorf("未知选项 %s", name)
		}
		index++
	}

	if len(positional) > 1 {
		return flags, fmt.Errorf("多余的参数 %q", positional[1])
	}
	if len(positional) == 1 {
		canonical, ok := commandAliases[positional[0]]
		if !ok {
			return flags, fmt.Errorf("未知命令 %q", positional[0])
		}
		flags.command = canonical
	}

	if flags.help || flags.showVersion {
		return flags, nil
	}
	if flags.command == "" {
		if flags.json {
			return flags, fmt.Errorf("--json 需要指定命令")
		}
		// 没有命令且不是 --json：启动交互界面（这是最主要的用法）。
		return flags, nil
	}
	if flags.command == "help" {
		flags.help = true
		flags.command = ""
	}
	if err := flags.options.Validate(); err != nil {
		return flags, err
	}
	return flags, nil
}

// optionValue 取出选项值，支持 `--k=v` 与 `--k v`。
func optionValue(argv []string, index int, inline, name string) (string, int, error) {
	if inline != "" {
		return inline, index, nil
	}
	next := index + 1
	if next >= len(argv) {
		return "", index, fmt.Errorf("选项 %s 缺少取值", name)
	}
	value := argv[next]
	if strings.HasPrefix(value, "--") {
		return "", index, fmt.Errorf("选项 %s 缺少取值", name)
	}
	return value, next, nil
}

// intOption 取出整数选项值并校验范围。
func intOption(argv []string, index int, inline, name string, low, high int) (int, int, error) {
	raw, next, err := optionValue(argv, index, inline, name)
	if err != nil {
		return 0, index, err
	}
	value, convErr := strconv.Atoi(strings.TrimSpace(raw))
	if convErr != nil {
		return 0, index, fmt.Errorf("选项 %s 需要整数", name)
	}
	if value < low || value > high {
		return 0, index, fmt.Errorf("选项 %s 取值范围为 %d..%d", name, low, high)
	}
	return value, next, nil
}

// printHelp 输出帮助。
func printHelp() {
	lines := []string{
		"BetterHRBUSTTUI " + versionLine() + " · BetterHRBUST 终端界面",
		"",
		"用法：",
		"  BetterHRBUSTTUI                          启动交互界面（需要 TTY）",
		"  BetterHRBUSTTUI --json <命令> [选项]      机器可读输出，供脚本与 AI 使用",
		"",
		"命令：",
		"  status                    登录状态、本地数据与上次同步时间",
		"  summary                   概览：身份、今日课程、近期考试、学业指标",
		"  timetable                 课程表（默认当前教学周）",
		"  scores                    成绩与 GPA（必修课口径，与图形界面一致）",
		"  exams                     考试日程（默认近期安排）",
		"  plan                      培养方案与学分进度",
		"  notices                   教学公告",
		"  profile                   学籍档案（证件号 / 手机号默认打码）",
		"  courses                   本学期课程名录",
		"  doctor                    数据文件、路径与可用性诊断",
		"  check [--strict]          错误检查：数据完整性、会话与同步失败项",
		"  version                   显示版本",
		"",
		"选项：",
		"  --data-dir <路径>          指定数据目录（默认与图形界面一致）",
		"  --week <1..26>            指定教学周",
		"  --day <1..7>              指定星期",
		"  --today                   只看今天剩余的课程",
		"  --base                    使用小节课表",
		"  --date <YYYY-MM-DD>       指定基准日期",
		"  --course <关键字>          课程名 / 课程号筛选",
		"  --property <属性>          必修 / 限选 / 任选 / 其他",
		"  --failed                  只看挂科",
		"  --merged                  输出去重合并后的课程清单",
		"  --days <N>                考试回看窗口天数",
		"  --all                     考试返回全部",
		"  --limit <N>               限制条数",
		"  --query <关键字>           公告 / 课程名录搜索",
		"  --group <关键字>           培养方案课组筛选",
		"  --strict                  check 严格模式",
		"  --theme <auto|dark|light|ansi16>",
		"  --no-color                不使用颜色",
		"  --ascii                   使用 ASCII 边框与符号",
		"  --nerd-font               使用 Nerd Font 图标（需终端已安装对应字体）",
		"  --screen <id>             启动时打开指定页面",
		"  --json                    机器可读输出",
		"  --version                 显示版本",
		"",
		"退出码：0 正常 · 1 参数或内部错误 · 2 未登录且无本地数据 · 3 存储不可用或缓存为空",
		"",
		"交互界面快捷键：1-9/0/, 切换页面 · tab 切换面板 · ctrl+p 命令面板",
		"                  ctrl+j AI 对话 · ctrl+r 重读缓存 · ctrl+t 换主题 · ? 帮助 · q 退出",
	}
	for _, line := range lines {
		fmt.Println(line)
	}
}

// exitCodeFor 把检查结果映射成退出码。
func exitCodeFor(data query.CheckData, strict bool) int {
	if data.Summary.Fail > 0 {
		return store.ExitNoData
	}
	if strict && data.Summary.Warn > 0 {
		return store.ExitError
	}
	return store.ExitOK
}
