<template>
  <aside
    :class="[
      'h-[calc(100vh_-_var(--tb))] flex flex-col bg-[var(--md-surface-container-low)] select-none overflow-hidden',
      // Desktop layout（sticky 顶部让出标题栏高度）
      'lg:sticky lg:top-[var(--tb)] lg:shrink-0 lg:z-30 lg:rounded-r-[var(--shape-xxl)]',
      'lg:transition-[width,background-color] lg:duration-300 lg:ease-[cubic-bezier(0.2,0,0,1)]',
      isCollapsedState ? 'lg:w-[76px]' : 'lg:w-[272px]',
      // Mobile / Responsive overlay layout
      'fixed top-[var(--tb)] bottom-0 left-0 z-50 w-[272px] rounded-r-[var(--shape-xxl)] transition-transform duration-300 ease-out',
      mobileOpen ? 'translate-x-0 shadow-[var(--shadow-float)] lg:shadow-none' : '-translate-x-full lg:translate-x-0'
    ]"
  >
    <!-- Brand / Header -->
    <div class="h-[72px] flex items-center px-4 overflow-hidden shrink-0">
      <div class="flex items-center min-w-0">
        <div class="w-11 h-11 flex items-center justify-center shrink-0">
          <div class="w-10 h-10 rounded-full overflow-hidden shrink-0 flex items-center justify-center bg-[var(--md-surface-container-high)]">
            <img
              :src="logoUrl"
              alt="HRBUST"
              class="w-full h-full object-cover shrink-0"
            />
          </div>
        </div>
        <div
          :class="[
            'ml-2.5 min-w-0 flex-1 whitespace-nowrap overflow-hidden transition-opacity duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
            isCollapsedState ? 'opacity-0' : 'opacity-100'
          ]"
        >
          <div class="font-semibold text-[15px] leading-tight text-[var(--ink-primary)] tracking-tight truncate">
            BetterHRBUST
          </div>
          <div class="text-[11px] text-[var(--ink-muted)] truncate mt-0.5">哈理工教务在线</div>
        </div>
      </div>
    </div>

    <!-- Navigation List：折叠时分类标题让出高度，图标放大并与相邻项贴合 -->
    <nav
      v-overlay-scroll
      class="flex-1 overflow-y-auto overflow-x-hidden px-3 pb-4"
    >
      <div
        v-for="group in navGroups"
        :key="group.title"
        :class="[
          'transition-[margin-top] duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
          isCollapsedState ? 'mt-0' : 'mt-5 first:mt-0'
        ]"
      >
        <div
          :class="[
            'flex items-center px-3 overflow-hidden select-none transition-[height,margin-bottom,opacity] duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
            isCollapsedState ? 'h-0 mb-0 opacity-0' : 'h-7 mb-1 opacity-100'
          ]"
        >
          <span
            class="text-[11px] font-semibold tracking-[0.08em] uppercase text-[var(--ink-muted)] whitespace-nowrap overflow-hidden truncate"
          >
            {{ group.title }}
          </span>
        </div>
        <div
          :class="[
            'flex flex-col transition-[gap] duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
            isCollapsedState ? 'gap-0' : 'gap-0.5'
          ]"
        >
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            :title="isCollapsedState ? item.label : undefined"
            :aria-current="activeTab === item.id ? 'page' : undefined"
            :class="[
              'relative w-full h-12 flex items-center rounded-[var(--shape-full)] text-[14px] cursor-pointer select-none group overflow-hidden active:scale-[0.98]',
              activeTab === item.id
                ? 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)] font-semibold'
                : 'text-[var(--ink-secondary)] font-medium hover:bg-[var(--md-surface-container)] hover:text-[var(--ink-primary)]'
            ]"
            @click="$emit('update:activeTab', item.id)"
          >
            <div
              class="w-12 h-12 flex items-center justify-center shrink-0 transition-transform duration-300 ease-[cubic-bezier(0.2,0,0,1)]"
              :class="isCollapsedState ? 'scale-125' : 'scale-100'"
            >
              <Icon
                :name="item.icon"
                customClass="w-5 h-5 shrink-0 transition-transform duration-200 group-hover:scale-105"
              />
            </div>
            <!-- 文字始终保留在 DOM 中，只淡入淡出：nowrap + overflow-hidden 保证宽度变化时不会换行、不会改变高度 -->
            <span
              :class="[
                'ml-1 pr-3 truncate whitespace-nowrap overflow-hidden select-none transition-opacity duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
                isCollapsedState ? 'opacity-0' : 'opacity-100'
              ]"
              :aria-hidden="isCollapsedState ? 'true' : undefined"
            >
              {{ item.label }}
            </span>
          </button>
        </div>
      </div>
    </nav>

    <!-- Bottom User Area：用户身份 + AI 入口 -->
    <div class="p-3 shrink-0 flex items-center gap-1.5 overflow-hidden">
      <button
        type="button"
        class="flex-1 min-w-0 h-[56px] px-1.5 flex items-center text-left rounded-[var(--shape-full)] cursor-pointer hover:bg-[var(--md-surface-container)] transition-colors duration-150 active:scale-[0.98] select-none overflow-hidden group"
        :title="isLoggedIn ? '已登录 · 点击可切换账号或重新认证' : '登录教务在线'"
        @click="openLoginModal()"
      >
        <div class="w-10 h-10 flex items-center justify-center shrink-0">
          <div
            class="w-10 h-10 rounded-full font-semibold text-sm flex items-center justify-center shrink-0 transition-colors"
            :class="isLoggedIn
              ? 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)]'
              : 'bg-[var(--md-surface-container-high)] text-[var(--ink-secondary)]'"
          >
            {{ isLoggedIn && userProfile.realName ? userProfile.realName.slice(0, 1) : '?' }}
          </div>
        </div>
        <div
          :class="[
            'ml-2 min-w-0 flex-1 leading-tight whitespace-nowrap overflow-hidden transition-opacity duration-300 ease-[cubic-bezier(0.2,0,0,1)]',
            isCollapsedState ? 'opacity-0' : 'opacity-100'
          ]"
          :aria-hidden="isCollapsedState ? 'true' : undefined"
        >
          <div class="text-[13px] font-semibold text-[var(--ink-primary)] truncate">
            {{ isLoggedIn ? (userProfile.realName || '已登录') : '未登录' }}
          </div>
          <div class="text-[11px] text-[var(--ink-muted)] num-tabular truncate mt-0.5">
            {{ isLoggedIn ? (userProfile.studentNumber || studentNumber) : '点击登录教务' }}
          </div>
        </div>
      </button>

      <!-- AI 对话入口：与用户身份同一行，位于名称右侧；折叠时随文字一起淡出并收起宽度 -->
      <button
        type="button"
        :aria-label="aiOpen ? '关闭 AI 对话' : '打开 AI 对话'"
        :title="aiOpen ? '关闭 AI 对话 (Ctrl+J)' : '打开 AI 对话 (Ctrl+J)'"
        :aria-hidden="isCollapsedState ? 'true' : undefined"
        :tabindex="isCollapsedState ? -1 : 0"
        class="shrink-0 h-10 rounded-full flex items-center justify-center text-[11.5px] font-semibold cursor-pointer overflow-hidden transition-[max-width,opacity,background-color,color] duration-300 ease-[cubic-bezier(0.2,0,0,1)]"
        :class="[
          isCollapsedState ? 'max-w-0 opacity-0' : 'max-w-[40px] opacity-100',
          aiOpen
            ? 'bg-[var(--md-primary)] text-[var(--md-on-primary)]'
            : 'bg-[var(--md-surface-container-high)] text-[var(--ink-secondary)] hover:bg-[var(--md-surface-container-highest)] hover:text-[var(--ink-primary)]'
        ]"
        @click="toggleAi"
      >
        <span class="w-10 h-10 flex items-center justify-center shrink-0">AI</span>
      </button>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import logoUrl from '@/assets/BetterHRBUST.png';
import { isUserscript } from '@/utils/platform.js';
import { useSession } from '@/composables/useSession.js';
import { useAiChat } from '@/composables/useAiChat.js';

// 意见反馈走独立服务端接口：Web 同源反代、桌面端直连公共服务端（见 services/feedback.js），油猴端无服务链路不提供入口
const feedbackAvailable = !isUserscript;

const { isLoggedIn, userProfile, studentNumber, openLoginModal } = useSession();
const { open: aiOpen, toggle: toggleAi } = useAiChat();

const props = defineProps({
  activeTab: {
    type: String,
    default: 'dashboard'
  },
  isCollapsed: {
    type: Boolean,
    default: false
  },
  mobileOpen: {
    type: Boolean,
    default: false
  }
});

defineEmits(['update:activeTab', 'close']);

const isMobile = ref(false);

function checkMobile() {
  if (typeof window !== 'undefined') {
    isMobile.value = window.innerWidth < 1024;
  }
}

onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
});

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
});

const isCollapsedState = computed(() => {
  if (isMobile.value) return false;
  return props.isCollapsed;
});

const navGroups = [
  {
    title: '日常查询',
    items: [
      { id: 'dashboard', label: '概览', icon: 'dashboard' },
      { id: 'timetable', label: '智能课程表', icon: 'timetable' },
      { id: 'exam', label: '考试日程与倒计时', icon: 'exam' }
    ]
  },
  {
    title: '学业与工具',
    items: [
      { id: 'score', label: '成绩与GPA分析', icon: 'score' },
      { id: 'program', label: '培养方案与学分', icon: 'program' },
      { id: 'process', label: '教学进程与阶段', icon: 'process' },
      { id: 'course', label: '我的课程名录', icon: 'course' },
      { id: 'classroom', label: '空教室与自习', icon: 'classroom' },
      { id: 'evaluation', label: '教学评价助手', icon: 'evaluation' },
      { id: 'resources', label: '资料查找', icon: 'resources' }
    ]
  },
  {
    title: '信息与系统',
    items: [
      { id: 'profile', label: '学籍档案与隐私', icon: 'profile' },
      { id: 'notice', label: '教学公告与校历', icon: 'notice' },
      // 意见反馈依赖服务端接口，油猴端不接入
      ...(feedbackAvailable ? [{ id: 'feedback', label: '意见反馈', icon: 'message' }] : []),
      { id: 'settings', label: '设置与系统状态', icon: 'settings' }
    ]
  }
];
</script>
