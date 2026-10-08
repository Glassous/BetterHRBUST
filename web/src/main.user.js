/**
 * 油猴(Tampermonkey)版专用入口
 *
 * 与 src/main.js 的区别:不依赖 index.html 提供的挂载环境,由脚本自行
 * 清理宿主 JSP 页面(含 frameset 框架页)后挂载应用。
 * 业务代码(client / api / parser / views)与 Web 版完全共用,零改动。
 */
import cssText from './assets/main.css?inline';
import { createApp } from 'vue';
import App from './App.vue';

// 与 index.html 的 <body class> 保持一致;写在 JS 里才能被 Tailwind 扫描到
const BODY_CLASSES = 'bg-[#f4f5f7] text-[#1e2329] dark:bg-[#14161a] dark:text-[#f0f2f5] min-h-screen transition-colors duration-200 antialiased selection:bg-zinc-200 selection:text-zinc-900 dark:selection:bg-zinc-800 dark:selection:text-zinc-100';

// 宿主页面已执行脚本注册的轮询定时器会继续操作被清空的 DOM,统一清掉
function clearHostTimers() {
  try {
    const highestId = setTimeout(() => {}, 0);
    for (let i = 0; i <= highestId; i += 1) {
      clearInterval(i);
      clearTimeout(i);
    }
  } catch { /* 忽略 */ }
}

function ensureViewport() {
  let meta = document.querySelector('meta[name="viewport"]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'viewport';
    document.head.appendChild(meta);
  }
  meta.content = 'width=device-width, initial-scale=1.0';
}

function bootstrap() {
  const doc = document;

  clearHostTimers();

  doc.head.querySelectorAll('style, link[rel="stylesheet"], script').forEach((el) => el.remove());

  // URP 框架页(main.jsp / frameset_index.jsp)的 body 是 <frameset>,无法承载内容,整体换成 <body>
  if (!doc.body || doc.body.tagName === 'FRAMESET') {
    doc.documentElement.querySelectorAll('frameset, frame').forEach((el) => el.remove());
    const stale = doc.body;
    const body = doc.createElement('body');
    if (stale) stale.remove();
    doc.documentElement.appendChild(body);
  }

  doc.documentElement.setAttribute('lang', 'zh-CN');
  doc.body.className = BODY_CLASSES;
  doc.body.innerHTML = '<div id="app"></div>';

  const style = doc.createElement('style');
  style.textContent = cssText;
  doc.head.appendChild(style);

  ensureViewport();
  doc.title = 'BetterHRBUST · 现代教务在线';

  createApp(App).mount('#app');
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrap, { once: true });
} else {
  bootstrap();
}
