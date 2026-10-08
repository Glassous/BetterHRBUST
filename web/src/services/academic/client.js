/**
 * 教务在线 HTTP 客户端
 * 负责处理 GBK / UTF-8 编码自适应解码、会话状态识别与错误捕获
 */

const BASE_PREFIX = '/academic/';

// 请求传输层(按优先级):
// 1. GM_xmlhttpRequest(仅油猴环境存在):走扩展后台网络栈发起,完全不受浏览器
//    对"安全上下文"发起请求的 HTTPS 自动升级策略影响(教务系统 443 无 TLS,
//    任何 https 尝试都会 ERR_CONNECTION_CLOSED),也不受页面 window.fetch
//    被其他脚本包装改写的影响;目标域 Cookie 由油猴按 @connect 域自动携带。
// 2. 页面 fetch(Web 版 / 本地冒烟):Web 版请求是同源相对路径(dev 经反代),
//    不存在升级问题;油猴环境的兜底也用页面上下文(unsafeWindow)的 fetch,
//    发起方为 http 页面本身,不参与升级。
const PAGE_FETCH =
  typeof unsafeWindow !== 'undefined' && unsafeWindow && typeof unsafeWindow.fetch === 'function'
    ? unsafeWindow.fetch.bind(unsafeWindow)
    : fetch.bind(globalThis);

/**
 * 相对路径转绝对 URL(GM_xmlhttpRequest 要求绝对地址)
 */
function absoluteUrl(path) {
  return new URL(resolveUrl(path), location.href).href;
}

/**
 * 统一请求执行:返回 { ok, status, contentType, buffer, finalUrl }
 */
function executeRequest(url, { method = 'GET', headers = {}, body = null } = {}) {
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
  return PAGE_FETCH(url, {
    method,
    headers,
    body,
    credentials: 'include', // 必传,携带与接收 Cookie
    redirect: 'follow'
  }).then(async (response) => ({
    ok: response.ok,
    status: response.status,
    contentType: response.headers.get('content-type') || '',
    buffer: await response.arrayBuffer(),
    finalUrl: response.url
  }));
}

// 登录页标记特征
const LOGIN_PAGE_MARKERS = ['j_acegi_security_check', 'getCaptcha.do', 'j_captcha'];

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
  return LOGIN_PAGE_MARKERS.some(marker => html.includes(marker));
}

/**
 * 从登录失败页中提取错误原因
 */
export function parseLoginFailureReason(html) {
  const text = String(html || '');
  if (text.includes('验证码')) return '验证码错误或已过期，请刷新重试';
  if (text.includes('密码') || text.includes('badCredentials') || text.includes('Bad credentials')) {
    return '学号或密码错误';
  }
  if (text.includes('用户名') || text.includes('用户不存在')) return '该学号不存在';
  if (text.includes('锁定')) return '账号已被系统锁定，请稍后再试';
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

  if (!preferredEncoding) {
    if (type.includes('charset=')) {
      const match = type.match(/charset=([a-z0-9_-]+)/i);
      if (match && match[1]) {
        charset = match[1].toLowerCase();
      }
    }
  }

  // 标准化编码名称
  if (charset.includes('gbk') || charset.includes('gb2312') || charset.includes('gb18030')) {
    charset = 'gbk';
  } else {
    charset = 'utf-8';
  }

  try {
    const decoder = new TextDecoder(charset);
    return decoder.decode(buffer);
  } catch {
    // 降级使用 UTF-8
    const fallback = new TextDecoder('utf-8');
    return fallback.decode(buffer);
  }
}

/**
 * 格式化相对路径为完整请求路径
 */
function resolveUrl(path) {
  if (!path) return BASE_PREFIX;
  // 关键防线：教务系统仅支持 HTTP (不支持 HTTPS)。若直接请求或跟随绝对域名，浏览器可能自动升级 HTTPS 导致 ERR_CONNECTION_CLOSED
  // 因此无论传入何种格式（包括含域名的绝对 URL），一律剥离域名，强制通过本地 /academic/ 代理
  let clean = String(path).replace(/^https?:\/\/jwzx\.hrbust\.edu\.cn(?::\d+)?\/?/i, '');
  clean = clean.replace(/^\/+/g, '');
  if (clean.startsWith('academic/')) {
    return '/' + clean;
  }
  return BASE_PREFIX + clean;
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
    checkAuth = true
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
    response = await executeRequest(url, { method, headers: requestHeaders, body: requestBody });
  } catch (err) {
    throw new Error(`网络连接失败：${err.message || '无法连接到教务系统，请确认是否处于校园网或VPN环境'}`);
  }

  const html = await decodeResponse(response.buffer, response.contentType, encoding);

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
    html
  };
}

/**
 * 获取验证码图片完整 URL
 */
export function getCaptchaUrl() {
  return `${BASE_PREFIX}getCaptcha.do?_t=${Date.now()}`;
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
    throw new Error(`网络连接超时或被阻断：${err.message}`);
  }

  const html = await decodeResponse(res.buffer, res.contentType, 'gbk');

  // 如果依然是登录页或者包含失败标记
  if (isLoginPage(html) || LOGIN_FAILURE_MARKERS.some(m => html.includes(m))) {
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
  try {
    await executeRequest(absoluteUrl('j_acegi_logout'), { method: 'GET' });
  } catch {
    // 静默忽略
  }
}
