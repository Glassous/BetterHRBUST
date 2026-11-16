/**
 * 「我的班级」推测 · 单元测试
 *
 * 教务档案里的班级名（材料成型及控制工程23-1班）与进程表的缩写（材型23（1-4））
 * 并不一致，匹配只能靠评分。这里锁定几个真实形态，避免阈值被无意放宽。
 *
 * 运行：node scripts/test-class-matcher.mjs
 */

import assert from 'node:assert/strict';
import {
  normalizeClassName,
  parseClassName,
  pickBestClass,
  profileClassNumber,
  profileGrade,
  scoreClass
} from '../src/utils/classMatcher.js';

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

console.log('\n[classMatcher] 归一化与解析');

test('全角括号、空格与大小写统一', () => {
  assert.equal(normalizeClassName('通 信26 (1-6)'), '通信26(1-6)');
  assert.equal(normalizeClassName('材型16(1-4）'), '材型16(1-4)');
  assert.equal(normalizeClassName('  软  件26（1-8） '), '软件26(1-8)');
});

test('班级名拆出专业缩写、年级与班号区间', () => {
  assert.deepEqual(parseClassName('材型23（1-4）'), { abbr: '材型', grade: '23', numbers: [1, 4] });
  assert.deepEqual(parseClassName('电气(大珩班)26-1'), { abbr: '电气(大珩班)', grade: '26', numbers: [1] });
  assert.deepEqual(parseClassName('汉语L23-1'), { abbr: '汉语l', grade: '23', numbers: [1] });
});

test('档案年级与班号能识别四位年份写法', () => {
  const profile = { className: '材料成型及控制工程2023-2班', grade: '2023级' };
  assert.equal(profileGrade(profile), '23');
  assert.equal(profileClassNumber(profile), 2);
  assert.equal(profileGrade({ className: '软件工程23-1班' }), '23');
});

console.log('\n[classMatcher] 评分');

test('完全一致得满分', () => {
  const profile = { className: '材型23（1-4）', major: '材料成型及控制工程', grade: '2023' };
  assert.equal(scoreClass(profile, '材型23（1-4）'), 100);
});

test('年级不符时分数显著降低', () => {
  const profile = { major: '材料成型及控制工程', grade: '2023' };
  const same = scoreClass(profile, '材型23（1-4）');
  const other = scoreClass(profile, '材型24（1-4）');
  assert.ok(same > other, '同年级候选必须高于其他年级');
  assert.ok(other < 40, '年级不符的候选不应达到可自动确认的分数');
});

test('区间班命中班号，比不含班号的候选更高', () => {
  const profile = { major: '材料成型及控制工程', grade: '2023', className: '材料成型及控制工程23-1班' };
  const inside = scoreClass(profile, '材型23（1-4）');
  const outside = scoreClass(profile, '材型23（5-8）');
  assert.ok(inside > outside);
  assert.ok(inside >= 60, '同专业同年级同班号区间应该可以被推荐');
});

test('缩写里专业名没有的字要扣分，避免「材化」压过「材型」', () => {
  const profile = { major: '材料成型及控制工程', grade: '2023', className: '材料成型及控制工程23-1班' };
  assert.ok(scoreClass(profile, '材型23（1-4）') > scoreClass(profile, '材化23（1-2）'));
  assert.ok(scoreClass(profile, '材型23（1-4）') > scoreClass(profile, '金材23（1-4）'));
});

console.log('\n[classMatcher] 候选选择');

test('单独命中时给出置信建议', () => {
  const profile = { major: '材料成型及控制工程', grade: '2023', className: '材料成型及控制工程23-1班' };
  const best = pickBestClass(profile, [
    { name: '材型23（1-4）', campus: '南区' },
    { name: '材型23（5-8）', campus: '南区' },
    { name: '高分子23（1-4）', campus: '南区' }
  ]);
  assert.equal(best.name, '材型23（1-4）');
  assert.equal(best.confident, true);
  assert.equal(best.campus, '南区');
});

test('档案里没有班号、几个候选并列时不置信，要求人工选择', () => {
  const profile = { major: '材料成型及控制工程', grade: '2023', className: '材料成型及控制工程23班' };
  const best = pickBestClass(profile, [
    { name: '材型23（1-4）', campus: '南区' },
    { name: '材型23（5-8）', campus: '南区' }
  ]);
  assert.equal(best.confident, false);
  assert.ok(best.margin < 10);
});

test('没有档案信息或候选为空时返回 null', () => {
  assert.equal(pickBestClass(null, [{ name: '材型23（1-4）' }]), null);
  assert.equal(pickBestClass({ major: '材料成型及控制工程' }, []), null);
});

console.log(`\n全部通过：${passed} 项\n`);
