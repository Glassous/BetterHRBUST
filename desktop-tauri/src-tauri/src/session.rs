//! 教务会话桥接：与终端界面（Go TUI）共用同一份会话 Cookie。
//!
//! 背景：WebView2 的 Cookie 存在它自己的用户数据目录里，终端读不到；
//! 而终端登录后 Cookie 只在自己进程里，图形界面也不知道。结果就是
//! "在终端登录一次，图形界面还要再登一次"，这与"两端打通"的目标相悖。
//!
//! 桥接文件 `{数据目录}/academic-session.json` 解决这个问题，两个方向：
//!
//! | 方向 | 触发点 | 行为 |
//! | --- | --- | --- |
//! | 导入 | `/academic/*` 请求缺少某个 Cookie | 从文件补齐（终端登录过 → 图形界面直接可用） |
//! | 导出 | 上游响应带 `Set-Cookie` | 写回文件（图形界面登录过 → 终端直接可用） |
//!
//! 结构由两端共同约定，字段与 `tui/internal/campus/session.go` 一一对应；
//! 权限降到 0600，文件里只有教务系统签发的会话 Cookie。
//! 任何 I/O 失败都只打印一行日志，绝不阻断代理请求。

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

/// 会话桥接文件名（与 Go 侧 `campus.SessionFileName` 一致）。
pub const FILE_NAME: &str = "academic-session.json";
/// 文件结构版本。
pub const VERSION: u32 = 1;
/// 会话所属的教务系统 origin。
pub const ORIGIN: &str = "http://jwzx.hrbust.edu.cn";

/// 单个 Cookie。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredCookie {
    pub name: String,
    #[serde(default)]
    pub value: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub path: String,
    /// 毫秒时间戳；0 表示会话 Cookie。
    #[serde(default, rename = "expiresAt", skip_serializing_if = "is_zero")]
    pub expires_at: i64,
}

fn is_zero(value: &i64) -> bool {
    *value == 0
}

/// 磁盘结构（与 Go 侧 `sessionFile` 一致）。
#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionFile {
    version: u32,
    origin: String,
    source: String,
    #[serde(rename = "updatedAt")]
    updated_at: i64,
    #[serde(default, rename = "studentNumber", skip_serializing_if = "String::is_empty")]
    student_number: String,
    #[serde(default)]
    cookies: Vec<StoredCookie>,
}

/// 会话桥接：内存里保留一份 Cookie，磁盘上是它与终端之间的交接点。
pub struct SessionBridge {
    path: PathBuf,
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    /// 上次读盘时文件的修改时间，用于发现终端写入了新会话。
    loaded_at: Option<SystemTime>,
    student_number: String,
    cookies: BTreeMap<String, StoredCookie>,
}

impl SessionBridge {
    /// 在数据目录下打开（或初始化）桥接文件。
    pub fn open(dir: &Path) -> Self {
        let bridge = Self {
            path: dir.join(FILE_NAME),
            inner: Mutex::new(Inner::default()),
        };
        bridge.refresh();
        bridge
    }

    /// 返回当前可用（未过期）的 Cookie 快照。
    ///
    /// 每次调用先检查文件是否被终端更新过：终端在图形界面运行期间登录时，
    /// 下一次请求就能用上新会话，不需要重启。
    pub fn cookies(&self) -> Vec<StoredCookie> {
        self.refresh();
        let now = now_millis();
        let guard = self.inner.lock().expect("会话桥接锁中毒");
        guard
            .cookies
            .values()
            .filter(|cookie| cookie.expires_at == 0 || cookie.expires_at > now)
            .cloned()
            .collect()
    }

    /// 批量导出响应头里的 Set-Cookie。
    ///
    /// 空值（教务删除 Cookie / 登出）会从文件里移除，与浏览器语义一致。
    pub fn export<'a, I>(&self, cookies: I)
    where
        I: IntoIterator<Item = &'a str>,
    {
        let mut changed = false;
        {
            let mut guard = self.inner.lock().expect("会话桥接锁中毒");
            for raw in cookies {
                let Some(cookie) = parse_set_cookie(raw) else {
                    continue;
                };
                if cookie.value.is_empty() {
                    changed |= guard.cookies.remove(&cookie.name).is_some();
                } else {
                    changed |= guard.cookies.get(&cookie.name) != Some(&cookie);
                    guard.cookies.insert(cookie.name.clone(), cookie);
                }
            }
        }
        if changed {
            if let Err(err) = self.save("gui") {
                eprintln!("[BetterHRBUST] 会话桥接写入失败: {err}");
            }
        }
    }

    /// 从磁盘重新读取（文件被终端更新时）。
    fn refresh(&self) {
        let modified = fs::metadata(&self.path).and_then(|meta| meta.modified()).ok();
        {
            let guard = self.inner.lock().expect("会话桥接锁中毒");
            if guard.loaded_at.is_some() && guard.loaded_at == modified {
                return;
            }
        }

        let bytes = match fs::read(&self.path) {
            Ok(bytes) => bytes,
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                let mut guard = self.inner.lock().expect("会话桥接锁中毒");
                guard.loaded_at = None;
                guard.cookies.clear();
                guard.student_number.clear();
                return;
            }
            Err(_) => return,
        };

        // 解析失败按"没有会话"处理：会话只是加速器，坏了不该让页面报错
        let parsed: Option<SessionFile> = serde_json::from_slice(&bytes).ok();
        let mut guard = self.inner.lock().expect("会话桥接锁中毒");
        guard.loaded_at = modified;
        guard.cookies.clear();
        guard.student_number.clear();
        if let Some(file) = parsed {
            guard.student_number = file.student_number;
            for cookie in file.cookies {
                if cookie.name.is_empty() || cookie.value.is_empty() {
                    continue;
                }
                guard.cookies.insert(cookie.name.clone(), cookie);
            }
        }
    }

    /// 原子写回文件（临时文件 + 改名，权限 0600）。
    fn save(&self, source: &str) -> std::io::Result<()> {
        let guard = self.inner.lock().expect("会话桥接锁中毒");
        let payload = SessionFile {
            version: VERSION,
            origin: ORIGIN.to_string(),
            source: source.to_string(),
            updated_at: now_millis(),
            student_number: guard.student_number.clone(),
            cookies: guard.cookies.values().cloned().collect(),
        };
        let mut encoded = serde_json::to_vec_pretty(&payload)
            .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidData, err))?;
        encoded.push(b'\n');
        drop(guard);

        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let tmp = self.path.with_extension("json.tmp");
        fs::write(&tmp, &encoded)?;
        restrict_permissions(&tmp);
        fs::rename(&tmp, &self.path)?;

        // 记下自己刚写的时间，避免下一次 refresh 又把同样的内容读一遍
        if let Ok(modified) = fs::metadata(&self.path).and_then(|meta| meta.modified()) {
            let mut guard = self.inner.lock().expect("会话桥接锁中毒");
            guard.loaded_at = Some(modified);
        }
        Ok(())
    }
}

/// 解析一条 `Set-Cookie`。
///
/// 只关心 name / value / Path / 过期时间；`Max-Age<=0` 视为删除。
fn parse_set_cookie(raw: &str) -> Option<StoredCookie> {
    let mut parts = raw.split(';');
    let first = parts.next()?.trim();
    let (name, value) = first.split_once('=')?;
    let name = name.trim();
    if name.is_empty() {
        return None;
    }

    let mut path = String::new();
    let mut expires_at = 0_i64;
    let mut deleted = false;
    for part in parts {
        let part = part.trim();
        let Some((key, val)) = part.split_once('=') else {
            continue;
        };
        let key = key.trim().to_ascii_lowercase();
        let val = val.trim();
        match key.as_str() {
            "path" => path = val.to_string(),
            "max-age" => {
                if let Ok(seconds) = val.parse::<i64>() {
                    if seconds <= 0 {
                        deleted = true;
                    } else {
                        expires_at = now_millis() + seconds * 1000;
                    }
                }
            }
            "expires" => {
                if let Some(millis) = parse_http_date(val) {
                    if millis <= now_millis() {
                        deleted = true;
                    } else if expires_at == 0 {
                        expires_at = millis;
                    }
                }
            }
            _ => {}
        }
    }

    Some(StoredCookie {
        name: name.to_string(),
        value: if deleted { String::new() } else { value.trim().to_string() },
        path,
        expires_at: if deleted { 0 } else { expires_at },
    })
}

/// 解析 `Expires=Thu, 01 Jan 1970 00:00:00 GMT`。
///
/// 只支持教务系统实际使用的 RFC 1123 形式；解析失败按"无过期时间"处理。
fn parse_http_date(text: &str) -> Option<i64> {
    // 形如 "Thu, 01 Jan 1970 00:00:00 GMT"
    let body = text.split_once(", ").map(|(_, rest)| rest).unwrap_or(text);
    let mut fields = body.split_whitespace();
    let day: i64 = fields.next()?.parse().ok()?;
    let month = match fields.next()?.to_ascii_lowercase().as_str() {
        "jan" => 1,
        "feb" => 2,
        "mar" => 3,
        "apr" => 4,
        "may" => 5,
        "jun" => 6,
        "jul" => 7,
        "aug" => 8,
        "sep" => 9,
        "oct" => 10,
        "nov" => 11,
        "dec" => 12,
        _ => return None,
    };
    let year: i64 = fields.next()?.parse().ok()?;
    let clock = fields.next()?;
    let mut clock_fields = clock.split(':');
    let hour: i64 = clock_fields.next()?.parse().ok()?;
    let minute: i64 = clock_fields.next()?.parse().ok()?;
    let second: i64 = clock_fields.next()?.parse().ok()?;

    let days = days_from_civil(year, month, day);
    Some(days * 86_400_000 + (hour * 3600 + minute * 60 + second) * 1000)
}

/// 公历转"距 1970-01-01 的天数"（Howard Hinnant 的算法）。
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month_index = (month + 9) % 12;
    let day_of_year = (153 * month_index + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

/// 当前毫秒时间戳。
fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as i64)
        .unwrap_or(0)
}

/// 收紧文件权限（Unix 上的 0600；Windows 由用户目录 ACL 兜底）。
fn restrict_permissions(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    {
        let _ = path;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_set_cookie_extracts_name_and_path() {
        let cookie = parse_set_cookie("JSESSIONID=abc123; Path=/academic; Max-Age=604800").unwrap();
        assert_eq!(cookie.name, "JSESSIONID");
        assert_eq!(cookie.value, "abc123");
        assert_eq!(cookie.path, "/academic");
        assert!(cookie.expires_at > 0, "Max-Age 应换算成绝对时间");
    }

    #[test]
    fn max_age_zero_marks_deletion() {
        let cookie = parse_set_cookie("JSESSIONID=; Max-Age=0; Path=/").unwrap();
        assert_eq!(cookie.value, "", "删除语义用空值表达");
        assert_eq!(cookie.expires_at, 0);
    }

    #[test]
    fn expired_expires_header_marks_deletion() {
        let cookie =
            parse_set_cookie("JSESSIONID=abc; Expires=Thu, 01 Jan 1970 00:00:00 GMT").unwrap();
        assert_eq!(cookie.value, "");
    }

    #[test]
    fn bridge_exports_and_reimports_cookies() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = SessionBridge::open(dir.path());
        bridge.export(vec!["JSESSIONID=abc; Path=/academic"]);
        assert_eq!(bridge.cookies().len(), 1);

        // 另一个进程（终端）读同一份文件
        let reopened = SessionBridge::open(dir.path());
        let cookies = reopened.cookies();
        assert_eq!(cookies.len(), 1);
        assert_eq!(cookies[0].name, "JSESSIONID");
        assert_eq!(cookies[0].value, "abc");
    }

    #[test]
    fn bridge_removes_cookie_on_logout_response() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = SessionBridge::open(dir.path());
        bridge.export(vec!["JSESSIONID=abc; Path=/academic"]);
        bridge.export(vec!["JSESSIONID=; Max-Age=0; Path=/academic"]);
        assert!(bridge.cookies().is_empty(), "登出后不应再有会话 Cookie");
    }

    #[test]
    fn corrupt_file_is_treated_as_no_session() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join(FILE_NAME), b"{ not json").unwrap();
        let bridge = SessionBridge::open(dir.path());
        assert!(bridge.cookies().is_empty());
    }

    #[test]
    fn http_date_parses_rfc1123() {
        // 2026-11-15T00:00:00Z == 1794700800000
        assert_eq!(
            parse_http_date("Sun, 15 Nov 2026 00:00:00 GMT"),
            Some(1_794_700_800_000)
        );
    }
}
