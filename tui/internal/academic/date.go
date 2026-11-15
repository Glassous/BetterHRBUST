// Package academic 是学业统计、校历换算与展示格式化的纯函数集合。
//
// 口径来源是 `web/src/services/academic/stats.js`，Rust 命令行
// （`desktop-tauri/src-tauri/src/cli/stats.rs`）是同一份算法的等价移植，
// 两端各有测试守住同一批黄金数值。本包是第三份实现，因此把黄金用例
// 抽到 `shared/parity/*.json` 由两端共读（见 parity_test.go）。
//
// 三条纪律：
//   - **不做 I/O**：不读文件、不发网络请求；
//   - **不调 time.Now()**：所有时间相关入参都是显式 Date，便于复现测试；
//   - **不改入参**：所有函数返回新切片，调用方可以安全共享缓存数据。
package academic

import "fmt"

// Date 是公历日期（不含时区信息），只承载年月日。
type Date struct {
	Year  int
	Month int
	Day   int
}

// String 返回 `YYYY-MM-DD`。
func (d Date) String() string {
	return fmt.Sprintf("%04d-%02d-%02d", d.Year, d.Month, d.Day)
}

// DayNumber 返回距 1970-01-01 的天数，用于日期比较与差值。
func (d Date) DayNumber() int64 {
	return daysFromCivil(d.Year, d.Month, d.Day)
}

// Weekday 返回 ISO 星期：1 = 周一 … 7 = 周日（与课表 day 字段同口径）。
func (d Date) Weekday() int {
	weekday := (d.DayNumber() + 4) % 7
	if weekday < 0 {
		weekday += 7
	}
	if weekday == 0 {
		return 7
	}
	return int(weekday)
}

// AddDays 返回偏移若干天后的日期。
func (d Date) AddDays(days int) Date {
	return FromDayNumber(d.DayNumber() + int64(days))
}

// Compare 返回 -1 / 0 / 1。
func (d Date) Compare(other Date) int {
	left, right := d.DayNumber(), other.DayNumber()
	switch {
	case left < right:
		return -1
	case left > right:
		return 1
	default:
		return 0
	}
}

// Before 判断 d 是否早于 other。
func (d Date) Before(other Date) bool { return d.DayNumber() < other.DayNumber() }

// FromDayNumber 把距 1970-01-01 的天数还原成公历日期。
func FromDayNumber(days int64) Date {
	year, month, day := civilFromDays(days)
	return Date{Year: year, Month: month, Day: day}
}

// ParseISODate 解析 `YYYY-MM-DD`；月份或日期越界时返回 false。
func ParseISODate(text string) (Date, bool) {
	var year, month, day int
	if _, err := fmt.Sscanf(text, "%4d-%2d-%2d", &year, &month, &day); err != nil {
		// 手工再试一次：Sscanf 对 1 位的月份/日期不宽松。
		parsed, ok := splitISODate(text)
		if !ok {
			return Date{}, false
		}
		year, month, day = parsed.Year, parsed.Month, parsed.Day
	}
	if month < 1 || month > 12 || day < 1 || day > 31 {
		return Date{}, false
	}
	return Date{Year: year, Month: month, Day: day}, true
}

// splitISODate 按 `-` 切分并逐段解析。
func splitISODate(text string) (Date, bool) {
	parts := make([]int, 0, 3)
	current := 0
	digits := 0
	for index := 0; index <= len(text); index++ {
		if index == len(text) || text[index] == '-' {
			if digits == 0 {
				return Date{}, false
			}
			parts = append(parts, current)
			current, digits = 0, 0
			continue
		}
		if text[index] < '0' || text[index] > '9' {
			return Date{}, false
		}
		current = current*10 + int(text[index]-'0')
		digits++
	}
	if len(parts) != 3 {
		return Date{}, false
	}
	if parts[1] < 1 || parts[1] > 12 || parts[2] < 1 || parts[2] > 31 {
		return Date{}, false
	}
	return Date{Year: parts[0], Month: parts[1], Day: parts[2]}, true
}

// daysFromCivil 是 Howard Hinnant 的 `days_from_civil`，与 Rust / JS 端逐位一致。
func daysFromCivil(year, month, day int) int64 {
	y := int64(year)
	if month <= 2 {
		y--
	}
	era := y / 400
	if y < 0 && y%400 != 0 {
		era--
	}
	yoe := y - era*400
	m := int64(month)
	mp := m + 9
	if m > 2 {
		mp = m - 3
	}
	doy := (153*mp+2)/5 + int64(day) - 1
	doe := yoe*365 + yoe/4 - yoe/100 + doy
	return era*146097 + doe - 719468
}

// civilFromDays 是 `daysFromCivil` 的逆运算。
func civilFromDays(days int64) (int, int, int) {
	z := days + 719468
	era := z / 146097
	if z < 0 && z%146097 != 0 {
		era--
	}
	doe := z - era*146097
	yoe := (doe - doe/1460 + doe/36524 - doe/146096) / 365
	y := yoe + era*400
	doy := doe - (365*yoe + yoe/4 - yoe/100)
	mp := (5*doy + 2) / 153
	day := doy - (153*mp+2)/5 + 1
	month := mp + 3
	if mp >= 10 {
		month = mp - 9
	}
	if month <= 2 {
		y++
	}
	return int(y), int(month), int(day)
}
