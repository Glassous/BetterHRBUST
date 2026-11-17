/**
 * 自定义教务地址（base URL）回归：
 *  1. 归一化契约（容错粘贴主页链接、裸域名、非法输入）；
 *  2. 未配置时请求路径与改造前逐字节一致（相对 /academic/）；
 *  3. 自定义地址下走绝对地址 + 油猴通道；桌面端仍走相对路径（Rust 反代决定上游）；
 *  4. 存储层保存/恢复默认。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/services/academic/client.js', import.meta.url), 'utf8')
  .replace(/^export /gm, '');

const OFFICIAL_PAGE = 'http://jwzx.hrbust.edu.cn/academic/index.jsdo';

function makeContext(overrides = {}) {
  const calls = [];
  const context = vm.createContext({
    location: new URL(OFFICIAL_PAGE),
    URL, URLSearchParams, FormData, TextDecoder, ArrayBuffer, Date, Blob,
    AbortSignal, setTimeout, clearTimeout,
    fetch: async (url) => {
      calls.push({ url });
      return new Response('<html>ok</html>', { status: 200, headers: { 'content-type': 'text/html' } });
    },
    ...overrides
  });
  vm.runInContext(source, context);
  return { context, calls };
}

// ---- 归一化 ----
const { context: base } = makeContext();
assert.equal(base.normalizeAcademicBase(''), '', '空值表示官方默认');
assert.equal(base.normalizeAcademicBase('   '), '');
assert.equal(
  base.normalizeAcademicBase('https://jwzx.fiacloud.top/homepage/index.do'),
  'https://jwzx.fiacloud.top/academic/',
  '粘贴主页链接只保留 origin'
);
assert.equal(base.normalizeAcademicBase('jwzx.fiacloud.top'), 'https://jwzx.fiacloud.top/academic/', '裸域名默认 HTTPS');
assert.equal(base.normalizeAcademicBase('jwzx.hrbust.edu.cn'), 'http://jwzx.hrbust.edu.cn/academic/', '官方域名默认 HTTP');
assert.equal(base.normalizeAcademicBase('http://10.20.30.40:8080/academic/x.jsdo'), 'http://10.20.30.40:8080/academic/');
assert.throws(() => base.normalizeAcademicBase('ftp://jwzx.example.edu.cn'), /http/, '非 http(s) 协议拒绝');
assert.throws(() => base.normalizeAcademicBase('http://user:pass@jwzx.example.edu.cn'), /用户名或密码/);
assert.equal(base.setAcademicBase('ftp://bad'), '', '非法地址回退官方默认');

// ---- 默认：相对路径，行为不变 ----
{
  const { context, calls } = makeContext();
  assert.equal(context.getAcademicBase(), '');
  assert.equal(context.usesAcademicProxy(), true);
  await context.request('student/currcourse/currcourse.jsdo');
  assert.equal(calls[0].url, 'http://jwzx.hrbust.edu.cn/academic/student/currcourse/currcourse.jsdo');
  assert.equal(context.getCaptchaUrl().startsWith('/academic/getCaptcha.do?_t='), true);
  assert.equal(context.captchaUsesProxy(), true);
}

// ---- 自定义地址 + 油猴通道：绝对地址，不经过页面 fetch ----
{
  const gmCalls = [];
  const { context, calls } = makeContext({
    GM_xmlhttpRequest: (details) => {
      gmCalls.push(details);
      details.onload({
        status: 200,
        responseHeaders: 'content-type: text/html',
        response: new TextEncoder().encode('<html>ok</html>').buffer,
        finalUrl: details.url
      });
    }
  });
  context.setAcademicBase('https://jwzx.fiacloud.top');
  assert.equal(context.usesAcademicProxy(), false);
  assert.equal(context.captchaUsesProxy(), false);
  await context.request('manager/score/studentOwnScore.do');
  assert.equal(gmCalls[0].url, 'https://jwzx.fiacloud.top/academic/manager/score/studentOwnScore.do');
  assert.equal(calls.length, 0, '自定义地址不得再落到页面同源 fetch');

  // 教务返回的绝对地址（官方域名或当前域名）都要被剥离后重新挂到当前 base
  await context.request('https://jwzx.hrbust.edu.cn/academic/student/x.jsdo');
  assert.equal(gmCalls[1].url, 'https://jwzx.fiacloud.top/academic/student/x.jsdo');
  await context.request('https://jwzx.fiacloud.top/academic/student/y.jsdo');
  assert.equal(gmCalls[2].url, 'https://jwzx.fiacloud.top/academic/student/y.jsdo');
  assert.equal(context.getCaptchaUrl().startsWith('https://jwzx.fiacloud.top/academic/getCaptcha.do?_t='), true);
}

// ---- 网页版：目标允许跨域时直连 ----
{
  const calls = [];
  const { context } = makeContext({
    fetch: async (url, options = {}) => {
      calls.push({ url: String(url), headers: options.headers || {} });
      return new Response('<html>ok</html>', { status: 200, headers: { 'content-type': 'text/html' } });
    }
  });
  context.setAcademicBase('https://jwzx.fiacloud.top');
  assert.equal(context.usesAcademicProxy(), false);
  await context.request('student/x.jsdo');
  assert.equal(calls[0].url, 'https://jwzx.fiacloud.top/academic/student/x.jsdo', '网页版优先直连自定义地址');
  assert.equal(calls[0].headers['x-betterhrbust-academic-base'], undefined, '直连不附带代理提示头');
}

// ---- 网页版：直连被浏览器拦下 → 自动改走同源代理，后续不再重复白试 ----
{
  const calls = [];
  const { context } = makeContext({
    fetch: async (url, options = {}) => {
      const target = String(url);
      calls.push({ url: target, headers: options.headers || {} });
      if (target.startsWith('https://jwzx.fiacloud.top')) throw new TypeError('Failed to fetch');
      return new Response('<html>ok</html>', { status: 200, headers: { 'content-type': 'text/html' } });
    }
  });
  context.setAcademicBase('https://jwzx.fiacloud.top');
  await context.request('student/x.jsdo');
  assert.equal(calls.length, 2, '先直连，失败后立刻改走代理');
  assert.equal(calls[0].url, 'https://jwzx.fiacloud.top/academic/student/x.jsdo');
  assert.equal(calls[1].url, '/academic/student/x.jsdo', '回退到同源 /academic/');
  assert.equal(
    calls[1].headers['x-betterhrbust-academic-base'],
    'https://jwzx.fiacloud.top/academic/',
    '代理提示头带上目标地址'
  );
  assert.equal(context.academicDirectBlocked(), true);

  await context.request('manager/score/studentOwnScore.do');
  assert.equal(calls.length, 3, '后续请求不再重复直连');
  assert.equal(calls[2].url, '/academic/manager/score/studentOwnScore.do');
  assert.equal(calls[2].headers['x-betterhrbust-academic-base'], 'https://jwzx.fiacloud.top/academic/');

  // 换地址后重新给直连一次机会
  context.setAcademicBase('https://jwzx.hrbust.edu.cn');
  assert.equal(context.academicDirectBlocked(), false);
}

// ---- 网页版：静态部署缺少 /academic 转发时，不能把 SPA 首页当成教务页面 ----
{
  const shell = '<!DOCTYPE html><html><head><title>BetterHRBUST</title>' +
    '<script>localStorage.getItem("better_hrbust_theme")</script></head><body><div id="app"></div></body></html>';
  const { context } = makeContext({
    fetch: async (url) => {
      if (String(url).startsWith('https://jwzx.fiacloud.top')) throw new TypeError('Failed to fetch');
      return new Response(shell, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
    }
  });
  context.setAcademicBase('https://jwzx.fiacloud.top');
  await assert.rejects(
    () => context.request('student/x.jsdo'),
    /未到达目标站点/,
    'SPA 回退页必须报出准确原因'
  );
}

// ---- 桌面端：即使配置了自定义地址也必须走相对路径（CSP + Cookie 归属） ----
{
  const { context, calls } = makeContext({ window: { desktopWindow: {} } });
  context.setAcademicBase('https://jwzx.fiacloud.top');
  assert.equal(context.usesAcademicProxy(), true);
  await context.request('student/x.jsdo');
  assert.equal(calls[0].url, 'http://jwzx.hrbust.edu.cn/academic/student/x.jsdo');
  assert.equal(context.captchaUsesProxy(), true, '桌面端验证码继续用 <img> 直连本地反代');
}

// ---- 存储层：保存 / 恢复默认 ----
const store = new Map();
globalThis.localStorage = {
  getItem: (key) => (store.has(key) ? store.get(key) : null),
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
  key: (index) => [...store.keys()][index] ?? null,
  get length() { return store.size; }
};

const {
  ACADEMIC_BASE_KEY,
  OFFICIAL_ACADEMIC_BASE,
  academicBaseState,
  saveAcademicBase,
  resetAcademicBase,
  subscribeAcademicBase
} = await import('../src/services/academic/base.js');

assert.equal(academicBaseState().display, OFFICIAL_ACADEMIC_BASE);
const seen = [];
const unsubscribe = subscribeAcademicBase((state) => seen.push(state.base));
assert.deepEqual(seen, [''], '订阅时立即回调当前值');

const saved = saveAcademicBase('jwzx.fiacloud.top');
assert.equal(saved.base, 'https://jwzx.fiacloud.top/academic/');
assert.equal(store.get(ACADEMIC_BASE_KEY), 'https://jwzx.fiacloud.top/academic/', '落盘的是归一化后的值');

const cleared = resetAcademicBase();
assert.equal(cleared.base, '');
assert.equal(store.has(ACADEMIC_BASE_KEY), false, '恢复默认等价于删除键');
assert.throws(() => saveAcademicBase('not a url with spaces'), /格式不正确/);
assert.equal(store.has(ACADEMIC_BASE_KEY), false, '非法输入不落盘');

unsubscribe();
assert.deepEqual(seen, ['', 'https://jwzx.fiacloud.top/academic/', '']);

console.log('PASS: 教务地址归一化、默认零变化、油猴/桌面分流、保存与恢复默认');
