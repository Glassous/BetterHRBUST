/**
 * 课程动态配色（每门课独占一种颜色）
 *
 * 策略：
 * - 以「课程名称」为主键（同一门课在课表、概览等页面颜色一致，名称缺失时退回课序号 / 课程 ID / 排课 ID）；
 * - 模块级注册表为每门课分配独立色相槽位，色相以黄金角 (137.508°) 序列展开，
 *   槽位间隔最大化、互不重复 —— 有多少门课就有多少种颜色，不会出现不同课程共用同色；
 * - 颜色通过 CSS 变量 --course-h 注入，样式规则见 assets/main.css 中的
 *   .course-card / .course-dot（明暗两套主题自动适配），颜色数量不受固定色板限制。
 *
 * 建议在课表数据加载 / 同步完成后调用 registerCourseColors() 批量注册，
 * 使颜色分配与数据源顺序无关且跨页面稳定。
 *
 * 非当前教学周的课程不参与取色，统一使用中性灰 COURSE_MUTED 弱化显示。
 */

const GOLDEN_ANGLE = 137.508;

/** 课程主键 → 色相槽位序号（一旦分配即稳定不变，重新登录 / 刷新保持一致） */
const slotRegistry = new Map();

function courseKeyOf(course) {
  return String(course?.courseName || course?.courseSeq || course?.courseId || course?.id || '')
    .replace(/\s+/g, '')
    .trim();
}

function slotOf(key) {
  let slot = slotRegistry.get(key);
  if (slot === undefined) {
    slot = slotRegistry.size;
    slotRegistry.set(key, slot);
  }
  return slot;
}

function hueOfSlot(slot) {
  return Math.round((slot * GOLDEN_ANGLE) % 360);
}

/**
 * 批量注册课程配色（在课表数据加载 / 同步完成后调用）。
 * 按课程名称的码点顺序注册，使颜色分配与数据源顺序无关；已注册课程不受影响，可安全重复调用。
 * @param {Array<{courseName?: string, courseSeq?: string, courseId?: string}>} courses
 */
export function registerCourseColors(courses) {
  const keys = new Set();
  for (const course of courses || []) {
    const key = courseKeyOf(course);
    if (key) keys.add(key);
  }
  [...keys]
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .forEach(slotOf);
}

/**
 * 非当前教学周课程的统一样式：中性灰 + 半透明，弱化但保持可点击。
 */
export const COURSE_MUTED =
  'bg-[var(--bg-subtle)] text-[var(--ink-muted)] border-[var(--border-hairline)] opacity-80 hover:opacity-100';

/**
 * 取某门课程的配色。
 * @param {{ courseName?: string, courseSeq?: string, courseId?: string }} course
 * @returns {{ hue: number, style: { '--course-h': string } }}
 */
export function getCourseColor(course) {
  const key = courseKeyOf(course);
  const hue = key ? hueOfSlot(slotOf(key)) : 0;
  return { hue, style: { '--course-h': String(hue) } };
}
