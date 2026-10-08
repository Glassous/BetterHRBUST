/**
 * 本地冒烟测试服务器:模拟教务系统的最小行为,在不连真实教务系统的情况下
 * 验证油猴脚本的「接管旧页面」能力。
 *
 * 用法:node scripts/smoke-server.mjs   (默认 http://127.0.0.1:8137)
 *
 *  - /test/page.html          仿真旧版 JSP 登录页(普通 body 页面)
 *  - /test/frameset.html      仿真 URP frameset 框架页(接管路径的边缘场景)
 *  - /better-hrbust.user.js   吐出构建产物,以 <script src> 方式模拟油猴注入
 *                              (包装层对 GM_* API 均有 typeof 守卫,纯浏览器环境可运行)
 *  - /academic/*              返回带登录页特征标记的 HTML(即「会话未建立」),
 *                              使应用走 checkAuth 失败 → 展示登录视图的分支
 *  - /academic/getCaptcha.do  返回 1x1 JPEG(验证码图片加载路径)
 *
 * 注意:桩响应只含 ASCII 文本,GBK 解码后内容不变,无需真正做 GBK 编码。
 */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const webRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const PORT = Number(process.argv[2] || 8137);

// ffmpeg 生成的合法 96x25 JPEG(手写 base64 容易内部损坏导致 onerror)
const TINY_JPEG = readFileSync(path.join(webRoot, 'scripts', 'captcha-stub.jpg'));

// 带「登录页特征」的桩页面:client.js 依此判定会话未建立
const STUB_LOGIN_PAGE = `<!DOCTYPE html>
<html><head><meta charset="GBK"><title>Login</title></head>
<body>
<h1>URP LOGIN PAGE (stub)</h1>
<form action="j_acegi_security_check" method="post">
  <input name="j_username"><input name="j_password" type="password">
  <input name="j_captcha"><img id="jcaptcha" src="/academic/getCaptcha.do">
  <button type="submit">login</button>
</form>
</body></html>`;

const TEST_PAGE = `<!DOCTYPE html>
<html><head><meta charset="GBK"><title>academic online</title>
<style>body{font-family:serif;background:#d8d8d8}td{border:1px solid gray}</style>
</head>
<body bgcolor="#e8e8e8">
<h2>=== OLD URP JSP PAGE (stub) ===</h2>
<table><tr><td>menu</td><td>content area</td></tr></table>
<script>setInterval(function(){ document.title = 'poll-' + Math.random(); }, 1000)</script>
<script src="/better-hrbust.user.js"></script>
</body></html>`;

// 注意:HTML 解析器在 frameset 内会丢弃 <script>(预加载扫描器仍会抓取,
// 造成"已加载"假象)。真实油猴是程序化注入,不存在此限制;桩页面改用
// DOMContentLoaded 后动态注入 script,等价于油猴 @run-at document-end。
const FRAMESET_PAGE = `<!DOCTYPE html>
<html><head><meta charset="GBK"><title>frameset</title>
<script>
  document.addEventListener('DOMContentLoaded', function () {
    var s = document.createElement('script');
    s.src = '/better-hrbust.user.js';
    document.head.appendChild(s);
  });
</script>
</head>
<frameset cols="200,*">
  <frame src="/academic/common/security/login.jsp">
  <frame src="/academic/checkPassword.do">
</frameset>
</html>`;

const server = createServer((req, res) => {
  const url = req.url.split('?')[0];
  console.log(`[stub] ${req.method} ${req.url}`);

  if (url === '/better-hrbust.user.js') {
    res.writeHead(200, { 'content-type': 'application/javascript; charset=utf-8' });
    res.end(readFileSync(path.join(webRoot, 'dist-userscript', 'better-hrbust.user.js')));
    return;
  }
  if (url === '/test/page.html' || url === '/') {
    res.writeHead(200, { 'content-type': 'text/html; charset=GBK' });
    res.end(Buffer.from(TEST_PAGE, 'latin1'));
    return;
  }
  if (url === '/test/frameset.html') {
    res.writeHead(200, { 'content-type': 'text/html; charset=GBK' });
    res.end(Buffer.from(FRAMESET_PAGE, 'latin1'));
    return;
  }
  if (url.startsWith('/academic/getCaptcha.do')) {
    res.writeHead(200, { 'content-type': 'image/jpeg' });
    res.end(TINY_JPEG);
    return;
  }
  if (url.startsWith('/academic/')) {
    // 其余 /academic/* 一律回登录页特征(模拟未登录会话)
    res.writeHead(200, { 'content-type': 'text/html; charset=GBK' });
    res.end(Buffer.from(STUB_LOGIN_PAGE, 'latin1'));
    return;
  }
  res.writeHead(404, { 'content-type': 'text/plain' });
  res.end('not found');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`smoke server: http://127.0.0.1:${PORT}/test/page.html`);
});
