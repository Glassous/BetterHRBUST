<template>
  <!-- 仅在桌面端（Tauri shim 注入 window.desktopWindow）渲染；浏览器环境不出现 -->
  <div
    v-if="available"
    class="tb-drag sticky top-0 z-[60] h-10 flex items-stretch justify-end pl-3 select-none bg-[#f6f7f9]/95 dark:bg-[#14161a]/95 backdrop-blur-md"
    @mousedown="onBarMouseDown"
  >
    <!-- 左侧留空：仅作为可拖拽区域（双击可切换最大化） -->
    <div class="flex-1 h-full"></div>

    <!--
      右侧窗口控制按钮：方形、彼此紧贴、与窗口右上角齐平。
      不留右侧内边距、不加圆角、不设间距 —— 保证最外一列像素以及按钮之间的接缝
      都落在按钮自身的可点击范围里（圆形按钮的四角与四周留白原本是死区）。
    -->
    <div class="tb-nodrag flex items-stretch">
      <button type="button" title="最小化" aria-label="最小化" :class="btnClass" @click="win.minimize()">
        <svg
          class="w-5 h-5"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
        >
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
      </button>
      <button
        type="button"
        :title="isMaximized ? '向下还原' : '最大化'"
        :aria-label="isMaximized ? '向下还原' : '最大化'"
        :class="btnClass"
        @click="win.toggleMaximize()"
      >
        <svg
          v-if="!isMaximized"
          class="w-5 h-5"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <rect x="6" y="6" width="12" height="12" rx="2.5" />
        </svg>
        <svg
          v-else
          class="w-5 h-5"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M9.5 5.5H16A2.5 2.5 0 0 1 18.5 8v6.5" />
          <rect x="5.5" y="9.5" width="9" height="9" rx="2.5" />
        </svg>
      </button>
      <button
        type="button"
        title="关闭"
        aria-label="关闭"
        :class="closeClass"
        @click="win.close()"
      >
        <svg
          class="w-5 h-5"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
        >
          <line x1="6" y1="6" x2="18" y2="18" />
          <line x1="18" y1="6" x2="6" y2="18" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';

const win = typeof window !== 'undefined' ? window.desktopWindow : null;
const available = !!win;
// Tauri 桌面端：WebView2 不认 -webkit-app-region，拖拽/双击最大化改由 mousedown 手动处理
const isTauri = typeof window !== 'undefined' && !!window.__TAURI__;
const isMaximized = ref(false);
let unsubscribe = null;

function onBarMouseDown(e) {
  if (!isTauri || !win) return; // 无窗口控制 API（非桌面端）时不处理拖拽
  if (e.buttons !== 1) return; // 仅响应左键
  if (e.target.closest('.tb-nodrag')) return; // 窗口控制按钮区域不参与拖拽
  if (e.detail === 2) win.toggleMaximize();
  else win.startDrag();
}

// 方形窗口控制键：与标题栏等高（40×40），无圆角、无间距，紧贴窗口右上角。
// 悬停/按下只改底色 —— 不用 scale 缩放：收缩动画会让紧贴的边缘露出缝隙，
// 也会让指针在按下瞬间落到按钮之外。
const btnBase =
  'shrink-0 w-10 h-full flex items-center justify-center cursor-pointer select-none transition-colors duration-150 text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-zinc-400';
const btnClass = `${btnBase} hover:bg-zinc-900/6 active:bg-zinc-900/10 dark:hover:bg-[#23262d] dark:active:bg-[#2a2e36]`;
const closeClass = `${btnBase} hover:bg-red-500/10 hover:text-red-600 active:bg-red-500/15 dark:hover:bg-red-500/15 dark:hover:text-red-400`;

onMounted(async () => {
  if (!win) return;
  try {
    isMaximized.value = await win.isMaximized();
  } catch {
    // 初始化失败保持默认非最大化
  }
  unsubscribe = win.onMaximizeChange((value) => {
    isMaximized.value = value;
  });
});

onUnmounted(() => {
  if (unsubscribe) unsubscribe();
});
</script>

<style scoped>
/* 桌面端窗口拖拽样式：整条标题栏可拖动窗口（双击切换最大化），按钮区域排除 */
.tb-drag {
  -webkit-app-region: drag;
}
.tb-nodrag,
.tb-drag button {
  -webkit-app-region: no-drag;
}
</style>
