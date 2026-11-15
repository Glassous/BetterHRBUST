//! 教务系统反向代理（/academic/* → http://jwzx.hrbust.edu.cn）
//!
//! 规则与 web/vite.config.mjs 的 academicProxy 1:1 对应：
//!  - Host 声明为教务系统域名（等价 node http-proxy 的 changeOrigin）
//!  - Referer 注入：教务系统校验该请求头
//!  - Set-Cookie 剥离 Domain 属性，使会话 Cookie 落在 127.0.0.1
//!  - 3xx 的绝对 Location 重写为 /academic/ 相对路径
//!    （教务系统仅有 HTTP(80)，绝对地址会被现代浏览器 HTTPS 自动升级
//!     或跨域拦截，导致 ERR_CONNECTION_CLOSED）
//!  - 上游超时 20s（与 tools/probe 的探测超时对齐）
//!
//! 字节流式透传，不碰编码（GBK 解码由前端 client.js 完成）。
//!
//! 错误语义（关键）：上游连接失败/超时时返回 Err 而非 502 响应，
//! hyper 会直接断开 TCP 连接 → 前端 fetch reject → 复用 client.js
//! 既有的「网络连接失败……请确认是否处于校园网或VPN环境」提示路径。

use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use futures_core::Stream;
use http::{header, HeaderValue, Method, Request, Response};
use http_body_util::BodyExt;
use hyper::body::Incoming;
use hyper_util::client::legacy::Client;

use crate::server::{empty_body, AppBody, GatewayError, Upstream};

/// 逐跳头：代理在两个方向都必须剥离（RFC 9110 7.6.1）
const HOP_BY_HOP: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
];

/// 转发前需要剥离并重新构造的头（Host/Referer 由本代理注入，
/// Content-Length 由 hyper 按实际 body 重新分帧）
const REBUILD: &[&str] = &["host", "referer", "content-length"];

/// 上游超时：响应头阶段为总超时（forward 里的 timeout），响应体阶段为逐帧空闲超时
/// （body_with_read_timeout）。教务系统中途挂起时连接不会无限占用。
const UPSTREAM_TIMEOUT: Duration = Duration::from_secs(20);

/// 反代转发的统一错误：任何上游故障都表现为 Err（连接被 hyper 掐断）
pub type ProxyResult = Result<Response<AppBody>, GatewayError>;

/// 转发一个 /academic 请求到教务系统
///
/// `session` 是跨端共享的会话桥（见 session.rs）：
/// 请求缺 Cookie 时从桥里补齐，响应带 Set-Cookie 时写回桥里，
/// 于是"终端登录一次，图形界面直接可用"，反之亦然。
pub async fn forward(
    client: &Client<hyper_util::client::legacy::connect::HttpConnector, AppBody>,
    upstream: &Upstream,
    req: Request<Incoming>,
    session: Option<&crate::session::SessionBridge>,
) -> ProxyResult {
    let fut = forward_inner(client, upstream, req, session);
    match tokio::time::timeout(UPSTREAM_TIMEOUT, fut).await {
        Ok(result) => result,
        Err(_) => Err(GatewayError::Timeout),
    }
}

async fn forward_inner(
    client: &Client<hyper_util::client::legacy::connect::HttpConnector, AppBody>,
    upstream: &Upstream,
    req: Request<Incoming>,
    session: Option<&crate::session::SessionBridge>,
) -> ProxyResult {
    // 上游 URI = 教务系统 origin + 原样 path+query（hyper client 需要绝对形式）
    let path_and_query = req
        .uri()
        .path_and_query()
        .map(|pq| pq.as_str().to_string())
        .unwrap_or_else(|| "/academic/".to_string());
    let upstream_uri = upstream.uri_for(&path_and_query);

    let mut builder = Request::builder()
        .method(req.method().clone())
        .uri(upstream_uri)
        // changeOrigin 语义：向上游声明 Host 为教务系统域名
        .header(header::HOST, upstream.host_header())
        // 教务系统校验 Referer
        .header(header::REFERER, upstream.referer());

    // 复制其余请求头（Cookie、Content-Type、Accept 等），跳过逐跳头
    let headers = builder.headers_mut().expect("builder with headers");
    for (name, value) in req.headers() {
        let lower = name.as_str().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&lower.as_str()) || REBUILD.contains(&lower.as_str()) {
            continue;
        }
        headers.append(name, value.clone());
    }

    // 会话桥接（导入方向）：WebView2 的 Cookie 里缺哪个，就从共享会话文件补哪个。
    // 终端登录过之后，图形界面第一次请求就已经是登录态，用户不需要再登一次。
    if let Some(bridge) = session {
        let existing = headers
            .get(header::COOKIE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .to_string();
        let extra: Vec<String> = bridge
            .cookies()
            .into_iter()
            .filter(|cookie| {
                !existing.split(';').any(|part| {
                    part.trim_start()
                        .starts_with(&format!("{}=", cookie.name))
                })
            })
            .map(|cookie| format!("{}={}", cookie.name, cookie.value))
            .collect();
        if !extra.is_empty() {
            let merged = if existing.trim().is_empty() {
                extra.join("; ")
            } else {
                format!("{existing}; {}", extra.join("; "))
            };
            if let Ok(value) = HeaderValue::from_str(&merged) {
                headers.insert(header::COOKIE, value);
            }
        }
    }

    // HEAD 无 body，直接发空体；其余流式透传原 body
    let body = req.into_body();
    let upstream_req = if matches!(builder.method_ref(), Some(&Method::HEAD)) {
        builder
            .body(empty_body())
            .map_err(GatewayError::Build)?
    } else {
        builder
            .body(body.map_err(GatewayError::Body).boxed())
            .map_err(GatewayError::Build)?
    };

    let response = client.request(upstream_req).await.map_err(GatewayError::Request)?;

    // 会话桥接（导出方向）：上游下发的 Cookie 写回共享文件，终端随后即可复用。
    if let Some(bridge) = session {
        let cookies: Vec<String> = response
            .headers()
            .get_all(header::SET_COOKIE)
            .iter()
            .filter_map(|value| value.to_str().ok().map(str::to_string))
            .collect();
        if !cookies.is_empty() {
            bridge.export(cookies.iter().map(String::as_str));
        }
    }

    Ok(rewrite_response(response))
}

/// 响应侧改写：剥离逐跳头、Set-Cookie 去掉 Domain 属性、3xx Location 重写为相对路径
fn rewrite_response(mut res: Response<Incoming>) -> Response<AppBody> {
    let headers = res.headers_mut();

    for name in HOP_BY_HOP {
        headers.remove(*name);
    }
    // 让 hyper 按流式 body 重新分帧
    headers.remove(header::CONTENT_LENGTH);

    // Set-Cookie: 剥离 Domain 属性，并给无过期属性的会话 Cookie 注入 Max-Age——
    // 教务 JSESSIONID 是无 Expires/Max-Age 的会话 Cookie，WebView2 默认不跨重启保存，会导致
    // 每次重启应用都要重新登录；注入持久化时限后落到用户数据目录，重启仍有效，
    // 直至教务侧会话过期（前端 isLoginPage 判定兜底）
    let cookies: Vec<HeaderValue> = headers
        .get_all(header::SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .map(rewrite_cookie)
        .filter_map(|s| HeaderValue::from_str(&s).ok())
        .collect();
    if !cookies.is_empty() {
        headers.remove(header::SET_COOKIE);
        for cookie in cookies {
            headers.append(header::SET_COOKIE, cookie);
        }
    }

    // 3xx Location: 绝对地址 http://jwzx.hrbust.edu.cn/academic/... → /academic/...
    if let Some(location) = headers.get(header::LOCATION).and_then(|v| v.to_str().ok()) {
        if let Some(rewritten) = rewrite_location(location) {
            if let Ok(value) = HeaderValue::from_str(&rewritten) {
                headers.insert(header::LOCATION, value);
            }
        }
    }

    let (parts, body) = res.into_parts();
    Response::from_parts(parts, body_with_read_timeout(body))
}

/// mpsc 接收端包装为 Stream（供 StreamBody 使用）
struct BodyChannel {
    rx: tokio::sync::mpsc::Receiver<Result<http_body::Frame<hyper::body::Bytes>, GatewayError>>,
}

impl Stream for BodyChannel {
    type Item = Result<http_body::Frame<hyper::body::Bytes>, GatewayError>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.rx.poll_recv(cx)
    }
}

/// 上游响应体逐帧读超时：总超时只覆盖到响应头（client.request 返回即完成），
/// body 是流式透传，若无超时，教务系统中途停摆会让连接与前端 fetch 无限挂起。
/// 这里用独立任务逐帧读取上游，每帧受 UPSTREAM_TIMEOUT 约束（空闲超时语义：
/// 持续有数据的慢传输不会中断，停摆则触发）。
fn body_with_read_timeout(body: Incoming) -> AppBody {
    let (tx, rx) = tokio::sync::mpsc::channel(4);
    tokio::spawn(async move {
        let mut body = Box::pin(body);
        loop {
            match tokio::time::timeout(UPSTREAM_TIMEOUT, body.as_mut().frame()).await {
                Ok(Some(Ok(frame))) => {
                    if tx.send(Ok(frame)).await.is_err() {
                        break; // 下游已断开，停止读取
                    }
                }
                Ok(Some(Err(err))) => {
                    let _ = tx.send(Err(GatewayError::Body(err))).await;
                    break;
                }
                Ok(None) => break,
                Err(_) => {
                    let _ = tx.send(Err(GatewayError::Timeout)).await;
                    break;
                }
            }
        }
    });
    http_body_util::StreamBody::new(BodyChannel { rx }).boxed()
}

/// 无过期属性的会话 Cookie 注入的持久化时限（7 天）。
/// 客户端保留时限只是上限，实际有效性以教务侧会话生命周期为准。
const SESSION_COOKIE_MAX_AGE: &str = "604800";

/// 改写单个 Set-Cookie：剥离 Domain=... 属性（大小写不敏感），
/// 无 Expires/Max-Age 时追加 Max-Age 使其可跨冷启动持久化；
/// 缺失时补 SameSite=Strict 与 HttpOnly——Cookie 落在 127.0.0.1 上，
/// SameSite=Strict 阻止本机其他端口页面借 Cookie 发子资源请求，
/// HttpOnly 使前端 JS 无法读出会话凭证（前端仅透传，从不读取）。
/// 例：`JSESSIONID=abc; Domain=hrbust.edu.cn; Path=/academic`
///   → `JSESSIONID=abc; Path=/academic; Max-Age=604800; SameSite=Strict; HttpOnly`
fn rewrite_cookie(cookie: &str) -> String {
    let mut has_expiry = false;
    let mut has_samesite = false;
    let mut has_httponly = false;
    let mut kept: Vec<&str> = Vec::new();
    for part in cookie.split(';') {
        let lower = part.trim_start().to_ascii_lowercase();
        if lower.starts_with("domain=") {
            continue; // 剥离 Domain，使 Cookie 落在 127.0.0.1
        }
        if lower.starts_with("expires=") || lower.starts_with("max-age=") {
            has_expiry = true; // 教务侧显式删除（Max-Age=0 等）原样保留
        }
        if lower.starts_with("samesite=") {
            has_samesite = true;
        }
        if lower == "httponly" {
            has_httponly = true;
        }
        kept.push(part);
    }
    let mut rewritten = kept.join(";");
    if !has_expiry {
        rewritten.push_str("; Max-Age=");
        rewritten.push_str(SESSION_COOKIE_MAX_AGE);
    }
    if !has_samesite {
        rewritten.push_str("; SameSite=Strict");
    }
    if !has_httponly {
        rewritten.push_str("; HttpOnly");
    }
    rewritten
}

/// 判断绝对地址是否指向教务系统的 /academic 路径，是则改写为相对路径。
/// 匹配 `http(s)://jwzx.hrbust.edu.cn[:port]/academic[/...]`（域名大小写不敏感），
/// 对应 vite.config.mjs 的正则 `^https?://jwzx\.hrbust\.edu\.cn(?::\d+)?/academic/?`。
fn rewrite_location(location: &str) -> Option<String> {
    let scheme_end = location.find("://")?;
    let rest = &location[scheme_end + 3..];
    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    // 去掉可选端口后与教务域名比对
    let host = authority.rsplit_once(':').map_or(authority, |(h, _)| h);
    if !host.eq_ignore_ascii_case("jwzx.hrbust.edu.cn") {
        return None;
    }
    // authority 之后必须是 /academic 路径（紧跟 ?/# 或为空都不改写）
    let tail = rest[authority_end..].strip_prefix('/')?;
    if tail.is_empty() {
        // `...edu.cn/` 形式：正则要求 /academic，不匹配则不改写
        return None;
    }
    if !tail.eq_ignore_ascii_case("academic") && !tail.to_ascii_lowercase().starts_with("academic/") {
        return None;
    }
    Some(format!("/{tail}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cookie_domain_stripped_and_session_persisted() {
        // 教务典型会话 Cookie：剥 Domain + 注入 Max-Age + 补 SameSite/HttpOnly
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; Domain=hrbust.edu.cn; Path=/academic"),
            "JSESSIONID=abc; Path=/academic; Max-Age=604800; SameSite=Strict; HttpOnly"
        );
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; domain=HRBUST.edu.cn; HttpOnly"),
            "JSESSIONID=abc; HttpOnly; Max-Age=604800; SameSite=Strict"
        );
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc"),
            "JSESSIONID=abc; Max-Age=604800; SameSite=Strict; HttpOnly"
        );
        // 已带 SameSite 的不重复追加
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; SameSite=Lax"),
            "JSESSIONID=abc; SameSite=Lax; Max-Age=604800; HttpOnly"
        );
    }

    #[test]
    fn cookie_with_explicit_expiry_left_alone() {
        // 已有 Max-Age/Expires 的不注入持久化时限（教务侧显式删除 Cookie 的路径保持原语义），
        // 但仍补 SameSite/HttpOnly 加固
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; Max-Age=0; Path=/"),
            "JSESSIONID=abc; Max-Age=0; Path=/; SameSite=Strict; HttpOnly"
        );
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; expires=Thu, 01 Jan 1970 00:00:00 GMT"),
            "JSESSIONID=abc; expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Strict; HttpOnly"
        );
        // 大小写不敏感
        assert_eq!(
            rewrite_cookie("JSESSIONID=abc; MAX-AGE=3600"),
            "JSESSIONID=abc; MAX-AGE=3600; SameSite=Strict; HttpOnly"
        );
    }

    #[test]
    fn location_rewritten_to_relative() {
        assert_eq!(
            rewrite_location("http://jwzx.hrbust.edu.cn/academic/index.jsp").as_deref(),
            Some("/academic/index.jsp")
        );
        assert_eq!(
            rewrite_location("http://JWZX.hrbust.edu.cn:80/academic/").as_deref(),
            Some("/academic/")
        );
        assert_eq!(
            rewrite_location("https://jwzx.hrbust.edu.cn/academic/index.jsp?x=1").as_deref(),
            Some("/academic/index.jsp?x=1")
        );
    }

    #[test]
    fn location_left_alone_when_not_academic() {
        assert_eq!(rewrite_location("http://jwzx.hrbust.edu.cn/other/page"), None);
        assert_eq!(rewrite_location("http://evil.example.com/academic/x"), None);
        assert_eq!(rewrite_location("http://jwzx.hrbust.edu.cn/"), None);
        assert_eq!(rewrite_location("http://jwzx.hrbust.edu.cn"), None);
    }
}
