/**
 * SSE 读取与增量拼装（浏览器版，语义对齐 TUI `internal/llm/`）
 *
 * 与常见实现的差别（都是为了不踩 TUI 已经踩过的坑）：
 *  - **按行切分，不做定长缓冲**：工具调用的 arguments 可能是上百 KB 的单帧，
 *    `TextDecoder` 的流式解码 + 字符串 indexOf('\n') 不会有"行长上限"；
 *  - 多行 `data:` 用 `\n` 拼接，`:` 注释 / `event:` / `id:` / `retry:` 一律忽略；
 *  - 单帧 JSON 解析失败只跳过这一帧，不中断整个流；
 *  - 同时兼容 `choices[].delta` 与 `choices[].message`（部分网关忽略 stream 直接回全量）；
 *  - 结束时 content 与 tool_calls 都为空 → 协议错误（模型名错 / 不支持流式）。
 */

/** 上游是否真的是 SSE（Content-Type 或首个非空行以 `data:` 开头） */
export function looksLikeSSE(contentType, prefix) {
  const type = String(contentType || '').toLowerCase();
  if (type.includes('text/event-stream')) return true;
  const head = String(prefix || '').replace(/^\s+/, '');
  return head.startsWith('data:') || head.startsWith(':');
}

/**
 * 逐事件读取 SSE：每次 yield 一个事件的 data 文本。
 * @param {ReadableStream<Uint8Array>} body
 */
export async function* readSSE(body) {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let dataLines = [];

  const flush = function* () {
    if (dataLines.length) {
      const payload = dataLines.join('\n');
      dataLines = [];
      yield payload;
    }
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let index = buffer.indexOf('\n');
      while (index >= 0) {
        let line = buffer.slice(0, index);
        buffer = buffer.slice(index + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);

        if (line === '') {
          yield* flush();
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^\s/, ''));
        }
        // 其它字段（event/id/retry/注释）忽略
        index = buffer.indexOf('\n');
      }
    }

    // 末尾残留（服务端未以空行结束）
    buffer += decoder.decode();
    if (buffer) {
      const line = buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer;
      if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^\s/, ''));
      else if (line.trim() && !line.startsWith(':')) {
        // 非 SSE 的整段 JSON：把整个残留当作一个 data 帧
        dataLines.push(line);
      }
    }
    yield* flush();
  } finally {
    try {
      await reader.cancel();
    } catch {
      // 已关闭
    }
  }
}

/** 一次请求的增量累加器 */
export function createAccumulator() {
  return {
    content: '',
    reasoning: '',
    toolCalls: new Map(), // index -> { id, name, arguments }
    usage: null,
    chunks: 0
  };
}

function mergeToolCall(target, delta, index) {
  const id = String(delta?.id || '');
  const name = String(delta?.function?.name || '');
  const args = delta?.function?.arguments;
  if (id) target.id = id;
  if (name) target.name = (target.name || '') + name;
  if (typeof args === 'string') target.arguments = (target.arguments || '') + args;
  if (!target.index && target.index !== 0) target.index = index;
}

/**
 * 应用一帧 JSON（已经 JSON.parse 的对象）。
 * @returns {{text: string, reasoning: string}} 本帧新增的可显示文本
 */
export function applyChunk(acc, chunk) {
  let text = '';
  let reasoning = '';

  if (chunk?.usage) acc.usage = chunk.usage;

  // 兼容部分服务：错误直接放在流帧里
  if (chunk?.error) {
    const message = typeof chunk.error === 'string' ? chunk.error : chunk.error?.message;
    throw new Error(message || '服务返回了错误');
  }

  const choice = Array.isArray(chunk?.choices) ? chunk.choices[0] : null;
  if (choice) {
    const delta = choice.delta || choice.message || {};
    if (typeof delta.content === 'string' && delta.content) {
      acc.content += delta.content;
      text += delta.content;
    }
    if (typeof delta.reasoning_content === 'string' && delta.reasoning_content) {
      acc.reasoning += delta.reasoning_content;
      reasoning += delta.reasoning_content;
    }
    const calls = Array.isArray(delta.tool_calls) ? delta.tool_calls : [];
    calls.forEach((call, position) => {
      const index = Number.isInteger(call?.index) ? call.index : position;
      const existing = acc.toolCalls.get(index) || { id: '', name: '', arguments: '', index };
      mergeToolCall(existing, call, index);
      acc.toolCalls.set(index, existing);
    });
  }

  acc.chunks += 1;
  return { text, reasoning };
}

/** 把累加器收尾成 assistant 消息（tool_calls 缺 id 时补 `call_%d`，否则回填工具结果无法对应） */
export function finalizeMessage(acc) {
  const calls = [...acc.toolCalls.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([index, call]) => ({
      id: call.id || `call_${index}`,
      type: 'function',
      function: {
        name: call.name || '',
        arguments: call.arguments || '{}'
      }
    }));

  if (!acc.content && calls.length === 0) {
    const error = new Error('服务没有返回任何内容（可能是模型名错误或服务不支持流式）');
    error.kind = 'protocol';
    throw error;
  }

  return {
    message: {
      role: 'assistant',
      content: acc.content,
      ...(calls.length ? { tool_calls: calls } : {})
    },
    usage: acc.usage
  };
}
