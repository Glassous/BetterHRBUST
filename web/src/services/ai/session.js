/**
 * 多轮对话 + 工具调用循环（对齐 TUI `internal/llm/session.go`）
 *
 * 关键不变量：
 *  - 历史裁剪必须从 **user 消息**开始切：否则会留下没有对应 assistant 的 tool 消息，
 *    服务端直接 400；
 *  - 除最近 6 条外，tool 结果替换为 `{"truncated":true}`，控制上下文膨胀；
 *  - reasoning 内容**绝不回填历史**（带 reasoning_content 的 assistant 消息再作输入会报错）；
 *  - 工具失败回填 `{"ok":false,"error":...}` 而不是中断——让模型自己解释并引导用户。
 */

import { AiRequestError, chatStream } from './client.js';
import { DEFAULT_SYSTEM_PROMPT, toolRoundLimitNotice } from './prompt.js';

/** 历史长度上限（含 system） */
const MAX_HISTORY = 40;
/** 保留完整结果的最近 tool 消息条数 */
const KEEP_TOOL_RESULTS = 6;

/** 工具调用的一行摘要（展示用） */
function describeCall(name, argsText) {
  const compact = String(argsText || '').replace(/\s+/g, ' ');
  const shown = compact.length > 80 ? `${compact.slice(0, 80)}…` : compact;
  return shown && shown !== '{}' ? `调用 ${name}（${shown}）` : `调用 ${name}`;
}

/** 工具结果的一行摘要 */
function summarizeResult(result) {
  const meta = result?.meta;
  if (meta && typeof meta.total === 'number') {
    return `完成（${meta.total} 条${meta.truncated ? '，已截断' : ''}）`;
  }
  return '完成';
}

/**
 * 创建一个会话。
 * @param {object} options
 * @param {{config: object, mode: string, apiKey: string}} options.view 配置视图（每次请求取最新值）
 * @param {{definitions: Array, invoke: Function}} options.toolset
 * @param {string} [options.systemPrompt]
 * @param {number} [options.maxToolRounds]
 */
export function createSession({ view, toolset, systemPrompt = DEFAULT_SYSTEM_PROMPT, maxToolRounds = 8 }) {
  const messages = [{ role: 'system', content: systemPrompt }];
  let turns = 0;

  /** 裁剪历史：保留 system，其余从最近的 user 消息开始 */
  function trimHistory() {
    if (messages.length <= MAX_HISTORY) return;
    let cut = messages.length - MAX_HISTORY;
    while (cut < messages.length && messages[cut].role !== 'user') cut += 1;
    if (cut >= messages.length) return;
    messages.splice(1, cut - 1);
  }

  /** 发给服务端的历史：旧的 tool 结果折叠掉 */
  function outgoingMessages() {
    const frozen = messages.map(item => ({ ...item }));
    const toolIndexes = [];
    frozen.forEach((item, index) => {
      if (item.role === 'tool') toolIndexes.push(index);
    });
    const keep = new Set(toolIndexes.slice(-KEEP_TOOL_RESULTS));
    return frozen.map((item, index) => {
      if (item.role === 'tool' && !keep.has(index)) {
        return { ...item, content: '{"truncated":true}' };
      }
      return item;
    });
  }

  function push(message) {
    messages.push(message);
    trimHistory();
  }

  /**
   * 提一个问题，流式推进直到模型给出最终回答。
   * @param {string} prompt
   * @param {{signal?: AbortSignal, onEvent?: (event: object) => void}} [options]
   */
  async function ask(prompt, { signal, onEvent } = {}) {
    const text = String(prompt || '').trim();
    if (!text) return;
    push({ role: 'user', content: text });
    turns += 1;

    const rounds = Math.max(1, Math.min(32, Number(maxToolRounds) || 8));

    for (let round = 1; round <= rounds; round += 1) {
      onEvent?.({ kind: 'round', round });

      const { message } = await chatStream({
        view,
        messages: outgoingMessages(),
        tools: toolset.definitions,
        temperature: Number(view?.config?.temperature) || 0,
        signal,
        onDelta: delta => onEvent?.({ kind: 'text', text: delta }),
        onReasoning: delta => onEvent?.({ kind: 'reasoning', text: delta })
      });

      const assistant = { role: 'assistant', content: message.content || '' };
      if (Array.isArray(message.tool_calls) && message.tool_calls.length) {
        assistant.tool_calls = message.tool_calls;
      }
      push(assistant);

      if (!assistant.tool_calls?.length) return;

      if (!toolset.definitions.length) {
        throw new Error('模型请求调用工具，但当前没有可用工具');
      }

      for (const call of assistant.tool_calls) {
        if (signal?.aborted) {
          throw new AiRequestError('已取消本次提问', 'canceled');
        }
        const name = call?.function?.name || '';
        const argsText = call?.function?.arguments || '{}';
        onEvent?.({ kind: 'tool_start', text: describeCall(name, argsText) });

        let result;
        try {
          if (typeof toolset.invoke !== 'function') throw new Error('工具集不可用');
          // 参数可能是空串 / 非法 JSON：明确回填而不是抛给上层
          let args = {};
          if (argsText && argsText !== 'null') {
            try {
              args = JSON.parse(argsText);
            } catch {
              throw new Error(`参数不是合法 JSON：${argsText.slice(0, 120)}`);
            }
          }
          result = await toolset.invoke(name, args || {});
          if (result === undefined) result = { ok: true };
          onEvent?.({ kind: 'tool_end', text: summarizeResult(result) });
        } catch (err) {
          result = { ok: false, error: String(err?.message || err) };
          onEvent?.({ kind: 'tool_end', text: `失败：${String(err?.message || err).slice(0, 80)}` });
        }

        push({
          role: 'tool',
          tool_call_id: call.id || '',
          content: JSON.stringify(result)
        });
      }
    }

    // 到达轮次上限：明确告诉用户"结果可能不完整"，不要假装已经查全
    const notice = toolRoundLimitNotice(rounds);
    push({ role: 'assistant', content: notice });
    onEvent?.({ kind: 'text', text: notice });
  }

  return {
    ask,
    clear() {
      messages.length = 1;
      turns = 0;
    },
    history: () => messages.map(item => ({ ...item })),
    turns: () => turns
  };
}
