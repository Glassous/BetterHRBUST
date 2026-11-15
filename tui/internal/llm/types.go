// Package llm 实现 OpenAI 兼容协议的流式客户端与多轮工具调用循环。
//
// 只依赖 `net/http` 与 `encoding/json`：一套协议即可对接 DeepSeek / OpenAI /
// Ollama / vLLM / 智谱 / Moonshot，无需为每家引入 SDK。
//
// 本包不知道教务数据长什么样：工具由 `internal/query` 注入，
// 因此"哪些数据可以离开本机"完全由注入方决定。
package llm

import (
	"context"
	"encoding/json"
	"strings"
	"time"
)

// Role 取值。
const (
	RoleSystem    = "system"
	RoleUser      = "user"
	RoleAssistant = "assistant"
	RoleTool      = "tool"
)

// Message 是对话消息（OpenAI 兼容形状）。
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content,omitempty"`
	// ToolCalls 仅 assistant 消息使用。
	ToolCalls []ToolCall `json:"tool_calls,omitempty"`
	// ToolCallID 仅 tool 消息使用，对应被回答的那次调用。
	ToolCallID string `json:"tool_call_id,omitempty"`
}

// ToolCall 是模型发起的一次函数调用。
type ToolCall struct {
	ID       string       `json:"id"`
	Type     string       `json:"type"`
	Function FunctionCall `json:"function"`
}

// FunctionCall 是函数名与 JSON 参数原文。
type FunctionCall struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// Tool 是工具定义（OpenAI 形状）。
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

// ToolInvoker 执行工具调用。
//
// 由调用方注入（`internal/query` 的 Toolset），本包只负责转发与错误呈现。
type ToolInvoker interface {
	Invoke(ctx context.Context, name string, args json.RawMessage) (any, error)
}

// Delta 是流式增量。
type Delta struct {
	// Content 是正文增量。
	Content string
	// Reasoning 是推理过程增量（DeepSeek-R1 之类）。
	// 它**绝不回填进历史**：带 reasoning_content 的 assistant 消息再作为输入会报错。
	Reasoning string
	// ToolCalls 是工具调用增量，按 Index 累积。
	ToolCalls []ToolCallDelta
}

// ToolCallDelta 是工具调用的分片。
type ToolCallDelta struct {
	Index     int
	ID        string
	Name      string
	Arguments string
}

// Usage 是 token 用量。
type Usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

// EventKind 是对话事件类型。
type EventKind int

const (
	// EventText 是正文增量。
	EventText EventKind = iota
	// EventReasoning 是推理增量。
	EventReasoning
	// EventToolStart 表示开始调用某个工具。
	EventToolStart
	// EventToolEnd 表示工具返回，Text 里是摘要。
	EventToolEnd
	// EventRound 表示开始新一轮。
	EventRound
)

// Event 是对话过程中推给 UI 的事件。
type Event struct {
	Kind EventKind
	Text string
	// Round 是从 1 开始轮次号。
	Round int
}

// ErrorKind 区分可重试与不可重试的错误。
type ErrorKind int

const (
	// ErrorUnknown 是未归类的错误。
	ErrorUnknown ErrorKind = iota
	// ErrorAuth 表示密钥无效（401）。
	ErrorAuth
	// ErrorEndpoint 表示路径不对（404，通常缺 /v1）。
	ErrorEndpoint
	// ErrorRateLimit 表示被限流（429）。
	ErrorRateLimit
	// ErrorTimeout 表示超时或长时间无数据。
	ErrorTimeout
	// ErrorCanceled 表示用户取消。
	ErrorCanceled
	// ErrorProtocol 表示响应不是预期的 SSE / JSON。
	ErrorProtocol
)

// Error 是带分类的对话错误。
type Error struct {
	Kind    ErrorKind
	Message string
	Status  int
}

// Error 实现 error。
func (e *Error) Error() string { return e.Message }

// classify 把 HTTP 状态码映射成错误类型与中文说明。
func classify(status int, body string) *Error {
	summary := summarizeBody(body)
	base := &Error{Status: status}
	switch {
	case status == 401 || status == 403:
		base.Kind = ErrorAuth
		base.Message = "API Key 无效或无权限（HTTP " + itoa(status) + "）：" + summary
	case status == 404:
		base.Kind = ErrorEndpoint
		base.Message = "接口路径不存在（HTTP 404）：请确认 base_url 是否需要以 /v1 结尾。" + summary
	case status == 429:
		base.Kind = ErrorRateLimit
		base.Message = "请求过于频繁或额度不足（HTTP 429）：" + summary
	case status >= 500:
		base.Kind = ErrorUnknown
		base.Message = "服务端错误（HTTP " + itoa(status) + "）：" + summary
	default:
		base.Kind = ErrorUnknown
		base.Message = "请求失败（HTTP " + itoa(status) + "）：" + summary
	}
	return base
}

// summarizeBody 截断错误响应体，避免把整页 HTML 塞进界面。
func summarizeBody(body string) string {
	text := strings.TrimSpace(body)
	if text == "" {
		return "服务端未返回内容"
	}
	if len(text) > 400 {
		return text[:400] + "…"
	}
	return text
}

// itoa 把整数转成字符串。
func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	digits := make([]byte, 0, 4)
	for value > 0 {
		digits = append([]byte{byte('0' + value%10)}, digits...)
		value /= 10
	}
	if negative {
		return "-" + string(digits)
	}
	return string(digits)
}

// idleTimeout 是"多久没有收到任何数据就判定卡住"。
const idleTimeout = 60 * time.Second

// DefaultSystemPrompt 是对话的系统提示。
//
// 明确三件事：数据来自本地缓存、统计口径以工具结果为准、不得编造。
const DefaultSystemPrompt = `你是 BetterHRBUST 的教务助手，运行在用户自己的终端里。

事实边界（必须遵守）：
- 你只能通过提供的工具读取数据；没有调用工具就不要给出具体数字。
- 所有数据来自用户本机的 BetterHRBUST 缓存，可能不是最新的。回答时附带工具返回的 lastSyncText（上次同步时间）；离线状态下要说明"这是上次登录时的缓存"。
- 学籍中的证件号、手机号、邮箱、地址已打码，保持打码原样，不要尝试还原或推测。
- 绩点、已获学分、挂科、学位资格、推免与风险预警的口径以工具返回的 stats 为准，不要用原始记录自行重算后当作官方结论。
- 工具返回 ok=false 或 readiness 不是 ready 时，据实说明，并引导用户到图形界面登录或刷新；不要编造数据。
- 教学公告没有发布日期，不要编造日期。

回答风格：
- 中文，简洁，先给结论再给依据。
- 涉及列表时用 Markdown 表格或短列表，便于在终端阅读。
- 需要多个数据源时，可以连续调用多个工具，但同一轮不要重复调用相同参数。`
