/**
 * 命令行模式的数据源（设置页"命令行模式"卡片使用）
 *
 * 两个端点都由桌面端本地服务（Rust）提供，与 `BetterHRBUSTCLI` 共用同一套解析逻辑：
 *  - `/__app/cli`       命令行的实时状态（路径、登录态、数据量）
 *  - `/__app/cli/skill` 内嵌的 SKILL 文本（预览 + 一键复制）
 *
 * 网页 / 油猴 / `tauri dev`（Vite 5173，无本地服务）拿不到这两个端点，
 * 因此这里的约定是**失败返回 null**，由卡片降级成静态文档，而不是抛错阻塞页面。
 */

/** 读取命令行状态；不可用时返回 null */
export async function fetchCliInfo() {
  try {
    const res = await fetch('/__app/cli', {
      headers: { Accept: 'application/json' },
      cache: 'no-store'
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

/** 读取 SKILL 文本；不可用时返回空串 */
export async function fetchCliSkill() {
  try {
    const res = await fetch('/__app/cli/skill', { cache: 'no-store' });
    if (!res.ok) return '';
    return await res.text();
  } catch {
    return '';
  }
}
