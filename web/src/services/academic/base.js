/**
 * 教务在线地址配置（三端共用一套语义）
 *
 * 存储键 `better_hrbust_academic_base` 落在平台存储门面之后（storage.js）：
 *   - 桌面端 → Rust 的 storage.json，本地反代每次请求都读同一个键决定上游；
 *   - 油猴  → GM 存储；
 *   - Web  → localStorage。
 *
 * 值为空（键不存在）表示"沿用官方默认"，此时前端请求与改造前逐字节一致：
 * 相对 `/academic/...` 交给同源代理。只有显式自定义才切换为绝对地址。
 */

import { storageGetItem, storageRemoveItem, storageSetItem } from '../storage.js';
import { OFFICIAL_BASE, normalizeAcademicBase, setAcademicBase } from './client.js';

/** 与 Rust / TUI 共用的键名，改动需三端同步 */
export const ACADEMIC_BASE_KEY = 'better_hrbust_academic_base';

/** 官方默认地址（只用于展示，请求仍走同源 `/academic/`） */
export const OFFICIAL_ACADEMIC_BASE = OFFICIAL_BASE;

const listeners = new Set();

let state = {
  /** 归一化后的自定义地址；'' = 官方默认 */
  base: '',
  /** 展示用地址（自定义优先） */
  display: OFFICIAL_ACADEMIC_BASE,
  /** 读取到非法值时的原因（不阻断使用，只是回退默认） */
  invalidReason: ''
};

/** 当前配置快照（只在 base.js 内更新，对外只读副本） */
export function academicBaseState() {
  return { ...state };
}

/** 订阅变化；返回取消订阅函数。订阅时立即回调一次当前值。 */
export function subscribeAcademicBase(listener) {
  if (typeof listener !== 'function') return () => {};
  listeners.add(listener);
  listener(academicBaseState());
  return () => listeners.delete(listener);
}

function emit() {
  const snapshot = academicBaseState();
  for (const listener of [...listeners]) {
    try {
      listener(snapshot);
    } catch {
      // 单个订阅者异常不影响其他订阅者（设置页与登录页各订一份）
    }
  }
}

/**
 * 应用地址（不写存储）：读取 raw 或存储中的值，归一化失败则回退官方默认。
 * 返回最新快照，invalidReason 非空时供设置页提示"已保存的地址不可用"。
 */
export function applyAcademicBase(raw) {
  const stored = raw === undefined ? storageGetItem(ACADEMIC_BASE_KEY) : raw;
  let normalized = '';
  let invalidReason = '';
  try {
    normalized = normalizeAcademicBase(stored);
  } catch (err) {
    normalized = '';
    invalidReason = err?.message || '教务地址不可用';
  }
  setAcademicBase(normalized);
  state = {
    base: normalized,
    display: normalized || OFFICIAL_ACADEMIC_BASE,
    invalidReason
  };
  emit();
  return academicBaseState();
}

/**
 * 保存自定义地址并立即生效；非法输入抛错（调用方展示错误，不落盘）。
 * 传空字符串等价于恢复默认。
 */
export function saveAcademicBase(raw) {
  const normalized = normalizeAcademicBase(raw);
  if (!normalized) return resetAcademicBase();
  storageSetItem(ACADEMIC_BASE_KEY, normalized);
  return applyAcademicBase(normalized);
}

/** 恢复官方默认：删除存储键，请求回到同源 `/academic/` */
export function resetAcademicBase() {
  storageRemoveItem(ACADEMIC_BASE_KEY);
  return applyAcademicBase('');
}

// 跨标签页同步：另一个标签改了地址后，本标签的请求目标立即跟随
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', (event) => {
    if (event.key !== ACADEMIC_BASE_KEY) return;
    applyAcademicBase(event.newValue ?? '');
  });
}

// 模块加载即落地一次（boot.js 在挂载前还会再调用一次，桌面端此时存储已水合）
if (typeof window !== 'undefined') {
  applyAcademicBase();
}
