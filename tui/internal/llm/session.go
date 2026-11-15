package llm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Session 管理一段对话与多轮工具调用。
type Session struct {
	Client *Client
	// Tools 执行工具调用。
	Tools ToolInvoker
	// Definitions 是暴露给模型的工具定义。
	Definitions []Tool
	// SystemPrompt 是系统提示。
	SystemPrompt string
	// MaxToolRounds 是工具调用轮次上限，防止模型陷入反复调用。
	MaxToolRounds int
	// MaxHistory 是保留的最大消息条数（不含系统提示）。
	MaxHistory int

	history []Message
}

// NewSession 构造对话会话。
func NewSession(client *Client, tools ToolInvoker, definitions []Tool, systemPrompt string, maxRounds int) *Session {
	if maxRounds <= 0 {
		maxRounds = 8
	}
	if strings.TrimSpace(systemPrompt) == "" {
		systemPrompt = DefaultSystemPrompt
	}
	return &Session{
		Client:        client,
		Tools:         tools,
		Definitions:   definitions,
		SystemPrompt:  systemPrompt,
		MaxToolRounds: maxRounds,
		MaxHistory:    40,
	}
}

// Clear 清空对话历史。
func (s *Session) Clear() { s.history = nil }

// History 返回历史副本。
func (s *Session) History() []Message {
	out := make([]Message, len(s.history))
	copy(out, s.history)
	return out
}

// Turns 返回用户提问的轮数。
func (s *Session) Turns() int {
	count := 0
	for _, message := range s.history {
		if message.Role == RoleUser {
			count++
		}
	}
	return count
}

// Ask 发送一条用户消息并跑到模型不再调用工具为止。
//
// 事件通过 emit 同步推送；emit 可能被同一 goroutine 内的流式回调反复调用，
// 因此实现方要保证轻量（例如只往 channel 里塞）。
func (s *Session) Ask(ctx context.Context, prompt string, emit func(Event)) error {
	if strings.TrimSpace(prompt) == "" {
		return errors.New("提问内容为空")
	}
	s.push(Message{Role: RoleUser, Content: prompt})

	for round := 1; round <= s.MaxToolRounds; round++ {
		if emit != nil {
			emit(Event{Kind: EventRound, Round: round})
		}

		onDelta := func(delta Delta) {
			if emit == nil {
				return
			}
			if delta.Reasoning != "" {
				emit(Event{Kind: EventReasoning, Text: delta.Reasoning, Round: round})
			}
			if delta.Content != "" {
				emit(Event{Kind: EventText, Text: delta.Content, Round: round})
			}
		}

		assistant, _, err := s.Client.ChatStream(ctx, s.messages(), s.Definitions, onDelta)
		if err != nil {
			return err
		}
		s.push(assistant)

		if len(assistant.ToolCalls) == 0 {
			return nil
		}
		if s.Tools == nil {
			return errors.New("模型请求调用工具，但当前没有可用工具")
		}

		for _, call := range assistant.ToolCalls {
			if err := ctx.Err(); err != nil {
				return &Error{Kind: ErrorCanceled, Message: "已取消"}
			}
			label := call.Function.Name
			if label == "" {
				label = "未知工具"
			}
			if emit != nil {
				emit(Event{Kind: EventToolStart, Text: describeCall(call), Round: round})
			}

			result, invokeErr := s.invoke(ctx, call)
			if invokeErr != nil {
				result = map[string]any{
					"ok":    false,
					"error": invokeErr.Error(),
				}
			}
			summary := summarizeResult(call.Function.Name, result)
			if emit != nil {
				emit(Event{Kind: EventToolEnd, Text: summary, Round: round})
			}

			payload, marshalErr := json.Marshal(result)
			if marshalErr != nil {
				payload = []byte(`{"ok":false,"error":"工具结果无法序列化"}`)
			}
			s.push(Message{Role: RoleTool, ToolCallID: call.ID, Content: string(payload)})
		}
	}

	notice := fmt.Sprintf("已达到工具调用上限（%d 轮）。以上是基于目前已取到的数据，可能不完整。", s.MaxToolRounds)
	s.push(Message{Role: RoleAssistant, Content: notice})
	if emit != nil {
		emit(Event{Kind: EventText, Text: "\n\n> " + notice + "\n", Round: s.MaxToolRounds})
	}
	return nil
}

// invoke 执行一次工具调用，并把 panic 转成错误（工具不该让界面崩溃）。
func (s *Session) invoke(ctx context.Context, call ToolCall) (result any, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			result = nil
			err = fmt.Errorf("工具 %s 内部错误：%v", call.Function.Name, recovered)
		}
	}()
	args := call.Function.Arguments
	if strings.TrimSpace(args) == "" {
		args = "{}"
	}
	if !json.Valid([]byte(args)) {
		return nil, fmt.Errorf("参数不是合法 JSON：%s", truncate(args, 200))
	}
	return s.Tools.Invoke(ctx, call.Function.Name, json.RawMessage(args))
}

// push 追加消息并按上限裁剪历史。
func (s *Session) push(message Message) {
	s.history = append(s.history, message)
	s.trim()
}

// trim 保留最近 MaxHistory 条消息，并跳过被裁掉的残缺工具链。
//
// 裁剪必须从「user 消息」开始，否则会留下一条 tool 消息却没有对应的
// assistant tool_calls，服务端会直接报 400。
func (s *Session) trim() {
	if s.MaxHistory <= 0 || len(s.history) <= s.MaxHistory {
		return
	}
	cut := len(s.history) - s.MaxHistory
	for cut < len(s.history) && s.history[cut].Role != RoleUser {
		cut++
	}
	if cut >= len(s.history) {
		return
	}
	kept := make([]Message, len(s.history)-cut)
	copy(kept, s.history[cut:])
	s.history = kept
}

// messages 组装发给模型的完整消息列表。
func (s *Session) messages() []Message {
	// 旧的工具结果换成占位符，避免长对话把上下文撑爆。
	compact := make([]Message, 0, len(s.history)+1)
	compact = append(compact, Message{Role: RoleSystem, Content: s.SystemPrompt})
	for index, message := range s.history {
		if message.Role == RoleTool && index < len(s.history)-6 {
			compact = append(compact, Message{Role: RoleTool, ToolCallID: message.ToolCallID, Content: `{"truncated":true}`})
			continue
		}
		compact = append(compact, message)
	}
	return compact
}

// describeCall 把工具调用渲染成一行摘要。
func describeCall(call ToolCall) string {
	args := strings.TrimSpace(call.Function.Arguments)
	if args == "" || args == "{}" {
		return call.Function.Name
	}
	return call.Function.Name + "（" + truncate(args, 120) + "）"
}

// summarizeResult 把工具结果渲染成一行摘要，供界面折叠显示。
func summarizeResult(name string, result any) string {
	encoded, err := json.Marshal(result)
	if err != nil {
		return "返回内容无法序列化"
	}
	var decoded map[string]any
	if json.Unmarshal(encoded, &decoded) == nil {
		if ok, has := decoded["ok"].(bool); has && !ok {
			if text, isText := decoded["error"].(string); isText {
				return "失败：" + truncate(text, 160)
			}
			return "失败"
		}
		if data, has := decoded["data"]; has {
			return describeData(data)
		}
	}
	return "返回 " + byteSize(len(encoded))
}

// describeData 尽量给出一句人类可读的结果描述。
func describeData(data any) string {
	switch typed := data.(type) {
	case map[string]any:
		parts := make([]string, 0, 3)
		for _, key := range []string{"notices", "courses", "exams", "groups", "scores"} {
			if list, ok := typed[key].([]any); ok {
				parts = append(parts, fmt.Sprintf("%s %d 条", key, len(list)))
			}
		}
		if stats, ok := typed["stats"].(map[string]any); ok {
			if gpa, has := stats["gpa"]; has {
				parts = append(parts, fmt.Sprintf("GPA %v", gpa))
			}
		}
		if len(parts) == 0 {
			return "已返回数据"
		}
		return "已返回：" + strings.Join(parts, " · ")
	case []any:
		return fmt.Sprintf("已返回 %d 条", len(typed))
	default:
		return "已完成"
	}
}

// truncate 按字节截断并补省略号。
func truncate(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	return text[:limit] + "…"
}

// byteSize 把字节数格式化成 KB。
func byteSize(size int) string {
	if size < 1024 {
		return fmt.Sprintf("%d B", size)
	}
	return fmt.Sprintf("%.1f KB", float64(size)/1024)
}
