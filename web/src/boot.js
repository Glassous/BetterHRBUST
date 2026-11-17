import { installDesktopExternalLinks } from './services/externalLinks.js';
installDesktopExternalLinks();
import { createApp } from 'vue'
import App from './App.vue'
import './assets/main.css'
import overlayScroll from './directives/overlayScroll.js'
import { applyAcademicBase } from './services/academic/base.js'

// 教务地址必须在首屏请求前落地：桌面端的存储刚刚水合完成，
// 这里再读一次，保证本次运行的所有教务请求都指向正确站点。
applyAcademicBase()

// 桌面端（Tauri shim 注入 window.desktopWindow）环境标记：
// 挂到 <html> 上驱动自定义标题栏与 --tb 高度变量
if (typeof window !== 'undefined' && window.desktopWindow) {
  document.documentElement.classList.add('desktop-chrome')
}

createApp(App).directive('overlay-scroll', overlayScroll).mount('#app')
