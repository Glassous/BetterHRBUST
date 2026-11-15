//! AI 对话（`/__app/ai/*`）：配置读写 + OpenAI 兼容端点流式转发
//!
//! 为什么必须由本进程代理，而不是让 WebView 直接 fetch 用户配置的接口：
//!  - 页面 CSP 的 `connect-src` 是静态白名单，而 base_url 由用户任意填写；
//!    把任意域名写进白名单等于废掉 CSP（见 server.rs 的 HTML_CSP 与守护测试）。
//!  - 多数 OpenAI 兼容服务不给浏览器放 CORS 头，前端直连会被预检拦下。
//!  因此前端只请求同源的 `/__app/ai/chat`，由这里带上 Authorization 转发上游。
//!
//! 端点：
//! | 请求 | 含义 |
//! | --- | --- |
//! | `GET /__app/ai/config` | 读取配置（**不回传明文 API Key**，只给 has_key / 打码文案） |
//! | `PUT /__app/ai/config` | 局部更新（缺省字段不改；`api_key` 为空串不改，`clear_key` 清除） |
//! | `POST /__app/ai/chat` | 转发一次 `/chat/completions` 流式请求（SSE 原样透传） |
//!
//! 配置文件 `{data_dir}/ai-config.json` 与 `storage.json` **分开**：
//! storage.json 是教务缓存，会被 `/__app/storage` 全量返回给页面，
//! 把 API Key 混进去等于让任意同源脚本一键读走。这里单独落盘并尽量收紧权限。
//!
//! 隐私边界：请求体只做转发，不落盘、不打日志（含学籍的工具结果已经在前端打码）。

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;

use futures_core::Stream;
use http::{header, HeaderValue, Method, Request, Response, StatusCode};
use http_body_util::BodyExt;
use hyper::body::{Bytes, Incoming};
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::client::legacy::Client;
use serde::{Deserialize, Serialize};

use crate::server::{full_body, plain_response, AppBody};

/// 配置文件（与 storage.json 同目录，但独立文件）
pub const CONFIG_FILE: &str = "ai-config.json";
/// PUT 配置 / POST 对话的请求体上限（对话里带着消息历史与工具定义）
pub const MAX_BODY_BYTES: usize = 8 * 1024 * 1024;
/// 默认服务商（与 TUI 的 config.Default 保持一致）
const DEFAULT_BASE_URL: &str = "https://api.deepseek.com/v1";
const DEFAULT_MODEL: &str = "deepseek-flash";
/// 上游响应体逐帧空闲超时（持续有数据不中断，停摆才断）
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
/// 单次请求的硬上限（用户可配 5..600 秒，这里再夹一次避免误配成 0）
const TIMEOUT_FLOOR: u32 = 5;
const TIMEOUT_CEIL: u32 = 600;

/// AI 配置：字段名与 TUI 的 `config.LLMSettings` 对齐，便于两端共享同一个 JSON
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AiConfig {
    pub enabled: bool,
    pub consent: bool,
    pub base_url: String,
    pub model: String,
    /// 明文保存在 `ai-config.json`（unix 下 0600）
    pub api_key: String,
    pub temperature: f64,
    pub max_tool_rounds: u32,
    pub timeout_seconds: u32,
    pub send_profile: bool,
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            consent: false,
            base_url: DEFAULT_BASE_URL.to_string(),
            model: DEFAULT_MODEL.to_string(),
            api_key: String::new(),
            temperature: 0.3,
            max_tool_rounds: 8,
            timeout_seconds: 120,
            send_profile: false,
        }
    }
}

impl AiConfig {
    /// 区间校验与兜底：损坏/手改的配置不应该让请求带着 0 超时或 100 轮工具跑飞
    fn normalized(mut self) -> Self {
        if self.base_url.trim().is_empty() {
            self.base_url = DEFAULT_BASE_URL.to_string();
        }
        if self.model.trim().is_empty() {
            self.model = DEFAULT_MODEL.to_string();
        }
        if !(0.0..=2.0).contains(&self.temperature) {
            self.temperature = 0.3;
        }
        if !(1..=32).contains(&self.max_tool_rounds) {
            self.max_tool_rounds = 8;
        }
        if !(TIMEOUT_FLOOR..=TIMEOUT_CEIL).contains(&self.timeout_seconds) {
            self.timeout_seconds = 120;
        }
        self
    }

    fn has_key(&self) -> bool {
        !self.api_key.trim().is_empty()
    }
}

/// 局部更新载荷：字段缺省表示"不改"，避免前端每次都要回传完整配置（尤其是 key）
#[derive(Debug, Default, Deserialize)]
#[serde(default)]
pub struct AiPatch {
    pub enabled: Option<bool>,
    pub consent: Option<bool>,
    pub base_url: Option<String>,
    pub model: Option<String>,
    pub api_key: Option<String>,
    pub temperature: Option<f64>,
    pub max_tool_rounds: Option<u32>,
    pub timeout_seconds: Option<u32>,
    pub send_profile: Option<bool>,
    /// 显式清除已保存的 key（前端"清除"按钮），与 api_key="" 的语义区分开
    pub clear_key: Option<bool>,
}

/// 配置的内存镜像 + 磁盘文件
pub struct AiStore {
    path: PathBuf,
    config: Mutex<AiConfig>,
}

impl AiStore {
    /// 打开配置；文件损坏时回退默认值但**保留原文件**（里面可能存有密钥，
    /// 静默覆盖会让用户彻底丢失它——与 TUI 的 config.Load 处理一致）
    pub fn open(dir: &Path) -> Self {
        let path = dir.join(CONFIG_FILE);
        let config = match fs::read(&path) {
            Ok(bytes) => match serde_json::from_slice::<AiConfig>(&bytes) {
                Ok(config) => config.normalized(),
                Err(err) => {
                    eprintln!("[BetterHRBUST] AI 配置解析失败（保留原文件）: {err}");
                    AiConfig::default()
                }
            },
            Err(_) => AiConfig::default(),
        };
        Self { path, config: Mutex::new(config) }
    }

    fn snapshot(&self) -> AiConfig {
        self.config.lock().expect("ai 配置锁中毒").clone()
    }

    /// 对外视图：**绝不包含明文 key**
    pub fn redacted(&self) -> serde_json::Value {
        let config = self.snapshot();
        serde_json::json!({
            "enabled": config.enabled,
            "consent": config.consent,
            "base_url": config.base_url,
            "model": config.model,
            "temperature": config.temperature,
            "max_tool_rounds": config.max_tool_rounds,
            "timeout_seconds": config.timeout_seconds,
            "send_profile": config.send_profile,
            "has_key": config.has_key(),
            "api_key_masked": if config.has_key() { "已设置（已隐藏）" } else { "未设置" },
            "path": self.path.display().to_string(),
        })
    }

    /// 应用局部更新并落盘
    pub fn apply(&self, patch: AiPatch) -> io::Result<()> {
        let mut guard = self.config.lock().expect("ai 配置锁中毒");
        if let Some(value) = patch.enabled {
            guard.enabled = value;
        }
        if let Some(value) = patch.consent {
            guard.consent = value;
        }
        if let Some(value) = patch.base_url {
            guard.base_url = value.trim().to_string();
        }
        if let Some(value) = patch.model {
            guard.model = value.trim().to_string();
        }
        if let Some(value) = patch.temperature {
            guard.temperature = value;
        }
        if let Some(value) = patch.max_tool_rounds {
            guard.max_tool_rounds = value;
        }
        if let Some(value) = patch.timeout_seconds {
            guard.timeout_seconds = value;
        }
        if let Some(value) = patch.send_profile {
            guard.send_profile = value;
        }
        // key 的三态：clear_key=true 清除；api_key 非空则覆盖；空串表示"保持原值"
        if patch.clear_key.unwrap_or(false) {
            guard.api_key.clear();
        } else if let Some(value) = patch.api_key {
            let trimmed = value.trim().to_string();
            if !trimmed.is_empty() {
                guard.api_key = trimmed;
            }
        }
        *guard = guard.clone().normalized();
        self.save(&guard)
    }

    /// 原子落盘：临时文件 + 改名；unix 下把权限收到 0600
    fn save(&self, config: &AiConfig) -> io::Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut json = serde_json::to_vec_pretty(config)
            .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
        json.push(b'\n');
        let temporary = self.path.with_extension("json.tmp");
        fs::write(&temporary, &json)?;
        restrict_permissions(&temporary);
        fs::rename(&temporary, &self.path)
    }

    /// 供对话转发取用：请求里没带 key 时回退到已保存的 key
    fn resolved_key(&self, provided: &str) -> String {
        let trimmed = provided.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
        self.snapshot().api_key.trim().to_string()
    }
}

/// 尽可能收紧权限（Windows 无 POSIX 权限位，交给默认 ACL：文件位于当前用户
/// `%APPDATA%` 下，同一用户的其他进程本来就能读，这与 TUI 的处境相同）
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

/// TLS 连接器：既支持 https（DeepSeek / OpenAI / 智谱），也支持 http（本地 Ollama）
pub type AiConnector = hyper_rustls::HttpsConnector<HttpConnector>;
pub type AiClient = Client<AiConnector, AppBody>;

/// 构造转发用客户端。rustls 0.23 需要显式安装 crypto provider，
/// 否则首次握手会 panic（只在 ring feature 下可用）。
pub fn build_client() -> AiClient {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let connector = hyper_rustls::HttpsConnectorBuilder::new()
        .with_webpki_roots()
        .https_or_http()
        .enable_http1()
        .build();
    Client::builder(hyper_util::rt::TokioExecutor::new()).build(connector)
}

/// `GET/PUT /__app/ai/config`
pub async fn handle_config(store: &AiStore, req: Request<Incoming>) -> Response<AppBody> {
    match *req.method() {
        Method::GET => json_response(StatusCode::OK, &store.redacted()),
        Method::PUT => {
            let bytes = match read_limited(req).await {
                Ok(bytes) => bytes,
                Err(response) => return response,
            };
            let patch: AiPatch = match serde_json::from_slice(&bytes) {
                Ok(patch) => patch,
                Err(_) => return plain_response(StatusCode::BAD_REQUEST, "invalid json"),
            };
            match store.apply(patch) {
                Ok(()) => json_response(StatusCode::OK, &store.redacted()),
                Err(_) => plain_response(StatusCode::INTERNAL_SERVER_ERROR, "write failed"),
            }
        }
        _ => plain_response(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed"),
    }
}

/// `POST /__app/ai/chat` 的请求体（前端组装，Rust 只补齐协议字段）
#[derive(Debug, Deserialize)]
struct ChatRequest {
    base_url: String,
    #[serde(default)]
    model: String,
    messages: Vec<serde_json::Value>,
    #[serde(default)]
    tools: Vec<serde_json::Value>,
    #[serde(default)]
    temperature: f64,
    #[serde(default)]
    timeout_seconds: u32,
    /// 允许前端显式传 key（浏览器端直连时用不到，桌面端优先用已保存的 key）
    #[serde(default)]
    api_key: String,
}

/// `POST /__app/ai/chat`：把一次 chat/completions 请求转发给用户配置的服务商
pub async fn handle_chat(
    client: &AiClient,
    store: &AiStore,
    req: Request<Incoming>,
) -> Response<AppBody> {
    if *req.method() != Method::POST {
        return plain_response(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
    }
    let bytes = match read_limited(req).await {
        Ok(bytes) => bytes,
        Err(response) => return response,
    };
    let payload: ChatRequest = match serde_json::from_slice(&bytes) {
        Ok(payload) => payload,
        Err(_) => return plain_response(StatusCode::BAD_REQUEST, "invalid json"),
    };

    // 保存的配置兜底：前端可以只传 messages/tools，地址与模型取自已保存配置
    let saved = store.snapshot();
    let base_url = if payload.base_url.trim().is_empty() {
        saved.base_url.clone()
    } else {
        payload.base_url.trim().to_string()
    };
    let model = if payload.model.trim().is_empty() {
        saved.model.clone()
    } else {
        payload.model.trim().to_string()
    };
    let endpoint = match endpoint_for(&base_url) {
        Ok(uri) => uri,
        Err(message) => return error_response(StatusCode::BAD_REQUEST, &message),
    };

    // 发给上游的请求体：协议字段由这里统一补齐，前端不必理解 stream_options 细节
    let mut upstream = serde_json::Map::new();
    upstream.insert("model".into(), serde_json::Value::String(model));
    upstream.insert("messages".into(), serde_json::Value::Array(payload.messages));
    upstream.insert("stream".into(), serde_json::Value::Bool(true));
    upstream.insert(
        "stream_options".into(),
        serde_json::json!({ "include_usage": true }),
    );
    // 与 TUI 一致：0 表示不下发该字段
    let temperature = if payload.temperature > 0.0 && payload.temperature <= 2.0 {
        payload.temperature
    } else {
        saved.temperature
    };
    if temperature > 0.0 {
        upstream.insert("temperature".into(), serde_json::json!(temperature));
    }
    if !payload.tools.is_empty() {
        upstream.insert("tools".into(), serde_json::Value::Array(payload.tools));
    }
    let body = match serde_json::to_vec(&upstream) {
        Ok(body) => body,
        Err(_) => return plain_response(StatusCode::INTERNAL_SERVER_ERROR, "encode failed"),
    };

    let key = store.resolved_key(&payload.api_key);
    let mut builder = Request::builder()
        .method(Method::POST)
        .uri(endpoint)
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::ACCEPT, "text/event-stream")
        // 不要压缩：本代理不解压，压缩后的字节会让前端的 SSE 解析失败
        .header(header::ACCEPT_ENCODING, "identity");
    if !key.is_empty() {
        // key 含非 ASCII 时 HeaderValue 会拒绝：明确报错，而不是静默发出无凭证请求
        let Ok(value) = HeaderValue::from_str(&format!("Bearer {key}")) else {
            return error_response(StatusCode::BAD_REQUEST, "API Key 含有非法字符");
        };
        builder = builder.header(header::AUTHORIZATION, value);
    }
    let upstream_request = match builder.body(full_body(Bytes::from(body))) {
        Ok(request) => request,
        Err(_) => return plain_response(StatusCode::INTERNAL_SERVER_ERROR, "build failed"),
    };

    let timeout_seconds = payload.timeout_seconds.clamp(TIMEOUT_FLOOR, TIMEOUT_CEIL);
    let timeout = Duration::from_secs(u64::from(timeout_seconds));
    let response = match tokio::time::timeout(timeout, client.request(upstream_request)).await {
        Ok(Ok(response)) => response,
        Ok(Err(err)) => {
            return error_response(
                StatusCode::BAD_GATEWAY,
                &format!("无法连接 AI 服务：{err}（请检查接口地址与网络）"),
            )
        }
        Err(_) => {
            return error_response(
                StatusCode::GATEWAY_TIMEOUT,
                &format!("{timeout_seconds} 秒内没有收到 AI 服务的响应"),
            )
        }
    };

    // 状态码与 Content-Type 原样透传：401/404/429 由前端按同一套中文文案分类，
    // 上游的错误 JSON body 也一并流回，便于用户看到服务商的原始原因
    let (parts, body) = response.into_parts();
    let mut builder = Response::builder().status(parts.status);
    if let Some(content_type) = parts.headers.get(header::CONTENT_TYPE) {
        builder = builder.header(header::CONTENT_TYPE, content_type.clone());
    }
    builder
        .header(header::CACHE_CONTROL, "no-store")
        .header("x-content-type-options", "nosniff")
        // 反向代理（nginx）不要缓冲，否则流式会变成一次性吐出
        .header("x-accel-buffering", "no")
        .body(stream_body(body))
        .expect("AI 转发响应构造不会失败")
}

/// base_url + `/chat/completions`；只接受 http(s)，其余直接拒绝
fn endpoint_for(base_url: &str) -> Result<http::Uri, String> {
    let trimmed = base_url.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err("接口地址为空".to_string());
    }
    let lower = trimmed.to_ascii_lowercase();
    if !lower.starts_with("http://") && !lower.starts_with("https://") {
        return Err("接口地址必须以 http:// 或 https:// 开头".to_string());
    }
    let text = format!("{trimmed}/chat/completions");
    text.parse::<http::Uri>()
        .map_err(|_| format!("接口地址无法解析：{trimmed}"))
}

/// 逐帧空闲超时地转发上游 body（复用 proxy.rs 的 mpsc + StreamBody 模式）
fn stream_body(body: Incoming) -> AppBody {
    let (tx, rx) = tokio::sync::mpsc::channel(8);
    tokio::spawn(async move {
        let mut body = Box::pin(body);
        loop {
            match tokio::time::timeout(IDLE_TIMEOUT, body.as_mut().frame()).await {
                Ok(Some(Ok(frame))) => {
                    if tx.send(Ok(frame)).await.is_err() {
                        break; // 前端已断开
                    }
                }
                Ok(Some(Err(err))) => {
                    let _ = tx.send(Err(crate::server::GatewayError::Body(err))).await;
                    break;
                }
                Ok(None) => break,
                Err(_) => {
                    let _ = tx
                        .send(Err(crate::server::GatewayError::Timeout))
                        .await;
                    break;
                }
            }
        }
    });
    http_body_util::StreamBody::new(BodyChannel { rx }).boxed()
}

struct BodyChannel {
    rx: tokio::sync::mpsc::Receiver<Result<http_body::Frame<Bytes>, crate::server::GatewayError>>,
}

impl Stream for BodyChannel {
    type Item = Result<http_body::Frame<Bytes>, crate::server::GatewayError>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<Option<Self::Item>> {
        self.rx.poll_recv(cx)
    }
}

/// 读请求体（带大小上限）
async fn read_limited(req: Request<Incoming>) -> Result<Bytes, Response<AppBody>> {
    let limited = http_body_util::Limited::new(req.into_body(), MAX_BODY_BYTES);
    match limited.collect().await {
        Ok(collected) => Ok(collected.to_bytes()),
        Err(err) if err.downcast_ref::<http_body_util::LengthLimitError>().is_some() => {
            Err(plain_response(StatusCode::PAYLOAD_TOO_LARGE, "payload too large"))
        }
        Err(_) => Err(plain_response(StatusCode::BAD_REQUEST, "bad body")),
    }
}

fn json_response(status: StatusCode, value: &serde_json::Value) -> Response<AppBody> {
    let body = serde_json::to_vec(value).unwrap_or_else(|_| b"{}".to_vec());
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::CACHE_CONTROL, "no-store")
        .header("x-content-type-options", "nosniff")
        .body(full_body(Bytes::from(body)))
        .expect("AI 配置响应构造不会失败")
}

/// 错误响应：统一 OpenAI 形状的 `{"error":{"message":...}}`，
/// 前端因此可以同时处理"本代理的错误"和"服务商的错误"
fn error_response(status: StatusCode, message: &str) -> Response<AppBody> {
    json_response(status, &serde_json::json!({ "error": { "message": message } }))
}

/// 供 TUI / CLI 复用的只读入口：读取 `{data_dir}/ai-config.json`
pub fn read_config(dir: &Path) -> Option<AiConfig> {
    let bytes = fs::read(dir.join(CONFIG_FILE)).ok()?;
    serde_json::from_slice::<AiConfig>(&bytes).ok().map(AiConfig::normalized)
}

/// 存储句柄（Arc 进 AppState）
pub type SharedStore = Arc<AiStore>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_roundtrip_never_leaks_key_in_redacted_view() {
        let dir = tempfile::tempdir().unwrap();
        let store = AiStore::open(dir.path());
        store
            .apply(AiPatch {
                base_url: Some("https://api.example.com/v1".into()),
                api_key: Some("sk-secret".into()),
                ..Default::default()
            })
            .unwrap();

        let view = store.redacted();
        assert_eq!(view["base_url"], "https://api.example.com/v1");
        assert_eq!(view["has_key"], true);
        assert_eq!(view["api_key_masked"], "已设置（已隐藏）");
        // 明文 key 绝不能出现在对外 JSON 里
        assert!(!view.to_string().contains("sk-secret"));

        // 重新打开：落盘内容仍然可读
        let reopened = AiStore::open(dir.path());
        assert_eq!(reopened.redacted()["has_key"], true);
    }

    #[test]
    fn empty_api_key_keeps_existing_and_clear_key_removes_it() {
        let dir = tempfile::tempdir().unwrap();
        let store = AiStore::open(dir.path());
        store.apply(AiPatch { api_key: Some("sk-1".into()), ..Default::default() }).unwrap();
        // 前端留空表示"不修改"
        store.apply(AiPatch { api_key: Some(String::new()), ..Default::default() }).unwrap();
        assert_eq!(store.redacted()["has_key"], true);
        // 显式清除
        store.apply(AiPatch { clear_key: Some(true), ..Default::default() }).unwrap();
        assert_eq!(store.redacted()["has_key"], false);
    }

    #[test]
    fn corrupt_config_falls_back_and_keeps_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(CONFIG_FILE);
        fs::write(&path, b"{ not json").unwrap();
        let store = AiStore::open(dir.path());
        assert_eq!(store.redacted()["base_url"], DEFAULT_BASE_URL);
        assert!(path.exists(), "损坏文件必须保留原样（里面可能有密钥）");
    }

    #[test]
    fn endpoint_requires_http_scheme() {
        assert!(endpoint_for("https://api.deepseek.com/v1").is_ok());
        assert_eq!(
            endpoint_for("https://api.deepseek.com/v1/").unwrap(),
            "https://api.deepseek.com/v1/chat/completions"
        );
        assert!(endpoint_for("file:///etc/passwd").is_err());
        assert!(endpoint_for("").is_err());
    }

    #[test]
    fn values_are_normalized_into_safe_ranges() {
        let dir = tempfile::tempdir().unwrap();
        let store = AiStore::open(dir.path());
        store
            .apply(AiPatch {
                temperature: Some(9.0),
                max_tool_rounds: Some(0),
                timeout_seconds: Some(1),
                base_url: Some("   ".into()),
                ..Default::default()
            })
            .unwrap();
        let view = store.redacted();
        assert_eq!(view["temperature"], 0.3);
        assert_eq!(view["max_tool_rounds"], 8);
        assert_eq!(view["timeout_seconds"], 120);
        assert_eq!(view["base_url"], DEFAULT_BASE_URL);
    }
}
