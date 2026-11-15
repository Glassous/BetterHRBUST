/**
 * AI 对话抽屉状态（模块级单例，与项目其它 composable 一致）
 *
 * 语义对齐 TUI 的聊天抽屉：
 *  - 抽屉关闭时事件仍写入 entries（用户在生成中途关掉再打开，不该看到残缺回答）；
 *  - ctrl+n 清空会同时丢弃会话历史；
 *  - 流式期间 assistant 条目置 pending，由渲染层决定"流式纯文本、完成后 Markdown"；
 *  - 未就绪时把 Ready 的原因作为错误条目展示，而不是静默不发。
 */

import { computed, ref } from 'vue';
import { aiReadiness, loadAiConfig, providerHost, resetAiConfig, saveAiConfig } from '@/services/ai/config.js';
import { buildToolset } from '@/services/ai/tools.js';
import { createSession } from '@/services/ai/session.js';

/** 抽屉开关 */
const open = ref(false);
/** 配置视图（{config, hasKey, mode, apiKey, apiKeyMasked, path}） */
const view = ref(null);
/** 对话条目：role = user / assistant / tool / error / notice；pending 表示流式中 */
const entries = ref([]);
const streaming = ref(false);
const round = ref(0);
const input = ref('');
const lastError = ref('');
/** 流式期间用于展示"思考中"的占位条目标记 */
const reasoningEntryId = ref(0);

let session = null;
let toolsetSignature = '';
let controller = null;
let entryCounter = 0;

const readiness = computed(() => aiReadiness(view.value));
const ready = computed(() => readiness.value.ready);
const provider = computed(() => providerHost(view.value?.config?.base_url));
const canChat = computed(() => ready.value && !streaming.value);

function entryId() {
  entryCounter += 1;
  return entryCounter;
}

function pushEntry(role, text, extra = {}) {
  const entry = { id: entryId(), role, text, pending: false, ...extra };
  entries.value.push(entry);
  return entry;
}

function lastEntry() {
  return entries.value[entries.value.length - 1];
}

/** 正文增量：接到末尾的 pending assistant 上，否则新开一条 */
function appendText(text) {
  if (!text) return;
  const last = lastEntry();
  if (last && last.role === 'assistant' && last.pending) {
    last.text += text;
    return;
  }
  pushEntry('assistant', text, { pending: true });
}

/** 推理内容只放一条"思考中…"占位，不展示原文（也不进入历史） */
function markReasoning() {
  if (reasoningEntryId.value) return;
  const entry = pushEntry('notice', '思考中…', { reasoning: true });
  reasoningEntryId.value = entry.id;
}

function dropReasoningPlaceholder() {
  if (!reasoningEntryId.value) return;
  entries.value = entries.value.filter(item => item.id !== reasoningEntryId.value);
  reasoningEntryId.value = 0;
}

/** 刷新配置（设置页保存后调用） */
async function refreshConfig() {
  view.value = await loadAiConfig();
  // 配置变化后工具集可能变化（发送学籍开关），让下一次提问重建会话
  session = null;
  toolsetSignature = '';
  return view.value;
}

async function ensureConfig() {
  if (!view.value) await refreshConfig();
  return view.value;
}

/** 保存配置并让对话侧立刻用上新值 */
async function applyConfig(patch) {
  view.value = await saveAiConfig(patch);
  session = null;
  toolsetSignature = '';
  return view.value;
}

async function clearConfig() {
  view.value = await resetAiConfig();
  session = null;
  toolsetSignature = '';
  reset();
  return view.value;
}

function ensureSession() {
  const config = view.value?.config || {};
  const signature = `${config.send_profile ? '1' : '0'}:${config.max_tool_rounds}`;
  if (session && signature === toolsetSignature) return session;
  const toolset = buildToolset({ sendProfile: !!config.send_profile });
  session = createSession({
    view: view.value,
    toolset,
    maxToolRounds: Number(config.max_tool_rounds) || 8
  });
  toolsetSignature = signature;
  return session;
}

function cancel() {
  if (controller) {
    controller.abort();
    controller = null;
  }
}

/** 清空对话（同时丢弃会话历史与在飞请求） */
function reset() {
  cancel();
  session = null;
  toolsetSignature = '';
  entries.value = [];
  streaming.value = false;
  round.value = 0;
  lastError.value = '';
  reasoningEntryId.value = 0;
  input.value = '';
}

/** 打开/关闭抽屉 */
async function toggle(next) {
  const value = typeof next === 'boolean' ? next : !open.value;
  open.value = value;
  if (value) {
    await ensureConfig().catch(() => {});
  }
}

/** 发一条提问 */
async function send() {
  const prompt = String(input.value || '').trim();
  if (!prompt || streaming.value) return;

  const current = await ensureConfig().catch(() => null);
  const state = aiReadiness(current);
  if (!state.ready) {
    pushEntry('error', state.reason);
    return;
  }

  input.value = '';
  lastError.value = '';
  pushEntry('user', prompt);
  streaming.value = true;
  round.value = 0;

  const active = ensureSession();
  controller = new AbortController();

  try {
    await active.ask(prompt, {
      signal: controller.signal,
      onEvent: event => {
        // 抽屉关闭时也照常写入 entries：否则重开会看到残缺回答
        if (event.kind === 'round') {
          round.value = event.round || 0;
          return;
        }
        if (event.kind === 'text') {
          dropReasoningPlaceholder();
          appendText(event.text);
          return;
        }
        if (event.kind === 'reasoning') {
          markReasoning();
          return;
        }
        if (event.kind === 'tool_start') {
          dropReasoningPlaceholder();
          pushEntry('tool', event.text, { pending: true });
          return;
        }
        if (event.kind === 'tool_end') {
          const last = lastEntry();
          if (last && last.role === 'tool' && last.pending) {
            last.text = `${last.text} · ${event.text}`;
            last.pending = false;
          } else {
            pushEntry('tool', `工具${event.text}`);
          }
        }
      }
    });
  } catch (err) {
    lastError.value = String(err?.message || err);
    pushEntry('error', lastError.value);
  } finally {
    dropReasoningPlaceholder();
    const last = lastEntry();
    if (last && last.role === 'assistant' && last.pending) last.pending = false;
    streaming.value = false;
    round.value = 0;
    controller = null;
  }
}

export function useAiChat() {
  return {
    // 状态
    open,
    view,
    entries,
    streaming,
    round,
    input,
    lastError,
    readiness,
    ready,
    provider,
    canChat,
    // 行为
    toggle,
    send,
    cancel,
    reset,
    refreshConfig,
    applyConfig,
    clearConfig
  };
}
