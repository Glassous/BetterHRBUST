/**
 * 课程表增强（单周临时调课 + 手动实验/自定义课程 + 自定义时间）纯逻辑测试
 *
 * 运行：node scripts/test-timetable-overrides.mjs
 */

import assert from 'node:assert/strict';
import {
  buildEffectiveTimetableCells,
  buildRescheduleSourceMatch,
  customCourseToCell,
  isDifferentRescheduleTarget,
  normalizeCustomCourse,
  normalizeTimetableReschedule,
  resolveCustomTimeAnchor
} from '../src/utils/timetableOverrides.js';
import {
  getCourseTimeRange,
  hasCourseEnded,
  parseTimeToMinutes
} from '../src/utils/periodTimes.js';

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

console.log('\n[timetableOverrides] 时间解析与自定义时间锚定');

test('parseTimeToMinutes 解析合法与非法时间', () => {
  assert.equal(parseTimeToMinutes('08:10'), 490);
  assert.equal(parseTimeToMinutes('21:30'), 1290);
  assert.equal(parseTimeToMinutes('24:00'), null);
  assert.equal(parseTimeToMinutes('14:99'), null);
  assert.equal(parseTimeToMinutes('abc'), null);
});

test('resolveCustomTimeAnchor 将自定义时间锚定到最匹配的大节与小节', () => {
  // 14:00 - 16:30 与第 3 大节重叠 70 分钟，与第 4 大节重叠 60 分钟 → 锚定到第 3 大节、第 6 小节
  const afternoon = resolveCustomTimeAnchor('14:00', '16:30');
  assert.equal(afternoon.combineSection, 3);
  assert.equal(afternoon.baseSection, 6);

  // 12:10 - 13:20 在午间非标准时段，距第 3 大节更近（或第 2/3 大节中点最近）
  const noon = resolveCustomTimeAnchor('12:40', '13:25');
  assert.equal(noon.combineSection, 3);
  assert.equal(noon.baseSection, 5);
});

test('hasCourseEnded 对自定义时间优先按 customEndTime 判定', () => {
  const lab = customCourseToCell(
    normalizeCustomCourse({
      category: 'lab',
      courseName: '大学物理实验',
      week: 8,
      day: 3,
      timeMode: 'custom',
      startTime: '18:30',
      endTime: '20:30'
    }),
    'combine'
  );
  // 第 5 大节 19:40 已结束，但该实验 20:30 才结束
  assert.equal(hasCourseEnded(lab, new Date(2026, 10, 25, 20, 0)), false);
  assert.equal(hasCourseEnded(lab, new Date(2026, 10, 25, 20, 30)), true);

  const range = getCourseTimeRange(lab, 'combine');
  assert.equal(range.text, '18:30 - 20:30');
  assert.equal(range.isCustom, true);
});

console.log('\n[timetableOverrides] 手动实验课（节次与自定义时间）');

test('按小节单节添加实验课时保留精确起止时间并正确映射大节位置', () => {
  const entry = normalizeCustomCourse({
    category: 'lab',
    courseName: '数字逻辑实验',
    location: '实验楼 B304',
    teacher: '王老师',
    week: 10,
    day: 4,
    timeMode: 'slot',
    slotType: 'base',
    slotPeriod: 3,
    slotSpan: 1
  });
  const combineCell = customCourseToCell(entry, 'combine');
  const baseCell = customCourseToCell(entry, 'base');

  assert.equal(combineCell.sectionIndex, 2);
  assert.equal(combineCell.customTimeText, '10:10 - 10:55');
  assert.equal(combineCell.category, 'lab');
  assert.equal(baseCell.sectionIndex, 3);
  assert.equal(baseCell.location, '实验楼 B304');
});

test('手动实验课仅在目标教学周生效，不影响其他周', () => {
  const lab = normalizeCustomCourse({
    category: 'lab',
    courseName: '电路实验',
    week: 10,
    day: 2,
    timeMode: 'custom',
    startTime: '14:00',
    endTime: '16:00'
  });

  const week10 = buildEffectiveTimetableCells({
    rawCells: [],
    week: 10,
    viewMode: 'combine',
    customCourses: [lab]
  });
  const week11 = buildEffectiveTimetableCells({
    rawCells: [],
    week: 11,
    viewMode: 'combine',
    customCourses: [lab]
  });

  assert.equal(week10.length, 1);
  assert.equal(week10[0].courseName, '电路实验');
  assert.equal(week10[0].customTimeText, '14:00 - 16:00');
  assert.equal(week11.length, 0);
});

console.log('\n[timetableOverrides] 单周临时调课');

test('isDifferentRescheduleTarget 拦截与原位置相同的目标位置', () => {
  const source = buildRescheduleSourceMatch({
    id: '2-100',
    courseId: '100',
    courseName: '高等数学',
    day: 2,
    sectionIndex: 1,
    location: '西-新D101',
    teacher: '张老师'
  }, 'combine');

  assert.equal(isDifferentRescheduleTarget(source, 2, 1, 'combine'), false);
  assert.equal(isDifferentRescheduleTarget(source, 2, 2, 'combine'), true);
  assert.equal(isDifferentRescheduleTarget(source, 4, 1, 'combine'), true);
  // 小节模式下原第 1 大节覆盖 1、2 小节
  assert.equal(isDifferentRescheduleTarget(source, 2, 1, 'base'), false);
  assert.equal(isDifferentRescheduleTarget(source, 2, 2, 'base'), false);
  assert.equal(isDifferentRescheduleTarget(source, 2, 3, 'base'), true);
});

test('单周调课仅影响目标周：原位置生成已调出占位，新位置应用自定义教室', () => {
  const rawCombine = [
    {
      id: '2-100',
      courseId: '100',
      day: 2,
      sectionIndex: 1,
      sectionLabel: '第1大节',
      courseName: '高等数学',
      courseSeq: '01',
      location: '西-新D101',
      teacher: '张老师',
      weeks: '1-16周',
      hoursType: '讲课学时'
    }
  ];

  const sourceMatch = buildRescheduleSourceMatch(rawCombine[0], 'combine');
  const reschedule = normalizeTimetableReschedule({
    week: 10,
    sourceMatch,
    targetDay: 4,
    targetSectionMode: 'combine',
    targetCombineSection: 3,
    location: '东-2号楼405',
    note: '教师临时调课'
  });

  const week10 = buildEffectiveTimetableCells({
    rawCells: rawCombine,
    week: 10,
    viewMode: 'combine',
    reschedules: [reschedule]
  });
  const week11 = buildEffectiveTimetableCells({
    rawCells: rawCombine,
    week: 11,
    viewMode: 'combine',
    reschedules: [reschedule]
  });

  assert.equal(week10.length, 2);
  const movedOut = week10.find(c => c.isMovedOut);
  const movedIn = week10.find(c => c.isRescheduled);
  assert.ok(movedOut, '原位置应保留已调出占位');
  assert.equal(movedOut.day, 2);
  assert.equal(movedOut.sectionIndex, 1);
  assert.equal(movedOut.movedToLabel, '周四 第 3 大节');

  assert.ok(movedIn, '新位置应生成调课后的课程卡');
  assert.equal(movedIn.day, 4);
  assert.equal(movedIn.sectionIndex, 3);
  assert.equal(movedIn.location, '东-2号楼405');
  assert.equal(movedIn.weeks, '第10周');
  assert.equal(movedIn.note, '教师临时调课');

  // 第 11 周完全不受影响
  assert.equal(week11.length, 1);
  assert.equal(week11[0].day, 2);
  assert.equal(week11[0].sectionIndex, 1);
  assert.equal(week11[0].location, '西-新D101');
  assert.equal(Boolean(week11[0].isRescheduled), false);
});

test('小节模式下两小节连堂课程被单周调课时只生成一张已调出占位卡', () => {
  const rawBase = [
    {
      id: '2-100-1',
      courseId: '100',
      day: 2,
      sectionIndex: 1,
      courseName: '高等数学',
      courseSeq: '01',
      location: '西-新D101',
      teacher: '张老师',
      weeks: '1-16周'
    },
    {
      id: '2-100-2',
      courseId: '100',
      day: 2,
      sectionIndex: 2,
      courseName: '高等数学',
      courseSeq: '01',
      location: '西-新D101',
      teacher: '张老师',
      weeks: '1-16周'
    }
  ];

  const sourceMatch = {
    cellId: '2-100',
    courseId: '100',
    courseName: '高等数学',
    courseSeq: '01',
    originDay: 2,
    originCombineSection: 1,
    originBaseSections: [1, 2],
    originLocation: '西-新D101',
    originTeacher: '张老师'
  };
  const reschedule = normalizeTimetableReschedule({
    week: 10,
    sourceMatch,
    targetDay: 5,
    targetSectionMode: 'combine',
    targetCombineSection: 4,
    location: '西-新D302'
  });

  const week10Base = buildEffectiveTimetableCells({
    rawCells: rawBase,
    week: 10,
    viewMode: 'base',
    reschedules: [reschedule]
  });

  const movedOutList = week10Base.filter(c => c.isMovedOut);
  const movedInList = week10Base.filter(c => c.isRescheduled);
  assert.equal(movedOutList.length, 1);
  assert.equal(movedInList.length, 1);
  assert.equal(movedInList[0].day, 5);
  assert.equal(movedInList[0].sectionIndex, 7);
  assert.equal(movedInList[0].location, '西-新D302');
});

console.log(`\n全部通过：${passed} 项\n`);
