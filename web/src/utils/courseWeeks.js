/**
 * 课程周次匹配
 *
 * 教务系统的周次表达式形如「1-16周(单)」「3-5,9-16周(双)」「1-17周」。
 * 解析单/双周修饰与数字区间，判断课程在指定教学周是否有课。
 */

/**
 * 判断课程在指定教学周是否上课。
 * 周次表达式为空时视为全学期均有课。
 * @param {{ weeks?: string }} course
 * @param {number} week 目标教学周 (1~26)
 * @returns {boolean}
 */
export function isCourseActiveInWeek(course, week) {
  const expr = String(course?.weeks || '');
  if (!expr) return true;

  const isOdd = expr.includes('单');
  const isEven = expr.includes('双');
  if (isOdd && week % 2 === 0) return false;
  if (isEven && week % 2 !== 0) return false;

  const parts = expr.match(/\d+(?:-\d+)?/g) || [];
  for (const p of parts) {
    const [start, end] = p.split('-').map(Number);
    const to = end || start;
    if (week >= start && week <= to) return true;
  }
  return parts.length === 0;
}

/**
 * 把周次表达式展开为具体教学周列表。
 * 表达式为空时视为全学期（1~maxWeek）。
 * @param {string} weeksExpr 形如「1-16周(单)」「3-5,9-16周(双)」
 * @param {number} [maxWeek] 空表达式的上限，默认 26
 * @returns {number[]}
 */
export function expandCourseWeeks(weeksExpr, maxWeek = 26) {
  const expr = String(weeksExpr || '');
  const isOdd = expr.includes('单');
  const isEven = expr.includes('双');
  const parts = expr.match(/\d+(?:-\d+)?/g) || [];
  const weeks = [];
  if (parts.length === 0) {
    for (let w = 1; w <= maxWeek; w += 1) weeks.push(w);
  } else {
    for (const p of parts) {
      const [start, end] = p.split('-').map(Number);
      const to = end || start;
      for (let w = start; w <= to && w <= maxWeek; w += 1) weeks.push(w);
    }
  }
  // 单周保留奇数周，双周保留偶数周（两者互斥，同时出现时按更严格的一侧过滤）
  return weeks.filter(w => (!isOdd || w % 2 !== 0) && (!isEven || w % 2 === 0));
}
