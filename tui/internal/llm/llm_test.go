package llm

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// sseServer 启动一个按脚本回放 SSE 的假服务。
func sseServer(t *testing.T, script string) (*Client, *int) {
	t.Helper()
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		calls++
		if request.URL.Path != "/v1/chat/completions" {
			writer.WriteHeader(http.StatusNotFound)
			return
		}
		writer.Header().Set("Content-Type", "text/event-stream")
		writer.WriteHeader(http.StatusOK)
		flusher, _ := writer.(http.Flusher)
		writer.Write([]byte(script))
		if flusher != nil {
			flusher.Flush()
		}
	}))
	t.Cleanup(server.Close)
	client := NewClient(server.URL+"/v1", "test-key", "test-model", 0, 5*time.Second)
	return client, &calls
}

func chunk(payload string) string {
	return "data: " + payload + "\n\n"
}

func TestChatStreamPlainText(t *testing.T) {
	script := chunk(`{"choices":[{"delta":{"content":"你好"}}]}`) +
		chunk(`{"choices":[{"delta":{"content":"，世界"}}]}`) +
		chunk(`[DONE]`)
	client, calls := sseServer(t, script)

	var received strings.Builder
	message, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "hi"}}, nil, func(delta Delta) {
		received.WriteString(delta.Content)
	})
	if err != nil {
		t.Fatalf("不应报错：%v", err)
	}
	if message.Content != "你好，世界" {
		t.Fatalf("内容期望「你好，世界」，实际 %q", message.Content)
	}
	if received.String() != "你好，世界" {
		t.Fatalf("增量回调应拼接成同样内容，实际 %q", received.String())
	}
	if *calls != 1 {
		t.Fatalf("应只请求一次，实际 %d", *calls)
	}
}

func TestChatStreamToolCallsAccumulate(t *testing.T) {
	// 模拟真实服务把参数拆到多帧、且中间夹着空 delta。
	script := chunk(`{"choices":[{"delta":{"content":""}}]}`) +
		chunk(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"query_scores","arguments":"{\"pro"}}]}}]}`) +
		chunk(`{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"perty\":\"必修\"}"}}]}}]}`) +
		chunk(`{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}`) +
		chunk(`[DONE]`)
	client, _ := sseServer(t, script)

	message, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "必修成绩"}}, nil, nil)
	if err != nil {
		t.Fatalf("不应报错：%v", err)
	}
	if len(message.ToolCalls) != 1 {
		t.Fatalf("应有 1 个工具调用，实际 %d", len(message.ToolCalls))
	}
	call := message.ToolCalls[0]
	if call.ID != "call_a" || call.Function.Name != "query_scores" {
		t.Fatalf("调用信息不符：%+v", call)
	}
	if call.Function.Arguments != `{"property":"必修"}` {
		t.Fatalf("参数应完整拼接，实际 %q", call.Function.Arguments)
	}
}

func TestChatStreamReasoningIsNotPersisted(t *testing.T) {
	script := chunk(`{"choices":[{"delta":{"reasoning_content":"先想一想"}}]}`) +
		chunk(`{"choices":[{"delta":{"content":"结论"}}]}`) +
		chunk(`[DONE]`)
	client, _ := sseServer(t, script)

	var reasoning strings.Builder
	message, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, func(delta Delta) {
		reasoning.WriteString(delta.Reasoning)
	})
	if err != nil {
		t.Fatalf("不应报错：%v", err)
	}
	if reasoning.String() != "先想一想" {
		t.Fatalf("推理增量应单独推送，实际 %q", reasoning.String())
	}
	if strings.Contains(message.Content, "先想一想") {
		t.Fatalf("推理内容不得混入正文：%q", message.Content)
	}
	// 只有正文进入历史；reasoning 不参与序列化。
	encoded, _ := json.Marshal(message)
	if strings.Contains(string(encoded), "先想一想") {
		t.Fatalf("推理内容不得进入消息序列化：%s", encoded)
	}
}

func TestChatStreamToleratesProtocolVariants(t *testing.T) {
	// CRLF、注释行、多行 data、空行、非 JSON 帧混合出现。
	script := ": heartbeat\r\n" +
		"event: message\r\n" +
		"data: {\"choices\":[{\"delta\":\r\n" +
		"data: {\"content\":\"跨行\"}}]}\r\n" +
		"\r\n" +
		"data: not-json\r\n\r\n" +
		"data: {\"choices\":[{\"delta\":{\"content\":null}}]}\r\n\r\n" +
		"data: [DONE]\r\n\r\n"
	client, _ := sseServer(t, script)

	message, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
	if err != nil {
		t.Fatalf("不应报错：%v", err)
	}
	if message.Content != "跨行" {
		t.Fatalf("多行 data 应拼接后解析，实际 %q", message.Content)
	}
}

func TestChatStreamHandlesOversizedFrame(t *testing.T) {
	// 超过 bufio.Scanner 默认 64 KB 上限的一帧参数，不能被当成错误。
	big := strings.Repeat("a", 200*1024)
	payload := fmt.Sprintf(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"query_scores","arguments":"%s"}}]}}]}`, big)
	client, _ := sseServer(t, chunk(payload)+chunk(`[DONE]`))

	message, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
	if err != nil {
		t.Fatalf("大帧不应报错：%v", err)
	}
	if len(message.ToolCalls) != 1 || len(message.ToolCalls[0].Function.Arguments) < len(big) {
		t.Fatalf("大帧参数应完整保留，实际长度 %d", len(message.ToolCalls[0].Function.Arguments))
	}
}

func TestChatStreamFallsBackToJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusOK)
		writer.Write([]byte(`{"choices":[{"message":{"content":"非流式回答"}}],"usage":{"total_tokens":7}}`))
	}))
	t.Cleanup(server.Close)
	client := NewClient(server.URL+"/v1", "k", "m", 0, 5*time.Second)

	message, usage, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
	if err != nil {
		t.Fatalf("非流式响应应被兼容：%v", err)
	}
	if message.Content != "非流式回答" {
		t.Fatalf("内容不符：%q", message.Content)
	}
	if usage.TotalTokens != 7 {
		t.Fatalf("usage 应被解析，实际 %d", usage.TotalTokens)
	}
}

func TestChatStreamClassifiesHTTPErrors(t *testing.T) {
	cases := []struct {
		status int
		kind   ErrorKind
		body   string
	}{
		{401, ErrorAuth, `{"error":"invalid api key"}`},
		{403, ErrorAuth, "forbidden"},
		{404, ErrorEndpoint, "not found"},
		{429, ErrorRateLimit, "rate limited"},
		{500, ErrorUnknown, "boom"},
	}
	for _, item := range cases {
		item := item
		t.Run(itoa(item.status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
				writer.WriteHeader(item.status)
				writer.Write([]byte(item.body))
			}))
			defer server.Close()
			client := NewClient(server.URL+"/v1", "k", "m", 0, 5*time.Second)
			_, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
			if err == nil {
				t.Fatal("应返回错误")
			}
			typed, ok := err.(*Error)
			if !ok {
				t.Fatalf("错误类型应为 *Error，实际 %T", err)
			}
			if typed.Kind != item.kind {
				t.Fatalf("错误分类期望 %v，实际 %v（%s）", item.kind, typed.Kind, typed.Message)
			}
		})
	}
}

func TestChatStreamCancel(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/event-stream")
		writer.WriteHeader(http.StatusOK)
		writer.Write([]byte(chunk(`{"choices":[{"delta":{"content":"开始"}}]}`)))
		if flusher, ok := writer.(http.Flusher); ok {
			flusher.Flush()
		}
		<-request.Context().Done()
	}))
	t.Cleanup(server.Close)
	client := NewClient(server.URL+"/v1", "k", "m", 0, 30*time.Second)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(80 * time.Millisecond)
		cancel()
	}()
	_, _, err := client.ChatStream(ctx, []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
	if err == nil {
		t.Fatal("取消后应返回错误")
	}
	typed, ok := err.(*Error)
	if !ok || typed.Kind != ErrorCanceled {
		t.Fatalf("应分类为取消，实际 %v", err)
	}
}

func TestChatStreamRejectsEmptyResponse(t *testing.T) {
	client, _ := sseServer(t, chunk(`[DONE]`))
	_, _, err := client.ChatStream(context.Background(), []Message{{Role: RoleUser, Content: "q"}}, nil, nil)
	if err == nil {
		t.Fatal("空响应应报错，而不是当成成功")
	}
	typed, ok := err.(*Error)
	if !ok || typed.Kind != ErrorProtocol {
		t.Fatalf("应分类为协议错误，实际 %v", err)
	}
}

// ---------- 会话与工具循环 ----------

// fakeInvoker 是可控的工具实现。
type fakeInvoker struct {
	mu    sync.Mutex
	calls []string
	// reply 决定每次调用的返回值。
	reply func(name string, call int) any
}

func (f *fakeInvoker) Invoke(_ context.Context, name string, _ json.RawMessage) (any, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, name)
	if f.reply == nil {
		return map[string]any{"ok": true}, nil
	}
	return f.reply(name, len(f.calls)), nil
}

func (f *fakeInvoker) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.calls)
}

func TestSessionRunsToolLoop(t *testing.T) {
	// 第一次返回工具调用，第二次返回结论。
	round := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		round++
		writer.Header().Set("Content-Type", "text/event-stream")
		writer.WriteHeader(http.StatusOK)
		if round == 1 {
			writer.Write([]byte(chunk(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"query_summary","arguments":"{}"}}]}}]}`)))
			writer.Write([]byte(chunk(`[DONE]`)))
			return
		}
		writer.Write([]byte(chunk(`{"choices":[{"delta":{"content":"你的 GPA 是 2.4"}}]}`)))
		writer.Write([]byte(chunk(`[DONE]`)))
	}))
	t.Cleanup(server.Close)

	client := NewClient(server.URL+"/v1", "k", "m", 0, 5*time.Second)
	invoker := &fakeInvoker{reply: func(string, int) any {
		return map[string]any{"data": map[string]any{"stats": map[string]any{"gpa": 2.4}}}
	}}
	session := NewSession(client, invoker, []Tool{{Type: "function", Function: ToolFunction{Name: "query_summary", Parameters: json.RawMessage(`{}`)}}}, "", 4)

	var events []Event
	if err := session.Ask(context.Background(), "我的绩点", func(event Event) {
		events = append(events, event)
	}); err != nil {
		t.Fatalf("不应报错：%v", err)
	}
	if invoker.count() != 1 {
		t.Fatalf("工具应被调用一次，实际 %d", invoker.count())
	}

	var toolStarts, toolEnds int
	for _, event := range events {
		switch event.Kind {
		case EventToolStart:
			toolStarts++
		case EventToolEnd:
			toolEnds++
		}
	}
	if toolStarts != 1 || toolEnds != 1 {
		t.Fatalf("应各有一个工具开始/结束事件，实际 %d/%d", toolStarts, toolEnds)
	}

	history := session.History()
	if len(history) < 4 {
		t.Fatalf("历史应包含 user / assistant / tool / assistant，实际 %d 条", len(history))
	}
	last := history[len(history)-1]
	if last.Role != RoleAssistant || !strings.Contains(last.Content, "2.4") {
		t.Fatalf("最后一条应为结论消息，实际 %+v", last)
	}
}

func TestSessionStopsAtToolRoundLimit(t *testing.T) {
	// 服务永远只会要求调用工具，用来验证上限保护。
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/event-stream")
		writer.WriteHeader(http.StatusOK)
		writer.Write([]byte(chunk(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"query_summary","arguments":"{}"}}]}}]}`)))
		writer.Write([]byte(chunk(`[DONE]`)))
	}))
	t.Cleanup(server.Close)

	client := NewClient(server.URL+"/v1", "k", "m", 0, 5*time.Second)
	invoker := &fakeInvoker{}
	session := NewSession(client, invoker, []Tool{{Type: "function", Function: ToolFunction{Name: "query_summary", Parameters: json.RawMessage(`{}`)}}}, "", 3)

	var events []Event
	if err := session.Ask(context.Background(), "循环", func(event Event) {
		events = append(events, event)
	}); err != nil {
		t.Fatalf("达到上限不应报错：%v", err)
	}
	if invoker.count() != 3 {
		t.Fatalf("工具应被调用 3 次（等于上限），实际 %d", invoker.count())
	}
	var notice string
	for _, event := range events {
		if event.Kind == EventText {
			notice += event.Text
		}
	}
	if !strings.Contains(notice, "上限") {
		t.Fatalf("应明确告知已达上限，实际 %q", notice)
	}
}

func TestSessionReportsToolError(t *testing.T) {
	round := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		round++
		writer.Header().Set("Content-Type", "text/event-stream")
		writer.WriteHeader(http.StatusOK)
		if round == 1 {
			writer.Write([]byte(chunk(`{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"query_bad","arguments":"{}"}}]}}]}`)))
			writer.Write([]byte(chunk(`[DONE]`)))
			return
		}
		writer.Write([]byte(chunk(`{"choices":[{"delta":{"content":"查不到"}}]}`)))
		writer.Write([]byte(chunk(`[DONE]`)))
	}))
	t.Cleanup(server.Close)

	client := NewClient(server.URL+"/v1", "k", "m", 0, 5*time.Second)
	session := NewSession(client, errorInvoker{}, nil, "", 3)
	if err := session.Ask(context.Background(), "q", nil); err != nil {
		t.Fatalf("工具报错不应让会话失败：%v", err)
	}
}

// errorInvoker 总是失败。
type errorInvoker struct{}

func (errorInvoker) Invoke(context.Context, string, json.RawMessage) (any, error) {
	return nil, fmt.Errorf("数据不可用")
}

func TestSessionTrimKeepsValidToolChain(t *testing.T) {
	session := NewSession(nil, nil, nil, "", 2)
	session.MaxHistory = 4
	// 构造 user → assistant(tool_calls) → tool → assistant 的完整链，再追加更多。
	session.push(Message{Role: RoleUser, Content: "第一问"})
	session.push(Message{Role: RoleAssistant, ToolCalls: []ToolCall{{ID: "c1"}}})
	session.push(Message{Role: RoleTool, ToolCallID: "c1", Content: "{}"})
	session.push(Message{Role: RoleAssistant, Content: "第一答"})
	session.push(Message{Role: RoleUser, Content: "第二问"})
	session.push(Message{Role: RoleAssistant, Content: "第二答"})

	history := session.History()
	if len(history) == 0 {
		t.Fatal("历史不应为空")
	}
	if history[0].Role != RoleUser {
		t.Fatalf("裁剪后必须以 user 消息开头，实际 %q", history[0].Role)
	}
	for _, message := range history {
		if message.Role == RoleTool {
			t.Fatalf("不得留下没有对应 tool_calls 的 tool 消息：%+v", message)
		}
	}
}

func TestSessionHistoryCompaction(t *testing.T) {
	session := NewSession(nil, nil, nil, "", 2)
	for index := 0; index < 10; index++ {
		session.push(Message{Role: RoleUser, Content: "问"})
		session.push(Message{Role: RoleAssistant, Content: "答"})
	}
	messages := session.messages()
	if messages[0].Role != RoleSystem {
		t.Fatal("第一条必须是系统提示")
	}
	for _, message := range messages {
		if message.Role == RoleTool && message.Content != `{"truncated":true}` {
			t.Fatalf("早期工具结果应被替换为占位符，实际 %q", message.Content)
		}
	}
}

func TestSessionRejectsEmptyPrompt(t *testing.T) {
	session := NewSession(nil, nil, nil, "", 2)
	if err := session.Ask(context.Background(), "   ", nil); err == nil {
		t.Fatal("空提问应报错")
	}
}

func TestDescribeAndSummarize(t *testing.T) {
	if got := describeCall(ToolCall{Function: FunctionCall{Name: "query_scores", Arguments: `{"failed":true}`}}); !strings.Contains(got, "query_scores") {
		t.Fatalf("摘要应包含工具名，实际 %q", got)
	}
	if got := describeCall(ToolCall{Function: FunctionCall{Name: "query_profile"}}); got != "query_profile" {
		t.Fatalf("无参数时只显示工具名，实际 %q", got)
	}
	result := map[string]any{"data": map[string]any{"stats": map[string]any{"gpa": 2.4}}}
	if got := summarizeResult("query_summary", result); !strings.Contains(got, "GPA") {
		t.Fatalf("应摘要出 GPA，实际 %q", got)
	}
	failed := map[string]any{"ok": false, "error": "数据不可用"}
	if got := summarizeResult("query_x", failed); !strings.Contains(got, "失败") {
		t.Fatalf("失败结果应明确标注，实际 %q", got)
	}
}
