import { installDesktopExternalLinks } from './services/externalLinks.js';
installDesktopExternalLinks();
import { createApp } from 'vue'
import App from './App.vue'
import './assets/main.css'
import overlayScroll from './directives/overlayScroll.js'

// 桌面端（Tauri shim 注入 window.desktopWindow）环境标记：
// 挂到 <html> 上驱动自定义标题栏与 --tb 高度变量
if (typeof window !== 'undefined' && window.desktopWindow) {
  document.documentElement.classList.add('desktop-chrome')
}

createApp(App).directive('overlay-scroll', overlayScroll).mount('#app')
