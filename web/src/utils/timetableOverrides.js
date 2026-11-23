/**
 * 课程表本地覆盖与增强纯逻辑（单周临时调课 + 手动实验/自定义课程）
 *
 * 设计原则：
 * - 不修改教务同步的原始课表数据（timetableCombine / timetableBase），以投影方式叠加，
 *   确保教务全量同步不覆盖本地修改，同时保持桌面端 CLI / TUI 的 storage.json 兼容；
 * - 单周临时调课与手动实验课仅作用于目标教学周，不污染其它教学周；
 * - 全部函数为无副作用纯函数，可在 Node ESM 环境下直接单测。
 */

import { isCourseActiveInWeek } from './courseWeeks.js';
import {
  BASE_SLOT_TIMES,
  COMBINE_SLOT_TIMES,
  parseSlotRangeText,
  parseTimeToMinutes
} from './periodTimes.js';

export const TIMETABLE_DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

export const CUSTOM_COURSE_CATEGORIES = [
  { value: 'lab', label: '实验课' },
  { value: 'custom', label: '自定义课程' }
];

function clampInt(value, min, max, fallback) {
  const num = Math.trunc(Number(value));
  if (!Number.isFinite(num)) return fallback;
  return Math.min(max, Math.max(min, num));
}

function createId(prefix) {
  const rand = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${Date.now().toString(36)}_${rand}`;
}

export function formatDaySectionLabel(day, period, viewMode = 'combine') {
  const dayLabel = TIMETABLE_DAY_NAMES[clampInt(day, 1, 7, 1) - 1];
  const unit = viewMode === 'base' ? '节' : '大节';
  return `${dayLabel} 第 ${period} ${unit}`;
}

/**
 * 在标准节次列表中寻找与 [startMinutes, endMinutes] 最匹配的节次序号。
 * 优先按重叠时长最大（同重叠取更早开始的节次）；若无重叠（例如午间），按区间中点距离最近。
 */
function findAnchorPeriod(slots, startMinutes, endMinutes) {
  let bestOverlapPeriod = null;
  let bestOverlapMinutes = 0;

  for (const slot of slots) {
    const range = parseSlotRangeText(slot.time);
    if (!range) continue;
    const overlap = Math.min(endMinutes, range.endMinutes) - Math.max(startMinutes, range.startMinutes);
    if (overlap > bestOverlapMinutes) {
      bestOverlapMinutes = overlap;
      bestOverlapPeriod = slot.period;
    }
  }

  if (bestOverlapPeriod !== null) return bestOverlapPeriod;

  const targetMid = (startMinutes + endMinutes) / 2;
  let nearestPeriod = slots[0]?.period || 1;
  let nearestDistance = Infinity;
  for (const slot of slots) {
    const range = parseSlotRangeText(slot.time);
    if (!range) continue;
    const slotMid = (range.startMinutes + range.endMinutes) / 2;
    const dist = Math.abs(targetMid - slotMid);
    if (dist < nearestDistance) {
      nearestDistance = dist;
      nearestPeriod = slot.period;
    }
  }
  return nearestPeriod;
}

/**
 * 将自定义起止时间（如 "14:00", "16:30"）锚定到网格节次（大节 1..6 与小节 1..12）。
 */
export function resolveCustomTimeAnchor(startTime, endTime) {
  const startMin = parseTimeToMinutes(startTime);
  const endMin = parseTimeToMinutes(endTime);
  if (startMin === null || endMin === null || endMin <= startMin) {
    return { combineSection: 1, baseSection: 1 };
  }
  return {
    combineSection: findAnchorPeriod(COMBINE_SLOT_TIMES, startMin, endMin),
    baseSection: findAnchorPeriod(BASE_SLOT_TIMES, startMin, endMin)
  };
}

/**
 * 计算一条自定义/实验课程在两个课表视图中的节次归属与精确起止时间。
 */
export function resolveCustomCourseSchedule(entry) {
  if (entry?.timeMode === 'custom') {
    const startTime = String(entry.startTime || '').trim();
    const endTime = String(entry.endTime || '').trim();
    const anchor = resolveCustomTimeAnchor(startTime, endTime);
    return {
      combineSection: anchor.combineSection,
      baseSection: anchor.baseSection,
      startTime,
      endTime,
      customTimeText: startTime && endTime ? `${startTime} - ${endTime}` : '',
      isCustomTime: true
    };
  }

  const slotType = entry?.slotType === 'base' ? 'base' : 'combine';
  if (slotType === 'base') {
    const startPeriod = clampInt(entry?.slotPeriod, 1, BASE_SLOT_TIMES.length, 1);
    const span = clampInt(entry?.slotSpan, 1, 4, 1);
    const endPeriod = Math.min(BASE_SLOT_TIMES.length, startPeriod + span - 1);
    const startRange = parseSlotRangeText(BASE_SLOT_TIMES[startPeriod - 1]?.time);
    const endRange = parseSlotRangeText(BASE_SLOT_TIMES[endPeriod - 1]?.time);
    const startTime = startRange?.start || '';
    const endTime = endRange?.end || '';
    const isStandardCombinePair = span === 2 && startPeriod % 2 === 1;
    return {
      combineSection: Math.ceil(startPeriod / 2),
      baseSection: startPeriod,
      startTime,
      endTime,
      customTimeText: isStandardCombinePair || !startTime || !endTime ? '' : `${startTime} - ${endTime}`,
      slotNote: span > 1 ? `第 ${startPeriod}-${endPeriod} 节` : `第 ${startPeriod} 节`,
      isCustomTime: !isStandardCombinePair
    };
  }

  const period = clampInt(entry?.slotPeriod, 1, COMBINE_SLOT_TIMES.length, 1);
  const range = parseSlotRangeText(COMBINE_SLOT_TIMES[period - 1]?.time);
  return {
    combineSection: period,
    baseSection: (period - 1) * 2 + 1,
    startTime: range?.start || '',
    endTime: range?.end || '',
    customTimeText: '',
    slotNote: `第 ${period} 大节`,
    isCustomTime: false
  };
}

/**
 * 规范化待保存的手动实验/自定义课程对象。
 */
export function normalizeCustomCourse(raw = {}) {
  const category = raw.category === 'custom' ? 'custom' : 'lab';
  const timeMode = raw.timeMode === 'custom' ? 'custom' : 'slot';
  const slotType = raw.slotType === 'base' ? 'base' : 'combine';
  const now = Date.now();
  return {
    id: String(raw.id || '').trim() || createId('custom'),
    kind: 'custom',
    category,
    courseName: String(raw.courseName || '').trim(),
    location: String(raw.location || '').trim(),
    teacher: String(raw.teacher || '').trim(),
    week: clampInt(raw.week, 1, 26, 1),
    day: clampInt(raw.day, 1, 7, 1),
    timeMode,
    slotType,
    slotPeriod: slotType === 'base'
      ? clampInt(raw.slotPeriod, 1, BASE_SLOT_TIMES.length, 1)
      : clampInt(raw.slotPeriod, 1, COMBINE_SLOT_TIMES.length, 1),
    slotSpan: slotType === 'base' ? clampInt(raw.slotSpan, 1, 4, 1) : 1,
    startTime: String(raw.startTime || '').trim(),
    endTime: String(raw.endTime || '').trim(),
    note: String(raw.note || '').trim(),
    createdAt: Number(raw.createdAt) || now,
    updatedAt: now
  };
}

/**
 * 将手动实验/自定义课程转换为课表单元格对象。
 */
export function customCourseToCell(entry, viewMode = 'combine') {
  const schedule = resolveCustomCourseSchedule(entry);
  const sectionIndex = viewMode === 'base' ? schedule.baseSection : schedule.combineSection;
  const categoryLabel = entry.category === 'lab' ? '实验' : '自定义';
  const sectionLabel = entry.timeMode === 'custom'
    ? `自定义时段 (${schedule.customTimeText || '未定'})`
    : (schedule.slotNote || (viewMode === 'base' ? `第 ${sectionIndex} 节` : `第 ${sectionIndex} 大节`));

  return {
    id: entry.id,
    courseId: entry.id,
    day: entry.day,
    sectionIndex,
    sectionLabel,
    courseName: entry.courseName,
    courseSeq: '',
    location: entry.location,
    teacher: entry.teacher,
    weeks: `第${entry.week}周`,
    hoursType: categoryLabel,
    isCustomCourse: true,
    category: entry.category,
    customTimeText: schedule.customTimeText,
    customStartTime: schedule.startTime,
    customEndTime: schedule.endTime,
    note: entry.note || '',
    customRecord: entry
  };
}

/**
 * 从某节课程（原教务课程或已调课程）提取稳定的原排课匹配指纹。
 */
export function buildRescheduleSourceMatch(cell, viewMode = 'combine') {
  if (cell?.rescheduleRecord?.sourceMatch) {
    return { ...cell.rescheduleRecord.sourceMatch };
  }
  const section = clampInt(cell?.sectionIndex, 1, 12, 1);
  const originCombineSection = viewMode === 'base' ? Math.ceil(section / 2) : clampInt(section, 1, 6, 1);
  const originBaseSections = viewMode === 'base'
    ? [section]
    : [(originCombineSection - 1) * 2 + 1, originCombineSection * 2];

  return {
    cellId: String(cell?.id || ''),
    courseId: String(cell?.courseId || ''),
    courseName: String(cell?.courseName || ''),
    courseSeq: String(cell?.courseSeq || ''),
    originDay: clampInt(cell?.day, 1, 7, 1),
    originCombineSection,
    originBaseSections,
    originLocation: String(cell?.location || ''),
    originTeacher: String(cell?.teacher || '')
  };
}

/**
 * 校验调整后的目标位置必须与原排课位置不同。
 */
export function isDifferentRescheduleTarget(sourceMatch, targetDay, targetPeriod, viewMode = 'combine') {
  if (!sourceMatch) return false;
  const day = clampInt(targetDay, 1, 7, 1);
  const period = clampInt(targetPeriod, 1, viewMode === 'base' ? 12 : 6, 1);
  if (day !== Number(sourceMatch.originDay)) return true;
  if (viewMode === 'base') {
    const originBases = Array.isArray(sourceMatch.originBaseSections) ? sourceMatch.originBaseSections : [];
    if (originBases.length > 0) return !originBases.includes(period);
    return Math.ceil(period / 2) !== Number(sourceMatch.originCombineSection);
  }
  return period !== Number(sourceMatch.originCombineSection);
}

/**
 * 规范化待保存的单周临时调课记录。
 */
export function normalizeTimetableReschedule(raw = {}) {
  const sourceMatch = raw.sourceMatch || {};
  const mode = raw.targetSectionMode === 'base' ? 'base' : 'combine';
  const targetCombineSection = mode === 'base'
    ? Math.ceil(clampInt(raw.targetBaseSection, 1, 12, 1) / 2)
    : clampInt(raw.targetCombineSection, 1, 6, 1);
  const targetBaseSection = mode === 'base'
    ? clampInt(raw.targetBaseSection, 1, 12, 1)
    : (targetCombineSection - 1) * 2 + 1;
  const now = Date.now();

  return {
    id: String(raw.id || '').trim() || createId('resched'),
    week: clampInt(raw.week, 1, 26, 1),
    sourceMatch: {
      cellId: String(sourceMatch.cellId || ''),
      courseId: String(sourceMatch.courseId || ''),
      courseName: String(sourceMatch.courseName || ''),
      courseSeq: String(sourceMatch.courseSeq || ''),
      originDay: clampInt(sourceMatch.originDay, 1, 7, 1),
      originCombineSection: clampInt(sourceMatch.originCombineSection, 1, 6, 1),
      originBaseSections: Array.isArray(sourceMatch.originBaseSections) && sourceMatch.originBaseSections.length > 0
        ? sourceMatch.originBaseSections.map(n => clampInt(n, 1, 12, 1))
        : [1, 2],
      originLocation: String(sourceMatch.originLocation || ''),
      originTeacher: String(sourceMatch.originTeacher || '')
    },
    targetDay: clampInt(raw.targetDay, 1, 7, 1),
    targetSectionMode: mode,
    targetCombineSection,
    targetBaseSection,
    location: String(raw.location || '').trim(),
    teacher: String(raw.teacher ?? sourceMatch.originTeacher ?? '').trim(),
    note: String(raw.note || '').trim(),
    createdAt: Number(raw.createdAt) || now,
    updatedAt: now
  };
}

/**
 * 判断教务课表单元格是否匹配某条调课记录的源课程。
 */
export function matchesRescheduleSource(cell, sourceMatch, viewMode = 'combine') {
  if (!cell || !sourceMatch) return false;
  if (Number(cell.day) !== Number(sourceMatch.originDay)) return false;

  const sectionMatch = viewMode === 'base'
    ? (Array.isArray(sourceMatch.originBaseSections) && sourceMatch.originBaseSections.length > 0
      ? sourceMatch.originBaseSections.includes(Number(cell.sectionIndex))
      : Math.ceil(Number(cell.sectionIndex) / 2) === Number(sourceMatch.originCombineSection))
    : Number(cell.sectionIndex) === Number(sourceMatch.originCombineSection);
  if (!sectionMatch) return false;

  if (viewMode === 'combine' && sourceMatch.cellId && cell.id) {
    return cell.id === sourceMatch.cellId && cell.courseName === sourceMatch.courseName;
  }
  if (cell.courseName !== sourceMatch.courseName) return false;
  if (sourceMatch.courseSeq && cell.courseSeq) {
    return String(cell.courseSeq) === String(sourceMatch.courseSeq);
  }
  if (sourceMatch.courseId && cell.courseId) {
    return String(cell.courseId) === String(sourceMatch.courseId);
  }
  return true;
}

/**
 * 计算指定教学周与视图模式下的最终有效课表单元格列表。
 *
 * - 教务原单元格若在该周被调出：原位置变为 `isMovedOut: true` 占位卡（小节连堂只发一张占位），
 *   并在目标位置插入 `isRescheduled: true` 调课卡；
 * - 属于该周的手动实验/自定义课程会一并并入。
 */
export function buildEffectiveTimetableCells({
  rawCells = [],
  week,
  viewMode = 'combine',
  reschedules = [],
  customCourses = []
}) {
  const targetWeek = Number(week);
  const weekReschedules = (reschedules || []).filter(r => r && Number(r.week) === targetWeek);
  const weekCustomCourses = (customCourses || []).filter(c => c && Number(c.week) === targetWeek);

  const triggeredReschedules = new Map();
  const matchedSourceCells = new Map();
  const cells = [];

  for (const cell of rawCells || []) {
    if (!cell) continue;
    const activeThisWeek = isCourseActiveInWeek(cell, targetWeek);
    const matched = activeThisWeek
      ? weekReschedules.find(r => matchesRescheduleSource(cell, r.sourceMatch, viewMode))
      : null;

    if (!matched) {
      cells.push(cell);
      continue;
    }

    if (!triggeredReschedules.has(matched.id)) {
      triggeredReschedules.set(matched.id, matched);
      matchedSourceCells.set(matched.id, cell);
      const targetPeriod = viewMode === 'base' ? matched.targetBaseSection : matched.targetCombineSection;
      cells.push({
        ...cell,
        id: `${cell.id || cell.courseName}__moved_out__${matched.id}`,
        isMovedOut: true,
        rescheduleId: matched.id,
        rescheduleRecord: matched,
        movedToLabel: formatDaySectionLabel(matched.targetDay, targetPeriod, viewMode)
      });
    }
    // 小节视图里一门大节可能占两行：第一行发占位卡，后续同源行隐藏
  }

  for (const record of triggeredReschedules.values()) {
    const originCell = matchedSourceCells.get(record.id);
    const targetSection = viewMode === 'base' ? record.targetBaseSection : record.targetCombineSection;
    const originPeriod = viewMode === 'base'
      ? (record.sourceMatch.originBaseSections?.[0] || (record.sourceMatch.originCombineSection - 1) * 2 + 1)
      : record.sourceMatch.originCombineSection;

    cells.push({
      ...(originCell || {}),
      id: `reschedule_${record.id}`,
      courseId: originCell?.courseId || record.sourceMatch.courseId,
      courseName: originCell?.courseName || record.sourceMatch.courseName,
      courseSeq: originCell?.courseSeq || record.sourceMatch.courseSeq,
      day: record.targetDay,
      sectionIndex: targetSection,
      sectionLabel: viewMode === 'base' ? `第 ${targetSection} 节` : `第 ${targetSection} 大节`,
      location: record.location,
      teacher: record.teacher || originCell?.teacher || record.sourceMatch.originTeacher || '',
      weeks: `第${record.week}周`,
      hoursType: originCell?.hoursType || '临时调课',
      isRescheduled: true,
      rescheduleId: record.id,
      rescheduleRecord: record,
      originSummary: `${formatDaySectionLabel(record.sourceMatch.originDay, originPeriod, viewMode)} · ${record.sourceMatch.originLocation || '待定教室'}`,
      note: record.note || ''
    });
  }

  for (const custom of weekCustomCourses) {
    cells.push(customCourseToCell(custom, viewMode));
  }

  return cells;
}
