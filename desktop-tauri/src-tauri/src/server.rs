//! 本地 HTTP 服务（仅监听 127.0.0.1）
//!
//! 架构：
//!  - 静态文件：web/dist 构建产物（release 经 rust-embed 嵌入二进制，
//!    debug 模式直接读磁盘），SPA 回退、/assets 长缓存、路径穿越防护
//!  - /academic/* → 反向代理 → http://jwzx.hrbust.edu.cn（见 proxy.rs）
//!  - /__app/storage → 键值持久化（见 storage.rs，教务数据缓存落盘）
//!  - /__app/cli、/__app/cli/skill → 命令行状态与内嵌 SKILL（见 cli_response）
//!  - 来源校验：全部请求校验 Host 指向本服务自身（阻断 DNS rebinding），
//!    /academic 与 /__app/storage 追加 Origin / Sec-Fetch-Site 白名单
//!  - 端口策略：固定 1950 起（建校年份），被占用向后顺延至多 10 次。
//!    固定端口保证页面源（http://127.0.0.1:1950）跨冷启动稳定，
//!    教务会话 Cookie 因此持久；数据缓存自 v1.1 起归 Rust 存储文件所有，
//!    端口漂移 / WebView 数据被清理均不影响。
//!
//! 依赖刻意保持最小（hyper/tokio/rust-embed，不引 axum/tower 全家桶），
//! 以满足体积与内存目标：安装包 <= 10MB、主进程 RSS <= 25MB。

use std::io;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use http::{header, HeaderMap, HeaderValue, Method, Request, Response, StatusCode, Uri};
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper_util::client::legacy::Client;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use rust_embed::RustEmbed;
use tokio::net::TcpListener;

pub use hyper::service::service_fn;

/// 教务系统 origin（仅 HTTP，无 443）
pub const UPSTREAM_ORIGIN: &str = "http://jwzx.hrbust.edu.cn";

/// 本地服务端口基值（建校年份），被占用时向后顺延
pub const PORT_BASE: u16 = 1950;
pub const PORT_TRIES: u16 = 10;

/// 统一响应体：静态用 Full，代理用流式，错误类型统一为 GatewayError
pub type AppBody = BoxBody<Bytes, GatewayError>;

pub fn full_body(bytes: Bytes) -> AppBody {
    Full::new(bytes).map_err(|infallible| match infallible {}).boxed()
}

pub fn empty_body() -> AppBody {
    Full::new(Bytes::new()).map_err(|infallible| match infallible {}).boxed()
}

/// /academic 转发失败的错误：从 service 冒泡给 hyper 后连接被直接断开
/// （不回 502 页面），让前端 fetch reject 走既有网络错误提示路径。
#[derive(Debug)]
pub enum GatewayError {
    Timeout,
    /// 发送上游请求失败（连接拒绝 / DNS / 池错误）
    Request(hyper_util::client::legacy::Error),
    /// 上游响应体读取中断
    Body(hyper::Error),
    Build(http::Error),
}

impl std::fmt::Display for GatewayError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            GatewayError::Timeout => write!(f, "upstream timeout"),
            GatewayError::Request(err) => write!(f, "upstream request error: {err}"),
            GatewayError::Body(err) => write!(f, "upstream body error: {err}"),
            GatewayError::Build(err) => write!(f, "request build error: {err}"),
        }
    }
}

impl std::error::Error for GatewayError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            GatewayError::Timeout => None,
            GatewayError::Request(err) => Some(err),
            GatewayError::Body(err) => Some(err),
            GatewayError::Build(err) => Some(err),
        }
    }
}

/// 上游教务系统描述：scheme + host + 可选端口
#[derive(Clone, Debug)]
pub struct Upstream {
    scheme: String,
    host: String,
    port: Option<u16>,
}

impl Upstream {
    /// 解析形如 "http://jwzx.hrbust.edu.cn" 的 origin
    pub fn parse(origin: &str) -> Self {
        let (scheme, rest) = origin.split_once("://").unwrap_or(("http", origin));
        let authority = rest.split(['/', '?', '#']).next().unwrap_or(rest);
        let (host, port) = match authority.rsplit_once(':') {
            Some((host, port)) => (host.to_string(), port.parse().ok()),
            None => (authority.to_string(), None),
        };
        Self { scheme: scheme.to_string(), host, port }
    }

    /// 发给上游的 Host 头（默认端口不显式携带，与 changeOrigin 行为一致）
    pub fn host_header(&self) -> String {
        match self.port {
            Some(port) => format!("{}:{}", self.host, port),
            None => self.host.clone(),
        }
    }

    /// 注入的 Referer（教务系统校验该头，规则来自 vite.config.mjs）
    pub fn referer(&self) -> String {
        format!("{}://{}/academic/", self.scheme, self.host_header())
    }

    /// 拼上游绝对 URI：origin + 原样 path+query
    pub fn uri_for(&self, path_and_query: &str) -> Uri {
        Uri::builder()
            .scheme(self.scheme.as_str())
            .authority(self.host_header().as_str())
            .path_and_query(path_and_query.to_string())
            .build()
            .expect("upstream uri 由固定 origin 与已校验 path 拼接，不会非法")
    }
}

/// 服务配置（测试用自定义 upstream / 端口）
#[derive(Clone, Debug)]
pub struct ServerConfig {
    pub upstream: Upstream,
    pub port_base: u16,
    pub port_tries: u16,
    /// 键值持久化目录（None = 不启用 /__app/storage，如 dev 模式）
    pub storage_dir: Option<PathBuf>,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self::production()
    }
}

impl ServerConfig {
    pub fn production() -> Self {
        Self {
            upstream: Upstream::parse(UPSTREAM_ORIGIN),
            port_base: PORT_BASE,
            port_tries: PORT_TRIES,
            storage_dir: None,
        }
    }
}

/// 前端构建产物：release 嵌入二进制（单 exe 便携版的来源），
/// debug 模式 rust-embed 自动改为运行时读磁盘，方便前端重建后即时生效
#[derive(RustEmbed)]
#[folder = "../../web/dist"]
struct WebAssets;

/// 每请求共享的客户端状态（hyper 连接池可克隆复用）
#[derive(Clone)]
struct AppState {
    /// 实际绑定端口：Host/Origin 来源校验要求精确匹配（见 host_allowed）
    port: u16,
    client: Client<hyper_util::client::legacy::connect::HttpConnector, AppBody>,
    upstream: Upstream,
    storage: Option<Arc<crate::storage::Storage>>,
    /// 数据目录：`/__app/cli` 需要按同一目录解析命令行状态（与 storage 同源）
    data_dir: Option<PathBuf>,
}

/// 启动本地服务：绑定端口（含顺延逻辑）并进入 accept 循环。
/// 返回实际绑定的地址，供窗口加载。
pub async fn start(config: ServerConfig) -> io::Result<SocketAddr> {
    let listener = bind_listener(config.port_base, config.port_tries).await?;
    let addr = listener.local_addr()?;
    // 存储打开失败仅降级（不启用持久化端点），不阻断应用启动
    let storage = config.storage_dir.as_deref().and_then(|dir| {
        match crate::storage::Storage::open(dir) {
            Ok(storage) => Some(Arc::new(storage)),
            Err(err) => {
                eprintln!("[BetterHRBUST] 持久化存储打开失败({}): {err}", dir.display());
                None
            }
        }
    });
    let state = AppState {
        port: addr.port(),
        client: Client::builder(TokioExecutor::new()).build_http::<AppBody>(),
        upstream: config.upstream,
        storage,
        data_dir: config.storage_dir,
    };

    tokio::spawn(async move {
        // 连续 accept 失败（如 fd 耗尽）时指数退避，避免忙轮询打满 CPU
        let mut backoff = std::time::Duration::from_millis(50);
        loop {
            let (stream, _peer) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => {
                    tokio::time::sleep(backoff).await;
                    backoff = (backoff * 2).min(std::time::Duration::from_millis(2000));
                    continue;
                }
            };
            backoff = std::time::Duration::from_millis(50);
            let state = state.clone();
            tokio::spawn(async move {
                let service = service_fn(move |req| handle(state.clone(), req));
                // service 返回 Err 时 hyper 直接断开连接（不回 502），
                // 这正是反代上游故障时需要的行为
                let _ = auto::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(stream), service)
                    .await;
            });
        }
    });

    Ok(addr)
}

/// 端口绑定：port_base == 0 时直接用系统分配的临时端口（测试用）；
/// 否则从 port_base 起逐个尝试，仅 AddrInUse 顺延，其他错误直接抛出
async fn bind_listener(port_base: u16, port_tries: u16) -> io::Result<TcpListener> {
    if port_base == 0 {
        return TcpListener::bind(("127.0.0.1", 0)).await;
    }
    let mut last_err: Option<io::Error> = None;
    for offset in 0..port_tries {
        match TcpListener::bind(("127.0.0.1", port_base + offset)).await {
            Ok(listener) => return Ok(listener),
            Err(err) if err.kind() == io::ErrorKind::AddrInUse => {
                last_err = Some(err);
            }
            Err(err) => return Err(err),
        }
    }
    Err(last_err.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::AddrInUse, "无可用本地端口")
    }))
}

/// 总入口：/academic 走反代（错误冒泡断连），/__app/storage 走键值持久化，
/// 其余走静态托管（错误转 4xx/5xx 响应）。
/// 所有请求先过 Host 校验（防 DNS rebinding），敏感端点再加 Origin/Sec-Fetch-Site 校验。
async fn handle(state: AppState, req: Request<Incoming>) -> Result<Response<AppBody>, GatewayError> {
    if !host_allowed(req.headers().get(header::HOST), state.port) {
        return Ok(plain_response(StatusCode::FORBIDDEN, "forbidden host"));
    }
    let path = req.uri().path();
    if path == "/academic" || path.starts_with("/academic/") {
        if !browser_origin_allowed(req.headers(), state.port) {
            return Ok(plain_response(StatusCode::FORBIDDEN, "forbidden origin"));
        }
        crate::proxy::forward(&state.client, &state.upstream, req).await
    } else if path == "/__app/storage" {
        if !browser_origin_allowed(req.headers(), state.port) {
            return Ok(plain_response(StatusCode::FORBIDDEN, "forbidden origin"));
        }
        Ok(crate::storage::handle(&state.storage, req).await)
    } else if path == "/__app/cli" || path == "/__app/cli/skill" {
        if !browser_origin_allowed(req.headers(), state.port) {
            return Ok(plain_response(StatusCode::FORBIDDEN, "forbidden origin"));
        }
        Ok(cli_response(&state, path, req.method()))
    } else {
        Ok(static_response(&req))
    }
}

/// 设置页的"命令行模式"数据源：
///  - `GET /__app/cli`       命令行状态（路径、登录态、数据量），与 `doctor` 同源
///  - `GET /__app/cli/skill` 内嵌 SKILL 文本，供预览与一键复制
///
/// 两个端点都只读，且与 `/__app/storage` 一样要求同源 Origin / Sec-Fetch-Site，
/// 避免任意网页把本地服务当成"读取本机教务数据"的接口。
fn cli_response(state: &AppState, path: &str, method: &Method) -> Response<AppBody> {
    if *method != Method::GET {
        return plain_response(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
    }
    let Some(dir) = state.data_dir.as_deref() else {
        // dev 模式（Vite 5173）没有本地服务，前端按"不可用"降级展示静态文档
        return plain_response(StatusCode::SERVICE_UNAVAILABLE, "storage not enabled");
    };
    if path == "/__app/cli/skill" {
        return text_response("text/markdown; charset=utf-8", crate::cli::skill_text());
    }
    match serde_json::to_vec(&crate::cli::info_for(dir)) {
        Ok(body) => Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::CACHE_CONTROL, "no-store")
            .header("x-content-type-options", "nosniff")
            .body(full_body(Bytes::from(body)))
            .expect("cli 状态响应构造不会失败"),
        Err(_) => plain_response(StatusCode::INTERNAL_SERVER_ERROR, "serialize failed"),
    }
}

fn text_response(content_type: &str, text: &str) -> Response<AppBody> {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "no-store")
        .header("x-content-type-options", "nosniff")
        .body(full_body(Bytes::copy_from_slice(text.as_bytes())))
        .expect("文本响应构造不会失败")
}

/// Host 头白名单：只接受指向本服务实际端口的回环主机名（或无端口的裸主机名）。
/// DNS rebinding 攻击中浏览器按攻击者域名发请求，Host 携带的是该域名，
/// 在此被 403，使本服务无法被恶意页面变成「同源」资源。不带 Host 头的
/// 客户端（部分 HTTP/1.0 工具）无法构造重绑定攻击，放行。
fn host_allowed(host: Option<&HeaderValue>, port: u16) -> bool {
    let Some(host) = host.and_then(|value| value.to_str().ok()) else {
        return true;
    };
    let host = host.trim().to_ascii_lowercase();
    host == format!("127.0.0.1:{port}")
        || host == format!("localhost:{port}")
        || host == "127.0.0.1"
        || host == "localhost"
}

/// 敏感端点（/academic、/__app/storage）的浏览器来源校验：
/// 带 Origin 头时必须精确等于本服务源；带 Sec-Fetch-Site 时只允许
/// same-origin（应用自身）与 none（地址栏直达）。两个头都不带的
/// 非浏览器客户端（curl / 集成测试）不受影响。
fn browser_origin_allowed(headers: &HeaderMap, port: u16) -> bool {
    if let Some(origin) = headers.get(header::ORIGIN).and_then(|value| value.to_str().ok()) {
        let origin = origin.trim().to_ascii_lowercase();
        return origin == format!("http://127.0.0.1:{port}")
            || origin == format!("http://localhost:{port}");
    }
    match headers.get("sec-fetch-site").and_then(|value| value.to_str().ok()) {
        Some(site) => {
            let site = site.trim().to_ascii_lowercase();
            site == "same-origin" || site == "none"
        }
        None => true,
    }
}

/// HTML 文档响应的 CSP（纵深防御：静态资源全部同源、无内联脚本，
/// 恶意注入的跨源脚本 / 内联脚本将被拒绝执行）。
///
/// connect-src 额外放行的来源都是前端直连、不经过本地反代的目标：
///  - `https://api.github.com`：桌面端更新检查（web/src/services/update.js）；
///  - `https://bthadmin.vodyanitsa.top`：意见反馈提交（web/src/services/feedback.js，
///    与 Android FeedbackRepository.BASE_URL 同一地址）。
///
/// 生产页面由本服务响应头管控，tauri.conf.json 的 `app.security.csp` 只作用于
/// dev 与 `tauri://` 协议资源，两处必须同步修改（有测试盯着）。
const HTML_CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; \
     img-src 'self' data: blob:; font-src 'self' data:; \
     connect-src 'self' https://api.github.com https://bthadmin.vodyanitsa.top; \
     object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

/// 静态托管：MIME、缓存策略、SPA 回退、路径穿越防护
fn static_response(req: &Request<Incoming>) -> Response<AppBody> {
    if !matches!(*req.method(), Method::GET | Method::HEAD) {
        return plain_response(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    let raw_path = req.uri().path();
    let decoded = match percent_encoding::percent_decode_str(raw_path).decode_utf8() {
        Ok(decoded) => decoded,
        Err(_) => return plain_response(StatusCode::BAD_REQUEST, "Bad Request"),
    };
    // 穿越防护：出现 .. 段一律拒绝（release 下资产嵌入二进制本就无法逃逸，
    // 该检查同时覆盖 debug 模式的磁盘读取）
    if decoded.split('/').any(|segment| segment == "..") {
        return plain_response(StatusCode::FORBIDDEN, "Forbidden");
    }

    let lookup = decoded.trim_start_matches('/');
    let lookup = if lookup.is_empty() { "index.html" } else { lookup };
    let head_only = *req.method() == Method::HEAD;

    if let Some(asset) = WebAssets::get(lookup) {
        return asset_response(lookup, &asset, head_only);
    }

    // SPA 回退：无扩展名路径回退 index.html；带扩展名的资源缺失则 404
    if Path::new(lookup).extension().is_some() {
        return plain_response(StatusCode::NOT_FOUND, "Not Found");
    }
    match WebAssets::get("index.html") {
        Some(asset) => asset_response("index.html", &asset, head_only),
        None => plain_response(StatusCode::NOT_FOUND, "Frontend build not found: web/dist"),
    }
}

fn asset_response(name: &str, asset: &rust_embed::EmbeddedFile, head_only: bool) -> Response<AppBody> {
    let mime = mime_guess::from_path(name).first_or_octet_stream();
    let mut builder = Response::builder()
        .header(header::CONTENT_TYPE, mime.as_ref())
        .header(header::CONTENT_LENGTH, asset.data.len())
        .header("x-content-type-options", "nosniff");
    if mime.type_() == mime_guess::mime::TEXT && mime.subtype() == mime_guess::mime::HTML {
        builder = builder.header(header::CONTENT_SECURITY_POLICY, HTML_CSP);
    }
    if name == "index.html" {
        // 入口不缓存，保证发版即生效
        builder = builder.header(header::CACHE_CONTROL, "no-cache");
    } else if name.starts_with("assets/") {
        // Vite 构建的 assets 带内容哈希，可长缓存
        builder = builder.header(header::CACHE_CONTROL, "public, max-age=31536000, immutable");
    }
    let body = if head_only {
        empty_body()
    } else {
        full_body(Bytes::from(asset.data.clone().into_owned()))
    };
    builder.body(body).expect("静态响应构造不会失败")
}

pub(crate) fn plain_response(status: StatusCode, text: &str) -> Response<AppBody> {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
        .header("x-content-type-options", "nosniff")
        .body(full_body(Bytes::copy_from_slice(text.as_bytes())))
        .expect("纯文本响应构造不会失败")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_must_point_to_this_service() {
        assert!(host_allowed(Some(&HeaderValue::from_static("127.0.0.1:1950")), 1950));
        assert!(host_allowed(Some(&HeaderValue::from_static("localhost:1950")), 1950));
        assert!(host_allowed(Some(&HeaderValue::from_static("LOCALHOST:1950")), 1950));
        assert!(host_allowed(None, 1950));
        // 重绑定形态：Host 为攻击者域名，即使端口指向本服务也拒绝
        assert!(!host_allowed(Some(&HeaderValue::from_static("evil.example.com:1950")), 1950));
        // 端口不匹配同样拒绝
        assert!(!host_allowed(Some(&HeaderValue::from_static("127.0.0.1:9999")), 1950));
    }

    #[test]
    fn browser_origin_must_be_own_source() {
        // 应用自身源放行
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("http://127.0.0.1:1950"));
        assert!(browser_origin_allowed(&headers, 1950));
        // 他源 Origin 拒绝
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("http://evil.example.com"));
        assert!(!browser_origin_allowed(&headers, 1950));
        // 无 Origin / 无 Sec-Fetch-Site（curl、集成测试等非浏览器客户端）放行
        assert!(browser_origin_allowed(&HeaderMap::new(), 1950));
        // Sec-Fetch-Site：仅 same-origin（应用自身）与 none（地址栏直达）放行
        for (site, allowed) in [
            ("same-origin", true),
            ("none", true),
            ("same-site", false),
            ("cross-site", false),
        ] {
            let mut headers = HeaderMap::new();
            headers.insert("sec-fetch-site", HeaderValue::from_static(site));
            assert_eq!(browser_origin_allowed(&headers, 1950), allowed, "site={site}");
        }
    }

    /// 直连来源只允许出现在 connect-src：漏放会让更新检查 / 意见反馈被 CSP 静默拦截，
    /// 放错指令（如 default-src）则会顺带放宽其他资源类型。
    #[test]
    fn html_csp_only_whitelists_direct_origins_in_connect_src() {
        let connect_src = HTML_CSP
            .split(';')
            .map(str::trim)
            .find(|directive| directive.starts_with("connect-src"))
            .expect("CSP 必须声明 connect-src");

        for origin in ["https://api.github.com", "https://bthadmin.vodyanitsa.top"] {
            assert!(connect_src.contains(origin), "connect-src 未放行直连来源 {origin}");
            let occurrences = HTML_CSP.matches(origin).count();
            assert_eq!(occurrences, 1, "{origin} 应只在 connect-src 中出现一次");
        }
    }

    /// tauri.conf.json 的 CSP 作用于 dev 与 tauri:// 协议资源，
    /// 与生产页面实际生效的 HTML_CSP 必须保持同一份直连来源清单。
    #[test]
    fn tauri_config_csp_keeps_same_direct_origins() {
        let config = include_str!("../tauri.conf.json");
        for origin in ["https://api.github.com", "https://bthadmin.vodyanitsa.top"] {
            assert!(config.contains(origin), "tauri.conf.json 的 CSP 未放行 {origin}");
        }
    }
}
