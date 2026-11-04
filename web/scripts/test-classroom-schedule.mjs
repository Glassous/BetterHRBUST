/**
 * 空教室查询 · 纯逻辑单元测试
 *
 * classroomSchedule.js 不依赖 DOM，因此可直接在 Node 中验证：
 * 占用归一、矩阵分段、连续空闲合并、越界裁剪、排序与文案格式化。
 *
 * 运行：node scripts/test-classroom-schedule.mjs
 */

import assert from 'node:assert/strict';
import {
  MIN_FREE_SPAN,
  buildDaySegments,
  countOccupiedSections,
  dayFreeRanges,
  expandOccupancy,
  findFreeSlots,
  formatSlot,
  isRoomFreeAllWeek,
  normalizeOccupancy,
  occupancyKey,
  parseSectionRange,
  sortByFreeTime,
  summarizeRooms
} from '../src/services/academic/classroomSchedule.js';

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

console.log('\n[classroomSchedule] 占用归一');

test('相邻区间合并、重叠区间取并集', () => {
  const normalized = normalizeOccupancy([
    { day: 1, start: 1, span: 2 },
    { day: 1, start: 3, span: 2 },
    { day: 1, start: 2, span: 3 }
  ]);
  assert.deepEqual(normalized, [{ day: 1, start: 1, span: 4, label: '' }]);
});

test('越界区间被裁剪，越界的星期被丢弃', () => {
  const normalized = normalizeOccupancy(
    [
      { day: 1, start: 11, span: 5 },
      { day: 1, start: 0, span: 2 },
      { day: 9, start: 1, span: 2 }
    ],
    { dayCount: 7, sectionCount: 12 }
  );
  assert.deepEqual(normalized, [
    { day: 1, start: 1, span: 1, label: '' },
    { day: 1, start: 11, span: 2, label: '' }
  ]);
});

test('非法记录被忽略，标签保留首个非空课程名', () => {
  const normalized = normalizeOccupancy([
    null,
    { day: 'x', start: 1, span: 2 },
    { day: 2, start: 1, span: 1, label: '' },
    { day: 2, start: 2, span: 1, label: '线性代数' }
  ]);
  assert.deepEqual(normalized, [{ day: 2, start: 1, span: 2, label: '线性代数' }]);
});

test('expandOccupancy 生成逐节集合', () => {
  const occupied = expandOccupancy([
    { day: 3, start: 1, span: 2 },
    { day: 3, start: 5, span: 1 }
  ]);
  assert.equal(occupied.size, 3);
  assert.ok(occupied.has(occupancyKey(3, 1)));
  assert.ok(occupied.has(occupancyKey(3, 2)));
  assert.ok(occupied.has(occupancyKey(3, 5)));
  assert.ok(!occupied.has(occupancyKey(3, 4)));
});

console.log('\n[classroomSchedule] 连续空闲时段');

test('整周无课时每天合并为一个长时段', () => {
  const slots = findFreeSlots([], { minSpan: MIN_FREE_SPAN });
  assert.equal(slots.length, 7);
  assert.deepEqual(slots[0], { day: 1, start: 1, end: 12, span: 12 });
  assert.deepEqual(slots[6], { day: 7, start: 1, end: 12, span: 12 });
});

test('周四 1-2 节被占后，剩余 3-12 节合并为一段', () => {
  const slots = findFreeSlots([{ day: 4, start: 1, span: 2 }]);
  const thursday = slots.filter((slot) => slot.day === 4);
  assert.deepEqual(thursday, [{ day: 4, start: 3, end: 12, span: 10 }]);
  assert.equal(slots.filter((slot) => slot.day !== 4).length, 6);
});

test('minSpan 过滤零散单节', () => {
  const occupancy = [
    { day: 1, start: 2, span: 1 },
    { day: 1, start: 4, span: 1 },
    { day: 1, start: 6, span: 1 }
  ];
  const defaultSlots = findFreeSlots(occupancy).filter((slot) => slot.day === 1);
  // 1 节 / 3 节 / 5 节 / 7-12 节：前三段都是单节，被默认 minSpan=2 过滤
  assert.deepEqual(defaultSlots, [{ day: 1, start: 7, end: 12, span: 6 }]);

  const looseSlots = findFreeSlots(occupancy, { minSpan: 1 }).filter((slot) => slot.day === 1);
  assert.equal(looseSlots.length, 4);
  assert.deepEqual(looseSlots.map((slot) => slot.start), [1, 3, 5, 7]);
});

test('整周排满时没有空闲时段', () => {
  const occupancy = [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, start: 1, span: 12 }));
  assert.deepEqual(findFreeSlots(occupancy), []);
});

test('dayFreeRanges 只统计指定星期，不把未查询的日子算成空闲', () => {
  const occupancy = [{ day: 2, start: 1, span: 2 }];
  // 周一没有任何占用记录，但只查了周二 → 周一必须仍视为「未知」，不能凭空整天空闲
  assert.deepEqual(dayFreeRanges(occupancy, 2), [{ day: 2, start: 3, end: 12, span: 10 }]);
  assert.deepEqual(dayFreeRanges(occupancy, 1), [{ day: 1, start: 1, end: 12, span: 12 }]);

  const single = dayFreeRanges(
    [
      { day: 1, start: 1, span: 1 },
      { day: 1, start: 3, span: 1 }
    ],
    1
  );
  assert.deepEqual(
    single.map((slot) => slot.start),
    [2, 4]
  );
  // minSpan=2 时只剩最后的长区间
  assert.deepEqual(
    dayFreeRanges([{ day: 1, start: 1, span: 1 }], 1, { minSpan: 2 }).map((slot) => slot.start),
    [2]
  );
});

console.log('\n[classroomSchedule] 矩阵分段');

test('单日分段：占用 1-2 节后接 3-12 节空闲', () => {
  const segments = buildDaySegments([{ day: 1, start: 1, span: 2, label: '高等数学' }], {
    dayCount: 2,
    sectionCount: 12
  });
  assert.equal(segments.length, 2);
  assert.deepEqual(segments[0], [
    { section: 1, span: 2, state: 'occupied', label: '高等数学' },
    { section: 3, span: 10, state: 'free', label: '' }
  ]);
  assert.deepEqual(segments[1], [{ section: 1, span: 12, state: 'free', label: '' }]);
});

test('课间零散占用会切出多段，段落总长等于小节数', () => {
  const segments = buildDaySegments(
    [
      { day: 2, start: 2, span: 1 },
      { day: 2, start: 8, span: 3 }
    ],
    { sectionCount: 12 }
  );
  const tuesday = segments[1];
  assert.equal(tuesday.reduce((sum, item) => sum + item.span, 0), 12);
  assert.deepEqual(
    tuesday.map((item) => `${item.state}:${item.section}+${item.span}`),
    ['free:1+1', 'occupied:2+1', 'free:3+5', 'occupied:8+3', 'free:11+2']
  );
});

console.log('\n[classroomSchedule] 汇总与格式化');

test('summarizeRooms 补齐矩阵、空闲时段与占用计数', () => {
  const [room] = summarizeRooms([
    {
      id: '1',
      name: '新A306',
      occupied: [
        { day: 1, start: 1, span: 2, label: '大学英语' },
        { day: 3, start: 5, span: 2 }
      ]
    }
  ]);
  assert.equal(room.occupiedCount, 4);
  // 周一 3-12、周二整天、周三 1-4 与 7-12、周四~周日整天 = 8 段
  assert.equal(room.freeSlotLabels.length, 8);
  assert.ok(room.freeSlotLabels.includes('周一 3-12 节'));
  assert.ok(room.freeSlotLabels.includes('周三 1-4 节'));
  assert.equal(isRoomFreeAllWeek(room), false);
  assert.equal(room.days.length, 7);
});

test('countOccupiedSections 与 isRoomFreeAllWeek', () => {
  assert.equal(countOccupiedSections([]), 0);
  assert.equal(countOccupiedSections([{ day: 1, start: 1, span: 3 }]), 3);
  assert.equal(isRoomFreeAllWeek({ occupied: [] }), true);
  assert.equal(isRoomFreeAllWeek({ occupied: [{ day: 2, start: 2, span: 1 }] }), false);
});

test('formatSlot 处理单节与连堂', () => {
  assert.equal(formatSlot({ day: 1, start: 3, end: 4 }), '周一 3-4 节');
  assert.equal(formatSlot({ day: 7, start: 9, end: 9 }), '周日 9 节');
  assert.equal(formatSlot({ day: 3, start: 1, span: 2 }), '周三 1-2 节');
  assert.equal(formatSlot(null), '');
});

test('sortByFreeTime 把空闲更多的教室排在前面', () => {
  const rooms = summarizeRooms([
    { name: '满课', occupied: [1, 2, 3, 4, 5].map((day) => ({ day, start: 1, span: 10 })) },
    { name: '轻松', occupied: [{ day: 1, start: 1, span: 2 }] },
    { name: '中等', occupied: [{ day: 1, start: 1, span: 6 }, { day: 2, start: 1, span: 4 }] }
  ]);
  const sorted = sortByFreeTime(rooms).map((room) => room.name);
  assert.deepEqual(sorted, ['轻松', '中等', '满课']);
});

test('parseSectionRange 兼容中文与区间写法', () => {
  assert.deepEqual(parseSectionRange('第3-4节'), { start: 3, end: 4, span: 2 });
  assert.deepEqual(parseSectionRange('5~6'), { start: 5, end: 6, span: 2 });
  assert.deepEqual(parseSectionRange('第 9 节'), { start: 9, end: 9, span: 1 });
  assert.equal(parseSectionRange('无'), null);
  assert.equal(parseSectionRange('4-2'), null);
});

console.log(`\n全部通过：${passed} 项\n`);
