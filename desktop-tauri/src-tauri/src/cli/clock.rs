//! 本地时间与时间戳格式化
//!
//! 命令行需要"当前教学周 / 今日剩余课程 / 考试倒计时"这类依赖本地时钟的判断，
//! 而项目没有引入 chrono 之类的依赖（体积与依赖面优先）。
//! Windows 下直接用 `GetLocalTime` / `FileTimeToLocalFileTime` + `FileTimeToSystemTime`
//! 取本地时间，非 Windows 平台用 UTC 兜底（本项目只发布 Windows 版）。

/// 本地日期时间（字段与 SYSTEMTIME 对齐）
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LocalDateTime {
    pub year: i32,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
    pub second: u32,
}

impl LocalDateTime {
    /// 当天 00:00 的"日编号"（从公历 1970-01-01 起的天数），用于日期比较与差值
    pub fn day_number(self) -> i64 {
        days_from_civil(self.year, self.month, self.day)
    }

    /// 一年中的第几天（1 起，单测用于校验公历换算）
    #[cfg(test)]
    fn year_day(self) -> u32 {
        let leap = is_leap(self.year);
        const DAYS: [u32; 12] = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
        let mut total = 0u32;
        for month in 1..self.month {
            total += DAYS[(month - 1) as usize];
            if month == 2 && leap {
                total += 1;
            }
        }
        total + self.day
    }

    /// ISO 星期：1 = 周一 … 7 = 周日（课表 day 字段同口径）
    pub fn weekday_index(self) -> u32 {
        let day = days_from_civil(self.year, self.month, self.day);
        // 1970-01-01 是周四
        let weekday = (day + 4).rem_euclid(7) as u32;
        if weekday == 0 {
            7
        } else {
            weekday
        }
    }

    /// 当天已过的分钟数
    pub fn minutes_of_day(self) -> u32 {
        self.hour * 60 + self.minute
    }
}

#[cfg(test)]
fn is_leap(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

/// 公历 → 1970-01-01 起的天数（Howard Hinnant 的 civil_from_days 逆运算）
fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let year = (if month <= 2 { year - 1 } else { year }) as i64;
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let yoe = year - era * 400;
    let month = month as i64;
    let doy = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// 天数 → 公历（对外：教学周第 N 天 → 日期标签）
pub fn from_day_number(days: i64) -> (i32, u32, u32) {
    civil_from_days(days)
}

/// 解析 `YYYY-MM-DD`（校历日期格式）为当天 00:00
pub fn parse_iso_date(text: &str) -> Option<LocalDateTime> {
    let mut parts = text.trim().split('-');
    let year: i32 = parts.next()?.parse().ok()?;
    let month: u32 = parts.next()?.parse().ok()?;
    let day: u32 = parts.next()?.parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    Some(LocalDateTime { year, month, day, hour: 0, minute: 0, second: 0 })
}

/// 天数 → 公历（Howard Hinnant 算法）
fn civil_from_days(days: i64) -> (i32, u32, u32) {
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    ((if month <= 2 { y + 1 } else { y }) as i32, month, day)
}

/// 当前本地时间
pub fn now() -> LocalDateTime {
    #[cfg(windows)]
    {
        if let Some(dt) = win_time::now_local() {
            return dt;
        }
    }
    // 兜底：按 UTC 计算（非 Windows 平台或 API 失败时仍能给出可用结果）
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let (year, month, day) = civil_from_days(seconds.div_euclid(86_400));
    LocalDateTime {
        year,
        month,
        day,
        hour: (seconds.rem_euclid(86_400) / 3600) as u32,
        minute: (seconds.rem_euclid(3600) / 60) as u32,
        second: (seconds.rem_euclid(60)) as u32,
    }
}

/// 毫秒时间戳 → 本地时间（缓存里的 lastSyncAt 等）
pub fn from_epoch_ms(ms: u64) -> LocalDateTime {
    #[cfg(windows)]
    {
        if let Some(dt) = win_time::local_from_epoch_ms(ms) {
            return dt;
        }
    }
    let seconds = (ms / 1000) as i64;
    let (year, month, day) = civil_from_days(seconds.div_euclid(86_400));
    LocalDateTime {
        year,
        month,
        day,
        hour: (seconds.rem_euclid(86_400) / 3600) as u32,
        minute: (seconds.rem_euclid(3600) / 60) as u32,
        second: (seconds.rem_euclid(60)) as u32,
    }
}

/// `2026-11-13 09:15`
pub fn format_minute(dt: LocalDateTime) -> String {
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}",
        dt.year, dt.month, dt.day, dt.hour, dt.minute
    )
}

/// `2026-11-13`
pub fn format_date(dt: LocalDateTime) -> String {
    format!("{:04}-{:02}-{:02}", dt.year, dt.month, dt.day)
}

/// ISO 星期名
pub fn weekday_name(index: u32) -> &'static str {
    match index {
        1 => "周一",
        2 => "周二",
        3 => "周三",
        4 => "周四",
        5 => "周五",
        6 => "周六",
        7 => "周日",
        _ => "未知",
    }
}

#[cfg(windows)]
mod win_time {
    use super::LocalDateTime;
    use std::ffi::c_void;

    #[repr(C)]
    #[derive(Default)]
    struct SystemTime {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }

    #[repr(C)]
    #[derive(Default)]
    struct FileTime {
        low: u32,
        high: u32,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GetLocalTime(system_time: *mut SystemTime);
        fn FileTimeToLocalFileTime(file_time: *const FileTime, local: *mut FileTime) -> i32;
        fn FileTimeToSystemTime(file_time: *const FileTime, system_time: *mut SystemTime) -> i32;
    }

    fn convert(st: SystemTime) -> LocalDateTime {
        LocalDateTime {
            year: st.year as i32,
            month: st.month as u32,
            day: st.day as u32,
            hour: st.hour as u32,
            minute: st.minute as u32,
            second: st.second as u32,
        }
    }

    pub fn now_local() -> Option<LocalDateTime> {
        let mut st = SystemTime::default();
        unsafe { GetLocalTime(&mut st as *mut SystemTime) };
        if st.year < 1980 {
            return None;
        }
        Some(convert(st))
    }

    /// 毫秒时间戳 → 本地时间。FILETIME 以 1601-01-01 起 100ns 为单位
    pub fn local_from_epoch_ms(ms: u64) -> Option<LocalDateTime> {
        const EPOCH_DIFF_100NS: u64 = 116_444_736_000_000_000;
        let ticks = ms.checked_mul(10_000)?.checked_add(EPOCH_DIFF_100NS)?;
        let file_time = FileTime { low: ticks as u32, high: (ticks >> 32) as u32 };
        let mut local = FileTime::default();
        let mut system = SystemTime::default();
        unsafe {
            if FileTimeToLocalFileTime(&file_time as *const FileTime, &mut local as *mut FileTime) == 0
            {
                return None;
            }
            if FileTimeToSystemTime(&local as *const FileTime, &mut system as *mut SystemTime) == 0 {
                return None;
            }
        }
        Some(convert(system))
    }

    // 保持 c_void 引用，避免部分工具链对未使用导入报错
    #[allow(dead_code)]
    type Reserved = *mut c_void;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_roundtrip_matches_known_dates() {
        // 2026-11-13 是周五
        let dt = LocalDateTime { year: 2026, month: 11, day: 13, hour: 0, minute: 0, second: 0 };
        assert_eq!(dt.weekday_index(), 5);
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(civil_from_days(days_from_civil(2026, 11, 13)), (2026, 11, 13));
        // 闰年边界
        assert_eq!(civil_from_days(days_from_civil(2024, 2, 29)), (2024, 2, 29));
        assert_eq!(dt.year_day(), 317);
    }

    #[test]
    fn weekday_of_known_sunday() {
        let dt = LocalDateTime { year: 2026, month: 11, day: 15, hour: 12, minute: 30, second: 0 };
        assert_eq!(dt.weekday_index(), 7, "2026-11-15 是周日");
        assert_eq!(dt.minutes_of_day(), 750);
        assert_eq!(format_minute(dt), "2026-11-15 12:30");
    }
}
