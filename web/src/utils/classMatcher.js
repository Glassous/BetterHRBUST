/**
 * 「我的班级」推测：教务档案里的班级名与教学进程表的缩写（材型23、电气(大珩班)26-1）
 * 并不一致，这里用可解释的加权评分给出候选，但只作为建议——必须由用户确认才落库。
 */

export function normalizeClassName(raw) {
  return String(raw ?? '')
    .normalize('NFKC')
    .replace(/[\s\u00a0\u3000]+/g, '')
    .replace(/[·、,，]/g, '')
    .toLowerCase();
}

/** 材型23（1-4）→ { abbr: '材型', grade: '23', numbers: [1,4] } */
export function parseClassName(raw) {
  const text = normalizeClassName(raw);
  const matched = /^([^\d]*)(\d{2})(.*)$/.exec(text);
  if (!matched) return { abbr: text, grade: '', numbers: [] };
  return {
    abbr: matched[1],
    grade: matched[2],
    numbers: (matched[3].match(/\d+/g) || []).map(Number)
  };
}

function trimYear(text) {
  return text.replace(/(19|20)\d{2}/g, '');
}

/** 档案里的年级：优先 grade 字段（2023 → 23），其次班级名里的两位年级 */
export function profileGrade(profile) {
  const raw = String(profile?.grade ?? '');
  const four = /(19|20)\d{2}/.exec(raw);
  if (four) return four[0].slice(2);
  const two = /\d{2}/.exec(trimYear(normalizeClassName(profile?.className || '')));
  return two ? two[0] : '';
}

/** 档案班级里的班号：材料成型及控制工程23-1班 → 1（排除年级数字） */
export function profileClassNumber(profile) {
  const text = trimYear(normalizeClassName(profile?.className || ''));
  const grade = profileGrade(profile);
  const numbers = (text.match(/\d{1,2}/g) || []).map(Number);
  const values = numbers.filter((value, index) => !(index === 0 && grade && value === Number(grade)));
  return values.length ? values[values.length - 1] : 0;
}

function commonPrefixLength(left, right) {
  let index = 0;
  while (index < left.length && index < right.length && left[index] === right[index]) index += 1;
  return index;
}

function sharedCharCount(left, right) {
  const pool = new Set(right);
  let count = 0;
  for (const char of new Set(left)) if (pool.has(char)) count += 1;
  return count;
}

/**
 * 候选班级评分（0–100）：年级最强，其次是专业名公共前缀与班号区间。
 * 缩写里出现专业名中不存在的字（材化 vs 材料成型）要扣分，否则「材型」「材化」难分。
 * 只是排序依据，不用于自动落库。
 */
export function scoreClass(profile, name) {
  const className = normalizeClassName(profile?.className);
  const target = normalizeClassName(name);
  if (className && className === target) return 100;

  const candidate = parseClassName(name);
  const majorKey = normalizeClassName(profile?.major);
  const grade = profileGrade(profile);
  const prefix = commonPrefixLength(candidate.abbr, majorKey);
  const overlap = sharedCharCount(candidate.abbr, majorKey);
  const abbrChars = [...new Set(candidate.abbr)].filter(char => /[\u4e00-\u9fff]/.test(char));
  const missing = majorKey ? abbrChars.filter(char => !majorKey.includes(char)).length : 0;

  let score = 0;
  if (grade && candidate.grade) score += grade === candidate.grade ? 35 : -40;
  score += Math.min(25, prefix * 5);

  // 专业名毫无关联时，年级与班号巧合不足以说明是同一个班
  if ((prefix > 0 || overlap > 0) && candidate.numbers.length) {
    const classNumber = profileClassNumber(profile);
    if (classNumber) {
      const [from, to = from] = candidate.numbers;
      score += classNumber >= from && classNumber <= to ? 20 : -5;
    }
  }

  score += Math.min(10, overlap * 2);
  score -= Math.min(18, missing * 9);
  return Math.max(0, Math.min(100, score));
}

/**
 * 在候选里挑出最可能的班级。
 * confident 需要同时满足「分数够高」与「明显领先第二名」，否则只提示用户手动选择。
 */
export function pickBestClass(profile, candidates) {
  if (!profile || !Array.isArray(candidates) || !candidates.length) return null;
  const scored = candidates
    .map(item => ({ ...item, score: scoreClass(profile, item.name) }))
    .sort((left, right) => right.score - left.score);
  const best = scored[0];
  const second = scored[1];
  const margin = best.score - (second ? second.score : 0);
  return {
    name: best.name,
    campus: best.campus,
    score: best.score,
    margin,
    confident: best.score >= 60 && margin >= 10
  };
}
