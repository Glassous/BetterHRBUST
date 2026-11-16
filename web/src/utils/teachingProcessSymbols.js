/**
 * 本科教学进程表的符号字典。
 *
 * 教务处原表用单个字形表示整周的教学安排（□理论教学、★军训、＝假期……），
 * 同一含义在不同年份会用不同的字形（Ä 与 ◆ 都是电工电子实习，：与 : 都是考试），
 * 第二学期还常写成「⊕/：」表示这一周跨两个阶段。本模块把这些写法归一化成语义 key，
 * 数据层（shared/teaching-process.json）只存原始字形，解释全部留在这里。
 */

/** 阶段分组：hue 为空表示用中性色（CSS 变量处理） */
export const SYMBOL_GROUPS = {
  theory: { label: '理论教学', hue: 214 },
  design: { label: '设计与论文', hue: 268 },
  practice: { label: '实习与实践', hue: 158 },
  exam: { label: '考试', hue: 352 },
  ceremony: { label: '入学与毕业教育', hue: 38 },
  break: { label: '假期与节假日', hue: null },
  idle: { label: '常规教学', hue: null },
  unknown: { label: '未收录符号', hue: null }
};

export const GROUP_ORDER = ['theory', 'design', 'practice', 'exam', 'ceremony', 'break', 'idle', 'unknown'];

/** 原表留空通常表示「按课表正常上课」，少数行是没写完，页面上统一说明即可 */

/** 原表出现过、但教务处图例从未给出含义的字形：不猜测，按原样展示 */
export const UNDOCUMENTED_GLYPHS = ['♥', '❉', '[1]'];

/**
 * 语义字典。glyphs 按「匹配优先级」排列，多字字形必须在单字之前（'::' 先于 ':'）。
 * hue 用于同一分组内的细分色（实习与实践类阶段多，靠色相区分相邻阶段）。
 */
export const SYMBOL_DICT = {
  theory: { label: '理论教学', short: '理论教学', group: 'theory', glyphs: ['□', '口'] },
  courseDesign: { label: '课程设计', short: '课程设计', group: 'design', glyphs: ['※'] },
  thesis: { label: '毕业设计', short: '毕业设计', group: 'design', glyphs: ['﹡', '＊', '*'] },
  yearDesign: { label: '学年设计（论文）', short: '学年设计', group: 'design', glyphs: ['■'] },
  research: { label: '科研训练', short: '科研训练', group: 'practice', hue: 152, glyphs: ['::', '²', '§'] },
  cognition: { label: '认识实习', short: '认识实习', group: 'practice', hue: 176, glyphs: ['©'] },
  eePractice: { label: '电工电子实习', short: '电工电子实习', group: 'practice', hue: 188, glyphs: ['Ä', '◆'] },
  computer: { label: '计算机实践', short: '计算机实践', group: 'practice', hue: 191, glyphs: ['#', '﹟', '＃'] },
  metalwork: { label: '金工实习/工程训练', short: '工程训练', group: 'practice', hue: 163, glyphs: ['╳', 'Ⅹ', 'X'] },
  production: { label: '生产实习', short: '生产实习', group: 'practice', hue: 145, glyphs: ['△'] },
  enrollment: { label: '入学教育', short: '入学教育', group: 'ceremony', glyphs: ['○'] },
  exam: { label: '考试', short: '考试', group: 'exam', glyphs: ['：', ':'] },
  military: { label: '军训', short: '军训', group: 'ceremony', glyphs: ['★', '☆'] },
  graduation: { label: '毕业教育', short: '毕业教育', group: 'ceremony', glyphs: ['＋', '+'] },
  vacation: { label: '假期', short: '假期', group: 'break', glyphs: ['＝', '='] },
  idle: { label: '常规教学', short: '常规教学', group: 'idle', glyphs: ['—', '－', '-', '–'] },
  sports: { label: '运动会、节假日', short: '运动会/节假日', group: 'break', glyphs: ['∨', 'V'] },
  offCampus: { label: '外地教学', short: '外地教学', group: 'practice', hue: 190, glyphs: ['⊙'] },
  skills: { label: '技能训练', short: '技能训练', group: 'practice', hue: 184, glyphs: ['◎'] },
  coursePractice: { label: '课程实践', short: '课程实践', group: 'practice', hue: 168, glyphs: ['▲'] },
  majorIntern: { label: '专业实习', short: '专业实习', group: 'practice', hue: 158, glyphs: ['◇'] },
  majorPractice: { label: '专业实践', short: '专业实践', group: 'practice', hue: 180, glyphs: ['∮'] },
  language: { label: '语言实践', short: '语言实践', group: 'practice', hue: 170, glyphs: ['¤'] },
  comprehensive: { label: '综合实践（自主学习）', short: '综合实践', group: 'practice', hue: 172, glyphs: ['⊕'] },
  innovation: { label: '创新创业实践', short: '创新创业实践', group: 'practice', hue: 136, glyphs: ['∞'] }
};

const GLYPH_INDEX = Object.entries(SYMBOL_DICT)
  .flatMap(([key, meta]) => meta.glyphs.map(glyph => ({ glyph, key })))
  .sort((left, right) => right.glyph.length - left.glyph.length);

export const UNKNOWN_KEY = 'unknown';

const UNKNOWN_META = { label: '未收录符号', short: '未收录', group: 'unknown' };

/** 去掉空白与全角斜杠，保留其余字形（含 ² § 这类变体，由字典解释） */
function normalizeCellText(raw) {
  return String(raw ?? '')
    .replace(/[\s\u00a0\u3000]+/g, '')
    .replace(/／/g, '/');
}

function symbolMeta(key) {
  return SYMBOL_DICT[key] || UNKNOWN_META;
}

export function groupMeta(group) {
  return SYMBOL_GROUPS[group] || SYMBOL_GROUPS.unknown;
}

/** 阶段色块：色相来自分组，中性组用专门的中性/虚线样式 */
export function stageClass(group) {
  if (group === 'unknown') return 'stage-block stage-block--unknown';
  if (group === 'idle') return 'stage-block stage-block--idle';
  if (group === 'break') return 'stage-block stage-block--break';
  return 'stage-block';
}

export function stageStyle(group, key = '') {
  const hue = SYMBOL_DICT[key]?.hue ?? groupMeta(group).hue;
  return hue === null ? {} : { '--stage-h': hue };
}

/** 图例/统计里的小圆点：色块工具类带圆角，圆点单独处理避免被覆盖 */
export function stageDotClass(group) {
  if (group === 'unknown') return 'bg-[var(--sem-warning)]';
  if (group === 'break') return 'bg-[var(--border-strong)]';
  if (group === 'idle') return 'border border-dashed border-[var(--border-strong)]';
  return '';
}

export function stageDotStyle(group, key = '') {
  const hue = SYMBOL_DICT[key]?.hue ?? groupMeta(group).hue;
  return hue === null ? {} : { backgroundColor: `hsl(${hue} 58% 48%)` };
}

function makePart(key, note = '') {
  const meta = symbolMeta(key);
  return {
    key: key === UNKNOWN_KEY ? UNKNOWN_KEY : key,
    label: key === UNKNOWN_KEY ? note || UNKNOWN_META.label : meta.label,
    short: key === UNKNOWN_KEY ? note || UNKNOWN_META.short : meta.short || meta.label,
    group: meta.group,
    note: key === UNKNOWN_KEY ? '' : note
  };
}

function matchGlyph(text, index) {
  return GLYPH_INDEX.find(item => text.startsWith(item.glyph, index)) || null;
}

/** 解析单个斜杠分段：允许连写（□⊕、◎：），字形后的中文并入 note */
function parseSegment(rawSegment) {
  const text = rawSegment.replace(/^[（(]+/, '').replace(/[）)]+$/, '');
  if (!text) return [];

  const parts = [];
  let buffer = '';
  let index = 0;
  const flush = () => {
    if (!buffer) return;
    if (parts.length) parts[parts.length - 1].note += buffer;
    else parts.push(makePart(UNKNOWN_KEY, buffer));
    buffer = '';
  };

  while (index < text.length) {
    const matched = matchGlyph(text, index);
    if (!matched) {
      buffer += text[index];
      index += 1;
      continue;
    }
    flush();
    parts.push(makePart(matched.key));
    index += matched.glyph.length;
  }
  flush();
  return parts;
}

/**
 * 解析单个单元格。
 * 返回的 parts 按该周内的先后顺序排列；空单元格视为「未安排」。
 */
export function parseCell(raw) {
  const text = normalizeCellText(raw);
  if (!text) return { parts: [], unknown: [] };

  const rawSegments = text.split('/');
  const segments = [];
  for (const segment of rawSegments) {
    // 极少数历史表格会出现三段以上，合并多余部分，避免图例失真
    if (segments.length >= 3) segments[2] += '/' + segment;
    else segments.push(segment);
  }

  const parts = [];
  const unknown = [];
  for (const segment of segments) {
    const parsed = parseSegment(segment);
    if (parsed.length === 0) {
      parts.push(makePart('idle'));
      continue;
    }
    for (const part of parsed) {
      if (part.key === UNKNOWN_KEY) unknown.push(part.label);
      parts.push(part);
    }
  }
  return { parts, unknown };
}

/** 某一周的有效分段：空单元格按「未安排」处理，供时间轴渲染使用 */
export function cellParts(raw) {
  const { parts } = parseCell(raw);
  return parts.length ? parts : [makePart('idle')];
}

/** 图例分组：把字典按组聚合，供图例卡渲染 */
export function legendGroups() {
  return GROUP_ORDER.filter(group => group !== UNKNOWN_KEY).map(group => ({
    group,
    ...groupMeta(group),
    symbols: Object.entries(SYMBOL_DICT)
      .filter(([, meta]) => meta.group === group)
      .map(([key, meta]) => ({ key, label: meta.label, glyph: meta.glyphs[0] }))
  }));
}
