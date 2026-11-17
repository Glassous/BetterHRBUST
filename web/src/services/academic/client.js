/**
 * 教务在线 HTTP 客户端
 * 负责处理 GBK / UTF-8 编码自适应解码、会话状态识别与错误捕获
 */

const BASE_PREFIX = '/academic/';

// 官方教务在线（默认地址）。教务系统仅开放 HTTP，没有 443 端口。
const OFFICIAL_HOST = 'jwzx.hrbust.edu.cn';
/** 官方地址（设置页展示与三端共用口径，避免多份字面量漂移） */
export const OFFICIAL_BASE = 'http://jwzx.hrbust.edu.cn/academic/';

/**
 * 当前生效的自定义教务地址（'' 表示官方默认）。
 *
 * 只由 setAcademicBase() 写入：本模块刻意保持零 import，
 * 既有测试直接用 vm 沙箱执行源码（见 scripts/test-http-transport.mjs）。
 * 桌面端即使配置了自定义地址也继续请求相对 `/academic/`，由 Rust 反代决定上游：
 * WebView 直连第三方域名会同时踩 CSP 白名单与 Cookie 归属两个坑。
 */
let ACADEMIC_BASE = '';

/**
 * 归一化自定义教务地址：统一为 `协议://主机[:端口]/academic/`。
 *
 * 容错规则：可直接粘贴主页链接（如 https://jwzx.example.edu.cn/homepage/index.do）
 * 或裸域名；路径、查询串、锚点一律丢弃。空值返回 ''（官方默认）。
 * 非法输入抛中文提示，供设置页原样展示。
 */
export function normalizeAcademicBase(raw) {
  const text = String(raw == null ? '' : raw).trim();
  if (!text) return '';
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text);
  if (hasScheme && !/^https?:\/\//i.test(text)) {
    throw new Error('教务地址只支持 http:// 或 https:// 开头');
  }
  let parsed;
  try {
    parsed = new URL(hasScheme ? text : `http://${text}`);
  } catch {
    throw new Error('教务地址格式不正确，请填写形如 https://jwzx.example.edu.cn 的地址');
  }
  if (parsed.username || parsed.password) throw new Error('教务地址不能包含用户名或密码');
  if (!parsed.hostname) throw new Error('教务地址缺少主机名');
  // 未显式声明协议时：官方域名沿用 HTTP（官方无 443），其他部署默认 HTTPS
  const scheme = hasScheme
    ? parsed.protocol.slice(0, -1).toLowerCase()
    : (parsed.hostname.toLowerCase() === OFFICIAL_HOST ? 'http' : 'https');
  return `${scheme}://${parsed.host}/academic/`;
}

/** 写入生效地址；非法输入回退官方默认，保证请求链路永远可用 */
export function setAcademicBase(raw) {
  try {
    ACADEMIC_BASE = normalizeAcademicBase(raw);
  } catch {
    ACADEMIC_BASE = '';
  }
  // 换地址后重新给直连一次机会（上一个站点被拦不代表新站点也被拦）
  DIRECT_BLOCKED = false;
  return ACADEMIC_BASE;
}

/** 当前自定义地址；'' 表示官方默认 */
export function getAcademicBase() {
  return ACADEMIC_BASE;
}

/**
 * 是否必须走同源 `/academic/` 代理：
 * 官方默认（Vite/自托管反代、油猴同源页面）与桌面端（Rust 反代）都是。
 */
export function usesAcademicProxy() {
  return !ACADEMIC_BASE || (typeof window !== 'undefined' && !!window.desktopWindow);
}

// HTTP 教务页面优先使用页面自身的同源 fetch，与验证码共享会话 Cookie。
// 扩展后台请求可能被浏览器升级至 HTTPS，不能作为 HTTP 页面上的首选。
// Web / 本地反代继续使用页面 fetch；其他油猴场景保留扩展通道。
let PAGE_FETCH =
  typeof unsafeWindow !== 'undefined' && unsafeWindow && typeof unsafeWindow.fetch === 'function'
    ? unsafeWindow.fetch.bind(unsafeWindow)
    : fetch.bind(globalThis);

// 在页面上下文中调用 fetch，避免调用方仍被认定为安全的扩展页面而升级 HTTP。
if (typeof unsafeWindow !== 'undefined' && typeof document !== 'undefined' &&
    location.protocol === 'http:' && location.hostname === 'jwzx.hrbust.edu.cn') {
  const key = `__betterHRBUSTFetch_${Date.now()}`;
  const script = document.createElement('script');
  script.textContent = `window[${JSON.stringify(key)}] = function(url, options) {
    return window.fetch(url, options);
  };`;
  document.documentElement.appendChild(script);
  script.remove();
  if (typeof unsafeWindow[key] === 'function') {
    PAGE_FETCH = unsafeWindow[key].bind(unsafeWindow);
    delete unsafeWindow[key];
  }
}

/**
 * 相对路径转绝对 URL(GM_xmlhttpRequest 要求绝对地址)
 */
function absoluteUrl(path) {
  return new URL(resolveUrl(path), location.href).href;
}

/**
 * 统一请求执行:返回 { ok, status, contentType, buffer, finalUrl }
 */
function executeRequest(url, { method = 'GET', headers = {}, body = null, referrer } = {}) {
  if (new URL(url, location.href).origin === location.origin &&
      new URL(url, location.href).pathname.startsWith(BASE_PREFIX)) {
    return (async () => {
      const pathname = new URL(url, location.href).pathname;
      const login = pathname === '/academic/j_acegi_security_check';
      const evaluation = pathname.startsWith('/academic/eva/');
      const logout = pathname === '/academic/logout_security_check';
      const context = pathname === '/academic/student/currcourse/currcourse.jsdo';
      const manual = (method.toUpperCase() === 'POST' && (login || evaluation)) || logout || context;
      const response = await PAGE_FETCH(url, {
        method, headers: { ...headers, 'Cache-Control': 'no-cache' }, body, credentials: 'include', cache: 'no-store',
        ...(referrer ? { referrer } : {}),
        redirect: manual ? 'manual' : 'follow', signal: AbortSignal.timeout(30000)
      });
      // 原站的 POST 成功响应可能跳转到不可用的 HTTPS；接收 Cookie 后只读 HTTP 状态。
      // 评教完成与否由调用方重新读取列表核对，302 本身不等于评教成功。
      if (manual && response.type === 'opaqueredirect') {
        if (logout) return { ok: true, status: 202, contentType: '', buffer: new ArrayBuffer(0), finalUrl: url };
        if (context) return executeRequest(absoluteUrl('common/security/login.jsp'));
        if (login) return executeRequest(absoluteUrl('student/currcourse/currcourse.jsdo'));
        return { ok: true, status: 202, contentType: '', buffer: new ArrayBuffer(0), finalUrl: url };
      }
      return {
        ok: response.ok, status: response.status,
        contentType: response.headers.get('content-type') || '',
        buffer: await response.arrayBuffer(), finalUrl: response.url || url
      };
    })();
  }
  if (typeof GM_xmlhttpRequest === 'function') {
    return new Promise((resolve, reject) => {
      const details = {
        method,
        url,
        headers: { ...headers },
        timeout: 30000,
        responseType: 'arraybuffer',
        onload: (r) => {
          const contentType =
            (String(r.responseHeaders || '').match(/content-type:\s*([^\r\n;]+)/i) || [])[1] || '';
          resolve({
            ok: r.status >= 200 && r.status < 300,
            status: r.status,
            contentType,
            buffer: r.response || new ArrayBuffer(0),
            finalUrl: r.finalUrl || url
          });
        },
        onerror: () => reject(new Error('网络连接失败(扩展通道)')),
        ontimeout: () => reject(new Error('请求超时(扩展通道)'))
      };
      if (body) {
        details.data = typeof body === 'string' ? body : String(body);
      }
      GM_xmlhttpRequest(details);
    });
  }
  // 纯浏览器（含油猴未提供扩展通道的兜底）：直连自定义地址，被浏览器拦下时自动改走同源代理
  return fetchDirectOrProxied(url, { method, headers, body, referrer });
}

/** 同源代理读取的自定义目标提示头（vite dev/preview 的 academic 中间件认识它） */
const PROXY_BASE_HEADER = 'x-betterhrbust-academic-base';

/**
 * 浏览器直连是否已被跨域策略拦下。
 *
 * 只用来避免每个请求都白试一次：一旦被拦下，本页后续请求直接走同源代理。
 * 切换地址（setAcademicBase）会重置。
 */
let DIRECT_BLOCKED = false;

/** 直连是否已被浏览器拦下（false 表示仍走直连） */
export function academicDirectBlocked() {
  return DIRECT_BLOCKED;
}

function readResponse(response) {
  return response.arrayBuffer().then((buffer) => ({
    ok: response.ok,
    status: response.status,
    contentType: response.headers.get('content-type') || '',
    buffer,
    finalUrl: response.url
  }));
}

/** 跨域被拦在浏览器里只表现为 TypeError/Failed to fetch，这里只区分"网络层失败" */
function isNetworkFailure(err) {
  if (err instanceof TypeError) return true;
  const message = String((err && err.message) || err || '');
  return /failed to fetch|network ?error|load failed|网络连接失败/i.test(message);
}

/**
 * 直连 → 同源代理 的回退链。
 *
 * 教务站点很少返回 CORS 头，浏览器会直接拦下跨域响应；此时把绝对地址折回同源的
 * `/academic/...` 并附上目标提示头，由本地代理（vite dev/preview 的 academic 中间件）
 * 转发。Cookie 与 302 都落在本地，登录流程照常可用。
 */
function fetchDirectOrProxied(url, options) {
  if (usesAcademicProxy() || DIRECT_BLOCKED) return fetchThroughProxy(url, options);
  const { method, headers, body, referrer } = options;
  return PAGE_FETCH(url, {
    method,
    headers,
    body,
    credentials: 'include', cache: 'no-store', // 私有数据不能复用旧账号的 HTTP 缓存
    ...(referrer ? { referrer } : {}),
    redirect: 'follow'
  }).then(readResponse).catch((err) => {
    if (!isNetworkFailure(err) || !canUseSameOriginProxy()) throw err;
    DIRECT_BLOCKED = true;
    return fetchThroughProxy(url, options);
  });
}

/**
 * 同源代理只存在于本应用自己的站点（浏览器里）。
 * 油猴脚本运行在教务页面内，回退到同源 `/academic/` 会打到官方站点——宁可直接报错。
 */
function canUseSameOriginProxy() {
  return typeof unsafeWindow === 'undefined';
}

/**
 * 同源代理请求：自定义地址折回 `/academic/...` 并带上目标头；
 * 官方默认 / 桌面端保持原绝对地址（与改造前一致）。
 */
function fetchThroughProxy(url, { method, headers, body, referrer }) {
  const absolute = new URL(url, location.href);
  const proxied = !usesAcademicProxy();
  const target = proxied
    ? BASE_PREFIX + absolute.pathname.replace(/^\/academic\//i, '') + absolute.search
    : absolute.href;
  const requestHeaders = proxied ? { ...headers, [PROXY_BASE_HEADER]: ACADEMIC_BASE } : headers;
  return PAGE_FETCH(target, {
    method,
    headers: requestHeaders,
    body,
    credentials: 'include',
    cache: 'no-store',
    ...(referrer ? { referrer } : {}),
    redirect: 'follow'
  }).then(async (response) => {
    const result = await readResponse(response);
    // 静态部署没有 /academic 转发时，SPA 回退会把首页当成教务页面返回；
    // 直接解析会变成莫名其妙的"页面结构不符"，这里给出准确原因。
    if (proxied && looksLikeAppShell(result.contentType, result.buffer)) {
      throw new Error(
        '教务请求未到达目标站点：浏览器拦截了跨域直连，当前站点也没有 /academic 转发。' +
        '本地可用 npm run dev / npm run preview 启动（内置转发），或让部署端转发 /academic。'
      );
    }
    return result;
  }).catch((err) => {
    if (!proxied || /教务请求未到达目标站点/.test(String(err && err.message))) throw err;
    throw new Error(
      '网络连接失败：目标教务地址不可达，或浏览器与本地代理都未能取到响应',
      { cause: err }
    );
  });
}

/** 响应是否是本应用自己的 SPA 首页（说明 /academic 没有转发而是走了 history 回退） */
function looksLikeAppShell(contentType, buffer) {
  if (!/text\/html/i.test(String(contentType || ''))) return false;
  try {
    const head = new TextDecoder('utf-8').decode(buffer.slice(0, 4096)).toLowerCase();
    return head.includes('id="app"') && head.includes('better_hrbust');
  } catch {
    return false;
  }
}

function visibleLoginText(html) {
  return String(html || '').replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
}

// 登录失败特征
const LOGIN_FAILURE_MARKERS = [
  'badCredentials',
  '密码错误',
  '用户名不存在',
  '验证码错误',
  'Bad credentials',
  '用户不存在'
];

/**
 * 判断 HTML 是否为登录页
 */
export function isLoginPage(html) {
  if (!html) return false;
  const content = String(html).replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
  return /<form\b[^>]*\baction\s*=\s*["'][^"']*j_acegi_security_check/i.test(content)
    || /<input\b[^>]*\bname\s*=\s*["']j_captcha["']/i.test(content);
}

/**
 * 从登录失败页中提取错误原因
 */
export function parseLoginFailureReason(html) {
  // 普通表单标签和前端脚本含“密码/验证码”，不能据此判定服务端错误。
  const text = String(html || '')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ');
  if (/锁定|已冻结|已禁用|accountLocked/i.test(text)) return '账号已被系统锁定，请稍后再试';
  if (/用户不存在|学号不存在|用户名不存在/.test(text)) return '该学号不存在';
  if (/验证码\s*(?:输入|校验|验证)?\s*(错误|已?过期|失效|不正确|无效)|captcha\s*(invalid|incorrect|expired)/i.test(text)) return '验证码错误或已过期，请刷新重试';
  if (/密码\s*(?:输入)?\s*(错误|不正确|无效)|badCredentials|Bad credentials/i.test(text)) return '学号或密码错误';
  return '登录失败，请检查学号与密码';
}

/**
 * 智能响应解码
 * @param {ArrayBuffer} buffer
 * @param {string} [contentType]
 * @param {string} [preferredEncoding]
 * @returns {string}
 */
async function decodeResponse(buffer, contentType, preferredEncoding) {
  const type = String(contentType || '').toLowerCase();

  let charset = preferredEncoding || 'utf-8';

  // encoding 是端点的兜底编码；响应明确声明编码时优先遵从响应。
  const declared = type.match(/charset=["']?([a-z0-9_-]+)/i)?.[1];
  if (declared) charset = declared.toLowerCase();

  // 标准化编码名称
  if (charset.includes('gbk') || charset.includes('gb2312') || charset.includes('gb18030')) {
    charset = 'gbk';
  } else {
    charset = 'utf-8';
  }

  try {
    const decoder = new TextDecoder(charset, { fatal: true });
    return decoder.decode(buffer);
  } catch {
    // 降级使用 UTF-8
    const fallback = new TextDecoder(charset === 'utf-8' ? 'gbk' : 'utf-8');
    return fallback.decode(buffer);
  }
}

/**
 * 剥离已知教务 origin（官方域名 + 当前自定义地址）。
 *
 * 关键防线：教务系统仅支持 HTTP。若直接请求或跟随绝对域名，浏览器可能自动升级
 * HTTPS 导致 ERR_CONNECTION_CLOSED，因此带域名的绝对地址一律改写为 path。
 */
function stripKnownOrigin(path) {
  let clean = String(path);
  clean = clean.replace(/^https?:\/\/jwzx\.hrbust\.edu\.cn(?::\d+)?\/?/i, '');
  if (ACADEMIC_BASE) {
    const origin = ACADEMIC_BASE.replace(/\/academic\/?$/i, '');
    const escaped = origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    clean = clean.replace(new RegExp(`^${escaped}/?`, 'i'), '');
  }
  return clean;
}

/**
 * 格式化相对路径为完整请求路径。
 *
 * - 官方默认 / 桌面端：`/academic/...`（同源代理，Cookie 与 302 都收敛在本地）
 * - 自定义地址（油猴 / 纯浏览器）：`协议://主机/academic/...` 绝对地址
 */
function resolveUrl(path) {
  const clean = stripKnownOrigin(path || '')
    .replace(/^\/+/g, '')
    .replace(/^academic\//i, '');
  if (usesAcademicProxy()) return BASE_PREFIX + clean;
  return ACADEMIC_BASE + clean;
}

/**
 * 执行 HTTP 请求
 */
export async function request(path, options = {}) {
  const url = absoluteUrl(path);
  const {
    method = 'GET',
    headers = {},
    body = null,
    encoding, // 'gbk' 或 'utf-8'，未指定时按 header 判断
    checkAuth = true,
    referrer
  } = options;

  const requestHeaders = {
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
    'X-Requested-With': 'XMLHttpRequest',
    ...headers
  };

  let requestBody = null;
  if (body) {
    if (typeof body === 'object' && !(body instanceof FormData) && !(body instanceof URLSearchParams)) {
      const params = new URLSearchParams();
      Object.entries(body).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
          params.append(key, String(value));
        }
      });
      requestBody = params;
      requestHeaders['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
    } else {
      requestBody = body;
    }
  }

  let response;
  try {
    response = await executeRequest(url, { method, headers: requestHeaders, body: requestBody, referrer });
  } catch (err) {
    throw new Error(`网络连接失败：${err.message || '无法连接到教务系统，请确认是否处于校园网或VPN环境'}`, { cause: err });
  }

  const html = await decodeResponse(response.buffer, response.contentType, encoding);
  if (!response.ok) throw new Error(`教务请求失败（HTTP ${response.status}），请稍后重试`);

  // 会话过期判定
  if (checkAuth && isLoginPage(html)) {
    const error = new Error('教务会话已失效或未登录');
    error.isSessionExpired = true;
    error.status = 401;
    throw error;
  }

  return {
    ok: response.ok,
    status: response.status,
    url: response.finalUrl,
    contentType: response.contentType,
    encoding: encoding || 'utf-8',
    html
  };
}

/**
 * 获取验证码图片完整 URL
 */
export function getCaptchaUrl() {
  return `${resolveUrl('getCaptcha.do')}?_t=${Date.now()}`;
}

/** 验证码是否可以直接用 <img src> 加载（同源代理 / 桌面端本地反代） */
export function captchaUsesProxy() {
  return usesAcademicProxy();
}

/**
 * 经同一请求通道抓取验证码图片并转成 Blob URL。
 *
 * 自定义地址下 `<img src>` 属于跨站请求：SameSite 会话 Cookie 不会随图片请求发送，
 * 表现为"验证码永远错误"。改用业务请求通道（油猴 GM 通道会自动携带真实会话），
 * 与登录、查成绩走同一条连接。调用方负责在替换时 URL.revokeObjectURL。
 */
export async function loadCaptchaImage() {
  const url = absoluteUrl(`getCaptcha.do?_t=${Date.now()}`);
  const response = await executeRequest(url, {
    method: 'GET',
    headers: { 'X-Requested-With': 'XMLHttpRequest' }
  });
  if (!response.ok) throw new Error(`验证码加载失败（HTTP ${response.status}）`);
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return String(url);
  }
  const type = String(response.contentType || '').split(';')[0].trim() || 'image/jpeg';
  return URL.createObjectURL(new Blob([response.buffer], { type }));
}

/**
 * 校验验证码（教务系统自身的前置校验接口）
 */
export async function checkCaptcha(captchaCode) {
  try {
    const res = await request(`checkCaptcha.do?captchaCode=${encodeURIComponent(captchaCode)}`, {
      method: 'POST',
      checkAuth: false
    });
    const result = res.html.trim().toLowerCase();
    return result === 'true';
  } catch {
    return true; // 即使前置校验接口超时，亦允许尝试提交主登录表单
  }
}

/**
 * 提交登录认证
 */
export async function postLogin(username, password, captcha) {
  const form = new URLSearchParams();
  form.append('j_username', String(username).trim());
  form.append('j_password', String(password).trim());
  form.append('j_captcha', String(captcha).trim());

  let res;
  try {
    res = await executeRequest(absoluteUrl('j_acegi_security_check'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: form
    });
  } catch (err) {
    throw new Error(`网络连接超时或被阻断：${err.message}`, { cause: err });
  }

  const html = await decodeResponse(res.buffer, res.contentType, 'gbk');
  if (!res.ok) throw new Error(`教务系统登录请求失败（HTTP ${res.status}），请稍后重试`);

  // 如果依然是登录页或者包含失败标记
  if (isLoginPage(html) || LOGIN_FAILURE_MARKERS.some(m => visibleLoginText(html).includes(m))) {
    return {
      success: false,
      message: parseLoginFailureReason(html)
    };
  }

  return {
    success: true,
    message: '登录成功'
  };
}

/**
 * 登出
 */
export async function postLogout() {
  const response = await executeRequest(absoluteUrl('logout_security_check'), { method: 'GET' });
  if (!response.ok) throw new Error(`退出旧会话失败（HTTP ${response.status}），请重试`);
  const probe = await executeRequest(absoluteUrl('student/currcourse/currcourse.jsdo'));
  const html = await decodeResponse(probe.buffer, probe.contentType, 'gbk');
  if (!probe.ok || !isLoginPage(html)) throw new Error('旧账号会话尚未结束，请重试后再输入验证码');
}
