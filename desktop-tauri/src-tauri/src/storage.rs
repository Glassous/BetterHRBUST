//! 桌面端键值持久化存储（本地 JSON 文件）
//!
//! 供前端存储门面（web/src/services/storage.js）经本地服务的
//! `/__app/storage` 端点读写，语义对齐 localStorage（字符串键值）：
//!
//! | 请求 | 含义 |
//! | --- | --- |
//! | `GET /__app/storage` | 全量键值（JSON 对象，`no-store`） |
//! | `PUT /__app/storage` | `{"key":"...","value":"..."}` 写入单键 |
//! | `DELETE /__app/storage?key=k` | 删除单键；不带 key 删除全部 |
//!
//! 存储文件为 `{app_data_dir}/storage.json`，数据归 Rust 进程所有：
//! 页面源端口漂移（1950 被占顺延）或 WebView2 用户数据目录被清理时，
//! 教务数据缓存与会话元数据不丢。写入采用「互斥锁内：临时文件 + 原子改名」，
//! 并发写串行化，崩溃不会留下半写文件；意外损坏时把原文件改名 `.corrupt` 保留现场。

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use http::{header, Method, Request, Response, StatusCode};
use http_body_util::BodyExt;
use hyper::body::{Bytes, Incoming};

use crate::server::{full_body, plain_response, AppBody};

/// PUT 请求体大小上限：全量数据（成绩/考试/公告等解析结果）通常 < 1MB，
/// 16MB 纯防御性兜底（回环可达的任意本机进程都可访问该端点，
/// 来源校验见 server.rs 的 Host / Origin 白名单）
pub const MAX_BODY_BYTES: usize = 16 * 1024 * 1024;
/// 键名长度上限（应用侧键均为 `better_hrbust_*` / `saved_student_number` 短标识符）
const MAX_KEY_LEN: usize = 256;

/// 键值存储：内存镜像 + 磁盘 JSON 文件，多请求共享（Arc 进 AppState）
pub struct Storage {
    path: PathBuf,
    data: Mutex<BTreeMap<String, String>>,
}

/// 只读快照：读出 `dir/storage.json` 的全部键值，**不产生任何写副作用**。
///
/// 供命令行（BetterHRBUSTCLI）使用：CLI 只查询、不修复数据，
/// 因此损坏时返回错误而不是像 `Storage::open` 那样把文件改名 `.corrupt`
/// —— 一个只读查询命令不应该动用户的文件。
/// 文件不存在返回空表（与前端"没有缓存"的语义一致）。
pub fn read_snapshot(dir: &Path) -> io::Result<BTreeMap<String, String>> {
    let bytes = match fs::read(dir.join("storage.json")) {
        Ok(bytes) => bytes,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(BTreeMap::new()),
        Err(err) => return Err(err),
    };
    // 容忍 BOM 与空文件：写入端是 serde_json + 换行，但手工编辑 / 第三方工具
    // 可能留下 BOM 或全空白内容
    let text = String::from_utf8_lossy(&bytes);
    let text = text.trim_start_matches('\u{feff}').trim();
    if text.is_empty() {
        return Ok(BTreeMap::new());
    }
    serde_json::from_str::<BTreeMap<String, String>>(text)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

impl Storage {
    /// 打开（或初始化）指定目录下的存储文件
    pub fn open(dir: &Path) -> io::Result<Self> {
        fs::create_dir_all(dir)?;
        let path = dir.join("storage.json");
        let data = match fs::read(&path) {
            Ok(bytes) => match serde_json::from_slice::<BTreeMap<String, String>>(&bytes) {
                Ok(map) => map,
                Err(_) => {
                    // 损坏文件保留现场后从空开始，而不是让应用永远起不来
                    let _ = fs::rename(&path, dir.join("storage.json.corrupt"));
                    BTreeMap::new()
                }
            },
            Err(err) if err.kind() == io::ErrorKind::NotFound => BTreeMap::new(),
            Err(err) => return Err(err),
        };
        Ok(Self { path, data: Mutex::new(data) })
    }

    /// 全量快照（GET 用）
    pub fn snapshot(&self) -> BTreeMap<String, String> {
        self.data.lock().expect("storage 锁中毒").clone()
    }

    /// 写入单键并落盘（save 在锁内执行，见 save() 注释）
    pub fn set(&self, key: String, value: String) -> io::Result<()> {
        let mut guard = self.data.lock().expect("storage 锁中毒");
        guard.insert(key, value);
        self.save(&guard)
    }

    /// 删除单键并落盘；键不存在则无操作。返回键是否存在过
    pub fn remove(&self, key: &str) -> io::Result<bool> {
        let mut guard = self.data.lock().expect("storage 锁中毒");
        let removed = guard.remove(key).is_some();
        if removed {
            self.save(&guard)?;
        }
        Ok(removed)
    }

    /// 清空全部键并落盘。返回清除的键数
    pub fn clear(&self) -> io::Result<usize> {
        let mut guard = self.data.lock().expect("storage 锁中毒");
        let cleared = guard.len();
        guard.clear();
        if cleared > 0 {
            self.save(&guard)?;
        }
        Ok(cleared)
    }

    /// 原子落盘：先写临时文件再改名（Windows 下 std::fs::rename 覆盖目标）。
    /// 必须在持有 data 锁时调用：所有请求共享同一个 tmp 路径，
    /// 锁外并发写会出现交错截断或 rename 二次失败（ENOENT），
    /// 轻则误报 500、重则 storage.json 损坏（下次启动整体改名 .corrupt）。
    fn save(&self, map: &BTreeMap<String, String>) -> io::Result<()> {
        let mut json = serde_json::to_vec(map)
            .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
        json.push(b'\n');
        let tmp = self.path.with_extension("json.tmp");
        fs::write(&tmp, &json)?;
        fs::rename(&tmp, &self.path)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 并发写盘不损坏存储文件：多线程交错 set 时 tmp 文件与 rename
    /// 都必须在锁内串行化，否则 storage.json 可能混合截断
    #[test]
    fn concurrent_writes_do_not_corrupt_storage_file() {
        let dir = tempfile::tempdir().unwrap();
        let storage = std::sync::Arc::new(Storage::open(dir.path()).unwrap());
        let writers: Vec<_> = (0..8)
            .map(|worker| {
                let storage = std::sync::Arc::clone(&storage);
                std::thread::spawn(move || {
                    for i in 0..50 {
                        storage
                            .set(format!("w{worker}-k{i}"), "v".repeat(64))
                            .expect("并发写入不应失败");
                    }
                })
            })
            .collect();
        for writer in writers {
            writer.join().expect("写入线程不应 panic");
        }

        // 重新打开：损坏的 JSON 会被改名 .corrupt 并从空开始，键数对不上即暴露
        let reloaded = Storage::open(dir.path()).unwrap();
        assert_eq!(reloaded.snapshot().len(), 8 * 50, "全部键应完整落盘且文件可解析");
    }

    /// 只读快照与落盘内容一致，且不创建 / 改动任何文件
    #[test]
    fn read_snapshot_matches_written_data() {
        let dir = tempfile::tempdir().unwrap();
        let storage = Storage::open(dir.path()).unwrap();
        storage.set("better_hrbust_has_session".into(), "true".into()).unwrap();
        storage.set("better_hrbust_cache_scores".into(), "[]".into()).unwrap();

        let snapshot = read_snapshot(dir.path()).unwrap();
        assert_eq!(snapshot.get("better_hrbust_has_session").map(String::as_str), Some("true"));
        assert_eq!(snapshot.len(), 2);
    }

    /// 文件不存在 → 空表（CLI 据此判定"没有本地数据"），不报错、不建目录
    #[test]
    fn read_snapshot_missing_file_is_empty() {
        let dir = tempfile::tempdir().unwrap();
        let nested = dir.path().join("not-created");
        assert!(read_snapshot(&nested).unwrap().is_empty());
        assert!(!nested.exists(), "只读快照不应创建目录");
    }

    /// 损坏文件：只读快照报错且**不改名、不删除**原文件
    /// （命令行是诊断工具，不应该像 Storage::open 那样清理现场）
    #[test]
    fn read_snapshot_reports_corrupt_without_touching_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("storage.json");
        fs::write(&path, b"{ not json").unwrap();

        let err = read_snapshot(dir.path()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(path.exists(), "损坏文件必须保留原样");
        assert!(!dir.path().join("storage.json.corrupt").exists());
    }

    /// BOM / 空白内容按空表处理：手工编辑过的文件不应让 CLI 直接报错
    #[test]
    fn read_snapshot_tolerates_bom_and_blank() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("storage.json");
        fs::write(&path, b"\xef\xbb\xbf{\"k\":\"v\"}\n").unwrap();
        assert_eq!(read_snapshot(dir.path()).unwrap().get("k").map(String::as_str), Some("v"));

        fs::write(&path, b"  \n\t").unwrap();
        assert!(read_snapshot(dir.path()).unwrap().is_empty());
    }
}

/// `/__app/storage` 请求处理。storage 未启用时统一 503，
/// 让前端门面走回落路径（dev 模式等场景）
pub async fn handle(storage: &Option<Arc<Storage>>, req: Request<Incoming>) -> Response<AppBody> {
    let Some(storage) = storage else {
        return plain_response(StatusCode::SERVICE_UNAVAILABLE, "storage not enabled");
    };

    match *req.method() {
        Method::GET => {
            let body = serde_json::to_vec(&storage.snapshot())
                .unwrap_or_else(|_| b"{}".to_vec());
            Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, "application/json")
                .header(header::CACHE_CONTROL, "no-store")
                .header("x-content-type-options", "nosniff")
                .body(full_body(Bytes::from(body)))
                .expect("存储 GET 响应构造不会失败")
        }
        Method::PUT => {
            // Limited 逐帧计数，超限即断流（不经 CORS 预检的恶意超大 PUT
            // 无法再把完整 body 缓冲进内存后才被拒绝）
            let limited = http_body_util::Limited::new(req.into_body(), MAX_BODY_BYTES);
            let bytes = match limited.collect().await {
                Ok(collected) => collected.to_bytes(),
                Err(err) if err.downcast_ref::<http_body_util::LengthLimitError>().is_some() => {
                    return plain_response(StatusCode::PAYLOAD_TOO_LARGE, "payload too large");
                }
                Err(_) => return plain_response(StatusCode::BAD_REQUEST, "bad body"),
            };
            let payload: serde_json::Value = match serde_json::from_slice(&bytes) {
                Ok(value) => value,
                Err(_) => return plain_response(StatusCode::BAD_REQUEST, "invalid json"),
            };
            let key = payload.get("key").and_then(|v| v.as_str()).unwrap_or("");
            let value = payload.get("value").and_then(|v| v.as_str()).unwrap_or("");
            if key.is_empty() || key.len() > MAX_KEY_LEN {
                return plain_response(StatusCode::BAD_REQUEST, "invalid key");
            }
            match storage.set(key.to_string(), value.to_string()) {
                Ok(()) => plain_response(StatusCode::OK, "stored"),
                Err(_) => plain_response(StatusCode::INTERNAL_SERVER_ERROR, "write failed"),
            }
        }
        Method::DELETE => {
            // ?key= 缺省 → 清空全部（退出登录场景）
            let key = req
                .uri()
                .query()
                .and_then(|query| query.split('&').find_map(|pair| pair.strip_prefix("key=")));
            let result = match key {
                Some(key) if !key.is_empty() => storage.remove(key).map(|_| ()),
                _ => storage.clear().map(|_| ()),
            };
            match result {
                Ok(()) => plain_response(StatusCode::OK, "deleted"),
                Err(_) => plain_response(StatusCode::INTERNAL_SERVER_ERROR, "write failed"),
            }
        }
        _ => plain_response(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed"),
    }
}
