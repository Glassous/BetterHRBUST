/**
 * 本科教学进程表的纯函数层：图案解码、阶段合并、按图案分组与本周快照。
 *
 * 数据层只存「每周单元格原始字形」，本模块负责把逐周符号压缩成人能读的阶段区间：
 *   ['', '', '∨', '◎', '◎', '⊕/：', '＝', '＝']
 *   → 常规教学 1–2 / 运动会 3 / 技能训练 4–5 / 综合实践与考试各占第 6 周一半 / 假期 7–8
 */
import { cellParts } from './teachingProcessSymbols.js';

/** 解码 "|a|b|c" → ['a','b','c']；首字符是哨兵，用来保留开头空单元格 */
export function decodePattern(encoded) {
  if (typeof encoded !== 'string' || !encoded.startsWith('|')) return [];
  return encoded.slice(1).split('|');
}

function isContiguous(end, start) {
  if (end.week === start.week) return end.idx + 1 === start.idx;
  return end.week + 1 === start.week && end.idx === end.count - 1 && start.idx === 0;
}

function segmentStartValue(node) {
  return node.week - 1 + (node.count > 1 ? node.idx / node.count : 0);
}

function segmentEndValue(node) {
  return node.week - 1 + (node.count > 1 ? (node.idx + 1) / node.count : 1);
}

/** 把逐周单元格合并成阶段区间；斜杠周的半周归属会精确到 idx/count */
export function buildSegments(cells) {
  const segments = [];
  (cells || []).forEach((cell, weekIndex) => {
    const parts = cellParts(cell);
    parts.forEach((part, idx) => {
      const node = { week: weekIndex + 1, idx, count: parts.length };
      const last = segments[segments.length - 1];
      if (last && last.key === part.key && last.note === part.note && isContiguous(last.end, node)) {
        last.end = node;
        return;
      }
      segments.push({
        key: part.key,
        label: part.label,
        short: part.short,
        group: part.group,
        note: part.note,
        start: node,
        end: node
      });
    });
  });

  return segments.map(segment => {
    const startWeek = segment.start.week;
    const endWeek = segment.end.week;
    const startValue = segmentStartValue(segment.start);
    const endValue = segmentEndValue(segment.end);
    return {
      ...segment,
      startWeek,
      endWeek,
      startValue,
      endValue,
      weekCount: Math.round((endValue - startValue) * 100) / 100,
      partial: segment.start.idx > 0 || segment.end.idx < segment.end.count - 1
    };
  });
}

function halfLabel(node) {
  if (node.count <= 1) return '';
  return node.idx === 0 ? '前半' : node.idx === 1 ? '后半' : `第 ${node.idx + 1} 段`;
}

/** 「第 12–19 周」「第 19 周后半」 */
export function formatWeekRange(segment) {
  if (segment.startWeek === segment.endWeek) {
    if (!segment.partial) return `第 ${segment.startWeek} 周`;
    return `第 ${segment.startWeek} 周${halfLabel(segment.start)}`;
  }
  if (!segment.partial) return `第 ${segment.startWeek}–${segment.endWeek} 周`;
  if (segment.start.idx > 0) return `第 ${segment.startWeek}–${segment.endWeek} 周（首周${halfLabel(segment.start)}）`;
  return `第 ${segment.startWeek}–${segment.endWeek} 周（末周${halfLabel(segment.end)}）`;
}

/**
 * 一行摘要：「军训 3–4 周 · 理论教学 5–18 周 · 考试 19 周 · 假期 20–26 周」。
 * 阶段过多时保留开头与最后一个阶段，中间用省略号——放假时间往往在最后，不能丢。
 */
export function summaryOf(segments, { max = 4, includeIdle = false } = {}) {
  const items = (segments || []).filter(segment => includeIdle || segment.group !== 'idle');
  if (!items.length) return '全学期未安排';
  const format = segment => `${segment.short} ${formatWeekRange(segment).replace(/（.*）$/, '')}`;
  if (items.length <= max) return items.map(format).join(' · ');
  const head = items.slice(0, max - 1).map(format);
  return [...head, '…', format(items[items.length - 1])].join(' · ');
}

/** 日期 → 教学周序号；不在任何一周内返回 null */
export function weekIndexOn(weeks, date = new Date()) {
  if (!Array.isArray(weeks) || !weeks.length) return null;
  const pad = value => String(value).padStart(2, '0');
  const iso = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  const hit = weeks.find(week => iso >= week.mon && iso <= week.sun);
  return hit ? hit.w : null;
}

/** 指定周次的逐班级阶段统计，按班级数降序 */
export function snapshotAt(classes, patterns, week) {
  const counts = new Map();
  for (const item of classes || []) {
    const cells = decodePattern(patterns[item.patternIndex]);
    const parts = cellParts(cells[week - 1]);
    const seen = new Set();
    for (const part of parts) {
      if (seen.has(part.key)) continue;
      seen.add(part.key);
      const entry = counts.get(part.key) || { key: part.key, label: part.short, group: part.group, count: 0 };
      entry.count += 1;
      counts.set(part.key, entry);
    }
  }
  return [...counts.values()].sort((left, right) => right.count - left.count);
}

/**
 * 当前阶段概览：现在处于哪一段、还有几周、下一段是什么、什么时候放假。
 * 视图顶部用它回答「我现在在什么阶段」，不再让人自己对着色带猜。
 */
export function stageInsights(segments, week, weekCount) {
  const list = segments || [];
  const active = week ? list.filter(segment => week >= segment.startWeek && week <= segment.endWeek) : [];
  const current = active.find(segment => segment.group !== 'idle') || active[0] || null;
  const next =
    list.find(segment => segment.startWeek > week && segment.group !== 'idle' && segment.key !== 'vacation') || null;
  const vacation = list.find(segment => segment.key === 'vacation' && segment.endWeek >= week) || null;

  return {
    current,
    /** 当前阶段还剩几周（含本周） */
    currentRemaining: current ? current.endWeek - week + 1 : 0,
    /** 当前阶段已经过去几周（含本周） */
    currentElapsed: current ? week - current.startWeek + 1 : 0,
    next,
    nextIn: next ? next.startWeek - week : 0,
    vacation,
    vacationIn: vacation ? vacation.startWeek - week : 0,
    /** 本学期还剩几周（含本周） */
    remaining: weekCount ? weekCount - week + 1 : 0
  };
}

/** 阶段相对当前周的位置：已结束 / 进行中 / 未开始 */
export function stageStatusOf(segment, week) {
  if (!week) return 'unknown';
  if (week > segment.endWeek) return 'past';
  if (week < segment.startWeek) return 'upcoming';
  return 'current';
}

/**
 * 按进程图案分组：进度完全相同的班级合并成一组。
 * 排序：我的班级所在组 → 班级数多 → 首个有效阶段更早。
 */
export function groupByPattern(classes, patterns, myClassName = '') {
  const map = new Map();
  for (const item of classes || []) {
    const key = item.patternIndex;
    if (!map.has(key)) {
      const cells = decodePattern(patterns[key]);
      map.set(key, { patternIndex: key, cells, segments: buildSegments(cells), classes: [] });
    }
    map.get(key).classes.push(item);
  }

  const groups = [...map.values()].map(group => {
    const firstActive = group.segments.find(segment => segment.group !== 'idle');
    return {
      ...group,
      summary: summaryOf(group.segments),
      hasMine: Boolean(myClassName) && group.classes.some(item => item.name === myClassName),
      firstWeek: firstActive ? firstActive.startWeek : Number.MAX_SAFE_INTEGER
    };
  });

  return groups.sort(
    (left, right) =>
      Number(right.hasMine) - Number(left.hasMine) ||
      right.classes.length - left.classes.length ||
      left.firstWeek - right.firstWeek
  );
}
