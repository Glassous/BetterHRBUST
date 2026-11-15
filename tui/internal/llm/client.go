package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Client 是对 OpenAI 兼容 `/chat/completions` 的流式客户端。
type Client struct {
	HTTP *http.Client
	// BaseURL 指向 `/v1` 根，缺少 `/v1` 时由调用方决定是否补全（这里只做拼接）。
	BaseURL string
	APIKey  string
	Model   string
	// Temperature 为 0 时不下发该字段（部分服务对 0 有特殊语义）。
	Temperature float64
	// Timeout 是单次请求的整体上限。
	Timeout time.Duration
}

// NewClient 构造客户端。
func NewClient(baseURL, apiKey, model string, temperature float64, timeout time.Duration) *Client {
	if timeout <= 0 {
		timeout = 120 * time.Second
	}
	return &Client{
		HTTP:        &http.Client{Timeout: timeout},
		BaseURL:     baseURL,
		APIKey:      apiKey,
		Model:       model,
		Temperature: temperature,
		Timeout:     timeout,
	}
}

// endpoint 返回完整的 chat completions 地址。
func (c *Client) endpoint() string {
	base := strings.TrimRight(strings.TrimSpace(c.BaseURL), "/")
	return base + "/chat/completions"
}

// chatRequest 是请求体。
type chatRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Tools       []Tool    `json:"tools,omitempty"`
	Stream      bool      `json:"stream"`
	Temperature float64   `json:"temperature,omitempty"`
	// StreamOptions 让支持的服务在流末尾回传 usage；不支持的服务通常会忽略。
	StreamOptions *streamOptions `json:"stream_options,omitempty"`
}

type streamOptions struct {
	IncludeUsage bool `json:"include_usage"`
}

// streamChunk 是流式响应的一帧。
type streamChunk struct {
	Choices []struct {
		Delta struct {
			Content          string `json:"content"`
			ReasoningContent string `json:"reasoning_content"`
			Reasoning        string `json:"reasoning"`
			ToolCalls        []struct {
				Index    int    `json:"index"`
				ID       string `json:"id"`
				Type     string `json:"type"`
				Function struct {
					Name      string `json:"name"`
					Arguments string `json:"arguments"`
				} `json:"function"`
			} `json:"tool_calls"`
		} `json:"delta"`
		Message struct {
			Content          string         `json:"content"`
			ReasoningContent string         `json:"reasoning_content"`
			ToolCalls        []toolCallWire `json:"tool_calls"`
		} `json:"message"`
		FinishReason *string `json:"finish_reason"`
	} `json:"choices"`
	Usage *Usage `json:"usage"`
	Error *struct {
		Message string `json:"message"`
		Type    string `json:"type"`
	} `json:"error"`
}

type toolCallWire struct {
	Index    int    `json:"index"`
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

// accumulator 负责把流式分片拼回一条 assistant 消息。
type accumulator struct {
	content   strings.Builder
	reasoning strings.Builder
	calls     map[int]*ToolCall
	order     []int
}

func newAccumulator() *accumulator {
	return &accumulator{calls: map[int]*ToolCall{}}
}

func (a *accumulator) push(delta Delta) {
	if delta.Content != "" {
		a.content.WriteString(delta.Content)
	}
	if delta.Reasoning != "" {
		a.reasoning.WriteString(delta.Reasoning)
	}
	for _, item := range delta.ToolCalls {
		call, ok := a.calls[item.Index]
		if !ok {
			call = &ToolCall{Type: "function"}
			a.calls[item.Index] = call
			a.order = append(a.order, item.Index)
		}
		if item.ID != "" {
			call.ID = item.ID
		}
		if item.Name != "" {
			call.Function.Name = item.Name
		}
		call.Function.Arguments += item.Arguments
	}
}

// message 返回拼装完成的 assistant 消息。
func (a *accumulator) message() Message {
	message := Message{Role: RoleAssistant, Content: a.content.String()}
	sort.Ints(a.order)
	for index, key := range a.order {
		call := a.calls[key]
		if call.ID == "" {
			// 少数服务不回 id，这里补一个稳定值，否则后续 tool 消息无法对应。
			call.ID = fmt.Sprintf("call_%d", index)
		}
		message.ToolCalls = append(message.ToolCalls, *call)
	}
	return message
}

// ChatStream 发起一次流式请求，返回拼装好的 assistant 消息。
//
// `onDelta` 可以为 nil。错误按 `*Error` 返回，便于上层分门别类地展示。
func (c *Client) ChatStream(
	ctx context.Context,
	messages []Message,
	tools []Tool,
	onDelta func(Delta),
) (Message, Usage, error) {
	payload := chatRequest{
		Model:         c.Model,
		Messages:      messages,
		Tools:         tools,
		Stream:        true,
		Temperature:   c.Temperature,
		StreamOptions: &streamOptions{IncludeUsage: true},
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return Message{}, Usage{}, &Error{Kind: ErrorProtocol, Message: "无法序列化请求：" + err.Error()}
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint(), bytes.NewReader(body))
	if err != nil {
		return Message{}, Usage{}, &Error{Kind: ErrorUnknown, Message: "无法构造请求：" + err.Error()}
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "text/event-stream")
	if c.APIKey != "" {
		request.Header.Set("Authorization", "Bearer "+c.APIKey)
	}

	response, err := c.HTTP.Do(request)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
			return Message{}, Usage{}, &Error{Kind: ErrorCanceled, Message: "已取消"}
		}
		if errors.Is(err, context.DeadlineExceeded) {
			return Message{}, Usage{}, &Error{Kind: ErrorTimeout, Message: "请求超时（" + c.Timeout.String() + "）"}
		}
		return Message{}, Usage{}, &Error{Kind: ErrorUnknown, Message: "无法连接服务：" + err.Error()}
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		raw, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		return Message{}, Usage{}, classify(response.StatusCode, string(raw))
	}

	prefix, err := readPrefix(response.Body, 512)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return Message{}, Usage{}, contextError(ctxErr, c.Timeout)
		}
		return Message{}, Usage{}, &Error{Kind: ErrorProtocol, Message: "读取响应失败：" + err.Error()}
	}
	reader := io.MultiReader(bytes.NewReader(prefix), response.Body)

	if !looksLikeSSE(response.Header.Get("Content-Type"), prefix) {
		// 部分实现（旧版 vLLM、某些网关）忽略 stream 直接返回完整 JSON。
		return decodeFullResponse(io.MultiReader(reader))
	}
	return c.consumeStream(ctx, reader, onDelta)
}

// consumeStream 解析 SSE 流并累积增量。
func (c *Client) consumeStream(
	ctx context.Context,
	source io.Reader,
	onDelta func(Delta),
) (Message, Usage, error) {
	decoder := newSSEDecoder(source)
	accumulated := newAccumulator()
	var usage Usage

	// 看门狗：超过 idleTimeout 一个字节都没收到就主动中断，
	// 而不是让用户对着转圈的界面一直等 HTTP 超时。
	childCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	idleHit := false
	var idleMu sync.Mutex
	watchdog := time.AfterFunc(idleTimeout, func() {
		idleMu.Lock()
		idleHit = true
		idleMu.Unlock()
		cancel()
	})
	defer watchdog.Stop()

	for {
		if err := childCtx.Err(); err != nil {
			idleMu.Lock()
			wasIdle := idleHit
			idleMu.Unlock()
			if wasIdle {
				return accumulated.message(), usage, &Error{
					Kind:    ErrorTimeout,
					Message: "超过 " + idleTimeout.String() + " 没有收到任何数据，已中断",
				}
			}
			return accumulated.message(), usage, &Error{Kind: ErrorCanceled, Message: "已取消"}
		}
		data, err := decoder.next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return accumulated.message(), usage, &Error{Kind: ErrorProtocol, Message: "流读取中断：" + err.Error()}
		}
		watchdog.Reset(idleTimeout)
		if strings.TrimSpace(data) == "[DONE]" {
			break
		}

		var chunk streamChunk
		if err := json.Unmarshal([]byte(data), &chunk); err != nil {
			// 单帧解析失败不应该终止整段回答：跳过并继续。
			continue
		}
		if chunk.Error != nil {
			return accumulated.message(), usage, &Error{
				Kind: ErrorUnknown, Message: "服务返回错误：" + chunk.Error.Message,
			}
		}
		if chunk.Usage != nil {
			usage = *chunk.Usage
		}

		for _, choice := range chunk.Choices {
			// 非流式形状混入时也兼容。
			if choice.Message.Content != "" || len(choice.Message.ToolCalls) > 0 {
				applyFullMessage(accumulated,
					choice.Message.Content, choice.Message.ReasoningContent, choice.Message.ToolCalls)
			}

			delta := Delta{
				Content:   choice.Delta.Content,
				Reasoning: firstNonEmpty(choice.Delta.ReasoningContent, choice.Delta.Reasoning),
			}
			for _, item := range choice.Delta.ToolCalls {
				delta.ToolCalls = append(delta.ToolCalls, ToolCallDelta{
					Index:     item.Index,
					ID:        item.ID,
					Name:      item.Function.Name,
					Arguments: item.Function.Arguments,
				})
			}
			if delta.Content == "" && delta.Reasoning == "" && len(delta.ToolCalls) == 0 {
				continue
			}
			accumulated.push(delta)
			if onDelta != nil {
				onDelta(delta)
			}
		}
	}

	message := accumulated.message()
	if message.Content == "" && len(message.ToolCalls) == 0 {
		return message, usage, &Error{
			Kind:    ErrorProtocol,
			Message: "服务没有返回任何内容（可能是模型名错误或服务不支持流式）",
		}
	}
	return message, usage, nil
}

// applyFullMessage 处理把完整 message 塞进流帧的实现。
func applyFullMessage(accumulated *accumulator, content, reasoning string, calls []toolCallWire) {
	accumulated.push(Delta{Content: content, Reasoning: reasoning})
	for _, call := range calls {
		accumulated.push(Delta{ToolCalls: []ToolCallDelta{{
			Index:     call.Index,
			ID:        call.ID,
			Name:      call.Function.Name,
			Arguments: call.Function.Arguments,
		}}})
	}
}

// decodeFullResponse 处理非流式响应。
func decodeFullResponse(source io.Reader) (Message, Usage, error) {
	raw, err := io.ReadAll(io.LimitReader(source, 8<<20))
	if err != nil {
		return Message{}, Usage{}, &Error{Kind: ErrorProtocol, Message: "读取响应失败：" + err.Error()}
	}
	var chunk streamChunk
	if err := json.Unmarshal(raw, &chunk); err != nil {
		return Message{}, Usage{}, &Error{
			Kind:    ErrorProtocol,
			Message: "响应既不是 SSE 也不是合法 JSON：" + summarizeBody(string(raw)),
		}
	}
	if chunk.Error != nil {
		return Message{}, Usage{}, &Error{Kind: ErrorUnknown, Message: "服务返回错误：" + chunk.Error.Message}
	}
	if len(chunk.Choices) == 0 {
		return Message{}, Usage{}, &Error{Kind: ErrorProtocol, Message: "响应里没有 choices"}
	}

	accumulated := newAccumulator()
	wire := chunk.Choices[0].Message
	applyFullMessage(accumulated, wire.Content, wire.ReasoningContent, wire.ToolCalls)
	message := accumulated.message()
	if message.Content == "" && len(message.ToolCalls) == 0 {
		return message, Usage{}, &Error{Kind: ErrorProtocol, Message: "服务没有返回任何内容"}
	}
	var usage Usage
	if chunk.Usage != nil {
		usage = *chunk.Usage
	}
	return message, usage, nil
}

// readPrefix 读取响应体开头若干字节用于判断协议，不消耗后续数据。
func readPrefix(source io.Reader, limit int) ([]byte, error) {
	buffer := make([]byte, limit)
	read, err := io.ReadFull(source, buffer)
	switch {
	case err == nil:
		return buffer, nil
	case errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF):
		return buffer[:read], nil
	default:
		return nil, err
	}
}

// firstNonEmpty 返回第一个非空字符串。
func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

// contextError 把 context 错误映射成分类错误。
func contextError(err error, timeout time.Duration) *Error {
	if errors.Is(err, context.DeadlineExceeded) {
		return &Error{Kind: ErrorTimeout, Message: "请求超时（" + timeout.String() + "）"}
	}
	return &Error{Kind: ErrorCanceled, Message: "已取消"}
}
