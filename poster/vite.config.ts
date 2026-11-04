import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 海报生成器为纯本地单页：不请求任何外部接口，也不参与云端构建，
// 仅注册 vue() + tailwindcss()，与 web/ 端保持同一套技术栈。
export default defineConfig({
  // 相对路径产物，方便直接把 dist/ 丢到任意目录双击 index.html 打开
  base: './',
  plugins: [vue(), tailwindcss()],
  // 与 landing(5174) / web(5173) 错开端口，可同时启动
  server: {
    port: 5175,
    host: '0.0.0.0'
  },
  preview: {
    port: 5175,
    host: '0.0.0.0'
  }
})
