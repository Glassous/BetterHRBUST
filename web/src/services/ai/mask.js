/**
 * 学籍打码（与 TUI `internal/academic/format.go` 逐参数一致）
 *
 * 这是唯一允许把学籍字段交给模型的入口：证件号 / 手机号 / 邮箱 / 地址
 * 在离开进程之前就已打码，模型永远拿不到明文。
 * 参数表改一个字，三端的脱敏口径就会分叉，因此不要局部调整。
 */

/** 折叠连续空白（避免换行 / 制表符把表格排版搞乱） */
export function collapseWhitespace(value) {
  return String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
}

/** 保留首尾、中间换成 `*`；长度不足时全部打码 */
export function maskMiddle(text, keepHead, keepTail) {
  const value = String(text == null ? '' : text);
  if (!value) return '';
  if (value.length <= keepHead + keepTail) {
    return '*'.repeat(value.length);
  }
  return `${value.slice(0, keepHead)}${'*'.repeat(value.length - keepHead - keepTail)}${value.slice(value.length - keepTail)}`;
}

/** 折叠空白 → 打码 → 截断到 40 列（与 TUI 的 MaskedField 一致） */
export function maskedField(text, keepHead, keepTail) {
  const masked = maskMiddle(collapseWhitespace(text), keepHead, keepTail);
  return masked.length > 40 ? `${masked.slice(0, 39)}…` : masked;
}

/** 打码参数表：idCard 4/2 · phone 3/2 · email 2/0 · address 6/0 */
export const MASKED_PROFILE_FIELDS = [
  { field: 'idCard', keepHead: 4, keepTail: 2 },
  { field: 'phone', keepHead: 3, keepTail: 2 },
  { field: 'email', keepHead: 2, keepTail: 0 },
  { field: 'address', keepHead: 6, keepTail: 0 }
];

/** 学籍脱敏：对外（模型上下文）只允许用这个函数的返回值 */
export function maskProfile(profile) {
  const source = profile || {};
  const masked = {
    studentNumber: collapseWhitespace(source.studentNumber),
    realName: collapseWhitespace(source.realName),
    college: collapseWhitespace(source.college),
    major: collapseWhitespace(source.major),
    direction: collapseWhitespace(source.direction),
    grade: collapseWhitespace(source.grade),
    className: collapseWhitespace(source.className),
    studentType: collapseWhitespace(source.studentType),
    status: collapseWhitespace(source.status)
  };
  for (const { field, keepHead, keepTail } of MASKED_PROFILE_FIELDS) {
    masked[field] = maskedField(source[field], keepHead, keepTail);
  }
  // 学籍变动记录可能夹带联系方式，同样折叠空白后只保留文本
  // （字段名与 parser.js 的 parsePersonalInfo 输出一致：type/date/reason/remark）
  masked.changes = Array.isArray(source.changes)
    ? source.changes.slice(0, 20).map(item => ({
        type: collapseWhitespace(item?.type),
        date: collapseWhitespace(item?.date),
        reason: collapseWhitespace(item?.reason),
        remark: collapseWhitespace(item?.remark)
      }))
    : [];
  return masked;
}
