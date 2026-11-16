import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// 介绍页为纯静态单页：不请求教务系统，无需任何代理，
// 仅注册 vue() + tailwindcss() 两个插件（与 web/ 端同一套技术栈）。
export default defineConfig({
  base: './',
  plugins: [vue(), tailwindcss()],
  resolve: {
    // landing 直接引用了 web/src,源码文件按自身路径向上找依赖,而 CI 只安装
    // landing 的 node_modules;gsap 必须 dedupe 到本项目根,否则构建报
    // "Rollup failed to resolve import gsap"(web/node_modules 在 CI 中不存在),
    // 同时保证与 landing 自身的 GSAP 共用同一实例。
    dedupe: ['vue', 'gsap'],
    alias: {
      '@': path.resolve(process.cwd(), '../web/src')
    }
  },
  // 与 web/ 端开发端口（5173）错开，便于两个项目同时启动。
  // 不设 allowedHosts:true：Vite 5.4.12+ 内置 DNS rebinding 的 Host 校验，
  // 显式关闭会让恶意页面可经重绑定访问本地 dev/preview 服务；
  // 局域网调试用 IP 访问（默认放行），自定义域名按需临时加白。
  server: {
    port: 5174,
    host: '0.0.0.0'
  },
  preview: {
    port: 5174,
    host: '0.0.0.0'
  }
})
