/**
 * 空教室查询 · 纯逻辑层
 *
 * 这一层**不依赖 DOM**：只处理「占用记录 → 周课表矩阵 / 连续空闲时段」，
 * 因此可以被 Node 直接单元测试（见 scripts/test-classroom-schedule.mjs），
 * 而不必依赖浏览器或油猴运行时。
 *
 * 术语约定（与教务表单保持一致，极易混淆，务必区分）：
 *   · day     星期几，1=周一 … 7=周日（对应表单字段 week）
 *   · section 第几小节，1 起（对应课表行）
 *   · span    连续占用的小节数
 *
 * 单条占用：{ day: 3, start: 1, span: 2, label?: '高等数学' }
 */

/** 星期定义（1 起，与教务 week 参数一致） */
export const WEEKDAYS = [
  { value: 1, label: '周一', short: '一' },
  { value: 2, label: '周二', short: '二' },
  { value: 3, label: '周三', short: '三' },
  { value: 4, label: '周四', short: '四' },
  { value: 5, label: '周五', short: '五' },
  { value: 6, label: '周六', short: '六' },
  { value: 7, label: '周日', short: '日' }
];

export const DAY_COUNT = WEEKDAYS.length;

/** 默认小节数：教务课表常见 12 小节 */
export const SECTION_COUNT = 12;

/** 连续空闲的最小长度：默认过滤零散单节，只保留可自习的连堂 */
export const MIN_FREE_SPAN = 2;

export const CELL_STATE = {
  FREE: 'free',
  OCCUPIED: 'occupied'
};

function clampInt(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(number)));
}

function resolveRange(options = {}) {
  return {
    dayCount: clampInt(options.dayCount, 1, 7, DAY_COUNT),
    sectionCount: clampInt(options.sectionCount, 1, 24, SECTION_COUNT),
    minSpan: clampInt(options.minSpan, 1, 24, MIN_FREE_SPAN)
  };
}

/** 占用格子的集合键，便于 O(1) 判断某天某节是否被占 */
export function occupancyKey(day, section) {
  return `${day}-${section}`;
}

/**
 * 把占用记录展开为「已占用格子」集合
 * @param {Array<{day:number,start:number,span?:number}>} occupancy
 * @returns {Set<string>}
 */
export function expandOccupancy(occupancy) {
  const occupied = new Set();
  for (const item of Array.isArray(occupancy) ? occupancy : []) {
    if (!item) continue;
    const day = Math.trunc(Number(item.day));
    const start = Math.trunc(Number(item.start));
    const span = Math.max(1, Math.trunc(Number(item.span)) || 1);
    if (!Number.isFinite(day) || !Number.isFinite(start)) continue;
    for (let offset = 0; offset < span; offset += 1) {
      occupied.add(occupancyKey(day, start + offset));
    }
  }
  return occupied;
}

/**
 * 规整占用记录：裁剪越界、合并重叠、按天排序
 *
 * 教务返回的表格可能有 rowspan 跨节、也可能同一格出现重复课程，
 * 统一在这里收敛成不重叠的区间，后续矩阵与空闲计算只认这一种形态。
 */
export function normalizeOccupancy(occupancy, options = {}) {
  const { dayCount, sectionCount } = resolveRange(options);
  const byDay = new Map();

  for (const item of Array.isArray(occupancy) ? occupancy : []) {
    if (!item) continue;
    const day = Math.trunc(Number(item.day));
    if (!Number.isFinite(day) || day < 1 || day > dayCount) continue;

    const rawStart = Math.trunc(Number(item.start));
    if (!Number.isFinite(rawStart)) continue;
    const rawSpan = Math.max(1, Math.trunc(Number(item.span)) || 1);

    const start = Math.max(1, rawStart);
    const end = Math.min(sectionCount, rawStart + rawSpan - 1);
    if (end < start) continue;

    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day).push({
      day,
      start,
      span: end - start + 1,
      label: item.label ? String(item.label) : ''
    });
  }

  const result = [];
  for (const day of [...byDay.keys()].sort((a, b) => a - b)) {
    const segments = byDay.get(day).sort((a, b) => a.start - b.start);
    /** @type {Array<{day:number,start:number,span:number,label:string}>} */
    const merged = [];

    for (const segment of segments) {
      const last = merged[merged.length - 1];
      const lastEnd = last ? last.start + last.span - 1 : 0;
      if (last && segment.start <= lastEnd + 1) {
        // 重叠或紧邻：合并为一个区间，保留首个非空课程名
        const newEnd = Math.max(lastEnd, segment.start + segment.span - 1);
        last.span = newEnd - last.start + 1;
        if (!last.label && segment.label) last.label = segment.label;
      } else {
        merged.push({ ...segment });
      }
    }

    result.push(...merged);
  }

  return result;
}

/**
 * 单日内的连续空闲区间
 *
 * 整楼按周查询一次只返回「一个星期」的数据，此时不能直接用 findFreeSlots
 * （未查询的星期会被误判为整天空闲），必须限定在这一天内扫描。
 *
 * @param {Array} occupancy 占用记录
 * @param {number} day 星期几（1-7）
 * @param {Object} [options]
 * @param {number} [options.sectionCount=12]
 * @param {number} [options.minSpan=1] 单日视图中单节空档也有意义，默认不过滤
 * @returns {Array<{day:number,start:number,end:number,span:number}>}
 */
export function dayFreeRanges(occupancy, day, options = {}) {
  const { sectionCount } = resolveRange(options);
  const minSpan = clampInt(options.minSpan, 1, 24, 1);
  const occupied = expandOccupancy(normalizeOccupancy(occupancy, { dayCount: 7, sectionCount }));
  return scanFree(occupied, Number(day), sectionCount, minSpan);
}

/** 在某一星期内扫描连续空闲小节 */
function scanFree(occupied, day, sectionCount, minSpan) {
  const slots = [];
  let start = null;

  for (let section = 1; section <= sectionCount + 1; section += 1) {
    const isFree = section <= sectionCount && !occupied.has(occupancyKey(day, section));

    if (isFree && start === null) {
      start = section;
    } else if (!isFree && start !== null) {
      const span = section - start;
      if (span >= minSpan) {
        slots.push({ day, start, end: section - 1, span });
      }
      start = null;
    }
  }

  return slots;
}

/**
 * 找出连续空闲时段
 * @param {Array} occupancy 占用记录
 * @param {Object} [options]
 * @param {number} [options.dayCount=7]
 * @param {number} [options.sectionCount=12]
 * @param {number} [options.minSpan=2] 只返回长度 >= minSpan 的时段
 * @returns {Array<{day:number,start:number,end:number,span:number}>}
 */
export function findFreeSlots(occupancy, options = {}) {
  const { dayCount, sectionCount, minSpan } = resolveRange(options);
  const occupied = expandOccupancy(normalizeOccupancy(occupancy, { dayCount, sectionCount }));
  const slots = [];

  for (let day = 1; day <= dayCount; day += 1) {
    slots.push(...scanFree(occupied, day, sectionCount, minSpan));
  }

  return slots;
}

/**
 * 构建单元格合并后的矩阵
 *
 * 相邻且状态相同的格子合并为一段，方便直接渲染「一列若干色块」，
 * 避免逐小节生成几十个节点。
 *
 * @returns {Array<Array<{section:number, span:number, state:'free'|'occupied', label:string}>>}
 *          外层按星期 1..dayCount 排列
 */
export function buildDaySegments(occupancy, options = {}) {
  const { dayCount, sectionCount } = resolveRange(options);
  const normalized = normalizeOccupancy(occupancy, { dayCount, sectionCount });

  return Array.from({ length: dayCount }, (_, index) => {
    const day = index + 1;
    const dayItems = normalized.filter((item) => item.day === day);
    /** @type {Array<{section:number,span:number,state:string,label:string}>} */
    const segments = [];
    let cursor = 1;

    for (const item of dayItems) {
      if (item.start > cursor) {
        segments.push({ section: cursor, span: item.start - cursor, state: CELL_STATE.FREE, label: '' });
      }
      segments.push({
        section: item.start,
        span: item.span,
        state: CELL_STATE.OCCUPIED,
        label: item.label || ''
      });
      cursor = item.start + item.span;
    }

    if (cursor <= sectionCount) {
      segments.push({ section: cursor, span: sectionCount - cursor + 1, state: CELL_STATE.FREE, label: '' });
    }

    return segments;
  });
}

/** 某教室在本周内的占用小节总数 */
export function countOccupiedSections(occupancy, options = {}) {
  const { dayCount, sectionCount } = resolveRange(options);
  return expandOccupancy(normalizeOccupancy(occupancy, { dayCount, sectionCount })).size;
}

/** 格式化单个空闲时段，如「周一 3-4 节」 */
export function formatSlot(slot) {
  if (!slot) return '';
  const weekday = WEEKDAYS.find((item) => item.value === Number(slot.day));
  const name = weekday ? weekday.label : `周${slot.day}`;
  const start = Number(slot.start);
  const end = Number(slot.end ?? slot.start + (slot.span || 1) - 1);
  return start === end ? `${name} ${start} 节` : `${name} ${start}-${end} 节`;
}

/**
 * 汇总教室列表：补上矩阵、空闲时段与占用统计
 *
 * @param {Array<{id?:string,name:string,occupied?:Array}>} rooms
 * @param {Object} [options] dayCount / sectionCount / minSpan
 */
export function summarizeRooms(rooms, options = {}) {
  const range = resolveRange(options);
  return (Array.isArray(rooms) ? rooms : []).map((room) => {
    const occupancy = normalizeOccupancy(room?.occupied, range);
    const freeSlots = findFreeSlots(occupancy, range);
    return {
      ...room,
      occupied: occupancy,
      days: buildDaySegments(occupancy, range),
      freeSlots,
      freeSlotLabels: freeSlots.map(formatSlot),
      occupiedCount: countOccupiedSections(occupancy, range)
    };
  });
}

/**
 * 判断本周是否完全没有排课
 * 仅用于 UI 提示「整周空闲」，不作为推断数据
 */
export function isRoomFreeAllWeek(room, options = {}) {
  const range = resolveRange(options);
  return countOccupiedSections(room?.occupied, range) === 0;
}

/** 按空闲时段数量降序、占用数升序排列（空闲最多的教室排前面） */
export function sortByFreeTime(rooms) {
  return [...(Array.isArray(rooms) ? rooms : [])].sort((a, b) => {
    const spanDiff = totalFreeSpan(b) - totalFreeSpan(a);
    if (spanDiff !== 0) return spanDiff;
    return (a.occupiedCount || 0) - (b.occupiedCount || 0);
  });
}

function totalFreeSpan(room) {
  return (room?.freeSlots || []).reduce((sum, slot) => sum + (slot.span || 0), 0);
}

/** 解析「第 3-4 节 / 3-4 / 第3节」这类文案为区间，解析失败返回 null */
export function parseSectionRange(text) {
  const value = String(text ?? '');
  const rangeMatch = value.match(/(\d{1,2})\s*[-~至]\s*(\d{1,2})/);
  if (rangeMatch) {
    const start = Number(rangeMatch[1]);
    const end = Number(rangeMatch[2]);
    if (start >= 1 && end >= start) return { start, end, span: end - start + 1 };
    return null;
  }
  const singleMatch = value.match(/(\d{1,2})/);
  if (singleMatch) {
    const start = Number(singleMatch[1]);
    if (start >= 1) return { start, end: start, span: 1 };
  }
  return null;
}
