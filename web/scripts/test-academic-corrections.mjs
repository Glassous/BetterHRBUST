import assert from 'node:assert/strict';
import { degreeCourses, buildAcademicStats, computeCreditsProgress, isLowScore, countedCourses, semesterSortKey } from '../src/services/academic/stats.js';
import { hasCombineSlotEnded } from '../src/utils/periodTimes.js';
const course = (courseId, score, credit, courseGroup = '', property = '必修', examType = '正常考试') => ({courseId, courseName: courseId, score: String(score), credit, courseGroup, property, examType, year: '2024', term: '1', passed: score >= 60});
const scores = [course('REQ',65,3),course('ZERO',0,0),course('E1',95,2,'E类','任选'),course('E2',90,2,'E类','任选'),course('A1',89,2,'A类','任选'),course('D1',60,2,'D类','任选')];
assert.deepEqual(degreeCourses(scores).map(s=>s.courseId),['REQ','E1','E2']);
assert.deepEqual(degreeCourses(scores.filter(s=>s.courseGroup!=='E类')).map(s=>s.courseId),['REQ']);
assert.equal(buildAcademicStats(scores).degree.requiredCredits,7);
assert.equal(buildAcademicStats(scores).degree.gpa,3.07);
const normal=course('NORMAL',90,3);
assert.equal(buildAcademicStats([normal,normal]).retakeCount,0);
assert.equal(buildAcademicStats([normal,{...normal,examType:'补考'}]).retakeCount,1);
const group={id:'1',name:'基础课',requiredCredits:20,courses:[]};
assert.equal(computeCreditsProgress([], [group,group]).requiredTotal,20);
assert.equal(computeCreditsProgress([], [group], {planTotalCredits:158.5}).requiredTotal,158.5);
assert.equal(computeCreditsProgress(scores, []).requiredTotal,0);
assert.equal(isLowScore('69'),true);assert.equal(isLowScore('70'),false);
assert.equal(hasCombineSlotEnded(1,new Date(2026,9,9,9,49)),false);
assert.equal(hasCombineSlotEnded(1,new Date(2026,9,9,9,50)),true);
assert.equal([1,2,3,4,5,6].every(i=>hasCombineSlotEnded(i,new Date(2026,9,9,21,30))),true);
console.log('通过：两门选修规则、0 学分排除、重复与补考、真实方案学分、70 分颜色阈值及课程结束边界');

const direction = {id:'direction',name:'专业限选',property:'限选',requiredCredits:10,courses:Array.from({length:10},(_,i)=>({code:'DIR'+i,name:'方向课程'+i,credit:2.5}))};
assert.equal(computeCreditsProgress([], [direction]).requiredTotal,10);
assert.equal(computeCreditsProgress([], [direction]).categories[0].required,10);

const ungraded = buildAcademicStats([course('VALID',90,3),{...course('PENDING',0,10),score:'缓考'}]);
assert.equal(ungraded.degree.gpa,4);assert.equal(ungraded.degree.qualified,true);

// L-1：未出分/缓考不再默认视为已通过（保守口径：未确认通过 ≠ 已通过）
const pendingStats = buildAcademicStats([
  course('OK', 70, 3),
  { ...course('DEFER', 0, 5), score: '缓考', passed: false, pending: true },
  { ...course('UNGRADED', 0, 2), score: '', passed: false, pending: true }
]);
assert.equal(pendingStats.earnedCredits, 3, 'pending 行不计入已获学分');
assert.equal(pendingStats.failedCount, 0, 'pending 行不计入挂科门数');
assert.equal(pendingStats.failedCredits, 0, 'pending 行不计入挂科学分');

// 等级制成绩（及格标志列为空）：合格/优秀/免修是明确的"通过"结论，必须计入已获学分，
// 不通过是明确的挂科结论（不得因保守化而丢进 pending）
const gradeTextStats = buildAcademicStats([
  course('GRADE_NUM', 70, 3),
  { ...course('GRADE_PASS', 0, 4), score: '合格', passed: true, pending: false },
  { ...course('GRADE_EXCL', 0, 1.5), score: '优秀', passed: true, pending: false },
  { ...course('GRADE_EXEMPT', 0, 2), score: '免修', passed: true, pending: false },
  { ...course('GRADE_FAIL', 0, 2), score: '不通过', passed: false, pending: false }
]);
assert.equal(gradeTextStats.earnedCredits, 10.5, '等级制"合格/优秀/免修"计入已获学分');
assert.equal(gradeTextStats.failedCount, 1, '等级制"不通过"计入挂科门数');
assert.equal(gradeTextStats.failedCredits, 2, '等级制"不通过"计入挂科学分');

assert.deepEqual(degreeCourses([{...course('MATH',85,3),courseName:'高等数学(A)'},course('MAJOR',90,2.5,'专业选修','任选')]).map(s=>s.courseId),['MATH','MAJOR']);

// GPA 走势学期顺序：教务「学年」列是学期所在公历年份（2023 秋 → 2024 春 → 2024 秋 连续），同一年内先春后秋
assert.ok(semesterSortKey('2024','春') < semesterSortKey('2024','秋'));
assert.ok(semesterSortKey('2023','秋') < semesterSortKey('2024','春'));
assert.ok(semesterSortKey('2025','秋') < semesterSortKey('2026','春'));
assert.ok(semesterSortKey('2024','1') < semesterSortKey('2024','2')); // 下拉序号 1=春、2=秋
const semesterScores = [
  { courseId: 'C6', score: '70', credit: 2, property: '必修', year: '2026', term: '春', passed: true },
  { courseId: 'C3', score: '80', credit: 2, property: '必修', year: '2024', term: '秋', passed: true },
  { courseId: 'C1', score: '90', credit: 2, property: '必修', year: '2023', term: '秋', passed: true },
  { courseId: 'C2', score: '85', credit: 2, property: '必修', year: '2024', term: '春', passed: true },
  { courseId: 'C5', score: '75', credit: 2, property: '必修', year: '2025', term: '秋', passed: true },
  { courseId: 'C4', score: '95', credit: 2, property: '必修', year: '2025', term: '春', passed: true }
];
const termGroups = new Map();
countedCourses(semesterScores).forEach(s => {
  const key = `${s.year} ${s.term}`;
  if (!termGroups.has(key)) termGroups.set(key, { year: s.year, term: s.term, list: [] });
  termGroups.get(key).list.push(s);
});
const trendLabels = [...termGroups.values()]
  .sort((a, b) => semesterSortKey(a.year, a.term) - semesterSortKey(b.year, b.term))
  .slice(-6)
  .map(g => `${g.year} ${g.term}`);
assert.deepEqual(trendLabels, ['2023 秋', '2024 春', '2024 秋', '2025 春', '2025 秋', '2026 春']);

// 门数是独立的上限检查，不能被另一项必修通过条件标红。
for (let count=0;count<=3;count++) {
 const records=[course('FAIL',50,3),...Array.from({length:count},(_,i)=>course('RET'+i,80,3,'','必修','重修'))];
 const result=buildAcademicStats(records).recommend;
 assert.equal(result.retakeCount,count);
 assert.equal(result.retakeWithinLimit,count<=2);
 assert.equal(result.qualified,false);
}

const { timetableDayDate } = await import('../src/utils/timetableDates.js');
assert.equal(timetableDayDate(6,6,1,new Date(2026,0,1)), '12/29');
assert.equal(timetableDayDate(6,7,1,new Date(2026,0,1)), '01/05');
assert.equal(timetableDayDate(6,6,4,new Date(2024,1,29)), '02/29');
assert.equal(timetableDayDate(6,6,7,new Date(2026,9,10)), '10/11');

const { teachingWeekOn } = await import('../src/utils/schoolCalendar.js');
assert.equal(teachingWeekOn(new Date(2026,9,10)),6);
assert.equal(teachingWeekOn(new Date(2027,0,10)),19);
assert.equal(teachingWeekOn(new Date(2027,0,11)),null);
assert.equal(teachingWeekOn(new Date(2027,2,1)),1);
assert.equal(teachingWeekOn(new Date(2027,6,11)),19);
assert.equal(teachingWeekOn(new Date(2027,6,12)),null);
assert.equal(timetableDayDate(26,6,6,new Date(2026,9,10)),'10/10');
assert.equal(timetableDayDate(6,20,1,new Date(2026,9,10)),'');

const { buildIcsCalendar, escapeIcsText } = await import('../src/utils/ics.js');
// ICS 转义（RFC 5545 3.3.11）：分号/逗号/换行必须转义，否则会被日历软件当分隔符
assert.equal(escapeIcsText('a;b,c' + String.fromCharCode(13, 10) + 'd\\'), 'a\\;b\\,c\\nd\\\\');
const longIcs = buildIcsCalendar([{ summary: '超'.repeat(60) + '长;课程,名', start: new Date(2026, 9, 5, 8, 0), end: new Date(2026, 9, 5, 9, 40) }]);
assert.ok(longIcs.startsWith('BEGIN:VCALENDAR\r\n'), 'ICS 行尾必须 CRLF');
assert.ok(longIcs.endsWith('END:VCALENDAR\r\n'), 'ICS 行尾必须 CRLF');
assert.ok(longIcs.includes('\\;'), 'SUMMARY 中的分号必须转义');
let folded = false;
for (const line of longIcs.split('\r\n')) {
  assert.ok(new TextEncoder().encode(line).length <= 75, 'ICS 内容行超过 75 字节未折叠: ' + line.slice(0, 24));
  if (line.startsWith(' ')) folded = true;
}
assert.equal(folded, true, '超长中文 SUMMARY 必须产生以空格开头的折叠续行');

// ---- 课表单元格字段拆解：行数可变，缺教师行不能把学时类型当成周次 ----
const { splitCourseCellFields } = await import('../src/utils/courseCell.js');
// 标准五行
assert.deepEqual(splitCourseCellFields(['<<算法设计与分析>>;2', '西-新A306', '张淑丽B', '4-10周', '讲课学时']),
  { location: '西-新A306', teacher: '张淑丽B', weeks: '4-10周', hoursType: '讲课学时' });
// 缺教师行（军训 / 体育等实践课）：周次只剩三行位置，必须仍取到「4-5周」
assert.deepEqual(splitCourseCellFields(['<<军事技能训练>>', '西-区田径场', '4-5周', '实践学时']),
  { location: '西-区田径场', teacher: '', weeks: '4-5周', hoursType: '实践学时' });
// 地点与教师都没有
assert.deepEqual(splitCourseCellFields(['<<军事技能训练>>', '4-5周', '实践学时']),
  { location: '', teacher: '', weeks: '4-5周', hoursType: '实践学时' });
// 周次后多一行说明（真实数据：复变函数与积分变换 → 一上午 → 讲课学时）
assert.deepEqual(splitCourseCellFields(['<<复变函数与积分变换>>;334', '西-新B502', '张三', '1-6周', '一上午', '讲课学时']),
  { location: '西-新B502', teacher: '张三', weeks: '1-6周', hoursType: '讲课学时' });

// ---- 已结束课程必须判定为不在本周（否则课表里仍显示为正常颜色）----
const { isCourseActiveInWeek, expandCourseWeeks } = await import('../src/utils/courseWeeks.js');
assert.equal(isCourseActiveInWeek({ weeks: '4-5周' }, 5), true);
assert.equal(isCourseActiveInWeek({ weeks: '4-5周' }, 11), false, '第 11 周不应再显示 4-5 周的课程');
assert.equal(isCourseActiveInWeek({ weeks: '4-5周' }, 6), false);
assert.equal(isCourseActiveInWeek({ weeks: '4-5周(单)' }, 5), true);
assert.equal(isCourseActiveInWeek({ weeks: '4-5周(单)' }, 4), false);
assert.equal(isCourseActiveInWeek({ weeks: '' }, 11), true, '无周次信息视为全学期');
assert.deepEqual(expandCourseWeeks('1-8周(双)', 8), [2, 4, 6, 8], '双周应保留偶数周');

const { execFileSync } = await import('node:child_process');
const { fileURLToPath } = await import('node:url');
execFileSync(process.execPath,[fileURLToPath(new URL('../../tools/generate-school-calendar.mjs',import.meta.url)),'--check']);
