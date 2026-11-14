<template>
  <aside
    :class="[
      'h-[calc(100vh_-_var(--tb))] flex flex-col bg-[var(--bg-surface)] border-r border-[var(--border-hairline)] select-none',
      // Desktop layout（sticky 顶部让出标题栏高度）
      'lg:sticky lg:top-[var(--tb)] lg:shrink-0 lg:z-30',
      'lg:transition-[width,background-color,border-color] lg:duration-200 lg:ease-out',
      isCollapsedState ? 'lg:w-[72px]' : 'lg:w-[264px]',
      // Mobile / Responsive overlay layout
      'fixed top-[var(--tb)] bottom-0 left-0 z-50 w-[264px] transition-transform duration-250 ease-out',
      mobileOpen ? 'translate-x-0 shadow-[var(--shadow-float)] lg:shadow-none' : '-translate-x-full lg:translate-x-0'
    ]"
  >
    <!-- Brand / Header -->
    <div class="h-[62px] flex items-center px-3.5 border-b border-[var(--border-hairline)] overflow-hidden shrink-0">
      <div class="w-full flex items-center justify-between">
        <div class="flex items-center min-w-0">
          <div class="w-11 h-11 flex items-center justify-center shrink-0">
            <div class="w-9 h-9 rounded-xl overflow-hidden shrink-0 flex items-center justify-center bg-[var(--bg-subtle)] border border-[var(--border-hairline)]">
              <img
                :src="logoUrl"
                alt="HRBUST"
                class="w-full h-full object-cover shrink-0"
              />
            </div>
          </div>
          <div v-if="!isCollapsedState" class="ml-2 min-w-0 flex-1 whitespace-nowrap overflow-hidden">
            <div class="font-semibold text-base text-[var(--ink-primary)] tracking-tight truncate">
              BetterHRBUST
            </div>
          </div>
        </div>

        <!-- Mobile Close Button -->
        <button
          type="button"
          class="lg:hidden w-9 h-9 rounded-[var(--radius-control)] text-[var(--ink-muted)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] cursor-pointer transition-colors shrink-0 flex items-center justify-center"
          title="关闭侧边栏"
          aria-label="关闭侧边栏"
          @click="$emit('close')"
        >
          <Icon name="x" customClass="w-5 h-5" />
        </button>
      </div>
    </div>

    <!-- Navigation List -->
    <nav class="flex-1 overflow-y-auto overflow-x-hidden px-3 py-4 space-y-5">
      <div v-for="group in navGroups" :key="group.title">
        <div class="h-6 flex items-center px-2.5 mb-1 overflow-hidden select-none">
          <span
            v-if="!isCollapsedState"
            class="text-xs font-semibold tracking-wide text-[var(--ink-muted)] whitespace-nowrap overflow-hidden truncate"
          >
            {{ group.title }}
          </span>
        </div>
        <div class="space-y-1">
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            :title="isCollapsedState ? item.label : undefined"
            :class="[
              'relative w-full h-[42px] flex items-center rounded-[var(--radius-control)] text-[14px] font-medium transition-all duration-150 cursor-pointer select-none group overflow-hidden active:scale-[0.985]',
              activeTab === item.id
                ? 'bg-[var(--accent-soft)] text-[var(--accent)] font-semibold'
                : 'text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-subtle)]'
            ]"
            @click="$emit('update:activeTab', item.id)"
          >
            <span
              v-if="activeTab === item.id && !isCollapsedState"
              class="absolute left-0 top-2.5 bottom-2.5 w-[3.5px] rounded-r-full bg-[var(--accent)] transition-all duration-200"
            />
            <div class="w-11 h-[42px] flex items-center justify-center shrink-0">
              <Icon
                :name="item.icon"
                customClass="w-[19px] h-[19px] shrink-0 transition-transform duration-150 group-hover:scale-105"
              />
            </div>
            <span
              v-if="!isCollapsedState"
              class="ml-1 truncate whitespace-nowrap overflow-hidden select-none"
            >
              {{ item.label }}
            </span>
          </button>
        </div>
      </div>
    </nav>

    <!-- Bottom User Area -->
    <div class="p-3 border-t border-[var(--border-hairline)] overflow-hidden shrink-0">
      <button
        type="button"
        class="w-full h-[52px] px-1 flex items-center text-left rounded-[var(--radius-panel)] cursor-pointer hover:bg-[var(--bg-subtle)] transition-all duration-150 active:scale-[0.985] select-none overflow-hidden group"
        :title="isLoggedIn ? '已登录 · 点击可切换账号或重新认证' : '登录教务在线'"
        @click="openLoginModal()"
      >
        <div class="w-10 h-10 flex items-center justify-center shrink-0">
          <div
            class="w-9 h-9 rounded-full font-semibold text-sm flex items-center justify-center shrink-0 transition-colors"
            :class="isLoggedIn
              ? 'bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)]'
              : 'bg-[var(--bg-subtle)] text-[var(--ink-secondary)] border border-[var(--border-hairline)]'"
          >
            {{ isLoggedIn && userProfile.realName ? userProfile.realName.slice(0, 1) : '?' }}
          </div>
        </div>
        <div v-if="!isCollapsedState" class="ml-2 min-w-0 flex-1 leading-tight whitespace-nowrap overflow-hidden">
          <div class="text-sm font-semibold text-[var(--ink-primary)] truncate">
            {{ isLoggedIn ? (userProfile.realName || '已登录') : '未登录' }}
          </div>
          <div class="text-xs text-[var(--ink-muted)] num-tabular truncate mt-0.5">
            {{ isLoggedIn ? (userProfile.studentNumber || studentNumber) : '点击登录教务' }}
          </div>
        </div>
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

// 意见反馈走独立服务端接口：Web 同源反代、桌面端直连公共服务端（见 services/feedback.js），油猴端无服务链路不提供入口
const feedbackAvailable = !isUserscript;

const { isLoggedIn, userProfile, studentNumber, openLoginModal } = useSession();

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
