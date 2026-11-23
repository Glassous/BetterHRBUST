/**
 * 哈尔滨理工大学官方标准上课时间（作息时间表）
 *
 * 小节（BASE，1~12 节）与 大节（COMBINE，1~6 节）的起止时间。
 * 数据来源：学校官方作息表
 *   第 1、2 节   8:10-8:55   9:05-9:50
 *   第 3、4 节  10:10-10:55 11:05-11:50
 *   第 5、6 节  13:30-14:15 14:25-15:10
 *   第 7、8 节  15:30-16:15 16:25-17:10
 *   第 9、10 节 18:00-18:45 18:55-19:40
 *   第 11、12 节 19:50-20:35 20:45-21:30
 */

export const BASE_SLOT_TIMES = [
  { period: 1, time: '08:10-08:55' },
  { period: 2, time: '09:05-09:50' },
  { period: 3, time: '10:10-10:55' },
  { period: 4, time: '11:05-11:50' },
  { period: 5, time: '13:30-14:15' },
  { period: 6, time: '14:25-15:10' },
  { period: 7, time: '15:30-16:15' },
  { period: 8, time: '16:25-17:10' },
  { period: 9, time: '18:00-18:45' },
  { period: 10, time: '18:55-19:40' },
  { period: 11, time: '19:50-20:35' },
  { period: 12, time: '20:45-21:30' }
];

export const COMBINE_SLOT_TIMES = [
  { period: 1, time: '08:10 - 09:50' },
  { period: 2, time: '10:10 - 11:50' },
  { period: 3, time: '13:30 - 15:10' },
  { period: 4, time: '15:30 - 17:10' },
  { period: 5, time: '18:00 - 19:40' },
  { period: 6, time: '19:50 - 21:30' }
];

/**
 * 取第 n 大节（COMBINE，1~6）的起止时间，如 '08:10 - 09:50'；越界返回空串。
 */
export function getCombineSlotTime(period) {
  return COMBINE_SLOT_TIMES.find(s => s.period === period)?.time || '';
}

/**
 * 解析 "HH:MM" 为当天分钟数；非法格式返回 null。
 */
export function parseTimeToMinutes(hhmm) {
  const m = String(hhmm || '').trim().match(/^(\d{1,2}):(\d{2})$/);
  if (!m) return null;
  const hours = Number(m[1]);
  const minutes = Number(m[2]);
  if (!Number.isInteger(hours) || !Number.isInteger(minutes)) return null;
  if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null;
  return hours * 60 + minutes;
}

/**
 * 解析时间区间字符串（如 '08:10 - 09:50' 或 '08:10-08:55'）。
 */
export function parseSlotRangeText(range) {
  const m = String(range || '').match(/(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})/);
  if (!m) return null;
  const startMinutes = parseTimeToMinutes(m[1]);
  const endMinutes = parseTimeToMinutes(m[2]);
  if (startMinutes === null || endMinutes === null) return null;
  return {
    start: m[1].padStart(5, '0'),
    end: m[2].padStart(5, '0'),
    startMinutes,
    endMinutes,
    text: `${m[1].padStart(5, '0')} - ${m[2].padStart(5, '0')}`
  };
}

/**
 * 取指定节次的时间区间对象。
 */
export function getSlotTimeRange(period, viewMode = 'combine') {
  const list = viewMode === 'base' ? BASE_SLOT_TIMES : COMBINE_SLOT_TIMES;
  const slot = list.find(s => s.period === Number(period));
  return slot ? parseSlotRangeText(slot.time) : null;
}

/**
 * 取课程单元格的有效起止时间（优先采用自定义时间，否则回落到标准节次时间）。
 */
export function getCourseTimeRange(course, viewMode = 'combine') {
  if (course?.customStartTime && course?.customEndTime) {
    const startMinutes = parseTimeToMinutes(course.customStartTime);
    const endMinutes = parseTimeToMinutes(course.customEndTime);
    if (startMinutes !== null && endMinutes !== null) {
      return {
        start: course.customStartTime,
        end: course.customEndTime,
        startMinutes,
        endMinutes,
        text: `${course.customStartTime} - ${course.customEndTime}`,
        isCustom: true
      };
    }
  }
  const slotRange = getSlotTimeRange(course?.sectionIndex, viewMode);
  return slotRange ? { ...slotRange, isCustom: false } : null;
}

/** 下课时刻起即视为结束，避免课程结束后仍显示待上。 */
export function hasCombineSlotEnded(period, now = new Date()) {
  const time = getCombineSlotTime(period).split('-')[1]?.trim();
  if (!time) return false;
  const [hours, minutes] = time.split(':').map(Number);
  return now.getHours() * 60 + now.getMinutes() >= hours * 60 + minutes;
}

/** 判断课程（含自定义时段实验课）在当前时刻是否已经下课。 */
export function hasCourseEnded(course, now = new Date(), viewMode = 'combine') {
  const range = getCourseTimeRange(course, viewMode);
  if (!range) return false;
  return now.getHours() * 60 + now.getMinutes() >= range.endMinutes;
}
