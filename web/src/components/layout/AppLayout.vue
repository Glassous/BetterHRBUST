<template>
  <!-- 布局锁高：整页不滚动，滚动交给主区域自己承担。
       否则主区域内容变高/变宽时会把整页撑开——AI 侧栏高度随之溢出，
       横向滚动条也会占掉视口高度、把画面整体抬高。 -->
  <div
    class="h-screen max-h-screen flex flex-col overflow-hidden bg-[var(--bg-canvas)] text-[var(--ink-primary)] antialiased selection:bg-[var(--accent-soft)] selection:text-[var(--ink-primary)]"
  >
    <a href="#main-content" class="visually-hidden skip-link">跳到主内容</a>
    <!-- 桌面端自定义标题栏（浏览器环境不渲染，占位高度由 --tb 驱动） -->
    <DesktopTitleBar />

    <!-- Responsive Backdrop: Click outside sidebar to close -->
    <Transition
      enter-active-class="transition-opacity duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="mobileOpen"
        class="fixed inset-0 bg-[var(--bg-backdrop)] backdrop-blur-[2px] z-40 lg:hidden"
        @click="mobileOpen = false"
      />
    </Transition>

    <!-- Main Content Flow：min-h-0 让这一行可以被压缩到视口高度内，
         overflow-hidden 裁掉 AI 面板入场位移，避免动画期间出现横向滚动条 -->
    <div class="flex-1 flex min-h-0 overflow-hidden">
      <!-- Collapsible / Responsive Overlay Sidebar -->
      <AppSidebar
        :isCollapsed="isCollapsed"
        :mobileOpen="mobileOpen"
        :activeTab="activeTab"
        @update:activeTab="handleTabSelect($event)"
        @close="mobileOpen = false"
        @collapse="isCollapsed = true"
      />

      <div class="flex-1 flex flex-col min-w-0 min-h-0">
        <AppHeader
          :isCollapsed="isCollapsed"
          :activeTab="activeTab"
          @toggle-sidebar="handleToggleSidebar"
          @navigate="$emit('update:activeTab', $event)"
        />
        <!-- 主区域自己滚动：宽内容（课表 / 成绩表）已在视图内自带 overflow-x-auto，
             这里再兜一层，保证任何情况下都不会把整页宽度撑破 -->
        <main
          id="main-content"
          tabindex="-1"
          v-overlay-scroll
          class="academic-main flex-1 min-w-0 min-h-0 overflow-y-auto overflow-x-hidden px-5 sm:px-8 lg:px-10 pt-1 sm:pt-2 pb-16 max-w-[1600px] w-full mx-auto outline-none"
        >
          <slot />
        </main>
      </div>

      <!-- AI 面板：与主区域同级，打开时压缩主区域（推挤而非覆盖），
           不随页面切换 / 窗口尺寸变化关闭 -->
      <AiChatDrawer />
    </div>

    <!-- Global Toast Container -->
    <UiToast />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useSession } from '@/composables/useSession.js';
const { refreshSchoolCalendar } = useSession();
let calendarTimer;
import AppSidebar from './AppSidebar.vue';
import AppHeader from './AppHeader.vue';
import DesktopTitleBar from './DesktopTitleBar.vue';
import UiToast from '@/components/ui/UiToast.vue';
import AiChatDrawer from '@/components/ai/AiChatDrawer.vue';
import { useAiChat } from '@/composables/useAiChat.js';

const { toggle: toggleAiDrawer } = useAiChat();

/** Ctrl/Cmd + J 打开对话（与 TUI 的 ctrl+j 一致） */
function onGlobalKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'j') {
    event.preventDefault();
    toggleAiDrawer();
  }
}

defineProps({
  activeTab: {
    type: String,
    default: 'dashboard'
  }
});

const emit = defineEmits(['update:activeTab']);

const isCollapsed = ref(false);
const mobileOpen = ref(false);

function handleToggleSidebar() {
  if (typeof window !== 'undefined' && window.innerWidth < 1024) {
    mobileOpen.value = !mobileOpen.value;
  } else {
    isCollapsed.value = !isCollapsed.value;
  }
}

function handleTabSelect(tabId) {
  emit('update:activeTab', tabId);
  mobileOpen.value = false;
}

function handleResize() {
  if (typeof window !== 'undefined' && window.innerWidth >= 1024 && mobileOpen.value) {
    mobileOpen.value = false;
  }
}

onMounted(() => {
  window.addEventListener('resize', handleResize);
  window.addEventListener('keydown', onGlobalKeydown);
  refreshSchoolCalendar();
  calendarTimer = window.setInterval(refreshSchoolCalendar, 60000);
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  window.removeEventListener('keydown', onGlobalKeydown);
  window.clearInterval(calendarTimer);
});
</script>
