/**
 * AI 对话配置（三端语义对齐）
 *
 * 字段名与 TUI 的 `config.LLMSettings` 一致，因此同一个 JSON 可以被三端理解：
 *
 *   enabled / consent / base_url / model / api_key / temperature /
 *   max_tool_rounds / timeout_seconds / send_profile
 *
 * 存储位置按环境分流：
 *  - 桌面端：Rust 的 `/__app/ai/config`，落盘在 `%APPDATA%/edu.hrbust.betterhrbust/ai-config.json`
 *    （**独立于 storage.json**：storage 会被 `/__app/storage` 全量返回给页面，
 *     把 key 混进去等于让任意同源脚本一键读走）；
 *  - Web / 油猴：浏览器 localStorage 的 `better_hrbust_ai_config`（含 key，明文——
 *    浏览器没有等价的文件权限，设置页会明确提示这一点）。
 *
 * 无论哪端，GET 都**不回传明文 key**：只给 `hasKey` 与打码文案，
 * 前端输入框留空代表"不修改"。
 */

import { storageGetItem, storageSetItem, storageRemoveItem } from '@/services/storage.js';
import { isDesktopApp } from '@/utils/platform.js';

const LOCAL_KEY = 'better_hrbust_ai_config';

export const DEFAULT_AI_CONFIG = {
  enabled: false,
  consent: false,
  base_url: 'https://api.deepseek.com/v1',
  model: 'deepseek-flash',
  temperature: 0.3,
  max_tool_rounds: 8,
  timeout_seconds: 120,
  send_profile: false
};

/** 温度 / 工具轮次 / 超时的可选档位（与 TUI 设置页一致） */
export const TEMPERATURE_OPTIONS = [0.1, 0.3, 0.7, 1.0];
export const TOOL_ROUND_OPTIONS = [4, 8, 12, 16];
export const TIMEOUT_OPTIONS = [60, 120, 300];

function clampNumber(value, low, high, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < low || number > high) return fallback;
  return number;
}

/** 区间与兜底校验（与 Rust 侧 normalized() 同一套范围） */
export function normalizeAiConfig(raw) {
  const source = raw || {};
  return {
    enabled: !!source.enabled,
    consent: !!source.consent,
    base_url: String(source.base_url || '').trim() || DEFAULT_AI_CONFIG.base_url,
    model: String(source.model || '').trim() || DEFAULT_AI_CONFIG.model,
    temperature: clampNumber(source.temperature, 0, 2, DEFAULT_AI_CONFIG.temperature),
    max_tool_rounds: Math.round(
      clampNumber(source.max_tool_rounds, 1, 32, DEFAULT_AI_CONFIG.max_tool_rounds)
    ),
    timeout_seconds: Math.round(
      clampNumber(source.timeout_seconds, 5, 1800, DEFAULT_AI_CONFIG.timeout_seconds)
    ),
    send_profile: !!source.send_profile
  };
}

/** 从 base_url 取 host，用于"数据会发到哪里"的确认文案 */
export function providerHost(baseUrl) {
  const value = String(baseUrl || '').trim();
  if (!value) return '未配置的服务商';
  try {
    return new URL(value.includes('://') ? value : `https://${value}`).host;
  } catch {
    return value;
  }
}

/** 是否满足发送条件：顺序与文案照抄 TUI 的 Config.Ready */
export function aiReadiness(view) {
  const config = view?.config || DEFAULT_AI_CONFIG;
  if (!config.enabled) {
    return { ready: false, reason: 'AI 对话未启用：在设置页打开开关' };
  }
  if (!config.consent) {
    return { ready: false, reason: '尚未确认数据出网：勾选同意后才会发送任何内容' };
  }
  if (!String(config.base_url || '').trim()) {
    return { ready: false, reason: '缺少接口地址：填写 OpenAI 兼容服务的 /v1 根地址' };
  }
  if (!String(config.model || '').trim()) {
    return { ready: false, reason: '缺少模型名：例如 deepseek-flash、gpt-4o-mini' };
  }
  if (!view?.hasKey) {
    return { ready: false, reason: '缺少 API Key：在设置页填写后保存' };
  }
  return { ready: true, reason: '' };
}

/**
 * 读取配置。
 * @returns {Promise<{config: object, hasKey: boolean, apiKeyMasked: string, apiKey: string,
 *                    path: string, mode: 'desktop'|'local', available: boolean}>}
 */
export async function loadAiConfig() {
  if (isDesktopApp) {
    try {
      const res = await fetch('/__app/ai/config', {
        headers: { Accept: 'application/json' },
        cache: 'no-store'
      });
      if (res.ok) {
        const data = await res.json();
        return {
          config: normalizeAiConfig(data),
          hasKey: !!data.has_key,
          apiKeyMasked: data.api_key_masked || '',
          // 桌面端的明文 key 只存在于 Rust 进程，前端不持有
          apiKey: '',
          path: data.path || '',
          mode: 'desktop',
          available: true
        };
      }
    } catch {
      // dev 模式（Vite 无 Rust 服务）等场景：回退浏览器存储
    }
  }

  const raw = storageGetItem(LOCAL_KEY);
  let parsed = {};
  if (raw) {
    try {
      parsed = JSON.parse(raw) || {};
    } catch {
      parsed = {};
    }
  }
  const config = normalizeAiConfig(parsed);
  const apiKey = String(parsed.api_key || '');
  return {
    config,
    hasKey: !!apiKey,
    apiKeyMasked: apiKey ? '已设置（已隐藏）' : '未设置',
    apiKey,
    path: '浏览器本地存储',
    mode: 'local',
    available: true
  };
}

/**
 * 局部更新配置（缺省字段不改）。
 * `api_key` 为空串表示"不修改"，`clear_key: true` 才是清除。
 */
export async function saveAiConfig(patch) {
  const payload = { ...patch };
  if (!payload.clear_key && typeof payload.api_key === 'string' && !payload.api_key.trim()) {
    delete payload.api_key;
  }

  if (isDesktopApp) {
    try {
      const res = await fetch('/__app/ai/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error(`配置保存失败（HTTP ${res.status}）`);
      return await loadAiConfig();
    } catch (err) {
      // 桌面端保存失败不能悄悄落到 localStorage：用户会以为已经存进安全位置
      if (err instanceof TypeError) {
        // 网络层失败（dev 无本地服务）才允许回退
      } else {
        throw err;
      }
    }
  }

  const raw = storageGetItem(LOCAL_KEY);
  let current = {};
  if (raw) {
    try {
      current = JSON.parse(raw) || {};
    } catch {
      current = {};
    }
  }
  const next = { ...current, ...payload };
  if (payload.clear_key) {
    delete next.api_key;
    delete next.clear_key;
  }
  if (typeof next.api_key === 'string' && !next.api_key.trim()) {
    delete next.api_key;
  }
  storageSetItem(LOCAL_KEY, JSON.stringify(next));
  return loadAiConfig();
}

/** 清空 AI 配置（设置页"删除配置"用） */
export async function resetAiConfig() {
  if (isDesktopApp) {
    try {
      await fetch('/__app/ai/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          enabled: false,
          consent: false,
          base_url: DEFAULT_AI_CONFIG.base_url,
          model: DEFAULT_AI_CONFIG.model,
          temperature: DEFAULT_AI_CONFIG.temperature,
          max_tool_rounds: DEFAULT_AI_CONFIG.max_tool_rounds,
          timeout_seconds: DEFAULT_AI_CONFIG.timeout_seconds,
          send_profile: false,
          clear_key: true
        })
      });
      return loadAiConfig();
    } catch {
      // 落到下面的本地清理
    }
  }
  storageRemoveItem(LOCAL_KEY);
  return loadAiConfig();
}
