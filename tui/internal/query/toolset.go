package query

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
)

// Tool 是 OpenAI 兼容协议里的函数定义。
type Tool struct {
	Type     string       `json:"type"`
	Function ToolFunction `json:"function"`
}

// ToolFunction 是函数签名。
type ToolFunction struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	Parameters  json.RawMessage `json:"parameters"`
}

// ToolResult 是工具调用结果。
//
// `Data` 与 `--json` 的 `data` 同形状（经过瘦身投影），
// `Meta` 让模型知道数据有多旧、是否离线，避免它把缓存当成实时数据回答。
type ToolResult struct {
	Data any      `json:"data"`
	Meta ToolMeta `json:"meta"`
}

// ToolMeta 是结果元信息。
type ToolMeta struct {
	GeneratedAt  string `json:"generatedAt"`
	LastSyncText string `json:"lastSyncText"`
	Offline      bool   `json:"offline"`
	Readiness    string `json:"readiness"`
	Total        int    `json:"total,omitempty"`
	Truncated    bool   `json:"truncated,omitempty"`
}

// Toolset 把查询服务包装成 LLM 工具集。
type Toolset struct {
	Service *Service
}

// NewToolset 构造工具集。
func NewToolset(service *Service) *Toolset { return &Toolset{Service: service} }

// MaxToolRecords 是单次工具结果返回的记录上限，避免把上百条成绩塞进上下文。
const MaxToolRecords = 40

// Definitions 返回全部工具定义。
func (t *Toolset) Definitions() []Tool {
	return []Tool{
		functionTool("query_summary",
			"查询学业概览：身份（已打码）、当前学期与教学周、GPA 与学分指标、指定日期的课程、近期考试。"+
				"回答「我今天有什么课」「我的绩点多少」「最近有什么考试」时优先用它。",
			`{"type":"object","properties":{"date":{"type":"string","description":"基准日期，格式 YYYY-MM-DD；省略表示今天"},"days":{"type":"integer","description":"考试回看窗口天数，默认 7，范围 1..3650"}},"additionalProperties":false}`),
		functionTool("query_timetable",
			"查询某一教学周的完整课程表，按周一到周日分组，并附未安排时间的课程。",
			`{"type":"object","properties":{"week":{"type":"integer","description":"教学周，1..26；省略表示当前周"},"base":{"type":"boolean","description":"true 表示小节课表（12 节），默认大节课表（6 节）"},"date":{"type":"string","description":"基准日期 YYYY-MM-DD，用于换算当前周与日期标签"}},"additionalProperties":false}`),
		functionTool("query_scores",
			"查询成绩：返回必修课口径的统计（GPA、加权平均分、已获学分、挂科、学位绩点、推免、风险预警）、"+
				"筛选后的原始记录，以及去重修去重后的课程清单。汇总结论一律以 stats 为准，不要自行重新计算。",
			`{"type":"object","properties":{"course":{"type":"string","description":"课程名或课程号关键字"},"property":{"type":"string","enum":["必修","限选","任选","其他"],"description":"选课属性筛选"},"failed":{"type":"boolean","description":"只看挂科记录"},"limit":{"type":"integer","description":"最多返回多少条原始记录，默认 40"}},"additionalProperties":false}`),
		functionTool("query_exams",
			"查询考试日程与倒计时状态。默认返回未来全部与过去 7 天的考试。",
			`{"type":"object","properties":{"days":{"type":"integer","description":"回看窗口天数，默认 7"},"all":{"type":"boolean","description":"true 返回全部考试"},"course":{"type":"string","description":"课程名关键字"}},"additionalProperties":false}`),
		functionTool("query_plan",
			"查询培养方案：各课组的要求学分、已获学分与完成度，以及总学分进度。",
			`{"type":"object","properties":{"group":{"type":"string","description":"课组名关键字"}},"additionalProperties":false}`),
		functionTool("query_notices",
			"查询教学公告。可按教学周或关键字过滤。注意公告本身没有发布日期，不要编造。",
			`{"type":"object","properties":{"week":{"type":"integer","description":"教学周"},"query":{"type":"string","description":"标题或正文关键字"},"limit":{"type":"integer","description":"最多返回条数，默认 10"}},"additionalProperties":false}`),
		functionTool("query_profile",
			"查询学籍档案。证件号、手机号、邮箱、地址已打码，不要尝试还原。",
			`{"type":"object","properties":{},"additionalProperties":false}`),
		functionTool("query_courses",
			"查询本学期课程名录（课程号、课序号、课程名、教师、学分）。",
			`{"type":"object","properties":{"query":{"type":"string","description":"课程名或课程号关键字"},"limit":{"type":"integer","description":"最多返回条数"}},"additionalProperties":false}`),
		functionTool("query_check",
			"检查本地缓存是否完整可用：文件可读性、会话状态、数据量、同步报错、缓存新鲜度。"+
				"当用户怀疑「数据不准 / 查不到数据」时先用它排查。",
			`{"type":"object","properties":{"strict":{"type":"boolean","description":"严格模式：注意项也计为问题"}},"additionalProperties":false}`),
	}
}

// functionTool 组装一个工具定义。
func functionTool(name, description, parameters string) Tool {
	return Tool{
		Type: "function",
		Function: ToolFunction{
			Name:        name,
			Description: description,
			Parameters:  json.RawMessage(parameters),
		},
	}
}

// Names 返回全部工具名。
func (t *Toolset) Names() []string {
	definitions := t.Definitions()
	names := make([]string, 0, len(definitions))
	for _, definition := range definitions {
		names = append(names, definition.Function.Name)
	}
	return names
}

// Invoke 执行一次工具调用。
//
// 所有参数都经过范围校验；任何校验失败都返回带中文说明的错误，
// 让模型能自行纠正重试，而不是拿到一个空结果。
func (t *Toolset) Invoke(ctx context.Context, name string, args json.RawMessage) (any, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	parsed, err := decodeArgs(args)
	if err != nil {
		return nil, fmt.Errorf("%s 的参数不是合法 JSON：%w", name, err)
	}
	options, date, err := optionsFromArgs(name, parsed)
	if err != nil {
		return nil, err
	}

	service := t.Service
	// 允许工具覆盖基准日期（模型回答"下周三有什么课"时需要）。
	if date != nil {
		service = t.Service.WithClock(t.Service.Clock.At(*date))
	}

	switch name {
	case "query_summary":
		data := service.Summary(options)
		return service.wrap(compactSummary(data), len(data.TodayCourses), false), nil
	case "query_timetable":
		data := service.Timetable(options)
		return service.wrap(compactTimetable(data), 0, false), nil
	case "query_scores":
		data := service.Scores(options)
		limit := MaxToolRecords
		if options.Limit != nil {
			limit = *options.Limit
		}
		total := len(data.Scores)
		compact := compactScores(data, limit)
		return service.wrap(compact, total, len(compact.Scores) < total), nil
	case "query_exams":
		data := service.Exams(options)
		return service.wrap(data, len(data.Exams), false), nil
	case "query_plan":
		data := service.Plan(options)
		return service.wrap(compactPlan(data), 0, false), nil
	case "query_notices":
		data := service.Notices(options)
		return service.wrap(data, data.Total, len(data.Notices) < data.Total), nil
	case "query_profile":
		return service.wrap(service.Profile(), 0, false), nil
	case "query_courses":
		data := service.Courses(options)
		return service.wrap(data, data.Total, len(data.Courses) < data.Total), nil
	case "query_check":
		data := service.Check(options)
		return service.wrap(data, len(data.Checks), false), nil
	default:
		return nil, fmt.Errorf("未知工具：%s（可用：%s）", name, strings.Join(t.Names(), "、"))
	}
}

// WithClock 返回使用新时钟的服务副本。
func (s *Service) WithClock(clock Clock) *Service {
	copied := *s
	copied.Clock = clock
	return &copied
}

// wrap 组装工具结果并附带元信息。
func (s *Service) wrap(data any, total int, truncated bool) ToolResult {
	return ToolResult{
		Data: data,
		Meta: ToolMeta{
			GeneratedAt:  s.GeneratedAtText(),
			LastSyncText: s.LastSyncText(),
			Offline:      s.Offline(),
			Readiness:    s.Readiness().Code(),
			Total:        total,
			Truncated:    truncated,
		},
	}
}

// decodeArgs 解析工具参数；空串视为空对象。
func decodeArgs(raw json.RawMessage) (map[string]any, error) {
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return map[string]any{}, nil
	}
	var object map[string]any
	if err := json.Unmarshal([]byte(trimmed), &object); err != nil {
		return nil, err
	}
	if object == nil {
		return map[string]any{}, nil
	}
	return object, nil
}

// optionsFromArgs 把工具参数转成 Options。
func optionsFromArgs(name string, args map[string]any) (Options, *academic.Date, error) {
	options := Options{}

	readInt := func(key string, low, high int) (*int, error) {
		raw, ok := args[key]
		if !ok || raw == nil {
			return nil, nil
		}
		number, ok := raw.(float64)
		if !ok {
			return nil, fmt.Errorf("%s 必须是整数", key)
		}
		value := int(number)
		if float64(value) != number {
			return nil, fmt.Errorf("%s 必须是整数", key)
		}
		if value < low || value > high {
			return nil, fmt.Errorf("%s 取值范围为 %d..%d", key, low, high)
		}
		return &value, nil
	}
	readBool := func(key string) bool {
		value, _ := args[key].(bool)
		return value
	}
	readString := func(key string) string {
		value, _ := args[key].(string)
		return strings.TrimSpace(value)
	}

	var date *academic.Date
	if raw := readString("date"); raw != "" {
		parsed, ok := academic.ParseISODate(raw)
		if !ok {
			return options, nil, fmt.Errorf("date 必须是 YYYY-MM-DD 格式的合法日期")
		}
		date = &parsed
	}

	var err error
	if options.Week, err = readInt("week", 1, 26); err != nil {
		return options, nil, err
	}
	if options.Days, err = readInt("days", 1, 3650); err != nil {
		return options, nil, err
	}
	if options.Limit, err = readInt("limit", 1, 500); err != nil {
		return options, nil, err
	}
	options.Base = readBool("base")
	options.All = readBool("all")
	options.Failed = readBool("failed")
	options.Strict = readBool("strict")
	options.Date = date
	options.Course = readString("course")
	options.Query = readString("query")
	options.Group = readString("group")

	if raw := readString("property"); raw != "" {
		property, ok := academic.PropertyFilter(raw)
		if !ok {
			return options, nil, fmt.Errorf("property 只能是 必修 / 限选 / 任选 / 其他")
		}
		options.Property = &property
	}

	// 公告与课程名录的默认上限不同，避免一次返回整页。
	if name == "query_notices" && options.Limit == nil {
		limit := 10
		options.Limit = &limit
	}
	return options, date, nil
}
