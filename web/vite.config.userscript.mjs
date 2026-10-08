import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

// 油猴脚本构建:以 src/main.user.js 为入口,IIFE 单文件输出。
// 样式经 main.css?inline 内联为字符串;HRBUST.png 等 Asset 全部内联为 data URI,
// 保证产物是零外部依赖的单文件,可直接安装进 Tampermonkey。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(process.cwd(), './src')
    }
  },
  build: {
    outDir: 'dist-userscript',
    publicDir: false,
    assetsInlineLimit: 100 * 1024 * 1024,
    rollupOptions: {
      input: path.resolve(process.cwd(), 'src/main.user.js'),
      output: {
        format: 'iife',
        entryFileNames: 'app.bundle.js'
      }
    }
  }
});
