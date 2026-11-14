//! 用户 PATH 维护（安装 / 卸载时由安装器调用）
//!
//! 为什么由 CLI 自己做，而不是在 NSIS 脚本里改注册表：
//! NSIS 的字符串上限默认 1024 字符，而真实用户的 PATH 长度常常超过这个值。
//! 实测（本机 PATH 1087 字符）用 `ReadRegStr` 读会失败并返回空串，
//! 后续写入就会把整个 PATH **覆盖成只剩一段**——这是不可接受的破坏。
//!
//! 因此把这件事交给 Rust：直接调用注册表 API 读取原始值（不展开 %VAR%），
//! 逐段比较后写回，并保持 REG_EXPAND_SZ 类型；长度上限提高到 32767 字符。
//! `--install` / `--uninstall` 只改动自己那一段，其余路径原样保留。

use std::path::Path;

/// 用户级环境变量注册表键（安装模式为 currentUser，无需管理员）
const ENV_SUBKEY: &str = "Environment";
const PATH_VALUE: &str = "Path";
/// PATH 最大长度（Windows 上限）
const MAX_PATH_CHARS: usize = 32_767;

/// 一次 PATH 操作的结果
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PathOutcome {
    /// 是否真的改动了 PATH（已存在 / 不存在时为 false）
    pub changed: bool,
    /// 操作后的完整 PATH（未展开）
    pub path: String,
}

/// 路径段归一化：去引号、去尾部斜杠、统一小写，用于大小写不敏感比较
fn normalize(segment: &str) -> String {
    let trimmed = segment.trim().trim_matches('"').trim();
    trimmed.trim_end_matches(['\\', '/']).to_ascii_lowercase()
}

/// 若 `dir` 不在 `existing` 中则追加；已存在返回 None
pub fn merge_path(existing: &str, dir: &str) -> Option<String> {
    let target = normalize(dir);
    if target.is_empty() {
        return None;
    }
    if existing.split(';').any(|segment| normalize(segment) == target) {
        return None;
    }
    let trimmed = existing.trim_end_matches(';');
    if trimmed.is_empty() {
        Some(dir.to_string())
    } else {
        Some(format!("{trimmed};{dir}"))
    }
}

/// 从 `existing` 中移除 `dir`；不存在返回 None
pub fn remove_path(existing: &str, dir: &str) -> Option<String> {
    let target = normalize(dir);
    if target.is_empty() {
        return None;
    }
    let segments: Vec<&str> = existing.split(';').collect();
    let kept: Vec<&str> =
        segments.iter().copied().filter(|segment| normalize(segment) != target).collect();
    if kept.len() == segments.len() {
        return None;
    }
    Some(kept.join(";"))
}

/// 把目录加入用户 PATH（已存在则不变）
pub fn install(dir: &Path) -> Result<PathOutcome, String> {
    let dir = dir.to_string_lossy().to_string();
    let (existing, kind) = read_path()?.unwrap_or_else(|| (String::new(), REG_EXPAND_SZ));
    match merge_path(&existing, &dir) {
        Some(updated) => {
            write_path(&updated, kind)?;
            broadcast_environment_change();
            Ok(PathOutcome { changed: true, path: updated })
        }
        None => Ok(PathOutcome { changed: false, path: existing }),
    }
}

/// 从用户 PATH 移除目录（不存在则不变）
pub fn uninstall(dir: &Path) -> Result<PathOutcome, String> {
    let dir = dir.to_string_lossy().to_string();
    let Some((existing, kind)) = read_path()? else {
        return Ok(PathOutcome { changed: false, path: String::new() });
    };
    match remove_path(&existing, &dir) {
        Some(updated) => {
            write_path(&updated, kind)?;
            broadcast_environment_change();
            Ok(PathOutcome { changed: true, path: updated })
        }
        None => Ok(PathOutcome { changed: false, path: existing }),
    }
}

/// 当前用户 PATH（原样，不展开 %VAR%）
pub fn current() -> Result<String, String> {
    Ok(read_path()?.map(|(value, _)| value).unwrap_or_default())
}

// ---------------- Windows 实现 ----------------

#[cfg(windows)]
const REG_SZ: u32 = 1;
#[cfg(windows)]
const REG_EXPAND_SZ: u32 = 2;
#[cfg(windows)]
const HKEY_CURRENT_USER: isize = 0x8000_0001u32 as i32 as isize;
#[cfg(windows)]
const KEY_QUERY_VALUE: u32 = 0x0001;
#[cfg(windows)]
const KEY_SET_VALUE: u32 = 0x0002;
#[cfg(windows)]
const KEY_CREATE_SUB_KEY: u32 = 0x0004;
#[cfg(windows)]
const ERROR_FILE_NOT_FOUND: i32 = 2;

#[cfg(windows)]
fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
fn from_wide_bytes(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
        .take_while(|unit| *unit != 0)
        .collect();
    String::from_utf16_lossy(&units)
}

#[cfg(windows)]
fn read_path() -> Result<Option<(String, u32)>, String> {
    unsafe {
        let mut key: isize = 0;
        let status = advapi::RegOpenKeyExW(
            HKEY_CURRENT_USER,
            wide(ENV_SUBKEY).as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut key,
        );
        if status == ERROR_FILE_NOT_FOUND {
            return Ok(None);
        }
        if status != 0 {
            return Err(format!("无法打开 HKCU\\{ENV_SUBKEY}（错误码 {status}）"));
        }

        let mut kind: u32 = 0;
        let mut size: u32 = 0;
        let status = advapi::RegQueryValueExW(
            key,
            wide(PATH_VALUE).as_ptr(),
            std::ptr::null_mut(),
            &mut kind,
            std::ptr::null_mut(),
            &mut size,
        );
        if status == ERROR_FILE_NOT_FOUND {
            advapi::RegCloseKey(key);
            return Ok(None);
        }
        if status != 0 {
            advapi::RegCloseKey(key);
            return Err(format!("无法读取 {PATH_VALUE}（错误码 {status}）"));
        }

        let mut buffer = vec![0u8; size.max(2) as usize];
        let mut filled = size;
        let status = advapi::RegQueryValueExW(
            key,
            wide(PATH_VALUE).as_ptr(),
            std::ptr::null_mut(),
            &mut kind,
            buffer.as_mut_ptr(),
            &mut filled,
        );
        advapi::RegCloseKey(key);
        if status != 0 {
            return Err(format!("无法读取 {PATH_VALUE} 内容（错误码 {status}）"));
        }
        buffer.truncate(filled as usize);
        Ok(Some((from_wide_bytes(&buffer), kind)))
    }
}

#[cfg(windows)]
fn write_path(value: &str, kind: u32) -> Result<(), String> {
    if value.chars().count() > MAX_PATH_CHARS {
        return Err(format!("PATH 超过 {MAX_PATH_CHARS} 字符，已放弃写入以免截断"));
    }
    let type_ = if kind == REG_SZ { REG_SZ } else { REG_EXPAND_SZ };
    unsafe {
        let mut key: isize = 0;
        let mut disposition: u32 = 0;
        let status = advapi::RegCreateKeyExW(
            HKEY_CURRENT_USER,
            wide(ENV_SUBKEY).as_ptr(),
            0,
            std::ptr::null_mut(),
            0,
            KEY_QUERY_VALUE | KEY_SET_VALUE | KEY_CREATE_SUB_KEY,
            std::ptr::null_mut(),
            &mut key,
            &mut disposition,
        );
        if status != 0 {
            return Err(format!("无法打开 HKCU\\{ENV_SUBKEY}（错误码 {status}）"));
        }
        let data = wide(value);
        let bytes = std::slice::from_raw_parts(
            data.as_ptr() as *const u8,
            data.len() * std::mem::size_of::<u16>(),
        );
        let status = advapi::RegSetValueExW(
            key,
            wide(PATH_VALUE).as_ptr(),
            0,
            type_,
            bytes.as_ptr(),
            bytes.len() as u32,
        );
        advapi::RegCloseKey(key);
        if status != 0 {
            return Err(format!("无法写入 {PATH_VALUE}（错误码 {status}）"));
        }
    }
    Ok(())
}

/// 广播 WM_SETTINGCHANGE，让新开的终端 / 资源管理器立即拿到新的 PATH
#[cfg(windows)]
fn broadcast_environment_change() {
    const WM_SETTINGCHANGE: u32 = 0x001A;
    const SMTO_ABORTIFHUNG: u32 = 0x0002;
    let mut result: usize = 0;
    unsafe {
        user32::SendMessageTimeoutW(
            0xFFFF,
            WM_SETTINGCHANGE,
            0,
            wide("Environment").as_ptr(),
            SMTO_ABORTIFHUNG,
            3000,
            &mut result,
        );
    }
}

#[cfg(windows)]
mod advapi {
    use std::ffi::c_void;

    #[link(name = "advapi32")]
    extern "system" {
        pub fn RegOpenKeyExW(
            key: isize,
            sub_key: *const u16,
            options: u32,
            sam: u32,
            result: *mut isize,
        ) -> i32;
        pub fn RegCreateKeyExW(
            key: isize,
            sub_key: *const u16,
            reserved: u32,
            class: *mut u16,
            options: u32,
            sam: u32,
            security: *mut c_void,
            result: *mut isize,
            disposition: *mut u32,
        ) -> i32;
        pub fn RegQueryValueExW(
            key: isize,
            value_name: *const u16,
            reserved: *mut u32,
            kind: *mut u32,
            data: *mut u8,
            size: *mut u32,
        ) -> i32;
        pub fn RegSetValueExW(
            key: isize,
            value_name: *const u16,
            reserved: u32,
            kind: u32,
            data: *const u8,
            size: u32,
        ) -> i32;
        pub fn RegCloseKey(key: isize) -> i32;
    }
}

#[cfg(windows)]
mod user32 {
    #[link(name = "user32")]
    extern "system" {
        pub fn SendMessageTimeoutW(
            hwnd: usize,
            msg: u32,
            wparam: usize,
            lparam: *const u16,
            flags: u32,
            timeout: u32,
            result: *mut usize,
        ) -> usize;
    }
}

#[cfg(not(windows))]
fn read_path() -> Result<Option<(String, u32)>, String> {
    Err("用户 PATH 维护仅支持 Windows".to_string())
}

#[cfg(not(windows))]
fn write_path(_value: &str, _kind: u32) -> Result<(), String> {
    Err("用户 PATH 维护仅支持 Windows".to_string())
}

#[cfg(not(windows))]
fn broadcast_environment_change() {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_appends_without_duplicating() {
        let dir = r"C:\Program Files\BetterHRBUST";
        assert_eq!(merge_path("", dir).as_deref(), Some(dir));
        assert_eq!(
            merge_path(r"C:\Windows", dir).as_deref(),
            Some(r"C:\Windows;C:\Program Files\BetterHRBUST")
        );
        // 末尾多余分号不会产生 ";;"
        assert_eq!(
            merge_path(r"C:\Windows;", dir).as_deref(),
            Some(r"C:\Windows;C:\Program Files\BetterHRBUST")
        );
        // 已存在（大小写 / 尾部斜杠 / 引号差异）→ 不重复添加
        assert_eq!(merge_path(r"c:\program files\betterhrbust", dir), None);
        assert_eq!(merge_path(r#" "C:\Program Files\BetterHRBUST\" "#, dir), None);
    }

    #[test]
    fn remove_keeps_other_entries_intact() {
        let dir = r"C:\BetterHRBUST";
        assert_eq!(remove_path(r"C:\Windows;C:\BetterHRBUST", dir).as_deref(), Some(r"C:\Windows"));
        assert_eq!(remove_path(r"C:\BetterHRBUST;C:\Windows", dir).as_deref(), Some(r"C:\Windows"));
        assert_eq!(remove_path(r"C:\BetterHRBUST", dir).as_deref(), Some(""));
        assert_eq!(remove_path(r"C:\Windows", dir), None);
        // 混合大小写也要能删掉，且不影响其它段的原始写法
        assert_eq!(
            remove_path(r"C:\Win;c:\betterhrbust;D:\Dev", dir).as_deref(),
            Some(r"C:\Win;D:\Dev")
        );
    }

    /// 真实注册表往返：只操作测试专用键，不触碰 Environment
    #[test]
    #[cfg(windows)]
    fn registry_roundtrip_on_test_key() {
        const TEST_KEY: &str = "Software\\BetterHRBUST\\PathTest";
        let dir = r"C:\BetterHRBUSTRegistryTest";

        write_env_value(TEST_KEY, "", REG_EXPAND_SZ).expect("写入测试键失败");
        let (existing, kind) = read_env_value(TEST_KEY).expect("读取测试键失败").expect("键应存在");
        assert_eq!(kind, REG_EXPAND_SZ);
        let updated = merge_path(&existing, dir).expect("应追加");
        write_env_value(TEST_KEY, &updated, kind).expect("写回失败");
        let (stored, _) = read_env_value(TEST_KEY).unwrap().unwrap();
        assert_eq!(stored, dir);

        let removed = remove_path(&stored, dir).expect("应移除");
        write_env_value(TEST_KEY, &removed, REG_EXPAND_SZ).expect("写回失败");
        let (after, _) = read_env_value(TEST_KEY).unwrap().unwrap();
        assert_eq!(after, "");
        delete_test_key(TEST_KEY);
    }

    /// 长 PATH（超过 NSIS 1024 字符上限）必须完整读回，这正是旧方案翻车的地方
    #[test]
    #[cfg(windows)]
    fn long_path_is_read_and_written_completely() {
        const TEST_KEY: &str = "Software\\BetterHRBUST\\PathTestLong";
        let mut long = String::new();
        for index in 0..60 {
            long.push_str(&format!(r"C:\very-long-directory-name-for-test\number-{index:02};"));
        }
        long.truncate(long.trim_end_matches(';').len());
        assert!(long.chars().count() > 1024, "构造的 PATH 必须超过 NSIS 字符串上限");

        write_env_value(TEST_KEY, &long, REG_EXPAND_SZ).expect("写入长值失败");
        let (stored, _) = read_env_value(TEST_KEY).unwrap().unwrap();
        assert_eq!(stored, long, "长 PATH 必须原样读回，不能截断");

        let merged = merge_path(&stored, r"C:\BetterHRBUST").expect("应追加");
        write_env_value(TEST_KEY, &merged, REG_EXPAND_SZ).expect("写回失败");
        let (stored, _) = read_env_value(TEST_KEY).unwrap().unwrap();
        assert!(stored.ends_with(r";C:\BetterHRBUST"));

        delete_test_key(TEST_KEY);
    }

    #[cfg(windows)]
    fn read_env_value(subkey: &str) -> Result<Option<(String, u32)>, String> {
        unsafe {
            let mut key: isize = 0;
            let status = advapi::RegOpenKeyExW(
                HKEY_CURRENT_USER,
                wide(subkey).as_ptr(),
                0,
                KEY_QUERY_VALUE,
                &mut key,
            );
            if status == ERROR_FILE_NOT_FOUND {
                return Ok(None);
            }
            if status != 0 {
                return Err(format!("open failed {status}"));
            }
            let mut kind: u32 = 0;
            let mut size: u32 = 0;
            let status = advapi::RegQueryValueExW(
                key,
                wide(PATH_VALUE).as_ptr(),
                std::ptr::null_mut(),
                &mut kind,
                std::ptr::null_mut(),
                &mut size,
            );
            if status != 0 {
                advapi::RegCloseKey(key);
                return Ok(None);
            }
            let mut buffer = vec![0u8; size.max(2) as usize];
            let mut filled = size;
            let status = advapi::RegQueryValueExW(
                key,
                wide(PATH_VALUE).as_ptr(),
                std::ptr::null_mut(),
                &mut kind,
                buffer.as_mut_ptr(),
                &mut filled,
            );
            advapi::RegCloseKey(key);
            if status != 0 {
                return Err(format!("query failed {status}"));
            }
            buffer.truncate(filled as usize);
            Ok(Some((from_wide_bytes(&buffer), kind)))
        }
    }

    #[cfg(windows)]
    fn write_env_value(subkey: &str, value: &str, kind: u32) -> Result<(), String> {
        unsafe {
            let mut key: isize = 0;
            let mut disposition: u32 = 0;
            let status = advapi::RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide(subkey).as_ptr(),
                0,
                std::ptr::null_mut(),
                0,
                KEY_QUERY_VALUE | KEY_SET_VALUE | KEY_CREATE_SUB_KEY,
                std::ptr::null_mut(),
                &mut key,
                &mut disposition,
            );
            if status != 0 {
                return Err(format!("create failed {status}"));
            }
            let data = wide(value);
            let bytes = std::slice::from_raw_parts(
                data.as_ptr() as *const u8,
                data.len() * std::mem::size_of::<u16>(),
            );
            let status = advapi::RegSetValueExW(
                key,
                wide(PATH_VALUE).as_ptr(),
                0,
                kind,
                bytes.as_ptr(),
                bytes.len() as u32,
            );
            advapi::RegCloseKey(key);
            if status != 0 {
                return Err(format!("set failed {status}"));
            }
        }
        Ok(())
    }

    #[cfg(windows)]
    fn delete_test_key(subkey: &str) {
        #[link(name = "advapi32")]
        extern "system" {
            fn RegDeleteKeyW(key: isize, sub_key: *const u16) -> i32;
        }
        unsafe {
            RegDeleteKeyW(HKEY_CURRENT_USER, wide(subkey).as_ptr());
        }
    }
}
