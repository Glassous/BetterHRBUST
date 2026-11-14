<template>
  <div class="min-h-screen flex flex-col bg-[var(--bg-canvas)] text-[var(--ink-primary)] antialiased selection:bg-[var(--accent-soft)] selection:text-[var(--ink-primary)]">
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

    <!-- Main Content Flow -->
    <div class="flex-1 flex">
      <!-- Collapsible / Responsive Overlay Sidebar -->
      <AppSidebar
        :isCollapsed="isCollapsed"
        :mobileOpen="mobileOpen"
        :activeTab="activeTab"
        @update:activeTab="handleTabSelect($event)"
        @close="mobileOpen = false"
      />

      <div class="flex-1 flex flex-col min-w-0">
        <AppHeader
          :isCollapsed="isCollapsed"
          :activeTab="activeTab"
          @toggle-sidebar="handleToggleSidebar"
          @navigate="$emit('update:activeTab', $event)"
        />
        <main class="academic-main flex-1 min-w-0 px-5 sm:px-8 lg:px-10 pt-6 sm:pt-8 pb-14 max-w-[1600px] w-full mx-auto">
          <slot />
        </main>
      </div>
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
  refreshSchoolCalendar();
  calendarTimer = window.setInterval(refreshSchoolCalendar, 60000);
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  window.clearInterval(calendarTimer);
});
</script>