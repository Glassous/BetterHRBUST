/**
 * OpenAI 兼容客户端（流式）
 *
 * 两条传输路径，协议层完全一致，只有"谁来发请求"不同：
 *  - 桌面端：`POST /__app/ai/chat`，由 Rust 进程带 key 转发。这样既满足 CSP
 *    （页面只连同源），又绕开浏览器 CORS，key 也不必进入 JS 上下文；
 *  - Web / 油猴：直接 `POST {base_url}/chat/completions`。浏览器受 CORS 约束，
 *    服务商不放行时会在错误文案里点明原因。
 *
 * 超时三层（与 TUI 一致）：总超时（用户配置）→ 60s 空闲看门狗 → 外部 AbortSignal。
 */

import { isDesktopApp } from '@/utils/platform.js';
import {
  applyChunk,
  createAccumulator,
  finalizeMessage,
  looksLikeSSE,
  readSSE
} from './stream.js';

/** 空闲看门狗：持续有数据不中断，停摆 60s 才断 */
const IDLE_TIMEOUT_MS = 60000;

export class AiRequestError extends Error {
  constructor(message, kind = 'unknown', status = 0) {
    super(message);
    this.name = 'AiRequestError';
    this.kind = kind;
    this.status = status;
  }
}

/** 上游错误体截断，避免把整页 HTML 塞进对话框 */
function summarizeBody(text) {
  const compact = String(text || '').replace(/\s+/g, ' ').trim();
  return compact.length > 400 ? `${compact.slice(0, 400)}…` : compact;
}

/** 状态码 → 中文文案（照抄 TUI types.go 的 classify） */
function classifyStatus(status, bodyText) {
  const detail = summarizeBody(bodyText);
  const suffix = detail ? `（${detail}）` : '';
  if (status === 401 || status === 403) {
    return new AiRequestError(`认证失败（HTTP ${status}）：请检查 API Key 是否正确${suffix}`, 'auth', status);
  }
  if (status === 404) {
    return new AiRequestError(
      `接口不存在（HTTP 404）：请确认接口地址是否以 /v1 结尾${suffix}`,
      'endpoint',
      status
    );
  }
  if (status === 429) {
    return new AiRequestError(`请求过于频繁（HTTP 429）：请稍后再试${suffix}`, 'rate_limit', status);
  }
  return new AiRequestError(`AI 服务返回错误（HTTP ${status}）${suffix}`, 'unknown', status);
}

/** base_url → /chat/completions；只接受 http(s) */
export function buildEndpoint(baseUrl) {
  const trimmed = String(baseUrl || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(trimmed)) {
    throw new AiRequestError('接口地址必须以 http:// 或 https:// 开头', 'endpoint');
  }
  return `${trimmed}/chat/completions`;
}

/**
 * 发一次流式请求。
 *
 * @param {object} options
 * @param {{config: object, mode: string, apiKey: string}} options.view 当前配置视图
 * @param {Array<object>} options.messages OpenAI 消息数组
 * @param {Array<object>} [options.tools] 工具定义（空数组不发送）
 * @param {number} [options.temperature]
 * @param {AbortSignal} [options.signal]
 * @param {(text: string) => void} [options.onDelta] 正文增量
 * @param {(text: string) => void} [options.onReasoning] 推理增量（不下发历史）
 * @returns {Promise<{message: object, usage: object|null}>}
 */
export async function chatStream({
  view,
  messages,
  tools = [],
  temperature = 0,
  signal,
  onDelta,
  onReasoning
}) {
  const config = view?.config || {};
  const desktop = isDesktopApp && view?.mode === 'desktop';

  const headers = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream'
  };

  let url;
  let payload;
  if (desktop) {
    url = '/__app/ai/chat';
    payload = {
      base_url: config.base_url,
      model: config.model,
      messages,
      tools,
      temperature,
      timeout_seconds: config.timeout_seconds
    };
  } else {
    url = buildEndpoint(config.base_url);
    if (!view?.apiKey) {
      throw new AiRequestError('缺少 API Key：在设置页填写后保存', 'auth');
    }
    headers.Authorization = `Bearer ${view.apiKey}`;
    payload = {
      model: config.model,
      messages,
      stream: true,
      stream_options: { include_usage: true },
      ...(tools.length ? { tools } : {}),
      ...(temperature > 0 ? { temperature } : {})
    };
  }

  const controller = new AbortController();
  let timedOut = false;
  const abortFromOutside = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', abortFromOutside, { once: true });
  }

  const timeoutMs = Math.max(5, Number(config.timeout_seconds) || 120) * 1000;
  const totalTimer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  let idleTimer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, IDLE_TIMEOUT_MS);
  const resetIdle = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, IDLE_TIMEOUT_MS);
  };

  const cleanup = () => {
    clearTimeout(totalTimer);
    clearTimeout(idleTimer);
    if (signal) signal.removeEventListener('abort', abortFromOutside);
  };

  try {
    let response;
    try {
      response = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
        signal: controller.signal,
        cache: 'no-store'
      });
    } catch (err) {
      if (timedOut) {
        throw new AiRequestError(`${Math.round(timeoutMs / 1000)} 秒内没有收到响应，已中断`, 'timeout');
      }
      if (err?.name === 'AbortError') {
        throw new AiRequestError('已取消本次提问', 'canceled');
      }
      const hint = desktop
        ? '请检查网络与接口地址'
        : '浏览器直连可能被 CORS 拦截：可改用桌面端，或换用允许跨域的服务商';
      throw new AiRequestError(`无法连接 AI 服务：${err?.message || '网络错误'}（${hint}）`, 'network');
    }

    if (!response.ok) {
      const bodyText = await response.text().catch(() => '');
      throw classifyStatus(response.status, bodyText);
    }

    const contentType = response.headers.get('content-type') || '';
    const acc = createAccumulator();

    if (!response.body) {
      // 极端情况：没有可读流，按整段文本处理
      const text = await response.text();
      const parsed = JSON.parse(text);
      applyChunk(acc, parsed);
      return finalizeMessage(acc);
    }

    if (!looksLikeSSE(contentType, '')) {
      // 网关忽略了 stream，直接返回整包 JSON
      const text = await response.text();
      resetIdle();
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch {
        throw new AiRequestError(`服务返回了无法解析的内容：${summarizeBody(text)}`, 'protocol');
      }
      const delta = applyChunk(acc, parsed);
      if (delta.text) onDelta?.(delta.text);
      if (delta.reasoning) onReasoning?.(delta.reasoning);
      return finalizeMessage(acc);
    }

    for await (const data of readSSE(response.body)) {
      resetIdle();
      const frame = data.trim();
      if (!frame) continue;
      if (frame === '[DONE]') break;
      let chunk;
      try {
        chunk = JSON.parse(frame);
      } catch {
        // 单帧坏数据跳过，不终断整个回答
        continue;
      }
      const delta = applyChunk(acc, chunk);
      if (delta.text) onDelta?.(delta.text);
      if (delta.reasoning) onReasoning?.(delta.reasoning);
    }

    return finalizeMessage(acc);
  } catch (err) {
    if (err instanceof AiRequestError) throw err;
    if (timedOut) {
      throw new AiRequestError('等待响应超时，已中断', 'timeout');
    }
    if (err?.name === 'AbortError') {
      throw new AiRequestError('已取消本次提问', 'canceled');
    }
    throw new AiRequestError(err?.message || '请求失败', err?.kind || 'unknown');
  } finally {
    cleanup();
  }
}
