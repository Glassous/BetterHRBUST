<template>
  <aside
    :class="[
      'h-screen flex flex-col bg-white/95 dark:bg-[#191b20]/95 border-r border-zinc-200/80 dark:border-zinc-800/70 backdrop-blur-md select-none shadow-none',
      // Desktop layout
      'lg:sticky lg:top-0 lg:shrink-0 lg:z-30',
      'lg:transition-[width] lg:duration-300 lg:ease-in-out',
      isCollapsedState ? 'lg:w-[76px]' : 'lg:w-[280px]',
      // Mobile / Responsive overlay layout
      'fixed inset-y-0 left-0 z-50 w-[280px] transition-transform duration-300 ease-in-out',
      mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
    ]"
  >
    <!-- Brand / Header (Fixed logo position, circular cropped logo, mobile close button) -->
    <div class="h-18 flex items-center px-3.5 overflow-hidden">
      <div class="w-full flex items-center justify-between">
        <!-- Logo & Title -->
        <div class="flex items-center min-w-0">
          <div class="w-12 h-12 flex items-center justify-center shrink-0">
            <div class="w-9 h-9 rounded-full overflow-hidden shadow-xs shrink-0 flex items-center justify-center bg-zinc-100 dark:bg-zinc-800">
              <img
                :src="logoUrl"
                alt="HRBUST"
                class="w-full h-full object-cover rounded-full shrink-0"
              />
            </div>
          </div>
          <div v-if="!isCollapsedState" class="ml-1.5 min-w-0 flex-1 whitespace-nowrap overflow-hidden">
            <div class="font-bold text-base text-zinc-900 dark:text-zinc-100 tracking-tight truncate">
              BetterHRBUST
            </div>
          </div>
        </div>

        <!-- Mobile Close Button (X icon, strictly visible only in responsive mode, hidden on PC) -->
        <button
          type="button"
          class="lg:hidden p-2 -mr-1 rounded-xl text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:text-zinc-100 dark:hover:bg-zinc-800/80 cursor-pointer transition-colors shrink-0"
          title="关闭侧边栏"
          @click="$emit('close')"
        >
          <svg class="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
    </div>

    <!-- Navigation List (Strictly fixed 2D coordinates, zero horizontal or vertical shifts) -->
    <nav class="flex-1 overflow-y-auto overflow-x-hidden px-3.5 py-2 space-y-4">
      <div v-for="group in navGroups" :key="group.title">
        <!-- Reserved fixed height for group title to guarantee Y-axis stability -->
        <div class="h-5 flex items-center px-2 mb-1.5 overflow-hidden select-none">
          <span
            v-if="!isCollapsedState"
            class="text-xs font-semibold uppercase tracking-wider text-zinc-400 dark:text-zinc-500 whitespace-nowrap overflow-hidden truncate"
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
              'w-full h-11 flex items-center rounded-xl text-sm font-medium transition-colors duration-150 cursor-pointer select-none group overflow-hidden',
              activeTab === item.id
                ? 'bg-zinc-800 text-zinc-50 dark:bg-zinc-700/80 dark:text-zinc-100 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900 hover:bg-zinc-100/90 dark:text-zinc-300 dark:hover:text-white dark:hover:bg-zinc-800/50'
            ]"
            @click="$emit('update:activeTab', item.id)"
          >
            <div class="w-12 h-11 flex items-center justify-center shrink-0">
              <Icon
                :name="item.icon"
                :customClass="[
                  'w-5 h-5 shrink-0 transition-transform duration-150',
                  activeTab === item.id ? '' : 'group-hover:scale-105'
                ]"
              />
            </div>
            <span
              v-if="!isCollapsedState"
              class="ml-1.5 truncate whitespace-nowrap overflow-hidden select-none"
            >
              {{ item.label }}
            </span>
          </button>
        </div>
      </div>
    </nav>

    <!-- Bottom User Area (No gray background, no top divider, fixed avatar position) -->
    <div class="p-3.5 overflow-hidden">
      <div
        class="w-full h-12 flex items-center rounded-xl cursor-pointer hover:bg-zinc-100/70 dark:hover:bg-zinc-800/50 transition-colors select-none overflow-hidden group"
        :title="isLoggedIn ? '已登录 · 点击可切换账号或重新认证' : '登录教务在线'"
        @click="openLoginModal()"
      >
        <div class="w-12 h-12 flex items-center justify-center shrink-0">
          <div class="w-9 h-9 rounded-full bg-zinc-200 dark:bg-zinc-700 text-zinc-800 dark:text-zinc-200 font-bold text-sm flex items-center justify-center shrink-0 shadow-xs group-hover:scale-105 transition-transform">
            {{ isLoggedIn && userProfile.realName ? userProfile.realName.slice(0, 1) : '?' }}
          </div>
        </div>
        <div v-if="!isCollapsedState" class="ml-1.5 min-w-0 flex-1 leading-tight whitespace-nowrap overflow-hidden">
          <div class="text-sm font-semibold text-zinc-900 dark:text-zinc-100 truncate">
            {{ isLoggedIn ? (userProfile.realName || '已登录') : '未登录' }}
          </div>
          <div class="text-xs text-zinc-400 dark:text-zinc-400 truncate mt-0.5">
            {{ isLoggedIn ? (userProfile.studentNumber || studentNumber) : '点击登录教务' }}
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import logoUrl from '@/assets/HRBUST.png';
import { useSession } from '@/composables/useSession.js';

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
    title: '教务核心',
    items: [
      { id: 'dashboard', label: '概览', icon: 'dashboard' },
      { id: 'timetable', label: '智能课程表', icon: 'timetable' },
      { id: 'score', label: '成绩与GPA分析', icon: 'score' },
      { id: 'exam', label: '考试日程与倒计时', icon: 'exam' }
    ]
  },
  {
    title: '培养与资源',
    items: [
      { id: 'program', label: '培养方案与学分', icon: 'program' },
      { id: 'classroom', label: '空教室与自习', icon: 'classroom' },
      { id: 'course', label: '全校课程名录', icon: 'course' }
    ]
  },
  {
    title: '信息与系统',
    items: [
      { id: 'profile', label: '学籍档案与隐私', icon: 'profile' },
      { id: 'notice', label: '教学公告与校历', icon: 'notice' },
      { id: 'settings', label: '设置与系统状态', icon: 'settings' }
    ]
  }
];
</script>