/**
 * 课表单元格字段拆解
 *
 * 教务单元格的常规格式是「课程名 / 地点 / 教师 / 周次 / 学时类型」五行，
 * 但行数并不固定：
 * - 没录入任课教师的课程（军训、体育等实践类课程）会少一行，后续字段整体前移；
 * - 部分课程在周次之后还会多出一行说明（如「一上午」），学时类型被挤到下一行。
 *
 * 按固定下标读取就会错位：缺教师时「学时类型」被当成周次，
 * 周次判断随之失效 —— 已经结束的课程在后续教学周仍然显示为正常颜色。
 * 因此这里以「周次行」为锚点（同时包含数字与「周」）定位，再按相对位置取其余字段。
 */

/** 周次表达式：至少含一个数字且含「周」，如「1-16周」「3-5,9-16周(双)」 */
function isWeeksLine(line) {
  return /\d/.test(line) && line.includes('周');
}

/**
 * 从单元格各行拆出地点 / 教师 / 周次 / 学时类型。
 * @param {string[]} lines 以 `<br>` 切分并解码后的单元格文本，lines[0] 为课程名行
 * @returns {{ location: string, teacher: string, weeks: string, hoursType: string }}
 */
export function splitCourseCellFields(lines) {
  const list = Array.isArray(lines) ? lines : [];

  // 兜底：无法识别周次行时按常规五行位置读取，行为与原实现保持一致
  const fallback = {
    location: list[1] || '',
    teacher: list[2] || '',
    weeks: list[3] || '',
    hoursType: list[4] || ''
  };

  const rest = list.slice(1);
  const weeksIndex = rest.findIndex(isWeeksLine);
  if (weeksIndex < 0) return fallback;

  const before = rest.slice(0, weeksIndex);
  const after = rest.slice(weeksIndex + 1);

  let location = '';
  let teacher = '';
  if (before.length >= 2) {
    location = before[0];
    teacher = before.slice(1).join(' ');
  } else if (before.length === 1) {
    // 只剩一行时无法可靠区分地点与教师：按地点处理（教务中地点通常必填、教师可缺省）。
    // 即便实际是教师名，也只影响这一行的展示，不影响周次判断。
    location = before[0];
  }

  // 周次之后是学时类型；存在多余说明行时优先取带「学时」的那一行
  const hoursType = after.find(line => line.includes('学时')) || after[0] || '';

  return { location, teacher, weeks: rest[weeksIndex], hoursType };
}
