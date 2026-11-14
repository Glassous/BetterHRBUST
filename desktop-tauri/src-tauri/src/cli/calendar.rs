//! 校历、教学周、节次时间与考试时间解析
//!
//! 均为前端同名模块的等价实现，内嵌同一份校历数据
//! （`shared/school-calendar.json`，网页 / 油猴 / Android 共用）：
//!  - `web/src/utils/schoolCalendar.js`：`semesterOn` / `teachingWeekOn`
//!  - `web/src/utils/courseWeeks.js`：`isCourseActiveInWeek` / `expandCourseWeeks`
//!  - `web/src/utils/periodTimes.js`：大节起止时间与"是否已下课"
//!  - `web/src/utils/examTime.js`：考试时间文本解析
//!  - `web/src/utils/timetableDates.js`：教学周第 N 天的日期标签
//!
//! 命令行不能凭空猜学期起点：校历未收录的学期按"未知学期"处理，
//! 周次相关判断退回宽松兜底，与图形界面行为保持一致。

use super::clock::{self, LocalDateTime};

/// 内嵌校历（发布时随二进制一起走，无需网络与额外文件）
const CALENDAR_JSON: &str = include_str!("../../../../shared/school-calendar.json");

#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Semester {
    pub label: String,
    pub start_date: String,
    pub teaching_end_date: String,
    pub period_end_date: String,
    pub teaching_weeks: u32,
}

#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct Calendar {
    semesters: Vec<Semester>,
}

fn calendar() -> &'static Calendar {
    static CALENDAR: std::sync::OnceLock<Calendar> = std::sync::OnceLock::new();
    CALENDAR.get_or_init(|| {
        serde_json::from_str(CALENDAR_JSON).expect("内嵌校历 JSON 由仓库维护，必须可解析")
    })
}

pub fn semesters() -> &'static [Semester] {
    &calendar().semesters
}

/// 当天所在学期（含考试周，按 periodEndDate 收尾）
pub fn semester_on(today: LocalDateTime) -> Option<&'static Semester> {
    let day = today.day_number();
    semesters().iter().find(|semester| {
        let start = clock::parse_iso_date(&semester.start_date).map(LocalDateTime::day_number);
        let end = clock::parse_iso_date(&semester.period_end_date).map(LocalDateTime::day_number);
        matches!((start, end), (Some(start), Some(end)) if day >= start && day <= end)
    })
}

/// 当前教学周（1 起）。假期 / 校历未收录的学期返回 None
pub fn teaching_week_on(today: LocalDateTime) -> Option<u32> {
    let semester = semester_on(today)?;
    let start = clock::parse_iso_date(&semester.start_date)?.day_number();
    let end = clock::parse_iso_date(&semester.teaching_end_date)?.day_number();
    let day = today.day_number();
    if day < start || day > end {
        return None;
    }
    Some(((day - start) / 7) as u32 + 1)
}

/// 当前学期的教学周总数（校历未收录时按 20 周兜底，与前端一致）
pub fn teaching_weeks(today: LocalDateTime) -> u32 {
    semester_on(today).map(|semester| semester.teaching_weeks).unwrap_or(20)
}

// ---------------- 周次表达式 ----------------

/// 提取 `\d+(?:-\d+)?` 形式的区间，如「3-5,9-16周(双)」→ [(3,5),(9,16)]
fn week_ranges(expression: &str) -> Vec<(u32, u32)> {
    let chars: Vec<char> = expression.chars().collect();
    let mut ranges = Vec::new();
    let mut index = 0usize;
    while index < chars.len() {
        if !chars[index].is_ascii_digit() {
            index += 1;
            continue;
        }
        let start_begin = index;
        while index < chars.len() && chars[index].is_ascii_digit() {
            index += 1;
        }
        let start: u32 = chars[start_begin..index].iter().collect::<String>().parse().unwrap_or(0);
        let mut end = start;
        if index + 1 < chars.len() && chars[index] == '-' && chars[index + 1].is_ascii_digit() {
            index += 1;
            let end_begin = index;
            while index < chars.len() && chars[index].is_ascii_digit() {
                index += 1;
            }
            let parsed: u32 =
                chars[end_begin..index].iter().collect::<String>().parse().unwrap_or(0);
            // 与 JS `const to = end || start` 一致：缺省或 0 时退回起始周
            end = if parsed == 0 { start } else { parsed };
        }
        ranges.push((start, end));
    }
    ranges
}

/// 课程在指定教学周是否上课（空表达式视为全学期）
pub fn is_course_active_in_week(weeks: &str, week: u32) -> bool {
    if weeks.is_empty() {
        return true;
    }
    let odd_only = weeks.contains('单');
    let even_only = weeks.contains('双');
    if odd_only && week % 2 == 0 {
        return false;
    }
    if even_only && week % 2 != 0 {
        return false;
    }
    let ranges = week_ranges(weeks);
    if ranges.iter().any(|(start, end)| week >= *start && week <= *end) {
        return true;
    }
    ranges.is_empty()
}

/// 展开为具体教学周列表（单/双周过滤）
///
/// 与前端 `expandCourseWeeks` 对齐：命令行目前只用"某周是否有课"的判定，
/// 但保留展开能力以便后续按周导出，并由单测守住与前端一致的语义。
#[cfg_attr(not(test), allow(dead_code))]
pub fn expand_course_weeks(expression: &str, max_week: u32) -> Vec<u32> {
    let odd_only = expression.contains('单');
    let even_only = expression.contains('双');
    let ranges = week_ranges(expression);
    let mut weeks = Vec::new();
    if ranges.is_empty() {
        for week in 1..=max_week {
            weeks.push(week);
        }
    } else {
        for (start, end) in ranges {
            let mut week = start;
            while week <= end && week <= max_week {
                weeks.push(week);
                week += 1;
            }
        }
    }
    weeks
        .into_iter()
        .filter(|week| (!odd_only || week % 2 != 0) && (!even_only || week % 2 == 0))
        .collect()
}

// ---------------- 节次时间 ----------------

/// 大节（COMBINE，1~6）起止时间，来源：学校官方作息时间表
pub const COMBINE_SLOTS: [(u32, &str); 6] = [
    (1, "08:10 - 09:50"),
    (2, "10:10 - 11:50"),
    (3, "13:30 - 15:10"),
    (4, "15:30 - 17:10"),
    (5, "18:00 - 19:40"),
    (6, "19:50 - 21:30"),
];

pub fn combine_slot_time(period: u32) -> &'static str {
    COMBINE_SLOTS
        .iter()
        .find(|(index, _)| *index == period)
        .map(|(_, time)| *time)
        .unwrap_or("")
}

/// 大节下课时刻（当天分钟数）
pub fn combine_slot_end_minutes(period: u32) -> Option<u32> {
    let time = combine_slot_time(period);
    let end = time.split('-').nth(1)?.trim();
    let mut parts = end.split(':');
    let hour: u32 = parts.next()?.trim().parse().ok()?;
    let minute: u32 = parts.next()?.trim().parse().ok()?;
    Some(hour * 60 + minute)
}

/// 下课时刻起即视为结束（避免课程结束后仍显示"待上"）
pub fn has_combine_slot_ended(period: u32, minutes_of_day: u32) -> bool {
    match combine_slot_end_minutes(period) {
        Some(end) => minutes_of_day >= end,
        None => false,
    }
}

// ---------------- 教学周日期标签 ----------------

/// 所选教学周第 `day`（1~7）天的 `MM/DD` 标签；无法确定时返回空串
pub fn timetable_day_date(
    current_week: u32,
    selected_week: u32,
    day: u32,
    today: LocalDateTime,
) -> String {
    if !(1..=7).contains(&day) || !(1..=26).contains(&current_week) {
        return String::new();
    }
    match semester_on(today) {
        Some(semester) => {
            if !(1..=semester.teaching_weeks).contains(&selected_week) {
                return String::new();
            }
            let Some(start) = clock::parse_iso_date(&semester.start_date) else {
                return String::new();
            };
            let offset = (selected_week as i64 - 1) * 7 + day as i64 - 1;
            let (_, month, day_of_month) = clock::from_day_number(start.day_number() + offset);
            format!("{month:02}/{day_of_month:02}")
        }
        None => {
            if !(1..=26).contains(&selected_week) {
                return String::new();
            }
            let monday = today.day_number() - (today.weekday_index() as i64 - 1);
            let offset = (selected_week as i64 - current_week as i64) * 7 + day as i64 - 1;
            let (_, month, day_of_month) = clock::from_day_number(monday + offset);
            format!("{month:02}/{day_of_month:02}")
        }
    }
}

// ---------------- 考试时间 ----------------

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ExamTime {
    /// 考试日期（当天 00:00），用于"是否近期考试"判定
    pub date: LocalDateTime,
    /// 开考时刻（时, 分）；原文缺少时间时为 None
    pub start: Option<(u32, u32)>,
    /// 结束时刻
    pub end: Option<(u32, u32)>,
}

/// 解析「2026-01-05 14:00-16:00」「2026年1月5日 14:00~16:00」
pub fn parse_exam_datetime(text: &str) -> Option<ExamTime> {
    let chars: Vec<char> = text.chars().collect();
    let (year, month, day, used) = parse_date_part(&chars)?;
    let date = LocalDateTime { year, month, day, hour: 0, minute: 0, second: 0 };
    let (start, end) = parse_time_range(&chars[used..]).unwrap_or((None, None));
    Some(ExamTime { date, start, end })
}

/// `(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})`：返回 (年, 月, 日, 消耗到的索引)
fn parse_date_part(chars: &[char]) -> Option<(i32, u32, u32, usize)> {
    let mut index = 0usize;
    while index + 4 <= chars.len() {
        if chars[index..index + 4].iter().all(char::is_ascii_digit) {
            let year: i32 = chars[index..index + 4].iter().collect::<String>().parse().ok()?;
            let mut cursor = index + 4;
            if cursor >= chars.len() || !matches!(chars[cursor], '-' | '/' | '.' | '年') {
                index += 1;
                continue;
            }
            cursor += 1;
            let month_start = cursor;
            while cursor < chars.len() && cursor - month_start < 2 && chars[cursor].is_ascii_digit() {
                cursor += 1;
            }
            if cursor == month_start || cursor >= chars.len() {
                index += 1;
                continue;
            }
            let month: u32 = chars[month_start..cursor].iter().collect::<String>().parse().ok()?;
            if !matches!(chars[cursor], '-' | '/' | '.' | '月') {
                index += 1;
                continue;
            }
            cursor += 1;
            let day_start = cursor;
            while cursor < chars.len() && cursor - day_start < 2 && chars[cursor].is_ascii_digit() {
                cursor += 1;
            }
            if cursor == day_start {
                index += 1;
                continue;
            }
            let day: u32 = chars[day_start..cursor].iter().collect::<String>().parse().ok()?;
            if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
                index += 1;
                continue;
            }
            return Some((year, month, day, cursor));
        }
        index += 1;
    }
    None
}

fn is_time_separator(c: char) -> bool {
    matches!(c, '-' | '–' | '—' | '~' | '～' | '至')
}

/// `(\d{1,2}):(\d{2})\s*(?:[-–—~～]|至|to)\s*(\d{1,2}):(\d{2})`
fn parse_time_range(chars: &[char]) -> Option<(Option<(u32, u32)>, Option<(u32, u32)>)> {
    let mut index = 0usize;
    while index < chars.len() {
        let Some(start) = parse_clock(chars, index) else {
            index += 1;
            continue;
        };
        let mut cursor = start.1;
        while cursor < chars.len() && chars[cursor].is_whitespace() {
            cursor += 1;
        }
        let separator_ok = cursor < chars.len()
            && (is_time_separator(chars[cursor])
                || (chars[cursor].eq_ignore_ascii_case(&'t')
                    && cursor + 1 < chars.len()
                    && chars[cursor + 1].eq_ignore_ascii_case(&'o')));
        if !separator_ok {
            index = start.1;
            continue;
        }
        cursor += if is_time_separator(chars[cursor]) { 1 } else { 2 };
        while cursor < chars.len() && chars[cursor].is_whitespace() {
            cursor += 1;
        }
        if let Some(end) = parse_clock(chars, cursor) {
            return Some((Some(start.0), Some(end.0)));
        }
        index = start.1;
    }
    None
}

/// 在给定位置解析 `H:MM` / `HH:MM`，返回 ((时, 分), 结束索引)
fn parse_clock(chars: &[char], from: usize) -> Option<((u32, u32), usize)> {
    let mut cursor = from;
    let hour_start = cursor;
    while cursor < chars.len() && cursor - hour_start < 2 && chars[cursor].is_ascii_digit() {
        cursor += 1;
    }
    if cursor == hour_start || cursor >= chars.len() || chars[cursor] != ':' {
        return None;
    }
    let hour: u32 = chars[hour_start..cursor].iter().collect::<String>().parse().ok()?;
    cursor += 1;
    let minute_start = cursor;
    while cursor < chars.len() && cursor - minute_start < 2 && chars[cursor].is_ascii_digit() {
        cursor += 1;
    }
    if cursor - minute_start != 2 {
        return None;
    }
    let minute: u32 = chars[minute_start..cursor].iter().collect::<String>().parse().ok()?;
    if hour > 23 || minute > 59 {
        return None;
    }
    Some(((hour, minute), cursor))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dt(year: i32, month: u32, day: u32) -> LocalDateTime {
        LocalDateTime { year, month, day, hour: 0, minute: 0, second: 0 }
    }

    /// 与 web 端 `teachingWeekOn` 断言一致（2026-2027 学年校历）
    #[test]
    fn teaching_week_matches_school_calendar() {
        assert_eq!(teaching_week_on(dt(2026, 10, 10)), Some(6));
        assert_eq!(teaching_week_on(dt(2027, 1, 10)), Some(19));
        assert_eq!(teaching_week_on(dt(2027, 1, 11)), None, "超出教学周即假期");
        assert_eq!(teaching_week_on(dt(2027, 3, 1)), Some(1));
        assert_eq!(teaching_week_on(dt(2027, 7, 11)), Some(19));
        assert_eq!(teaching_week_on(dt(2027, 7, 12)), None);
        assert_eq!(teaching_weeks(dt(2026, 10, 10)), 19);
    }

    /// 与 web 端 `isCourseActiveInWeek` / `expandCourseWeeks` 断言一致
    #[test]
    fn course_weeks_filter_odd_even_and_ranges() {
        assert!(is_course_active_in_week("4-5周", 5));
        assert!(!is_course_active_in_week("4-5周", 11));
        assert!(!is_course_active_in_week("4-5周", 6));
        assert!(is_course_active_in_week("4-5周(单)", 5));
        assert!(!is_course_active_in_week("4-5周(单)", 4));
        assert!(is_course_active_in_week("", 11), "无周次信息视为全学期");
        assert_eq!(expand_course_weeks("1-8周(双)", 8), vec![2, 4, 6, 8]);
        assert_eq!(expand_course_weeks("3-5,9-16周(双)", 16), vec![4, 10, 12, 14, 16]);
    }

    /// 与 web 端 `hasCombineSlotEnded` 断言一致
    #[test]
    fn slot_end_boundary_matches_timetable() {
        assert!(!has_combine_slot_ended(1, 9 * 60 + 49));
        assert!(has_combine_slot_ended(1, 9 * 60 + 50));
        for period in 1..=6 {
            assert!(has_combine_slot_ended(period, 21 * 60 + 30));
        }
        assert_eq!(combine_slot_time(2), "10:10 - 11:50");
        assert_eq!(combine_slot_time(9), "");
    }

    /// 与 web 端 `timetableDayDate` 断言一致
    #[test]
    fn timetable_day_date_matches_web() {
        assert_eq!(timetable_day_date(6, 6, 1, dt(2026, 1, 1)), "12/29");
        assert_eq!(timetable_day_date(6, 7, 1, dt(2026, 1, 1)), "01/05");
        assert_eq!(timetable_day_date(6, 6, 4, dt(2024, 2, 29)), "02/29");
        assert_eq!(timetable_day_date(6, 6, 7, dt(2026, 10, 10)), "10/11");
        assert_eq!(timetable_day_date(26, 6, 6, dt(2026, 10, 10)), "10/10");
        assert_eq!(timetable_day_date(6, 20, 1, dt(2026, 10, 10)), "", "超出教学周上限时不推测日期");
        assert_eq!(timetable_day_date(0, 1, 1, dt(2026, 10, 10)), "");
    }

    #[test]
    fn exam_time_parses_common_formats() {
        let dashed = parse_exam_datetime("2026-01-05 14:00-16:00").expect("应能解析");
        assert_eq!((dashed.date.year, dashed.date.month, dashed.date.day), (2026, 1, 5));
        assert_eq!(dashed.start, Some((14, 0)));
        assert_eq!(dashed.end, Some((16, 0)));

        let chinese = parse_exam_datetime("2026年1月5日 14:00~16:00").expect("应能解析");
        assert_eq!(chinese.start, Some((14, 0)));
        assert_eq!(chinese.end, Some((16, 0)));

        let date_only = parse_exam_datetime("2026/1/5").expect("应能解析");
        assert_eq!(date_only.start, None, "只有日期时不能编造开考时间");

        assert!(parse_exam_datetime("待定").is_none());
    }
}
