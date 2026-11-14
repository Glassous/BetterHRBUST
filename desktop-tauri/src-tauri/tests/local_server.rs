//! 本地服务集成测试（静态托管、SPA 回退、路径穿越防护、反代规则、断连语义、端口顺延）
//!
//! 前置条件：web/dist 已构建（rust-embed 在 debug 模式从磁盘读取）。
//! 反代测试用本地 mock 教务系统（临时端口）替代真实上游，
//! 断言 Host/Referer 注入、Cookie 透传、Set-Cookie 域剥离与 302 重写——
//! 不依赖校园网即可完整验证代理规则。

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use http::{header, Request, Response, StatusCode};
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper_util::client::legacy::Client;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use tokio::net::TcpListener;

use betterhrbust_desktop_lib::server::{self, service_fn, ServerConfig, Upstream};

type TestClient = Client<HttpConnector, Full<Bytes>>;

/// mock 上游记录到的关键请求头快照
#[derive(Default)]
struct SeenHeaders {
    host: Option<String>,
    referer: Option<String>,
    cookie: Option<String>,
}

fn web_dist() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../web/dist")
}

fn require_web_dist() {
    let index = web_dist().join("index.html");
    assert!(
        index.exists(),
        "缺少前端构建产物 {}：请先在 web/ 目录执行 npm install && npm run build",
        index.display()
    );
}

async fn spawn_mock_upstream() -> (String, Arc<Mutex<SeenHeaders>>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Mutex::new(SeenHeaders::default()));
    let seen_task = seen.clone();

    tokio::spawn(async move {
        loop {
            let (stream, _) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => continue,
            };
            let seen = seen_task.clone();
            tokio::spawn(async move {
                let service = service_fn(move |req: Request<Incoming>| {
                    let seen = seen.clone();
                    async move {
                        {
                            let mut guard = seen.lock().unwrap();
                            guard.host = req
                                .headers()
                                .get(header::HOST)
                                .and_then(|v| v.to_str().ok())
                                .map(String::from);
                            guard.referer = req
                                .headers()
                                .get(header::REFERER)
                                .and_then(|v| v.to_str().ok())
                                .map(String::from);
                            guard.cookie = req
                                .headers()
                                .get(header::COOKIE)
                                .and_then(|v| v.to_str().ok())
                                .map(String::from);
                        }
                        if req.uri().path() == "/academic/redirect" {
                            // 模拟教务系统 302：绝对 Location + 带 Domain 的会话 Cookie
                            Ok::<_, std::convert::Infallible>(
                                Response::builder()
                                    .status(StatusCode::FOUND)
                                    .header(
                                        header::LOCATION,
                                        "http://jwzx.hrbust.edu.cn/academic/index.jsp",
                                    )
                                    .header(
                                        header::SET_COOKIE,
                                        "JSESSIONID=mock123; Domain=hrbust.edu.cn; Path=/academic",
                                    )
                                    .body(Full::new(Bytes::new()))
                                    .unwrap(),
                            )
                        } else {
                            // 模拟 GBK 页面：代理必须字节透传，不得改写 Content-Type
                            Ok(Response::builder()
                                .header(header::CONTENT_TYPE, "text/html;charset=GBK")
                                .body(Full::new(Bytes::from_static(
                                    b"<html><body>GBK \xd2\xb3\xc3\xe6</body></html>",
                                )))
                                .unwrap())
                        }
                    }
                });
                let _ = auto::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(stream), service)
                    .await;
            });
        }
    });

    (format!("http://{addr}"), seen)
}

/// 以指定上游 + 随机端口启动被测服务（不启用持久化）
async fn spawn_server(upstream_origin: &str) -> String {
    let config = ServerConfig {
        upstream: Upstream::parse(upstream_origin),
        port_base: 0,
        port_tries: 1,
        storage_dir: None,
    };
    let addr = server::start(config).await.expect("本地服务启动失败");
    format!("http://{addr}")
}

/// 以指定持久化目录 + 随机端口启动被测服务（静态/反代不涉及，无需 web/dist）
async fn spawn_server_with_storage(storage_dir: std::path::PathBuf) -> String {
    let config = ServerConfig {
        upstream: Upstream::parse("http://127.0.0.1:1"),
        port_base: 0,
        port_tries: 1,
        storage_dir: Some(storage_dir),
    };
    let addr = server::start(config).await.expect("本地服务启动失败");
    format!("http://{addr}")
}

fn client() -> TestClient {
    Client::builder(TokioExecutor::new()).build_http()
}

async fn get(
    url: &str,
    extra: &[(&str, &str)],
) -> Result<Response<Incoming>, hyper_util::client::legacy::Error> {
    let mut builder = Request::builder().uri(url);
    for (name, value) in extra {
        builder = builder.header(*name, *value);
    }
    let request = builder.body(Full::new(Bytes::new())).unwrap();
    tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
}

async fn body_text(res: Response<Incoming>) -> String {
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    String::from_utf8_lossy(&bytes).into_owned()
}

// ---------------------------------------------------------------- 静态托管

#[tokio::test]
async fn serves_index_html() {
    require_web_dist();
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CONTENT_TYPE].to_str().unwrap().contains("text/html"));
    assert!(body_text(res).await.contains("<html"));
}

#[tokio::test]
async fn assets_are_long_cached() {
    require_web_dist();
    let assets_dir = web_dist().join("assets");
    let first_js = std::fs::read_dir(&assets_dir)
        .expect("web/dist/assets 不存在")
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .find(|name| name.ends_with(".js"))
        .expect("assets 下无 js 产物");
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/assets/{first_js}"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CONTENT_TYPE].to_str().unwrap().contains("javascript"));
    assert!(res.headers()[header::CACHE_CONTROL]
        .to_str()
        .unwrap()
        .contains("immutable"));
}

#[tokio::test]
async fn spa_fallback_for_extensionless_routes() {
    require_web_dist();
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/some/unknown/route"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CONTENT_TYPE].to_str().unwrap().contains("text/html"));
}

#[tokio::test]
async fn missing_file_with_extension_is_404() {
    require_web_dist();
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/no-such-file.png"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn path_traversal_is_forbidden() {
    require_web_dist();
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/..%2f..%2fdesktop-tauri%2fpackage.json"), &[])
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN);
}

// ---------------------------------------------------------------- 反向代理

#[tokio::test]
async fn academic_proxy_injects_headers_and_streams_body() {
    require_web_dist();
    let (upstream, seen) = spawn_mock_upstream().await;
    let origin = spawn_server(&upstream).await;

    let res = get(
        &format!("{origin}/academic/index.jsp"),
        &[("cookie", "JSESSIONID=prev-session")],
    )
    .await
    .unwrap();

    assert_eq!(res.status(), StatusCode::OK);
    // 字节透传：Content-Type 原样保留（GBK 由前端解码）
    assert!(res.headers()[header::CONTENT_TYPE].to_str().unwrap().contains("GBK"));
    assert!(body_text(res).await.contains("<html>"));

    let guard = seen.lock().unwrap();
    // changeOrigin：上游收到的 Host 是上游自己的地址（生产环境即教务域名）
    let expected_authority = upstream.trim_start_matches("http://");
    assert_eq!(guard.host.as_deref(), Some(expected_authority));
    // Referer 注入
    let expected_referer = format!("{upstream}/academic/");
    assert_eq!(guard.referer.as_deref(), Some(expected_referer.as_str()));
    // 会话 Cookie 透传
    assert_eq!(guard.cookie.as_deref(), Some("JSESSIONID=prev-session"));
}

#[tokio::test]
async fn academic_redirect_location_and_cookie_domain_rewritten() {
    require_web_dist();
    let (upstream, _seen) = spawn_mock_upstream().await;
    let origin = spawn_server(&upstream).await;

    // 不跟随重定向（legacy client 默认行为），直接检查 302 响应
    let res = get(&format!("{origin}/academic/redirect"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::FOUND);
    assert_eq!(
        res.headers()[header::LOCATION].to_str().unwrap(),
        "/academic/index.jsp"
    );
    // Domain 剥离 + 会话 Cookie 注入 Max-Age 与 SameSite/HttpOnly
    // （跨冷启动持久化 + 阻断本机其他端口页面借 Cookie）
    assert_eq!(
        res.headers()[header::SET_COOKIE].to_str().unwrap(),
        "JSESSIONID=mock123; Path=/academic; Max-Age=604800; SameSite=Strict; HttpOnly"
    );
}

#[tokio::test]
async fn unreachable_upstream_aborts_connection() {
    require_web_dist();
    // 端口 1 几乎必然无监听：模拟非校园网环境教务系统不可达
    let origin = spawn_server("http://127.0.0.1:1").await;
    let result = get(&format!("{origin}/academic/anything"), &[]).await;
    // 关键语义：连接被掐断（fetch reject），而非收到 502 响应
    assert!(result.is_err(), "上游不可达时必须断开连接，实际得到: {:?}", result.map(|r| r.status()));
}

// ---------------------------------------------------------------- 端口策略

#[tokio::test]
async fn port_falls_back_when_occupied() {
    // 不直接用 PORT_BASE：开发者本机可能正运行桌面端（固定占用 1950），
    // 那会让这条与"端口顺延逻辑"无关的用例误报。
    //
    // 也不用 bind(0) 取系统临时端口：同一测试二进制里的用例并行执行，
    // 其他用例的临时端口（Windows 49152+ / Linux 32768+）可能刚好抢走 base+1，
    // 让"顺延到下一个端口"的断言偶发失败。这里在一段固定低端口区间内找一对
    // 相邻空闲端口：先占住 base，要求服务顺延到 base+1。
    let (occupant, base) = {
        let mut picked = None;
        for base in 18_000u16..18_100 {
            // Hyper-V / 保留端口区间会导致 bind 失败，跳过即可
            let Ok(occupant) = std::net::TcpListener::bind(("127.0.0.1", base)) else {
                continue;
            };
            // 探测用的监听器立刻释放，确保 base+1 空闲
            let Ok(probe) = std::net::TcpListener::bind(("127.0.0.1", base + 1)) else {
                continue;
            };
            drop(probe);
            picked = Some((occupant, base));
            break;
        }
        picked.expect("找不到可用的连续端口对")
    };
    let config = ServerConfig {
        upstream: Upstream::parse("http://127.0.0.1:1"),
        port_base: base,
        port_tries: server::PORT_TRIES,
        storage_dir: None,
    };
    let addr = server::start(config).await.expect("端口顺延后应绑定成功");
    assert_eq!(addr.port(), base + 1, "被占用时应顺延到下一个端口");
    drop(occupant);
}

// ---------------------------------------------------------------- 键值持久化

async fn put(
    url: &str,
    key: &str,
    value: &str,
) -> Result<Response<Incoming>, hyper_util::client::legacy::Error> {
    let request = Request::builder()
        .method(http::Method::PUT)
        .uri(url)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Full::new(Bytes::from(
            serde_json::json!({ "key": key, "value": value }).to_string(),
        )))
        .unwrap();
    tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
}

async fn delete(
    url: &str,
) -> Result<Response<Incoming>, hyper_util::client::legacy::Error> {
    let request = Request::builder().method(http::Method::DELETE).uri(url).body(Full::new(Bytes::new())).unwrap();
    tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
}

/// 落盘文件存在且为合法 JSON 对象
fn assert_storage_file(dir: &std::path::Path) {
    let text = std::fs::read_to_string(dir.join("storage.json")).expect("storage.json 应存在");
    let map = serde_json::from_str::<std::collections::BTreeMap<String, String>>(&text)
        .expect("storage.json 应为键值 JSON 对象");
    assert!(!map.is_empty());
}

#[tokio::test]
async fn storage_roundtrip_and_persistence_across_restart() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;

    // 空库 GET 返回空对象
    let res = get(&format!("{origin}/__app/storage"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CACHE_CONTROL].to_str().unwrap().contains("no-store"));
    assert_eq!(body_text(res).await, "{}");

    // PUT 写入两个键（模拟一次全量同步落缓存）
    put(&format!("{origin}/__app/storage"), "better_hrbust_cache_scores", r#"[{"name":"高数"}]"#)
        .await
        .unwrap();
    put(&format!("{origin}/__app/storage"), "better_hrbust_last_login_at", "1760000000000")
        .await
        .unwrap();
    assert_storage_file(dir.path());

    // GET 全量可见
    let res = get(&format!("{origin}/__app/storage"), &[]).await.unwrap();
    let map: serde_json::Value = serde_json::from_str(&body_text(res).await).unwrap();
    assert_eq!(map["better_hrbust_cache_scores"], r#"[{"name":"高数"}]"#);
    assert_eq!(map["better_hrbust_last_login_at"], "1760000000000");

    // 模拟冷启动：同一目录重开服务（新 Storage 实例），数据从磁盘恢复
    drop(origin);
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;
    let res = get(&format!("{origin}/__app/storage"), &[]).await.unwrap();
    let map: serde_json::Value = serde_json::from_str(&body_text(res).await).unwrap();
    assert_eq!(map["better_hrbust_cache_scores"], r#"[{"name":"高数"}]"#);
}

#[tokio::test]
async fn storage_delete_key_and_clear_all() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;

    put(&format!("{origin}/__app/storage"), "k1", "v1").await.unwrap();
    put(&format!("{origin}/__app/storage"), "k2", "v2").await.unwrap();

    // 删除单键
    let res = delete(&format!("{origin}/__app/storage?key=k1")).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let map: serde_json::Value =
        serde_json::from_str(&body_text(get(&format!("{origin}/__app/storage"), &[]).await.unwrap()).await).unwrap();
    assert!(map.get("k1").is_none());
    assert_eq!(map["k2"], "v2");

    // 不带 key 清空全部（退出登录场景）
    let res = delete(&format!("{origin}/__app/storage")).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let text = body_text(get(&format!("{origin}/__app/storage"), &[]).await.unwrap()).await;
    assert_eq!(text, "{}");
}

#[tokio::test]
async fn storage_rejects_invalid_requests() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;

    // 非 JSON 体
    let res = put_raw(&format!("{origin}/__app/storage"), "not-json").await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);

    // 缺 key
    let res = put_raw(&format!("{origin}/__app/storage"), r#"{"value":"x"}"#).await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);

    // 不支持的方法（POST）
    let request = Request::builder()
        .method(http::Method::POST)
        .uri(format!("{origin}/__app/storage"))
        .body(Full::new(Bytes::new()))
        .unwrap();
    let res = tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
        .unwrap();
    assert_eq!(res.status(), StatusCode::METHOD_NOT_ALLOWED);
}

#[tokio::test]
async fn storage_oversized_put_rejected_with_413() {
    // 超过 16MB 上限的 PUT 必须 413（Limited 在读到上限时即断流，不整包缓冲）
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;
    let oversized = vec![b'x'; 16 * 1024 * 1024 + 1];
    let request = Request::builder()
        .method(http::Method::PUT)
        .uri(format!("{origin}/__app/storage"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(Full::new(Bytes::from(oversized)))
        .unwrap();
    let res = tokio::time::timeout(Duration::from_secs(30), client().request(request))
        .await
        .expect("请求超时")
        .unwrap();
    assert_eq!(res.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

/// 直接以原始文本作为 PUT 体（校验 4xx 路径）
async fn put_raw(
    url: &str,
    raw: &str,
) -> Result<Response<Incoming>, hyper_util::client::legacy::Error> {
    let request = Request::builder()
        .method(http::Method::PUT)
        .uri(url)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Full::new(Bytes::from(raw.to_string())))
        .unwrap();
    tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
}

#[tokio::test]
async fn storage_disabled_returns_503() {
    // dev 模式（storage_dir = None）：端点明确 503，前端门面据此回落 localStorage
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/__app/storage"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
}

// ---------------------------------------------------------------- 命令行端点

/// 设置页"命令行模式"卡片的数据源：状态 JSON 与 SKILL 文本
#[tokio::test]
async fn cli_endpoint_reports_state_and_serves_skill() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;

    // 空目录：端点仍可访问，状态为"未登录、无数据"（设置页据此给出登录引导）
    let res = get(&format!("{origin}/__app/cli"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CACHE_CONTROL].to_str().unwrap().contains("no-store"));
    let info: serde_json::Value = serde_json::from_str(&body_text(res).await).unwrap();
    assert_eq!(info["loggedIn"], false);
    assert_eq!(info["hasData"], false);
    assert!(info["cliPath"].as_str().unwrap().ends_with("BetterHRBUSTCLI.exe"));
    assert_eq!(info["dataDir"].as_str().unwrap(), dir.path().to_string_lossy());

    // 写入缓存后状态跟随变化（与命令行 doctor 同源）
    put(
        &format!("{origin}/__app/storage"),
        "better_hrbust_cache_exams",
        r#"[{"courseName":"高等数学"}]"#,
    )
    .await
    .unwrap();
    let res = get(&format!("{origin}/__app/cli"), &[]).await.unwrap();
    let info: serde_json::Value = serde_json::from_str(&body_text(res).await).unwrap();
    assert_eq!(info["hasData"], true);
    assert_eq!(info["counts"]["exams"], 1);

    // SKILL 端点返回内嵌 Markdown（设置页预览 / 一键复制）
    let res = get(&format!("{origin}/__app/cli/skill"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    assert!(res.headers()[header::CONTENT_TYPE].to_str().unwrap().contains("text/markdown"));
    let skill = body_text(res).await;
    assert!(skill.contains("name: betterhrbust"), "SKILL 缺少 frontmatter");
    assert!(skill.contains("--json"));

    // 只读：非 GET 方法不允许（避免被当成写入接口）
    let request = Request::builder()
        .method(http::Method::POST)
        .uri(format!("{origin}/__app/cli"))
        .body(Full::new(Bytes::new()))
        .unwrap();
    let res = tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(res.status(), StatusCode::METHOD_NOT_ALLOWED);
}

/// 与 /__app/storage 同样的来源校验：跨源网页不能读取本机命令行状态
#[tokio::test]
async fn cli_endpoint_rejects_cross_origin() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;

    let res = get(&format!("{origin}/__app/cli"), &[("origin", "http://evil.example.com")])
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN);

    let res = get(&format!("{origin}/__app/cli/skill"), &[("sec-fetch-site", "cross-site")])
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN);
}

/// dev 模式没有数据目录：前端降级为静态文档
#[tokio::test]
async fn cli_endpoint_returns_503_without_data_dir() {
    let origin = spawn_server("http://127.0.0.1:1").await;
    let res = get(&format!("{origin}/__app/cli"), &[]).await.unwrap();
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
}

// ---------------------------------------------------------------- 来源校验

/// 以覆盖头发起请求（伪造 Host / Origin / Sec-Fetch-Site 模拟重绑定与跨站页面）
async fn request_with_headers(
    url: &str,
    extra: &[(&str, &str)],
) -> Result<Response<Incoming>, hyper_util::client::legacy::Error> {
    // 先构造再覆盖：http crate 允许显式 Host 覆盖客户端默认值
    let mut builder = Request::builder().uri(url);
    for (name, value) in extra {
        builder = builder.header(*name, *value);
    }
    let request = builder.body(Full::new(Bytes::new())).unwrap();
    tokio::time::timeout(Duration::from_secs(10), client().request(request))
        .await
        .expect("请求超时")
}

#[tokio::test]
async fn foreign_host_header_is_rejected_everywhere() {
    // DNS rebinding 防护：重绑定后浏览器按攻击者域名发请求，
    // Host 不指向本服务（静态与敏感端点同样）一律 403
    let origin = spawn_server("http://127.0.0.1:1").await;
    for path in ["/", "/__app/storage", "/academic/index.jsp"] {
        let res = request_with_headers(&format!("{origin}{path}"), &[("host", "evil.example.com")])
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::FORBIDDEN, "path={path}");
    }
}

#[tokio::test]
async fn cross_site_browser_markers_rejected_on_sensitive_endpoints() {
    let origin = spawn_server("http://127.0.0.1:1").await;
    for (marker, value) in [("origin", "http://evil.example.com"), ("sec-fetch-site", "cross-site")] {
        // 敏感端点：403
        let res = request_with_headers(
            &format!("{origin}/__app/storage"),
            &[(marker, value)],
        )
        .await
        .unwrap();
        assert_eq!(res.status(), StatusCode::FORBIDDEN, "{marker}={value}");
        let res = request_with_headers(
            &format!("{origin}/academic/index.jsp"),
            &[(marker, value)],
        )
        .await
        .unwrap();
        assert_eq!(res.status(), StatusCode::FORBIDDEN, "{marker}={value}");
    }
    // 应用自身标记放行：无 storage 时 /__app/storage 回到 503（未被来源校验拦截）
    let res = request_with_headers(
        &format!("{origin}/__app/storage"),
        &[("sec-fetch-site", "same-origin")],
    )
    .await
    .unwrap();
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn same_origin_and_plain_clients_pass_storage_gate() {
    let dir = tempfile::tempdir().unwrap();
    let origin = spawn_server_with_storage(dir.path().to_path_buf()).await;
    // 应用自身 Origin：200
    let port = origin.trim_start_matches("http://127.0.0.1:");
    let res = request_with_headers(
        &format!("{origin}/__app/storage"),
        &[("origin", &format!("http://127.0.0.1:{port}"))],
    )
    .await
    .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
}
