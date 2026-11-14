import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import { readFileSync } from 'node:fs';
import path from 'node:path';

// 构建期注入应用版本:与 Web 构建保持同一份 define,
// 避免共用模块(composables/useUpdate.js)在油猴产物中引用未定义全局而抛 ReferenceError
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));

// 在 Vite IIFE 顶部（组件 <style> 注入之前）记录宿主页面原有的 <style> 节点，
// 供 main.user.js 区分「宿主样式」与「应用自身通过 Vite 注入的组件样式」。
const HOST_STYLE_SNAPSHOT_KEY = '__betterHrbustHostStyles';

// 油猴脚本构建:以 src/main.user.js 为入口,IIFE 单文件输出。
// 样式经 main.css?inline 内联为字符串;HRBUST.png 等 Asset 全部内联为 data URI,
// 保证产物是零外部依赖的单文件,可直接安装进 Tampermonkey。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
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
        entryFileNames: 'app.bundle.js',
        banner: `globalThis.${HOST_STYLE_SNAPSHOT_KEY} = typeof document !== 'undefined' && document.head ? new Set(document.head.querySelectorAll('style')) : new Set();`
      }
    }
  }
});
