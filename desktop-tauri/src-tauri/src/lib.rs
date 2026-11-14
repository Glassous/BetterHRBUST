//! BetterHRBUST 桌面端（Tauri）应用入口
//!
//! 架构：WebView2 窗口 → http://127.0.0.1:1950 本地服务（server.rs）
//!   ├─ 静态文件：web/dist（嵌入二进制）
//!   ├─ /academic/* → 反向代理 → http://jwzx.hrbust.edu.cn（proxy.rs）
//!   ├─ /__app/storage → 键值持久化（storage.rs，落盘 app_data_dir，
//!   │   存教务数据缓存与会话元数据，见前端 services/storage.js 门面）
//!   └─ /__app/cli → 命令行状态与 SKILL 文本（设置页"命令行模式"卡片）
//!
//! 仅监听 127.0.0.1，不对局域网暴露；http://127.0.0.1 在 WebView2
//! 中属于 secure context，无明文/混合内容限制。教务会话 Cookie 由
//! WebView2 用户数据目录（按 bundle identifier 固定）持久保存。
//!
//! 窗口控制不走自定义 IPC 命令：shim.js 桥接 withGlobalTauri 的
//! window.__TAURI__，权限经 capabilities/main.json 的 remote.urls 授予。

// 命令行模式（BetterHRBUSTCLI.exe）：只读本地缓存，供终端与 AI 工具查询。
// 逻辑放在库里，本地服务 `/__app/cli` 复用同一套路径与登录态判定。
pub mod cli;
mod proxy;
pub mod server;
mod storage;

use tauri::Manager;

/// 注入页面的 window.desktopWindow 兼容 shim（见 shim.js 头注释）
const SHIM: &str = include_str!("shim.js");

pub fn run() {
    tauri::Builder::default()
        // 单实例：二次启动时聚焦既有窗口
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                if window.is_minimized().unwrap_or(false) {
                    let _ = window.unminimize();
                }
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        // 外链转系统浏览器
        .plugin(tauri_plugin_opener::init())
        // 页面加载完成后再显示主窗口，避免白屏闪烁
        .on_page_load(|webview, payload| {
            if payload.event() == tauri::webview::PageLoadEvent::Finished && webview.label() == "main"
            {
                if let Some(window) = webview.app_handle().get_webview_window("main") {
                    let _ = window.show();
                }
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            // 键值持久化目录：%APPDATA%/edu.hrbust.betterhrbust/storage.json
            // （打开失败在 server::start 内降级为不启用，不影响启动）
            let data_dir = app.path().app_data_dir().ok();
            tauri::async_runtime::spawn(async move {
                // 开发模式直连 Vite dev server（其自带 /academic 代理，热更新）；
                // 生产启动内置本地服务
                let url = if tauri::is_dev() {
                    "http://localhost:5173/".to_string()
                } else {
                    let mut config = server::ServerConfig::production();
                    config.storage_dir = data_dir;
                    match server::start(config).await {
                        Ok(addr) => format!("http://{addr}/"),
                        Err(err) => {
                            eprintln!("[BetterHRBUST] 本地服务启动失败: {err}");
                            handle.exit(1);
                            return;
                        }
                    }
                };
                if let Err(err) = create_window(&handle, &url) {
                    eprintln!("[BetterHRBUST] 窗口创建失败: {err}");
                    handle.exit(1);
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("BetterHRBUST 运行失败");
}

fn create_window(
    app: &tauri::AppHandle,
    url: &str,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let parsed: tauri::Url = url.parse()?;
    // 导航守卫：仅允许停留在应用源内，其余 http(s) 转系统浏览器
    let allowed_origin = format!("{}://{}", parsed.scheme(), parsed.authority());

    tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::External(parsed))
        .title("BetterHRBUST")
        .inner_size(1280.0, 800.0)
        .min_inner_size(960.0, 600.0)
        // 无原生标题栏：顶部区域由前端 DesktopTitleBar 以 WebUI 风格接管
        .decorations(false)
        // 先隐藏，页面加载完成后再显示，避免白屏闪烁（等价 ready-to-show）
        .visible(false)
        .initialization_script(SHIM)
        .on_navigation(move |nav| {
            let target = nav.as_str();
            if navigation_allowed(target, &allowed_origin) {
                return true;
            }
            if nav.scheme() == "http" || nav.scheme() == "https" {
                let _ = tauri_plugin_opener::open_url(target.to_string(), None::<&str>);
            }
            false
        })
        .build()?;

    Ok(())
}

/// 导航守卫判定：仅允许停留在应用源内（其余 http(s) 由调用方转系统浏览器）。
/// 纵深防御：应用源内也拒绝文档导航进入 /academic 与 /__app 端点路径——
/// 这些路径是给页面内 fetch/XHR 用的程序化端点，没有可展示的页面内容，
/// 混入恶意文档导航只会扩大攻击面（fetch/XHR 不受本守卫影响）。
fn navigation_allowed(target: &str, allowed_origin: &str) -> bool {
    let same_origin = target.starts_with(allowed_origin)
        && (target.len() == allowed_origin.len()
            || target.as_bytes().get(allowed_origin.len()) == Some(&b'/')
            || target.as_bytes().get(allowed_origin.len()) == Some(&b'?'));
    if !same_origin {
        return false;
    }
    let rest = &target[allowed_origin.len()..];
    let path_only = match rest.find(['?', '#']) {
        Some(index) => &rest[..index],
        None => rest,
    };
    let is_endpoint_path = |prefix: &str| {
        path_only == prefix || path_only.starts_with(&format!("{prefix}/"))
    };
    !(is_endpoint_path("/academic") || is_endpoint_path("/__app"))
}

#[cfg(test)]
mod tests {
    use super::navigation_allowed;

    const ORIGIN: &str = "http://127.0.0.1:1950";

    #[test]
    fn allows_app_pages_and_assets() {
        assert!(navigation_allowed("http://127.0.0.1:1950/", ORIGIN));
        assert!(navigation_allowed("http://127.0.0.1:1950/index.html", ORIGIN));
        assert!(navigation_allowed("http://127.0.0.1:1950/assets/app.js?v=1", ORIGIN));
        assert!(navigation_allowed("http://127.0.0.1:1950?tab=1", ORIGIN));
    }

    #[test]
    fn rejects_foreign_origins() {
        assert!(!navigation_allowed("https://evil.example.com/", ORIGIN));
        // 前缀相同但边界字符不同的伪装路径
        assert!(!navigation_allowed("http://127.0.0.1:19500/x", ORIGIN));
        assert!(!navigation_allowed("http://127.0.0.1:1950.evil.com/", ORIGIN));
    }

    #[test]
    fn rejects_endpoint_document_navigation() {
        assert!(!navigation_allowed("http://127.0.0.1:1950/academic/index.jsp", ORIGIN));
        assert!(!navigation_allowed("http://127.0.0.1:1950/academic", ORIGIN));
        assert!(!navigation_allowed("http://127.0.0.1:1950/__app/storage", ORIGIN));
        assert!(!navigation_allowed("http://127.0.0.1:1950/__app/storage?key=k", ORIGIN));
        // 前缀相似但不是端点路径的正常页面不误伤
        assert!(navigation_allowed("http://127.0.0.1:1950/academics", ORIGIN));
        assert!(navigation_allowed("http://127.0.0.1:1950/__apple", ORIGIN));
    }
}
