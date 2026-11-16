/**
 * 本科教学进程表 · 纯逻辑单元测试
 *
 * 覆盖三个不依赖 DOM 的部分：
 *   1. teachingProcessSymbols.js —— 字形变体归一、斜杠过渡周、未收录字形
 *   2. teachingProcess.js        —— 图案解码、阶段合并、本周快照、分组排序
 *   3. shared/teaching-process.json —— 爬虫产物的结构不变量
 *
 * 运行：node scripts/test-teaching-process.mjs
 */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  UNDOCUMENTED_GLYPHS,
  cellParts,
  legendGroups,
  parseCell
} from '../src/utils/teachingProcessSymbols.js';
import {
  buildSegments,
  decodePattern,
  formatWeekRange,
  groupByPattern,
  snapshotAt,
  stageInsights,
  stageStatusOf,
  summaryOf,
  weekIndexOn
} from '../src/utils/teachingProcess.js';

let passed = 0;

function test(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`  ✓ ${name}`);
  } catch (err) {
    console.error(`  ✗ ${name}`);
    throw err;
  }
}

const data = JSON.parse(readFileSync(new URL('../../shared/teaching-process.json', import.meta.url), 'utf8'));

const keys = raw => parseCell(raw).parts.map(part => part.key);

console.log('\n[teachingProcess] 字形归一');

test('全角/半角冒号、金工实习、电工实习等变体归一到同一语义', () => {
  assert.deepEqual(keys('：'), ['exam']);
  assert.deepEqual(keys(':'), ['exam']);
  assert.deepEqual(keys('╳'), ['metalwork']);
  assert.deepEqual(keys('Ⅹ'), ['metalwork']);
  assert.deepEqual(keys('Ä'), ['eePractice']);
  assert.deepEqual(keys('◆'), ['eePractice']);
  assert.deepEqual(keys('﹟'), ['computer']);
  assert.deepEqual(keys('²'), ['research']);
  assert.deepEqual(keys('§'), ['research']);
  assert.deepEqual(keys('::'), ['research']);
  assert.deepEqual(keys('＝'), ['vacation']);
  assert.deepEqual(keys('='), ['vacation']);
  assert.deepEqual(keys('－'), ['idle']);
  assert.deepEqual(keys('-'), ['idle']);
});

test('空单元格与破折号都表示「常规教学」', () => {
  assert.deepEqual(parseCell(''), { parts: [], unknown: [] });
  assert.deepEqual(keys('—'), ['idle']);
  assert.deepEqual(cellParts('').map(part => part.key), ['idle']);
});

test('斜杠周的过渡语义：前后各占半周', () => {
  const split = parseCell('⊕/：');
  assert.deepEqual(split.parts.map(part => part.key), ['comprehensive', 'exam']);
  assert.equal(split.parts[1].group, 'exam');

  // "/：" 表示当周前半段没有特殊安排、后半段考试
  assert.deepEqual(keys('/：'), ['idle', 'exam']);
  // 旧年制里出现过三段写法，保留前两段并把余下内容并入
  assert.deepEqual(keys('/◎/：'), ['idle', 'skills', 'exam']);
});

test('连写单元格拆成多段，中文后缀进 note', () => {
  assert.deepEqual(keys('□⊕'), ['theory', 'comprehensive']);
  assert.deepEqual(keys('◎：'), ['skills', 'exam']);
  const notePart = parseCell('◆电子').parts[0];
  assert.equal(notePart.key, 'eePractice');
  assert.equal(notePart.note, '电子');
  assert.deepEqual(keys('⊕创新'), ['comprehensive']);
});

test('未收录字形不猜测含义，原样保留', () => {
  const unknown = parseCell('♥');
  assert.equal(unknown.parts[0].key, 'unknown');
  assert.deepEqual(unknown.unknown, ['♥']);
  assert.equal(unknown.parts[0].label, '♥');
  assert.deepEqual(keys('❉'), ['unknown']);
});

test('图例按组聚合，每组都带可渲染的符号列表', () => {
  const groups = legendGroups();
  // 未收录符号没有固定字形，单独在页面底部说明，不进图例分组
  assert.equal(groups.length, 7);
  assert.ok(!groups.some(group => group.group === 'unknown'));
  const practice = groups.find(group => group.group === 'practice');
  assert.ok(practice.symbols.some(symbol => symbol.key === 'production'));
  assert.ok(groups.every(group => Array.isArray(group.symbols) && group.symbols.length > 0));
});

console.log('\n[teachingProcess] 阶段合并');

test('普通周按连续区间合并', () => {
  const segments = buildSegments(['', '', '∨', '◎', '◎', '⊕', '⊕', '＝', '＝']);
  assert.deepEqual(
    segments.map(segment => `${segment.key}:${segment.startWeek}-${segment.endWeek}`),
    ['idle:1-2', 'sports:3-3', 'skills:4-5', 'comprehensive:6-7', 'vacation:8-9']
  );
});

test('斜杠周的前半段并入前一阶段，后半段单独成段', () => {
  const segments = buildSegments(['⊕', '⊕', '⊕/：', '＝', '＝']);
  const comprehensive = segments.find(segment => segment.key === 'comprehensive');
  const exam = segments.find(segment => segment.key === 'exam');
  assert.equal(comprehensive.endWeek, 3);
  assert.equal(comprehensive.partial, true);
  assert.equal(formatWeekRange(comprehensive), '第 1–3 周（末周前半）');
  assert.equal(exam.startWeek, 3);
  assert.equal(formatWeekRange(exam), '第 3 周后半');
});

test('跨周的同类阶段在斜杠周处仍能接上', () => {
  const segments = buildSegments(['⊕/：', '：', '：']);
  const exam = segments.find(segment => segment.key === 'exam');
  assert.equal(exam.startWeek, 1);
  assert.equal(exam.endWeek, 3);
  assert.equal(exam.start.idx, 1);
});

test('全空学期只产生一个「常规教学」阶段', () => {
  const segments = buildSegments(['', '', '']);
  assert.equal(segments.length, 1);
  assert.equal(segments[0].key, 'idle');
  assert.equal(segments[0].weekCount, 3);
});

test('摘要跳过常规教学，并保留最后一个阶段', () => {
  const segments = buildSegments(['', '★', '★', '□', '□', '□', '□', '：', '＝', '＝']);
  const text = summaryOf(segments, { max: 4 });
  assert.ok(text.includes('军训'));
  assert.ok(text.includes('假期'), '阶段过多时也必须保留最后一段（放假时间）');
  assert.ok(text.endsWith('假期 第 9–10 周'));
});

test('weekIndexOn 用起止日期定位当前教学周', () => {
  const weeks = [
    { w: 1, mon: '2026-08-31', sun: '2026-09-06' },
    { w: 2, mon: '2026-09-07', sun: '2026-09-13' }
  ];
  assert.equal(weekIndexOn(weeks, new Date('2026-08-31T12:00:00')), 1);
  assert.equal(weekIndexOn(weeks, new Date('2026-09-13T23:00:00')), 2);
  assert.equal(weekIndexOn(weeks, new Date('2026-09-14T00:00:00')), null);
});

console.log('\n[teachingProcess] 当前阶段概览');

test('stageInsights 指出当前阶段、剩余周数、下一阶段与放假时间', () => {
  // 26 周：1–4 常规、5 运动会、6–9 常规、10–11 技能训练、12–19 综合实践（末周后半考试）、20–26 假期
  const cells = ['', '', '', '', '∨', '', '', '', '', '◎', '◎', ...Array(7).fill('⊕'), '⊕/：', ...Array(7).fill('＝')];
  const segments = buildSegments(cells);
  const info = stageInsights(segments, 14, cells.length);

  assert.equal(info.current.key, 'comprehensive');
  assert.equal(info.currentRemaining, 6);
  assert.equal(info.currentElapsed, 3);
  assert.equal(info.next.key, 'exam');
  assert.equal(info.nextIn, 5);
  assert.equal(info.vacation.key, 'vacation');
  assert.equal(info.vacationIn, 6);
  assert.equal(info.remaining, 13);
});

test('在假期里时 next 不再指向后面的阶段，放假指标变成「假期中」', () => {
  const segments = buildSegments([...Array(3).fill('⊕'), ...Array(4).fill('＝')]);
  const info = stageInsights(segments, 5, 7);
  assert.equal(info.current.key, 'vacation');
  assert.equal(info.next, null);
  assert.equal(info.vacation.startWeek, 4);
  assert.equal(info.vacationIn, -1);
  assert.equal(info.currentRemaining, 3);
});

test('stageStatusOf 区分已结束、进行中与未开始', () => {
  const [past, current, upcoming] = buildSegments(['◎', '◎', '', '', '⊕', '⊕']);
  assert.equal(stageStatusOf(past, 3), 'past');
  assert.equal(stageStatusOf(current, 3), 'current');
  assert.equal(stageStatusOf(upcoming, 3), 'upcoming');
  assert.equal(stageStatusOf(past, 0), 'unknown');
});

test('groupByPattern 合并同进度班级，并把「我的班级」所在组置顶', () => {
  const patterns = ['|⊕|⊕', '|＝|＝'];
  const classes = [
    { name: 'A班', hours: 8, patternIndex: 1 },
    { name: 'B班', hours: 8, patternIndex: 0 },
    { name: 'C班', hours: 8, patternIndex: 0 }
  ];
  const groups = groupByPattern(classes, patterns, 'C班');
  assert.equal(groups.length, 2);
  assert.equal(groups[0].hasMine, true);
  assert.deepEqual(groups[0].classes.map(item => item.name), ['B班', 'C班']);
  assert.equal(groups[1].classes[0].name, 'A班');
});

test('snapshotAt 统计指定周次的班级分布', () => {
  const patterns = ['|⊕', '|＝', '|⊕/：'];
  const classes = [
    { name: 'A', patternIndex: 0 },
    { name: 'B', patternIndex: 1 },
    { name: 'C', patternIndex: 2 }
  ];
  const snapshot = snapshotAt(classes, patterns, 1);
  assert.equal(snapshot[0].key, 'comprehensive');
  assert.equal(snapshot[0].count, 2);
  assert.ok(snapshot.some(item => item.key === 'exam' && item.count === 1));
});

console.log('\n[teachingProcess] 数据快照结构');

test('学期按学年学期倒序，且 id 唯一', () => {
  const ids = data.semesters.map(semester => semester.id);
  assert.equal(new Set(ids).size, ids.length);
  const values = data.semesters.map(semester => Number(semester.academicYear.slice(0, 4)) * 10 + semester.term);
  assert.deepEqual(values, [...values].sort((left, right) => right - left));
  assert.equal(values[0], 20261, '最新的 2026-2027 学年第一学期必须排在最前');
});

test('每周起止日期连续：周日 = 周一 + 6 天、相邻周相差 7 天', () => {
  for (const semester of data.semesters) {
    for (const [index, week] of semester.weeks.entries()) {
      const mon = Date.parse(`${week.mon}T00:00:00Z`);
      const sun = Date.parse(`${week.sun}T00:00:00Z`);
      assert.equal(sun - mon, 6 * 86400000, `${semester.label} 第 ${week.w} 周起止异常`);
      assert.equal(week.w, index + 1);
      if (index > 0) {
        const previous = Date.parse(`${semester.weeks[index - 1].mon}T00:00:00Z`);
        assert.equal(mon - previous, 7 * 86400000, `${semester.label} 第 ${week.w} 周与上一周不连续`);
      }
    }
  }
});

test('图案长度与所在学期的周数一致，索引不越界', () => {
  for (const semester of data.semesters) {
    const weekCount = semester.weeks.length;
    for (const campus of semester.campuses) {
      assert.ok(campus.classes.length > 0, `${semester.label} ${campus.name} 没有班级`);
      const names = new Set();
      for (const [name, hours, patternIndex] of campus.classes) {
        assert.ok(name && name.length <= 40, `${semester.label} 班级名异常：${name}`);
        assert.equal(Number.isInteger(hours), true);
        assert.ok(patternIndex >= 0 && patternIndex < data.patterns.length);
        assert.equal(decodePattern(data.patterns[patternIndex]).length, weekCount, `${name} 图案长度与周数不一致`);
        assert.ok(!names.has(name), `${semester.label} ${campus.name} 班级重复：${name}`);
        names.add(name);
      }
    }
  }
});

test('所有图案的字形要么在字典里，要么落在显式白名单内', () => {
  const unknown = new Set();
  for (const pattern of data.patterns) {
    for (const cell of decodePattern(pattern)) {
      for (const glyph of parseCell(cell).unknown) unknown.add(glyph);
    }
  }
  assert.deepEqual([...unknown].sort(), [...UNDOCUMENTED_GLYPHS].sort());
});

test('元数据指向教务处公开页面，且带更新日期', () => {
  assert.match(data.source, /^http:\/\/jwzx\.hrbust\.edu\.cn\/homepage\/downloads\/jincheng\.htm$/);
  assert.match(data.sourceUpdatedAt, /^\d{4}-\d{2}-\d{2}$/);
  assert.match(data.updatedAt, /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(data.schemaVersion, 1);
  assert.ok(data.patterns.every(pattern => pattern.startsWith('|')));
  assert.ok(data.patterns.every(pattern => !pattern.includes('\u0000')));
});

console.log(`\n全部通过：${passed} 项\n`);
