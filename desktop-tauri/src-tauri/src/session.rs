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
/// 官方教务系统主机名：旧会话文件没有 host 字段时按它归属。
pub const OFFICIAL_HOST: &str = "jwzx.hrbust.edu.cn";

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
    /// Cookie 归属的教务站点主机名。空值 = 官方站点（向后兼容旧文件）。
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub host: String,
}

fn is_zero(value: &i64) -> bool {
    *value == 0
}

/// Cookie 归属主机名归一化：空值按官方站点处理，其余统一小写。
fn cookie_host(host: &str) -> String {
    let host = host.trim();
    if host.is_empty() {
        OFFICIAL_HOST.to_string()
    } else {
        host.to_ascii_lowercase()
    }
}

/// 内存表的键：不同站点的同名 Cookie（都是 JSESSIONID）必须互不覆盖。
fn cookie_key(host: &str, name: &str) -> String {
    format!("{}\u{1}{name}", cookie_host(host))
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

    /// 返回指定上游站点的可用 Cookie 快照。
    ///
    /// 每次调用先检查文件是否被终端更新过：终端在图形界面运行期间登录时，
    /// 下一次请求就能用上新会话，不需要重启。
    ///
    /// 切换自定义教务地址后，官方站点的 Cookie 不会被发给第三方站点；
    /// 若终端在自定义站点登录过，这里仍能读到对应的那份会话。
    pub fn cookies_for(&self, host: &str) -> Vec<StoredCookie> {
        self.refresh();
        let now = now_millis();
        let wanted = cookie_host(host);
        let guard = self.inner.lock().expect("会话桥接锁中毒");
        guard
            .cookies
            .values()
            .filter(|cookie| cookie.expires_at == 0 || cookie.expires_at > now)
            .filter(|cookie| cookie_host(&cookie.host) == wanted)
            .cloned()
            .collect()
    }

    /// 批量导出指定上游站点的 Set-Cookie。
    ///
    /// 空值（教务删除 Cookie / 登出）会从文件里移除，与浏览器语义一致。
    /// `host` 来自当前生效上游（可能与官方不同），`origin` 只是文件里的展示字段。
    pub fn export_at<'a, I>(&self, cookies: I, host: &str, origin: &str)
    where
        I: IntoIterator<Item = &'a str>,
    {
        let host = cookie_host(host);
        let mut changed = false;
        {
            let mut guard = self.inner.lock().expect("会话桥接锁中毒");
            for raw in cookies {
                let Some(mut cookie) = parse_set_cookie(raw) else {
                    continue;
                };
                cookie.host = host.clone();
                let key = cookie_key(&host, &cookie.name);
                if cookie.value.is_empty() {
                    changed |= guard.cookies.remove(&key).is_some();
                } else {
                    changed |= guard.cookies.get(&key) != Some(&cookie);
                    guard.cookies.insert(key, cookie);
                }
            }
        }
        if changed {
            if let Err(err) = self.save("gui", origin) {
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
            for mut cookie in file.cookies {
                if cookie.name.is_empty() || cookie.value.is_empty() {
                    continue;
                }
                // 旧文件没有 host 字段：按官方站点归属
                cookie.host = cookie_host(&cookie.host);
                let key = cookie_key(&cookie.host, &cookie.name);
                guard.cookies.insert(key, cookie);
            }
        }
    }

    /// 原子写回文件（临时文件 + 改名，权限 0600）。
    fn save(&self, source: &str, origin: &str) -> std::io::Result<()> {
        let guard = self.inner.lock().expect("会话桥接锁中毒");
        let payload = SessionFile {
            version: VERSION,
            origin: origin.to_string(),
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
        // 归属由调用方（当前上游 host）在 export_at 中补齐
        host: String::new(),
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
    /// 测试里模拟"官方站点"的 origin（生产由当前生效上游决定）
    const TEST_ORIGIN: &str = "http://jwzx.hrbust.edu.cn";

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
        bridge.export_at(vec!["JSESSIONID=abc; Path=/academic"], OFFICIAL_HOST, TEST_ORIGIN);
        assert_eq!(bridge.cookies_for(OFFICIAL_HOST).len(), 1);

        // 另一个进程（终端）读同一份文件
        let reopened = SessionBridge::open(dir.path());
        let cookies = reopened.cookies_for(OFFICIAL_HOST);
        assert_eq!(cookies.len(), 1);
        assert_eq!(cookies[0].name, "JSESSIONID");
        assert_eq!(cookies[0].value, "abc");
    }

    #[test]
    fn bridge_removes_cookie_on_logout_response() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = SessionBridge::open(dir.path());
        bridge.export_at(vec!["JSESSIONID=abc; Path=/academic"], OFFICIAL_HOST, TEST_ORIGIN);
        bridge.export_at(vec!["JSESSIONID=; Max-Age=0; Path=/academic"], OFFICIAL_HOST, TEST_ORIGIN);
        assert!(bridge.cookies_for(OFFICIAL_HOST).is_empty(), "登出后不应再有会话 Cookie");
    }

    #[test]
    fn corrupt_file_is_treated_as_no_session() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join(FILE_NAME), b"{ not json").unwrap();
        let bridge = SessionBridge::open(dir.path());
        assert!(bridge.cookies_for(OFFICIAL_HOST).is_empty());
    }

    #[test]
    fn cookies_are_isolated_per_upstream_host() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = SessionBridge::open(dir.path());
        bridge.export_at(vec!["JSESSIONID=official; Path=/academic"], OFFICIAL_HOST, TEST_ORIGIN);
        bridge.export_at(vec!["JSESSIONID=mirror; Path=/academic"], "JWZX.fiacloud.top", "https://jwzx.fiacloud.top");

        assert_eq!(bridge.cookies_for("jwzx.hrbust.edu.cn")[0].value, "official");
        assert_eq!(bridge.cookies_for("jwzx.fiacloud.top")[0].value, "mirror");
        // 同名 Cookie 不互相覆盖，也不会把官方会话发给第三方站点
        assert_eq!(bridge.cookies_for(OFFICIAL_HOST)[0].value, "official");

        // 登出只清掉当前站点的 Cookie
        bridge.export_at(vec!["JSESSIONID=; Max-Age=0; Path=/academic"], "jwzx.fiacloud.top", "https://jwzx.fiacloud.top");
        assert!(bridge.cookies_for("jwzx.fiacloud.top").is_empty());
        assert_eq!(bridge.cookies_for("jwzx.hrbust.edu.cn")[0].value, "official");
    }

    #[test]
    fn legacy_session_file_without_host_defaults_to_official() {
        let dir = tempfile::tempdir().unwrap();
        let legacy = r#"{"version":1,"origin":"http://jwzx.hrbust.edu.cn","source":"tui","updatedAt":1,
            "cookies":[{"name":"JSESSIONID","value":"legacy","path":"/academic"}]}"#;
        fs::write(dir.path().join(FILE_NAME), legacy).unwrap();
        let bridge = SessionBridge::open(dir.path());
        assert_eq!(bridge.cookies_for("jwzx.hrbust.edu.cn")[0].value, "legacy");
        assert!(bridge.cookies_for("jwzx.fiacloud.top").is_empty());
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
