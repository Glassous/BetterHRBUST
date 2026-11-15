package llm

import (
	"bufio"
	"errors"
	"io"
	"strings"
)

// sseDecoder 是按行读取的 SSE 解析器。
//
// 为什么不用 `bufio.Scanner`：工具调用的 JSON 参数可能一次超过 64 KB，
// Scanner 的默认缓冲区会直接报 `token too long`，把一次正常回答变成错误。
// 这里用 Reader.ReadString，长度只受内存限制。
type sseDecoder struct {
	reader *bufio.Reader
}

// newSSEDecoder 构造解析器。
func newSSEDecoder(source io.Reader) *sseDecoder {
	return &sseDecoder{reader: bufio.NewReaderSize(source, 64*1024)}
}

// next 返回下一个事件的 `data` 负载。
//
// 返回 io.EOF 表示正常结束。多行 `data:` 会用换行拼接（SSE 规范）；
// 注释行（`:` 开头）、`event:`、`id:`、`retry:` 均忽略。
func (d *sseDecoder) next() (string, error) {
	var builder strings.Builder
	hasData := false

	for {
		line, err := d.reader.ReadString('\n')
		if err != nil && !errors.Is(err, io.EOF) {
			return "", err
		}
		// ReadString 在最后一行没有换行时也会返回内容，需要先处理内容再看错误。
		trimmed := strings.TrimRight(line, "\r\n")

		switch {
		case trimmed == "":
			// 空行是事件分隔符。
			if hasData {
				return builder.String(), nil
			}
		case strings.HasPrefix(trimmed, ":"):
			// 注释或心跳，忽略。
		case strings.HasPrefix(trimmed, "data:"):
			value := strings.TrimPrefix(trimmed, "data:")
			value = strings.TrimPrefix(value, " ")
			if hasData {
				builder.WriteString("\n")
			}
			builder.WriteString(value)
			hasData = true
		default:
			// event: / id: / retry: 以及非标准行一律忽略。
		}

		if errors.Is(err, io.EOF) {
			if hasData {
				return builder.String(), nil
			}
			return "", io.EOF
		}
	}
}

// looksLikeSSE 判断响应体是否走 SSE 协议。
func looksLikeSSE(contentType string, prefix []byte) bool {
	if strings.Contains(strings.ToLower(contentType), "text/event-stream") {
		return true
	}
	text := strings.TrimSpace(string(prefix))
	return strings.HasPrefix(text, "data:") || strings.HasPrefix(text, ":")
}
