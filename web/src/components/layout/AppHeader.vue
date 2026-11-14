<template>
  <header
    class="h-[62px] px-5 sm:px-8 lg:px-10 sticky top-[var(--tb)] z-20 bg-[var(--bg-canvas)]/92 backdrop-blur-md border-b border-[var(--border-hairline)] flex items-center justify-between gap-3 transition-colors duration-200"
  >
    <!-- 左侧：侧栏折叠 + 当前页面与周次定位 -->
    <div class="flex items-center gap-3.5 min-w-0">
      <button
        type="button"
        class="w-10 h-10 rounded-[var(--radius-control)] bg-[var(--bg-surface)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] border border-[var(--border-hairline)] hover:border-[var(--border-strong)] cursor-pointer transition-all duration-150 active:scale-95 flex items-center justify-center shrink-0"
        :title="isCollapsed ? '展开侧边栏' : '折叠侧边栏'"
        :aria-label="isCollapsed ? '展开侧边栏' : '折叠侧边栏'"
        @click="$emit('toggle-sidebar')"
      >
        <Icon :name="isCollapsed ? 'expand' : 'collapse'" customClass="w-[18px] h-[18px]" />
      </button>

      <div class="flex items-center gap-2.5 min-w-0">
        <h1 class="text-base font-semibold text-[var(--ink-primary)] tracking-tight truncate">
          {{ activeTitle }}
        </h1>
        <span
          class="hidden sm:inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[13px] font-medium num-tabular shrink-0 border transition-colors"
          :class="isSchoolHoliday
            ? 'bg-[var(--bg-subtle)] text-[var(--ink-secondary)] border-[var(--border-hairline)]'
            : 'bg-[var(--accent-soft)] text-[var(--accent)] border-[var(--accent-border)]'"
        >
          {{ isSchoolHoliday ? '假期中' : `第 ${currentWeek} 周` }}
        </span>
      </div>
    </div>

    <!-- 右侧操作区：会话提示 + 手动同步 + 主题切换 -->
    <div class="flex items-center gap-2.5 shrink-0">
      <div v-if="shouldShowSessionBanner" class="flex items-center gap-1.5 px-3 py-1 rounded-[var(--radius-control)] bg-[var(--sem-warning-soft)] border border-[var(--sem-warning-border)] text-[var(--sem-warning)] text-[13px]">
        <span class="w-1.5 h-1.5 rounded-full bg-[var(--sem-warning)] shrink-0" />
        <span class="hidden sm:inline font-medium">会话已失效</span>
        <button
          type="button"
          class="hidden sm:inline-block px-1.5 py-0.5 rounded font-medium hover:bg-[var(--sem-warning-soft)] cursor-pointer transition-colors"
          @click="dismissSessionPrompt"
        >
          忽略
        </button>
        <button
          type="button"
          class="px-2 py-0.5 rounded-[var(--radius-badge)] bg-[var(--sem-warning)] text-white font-medium cursor-pointer transition-opacity hover:opacity-90"
          @click="openLoginModal"
        >
          重登
        </button>
      </div>

      <!-- 数据刷新：登录后教务数据不再自动获取，仅此处手动触发全量同步 -->
      <button
        v-if="isLoggedIn || isSessionExpired"
        type="button"
        class="h-10 px-3.5 rounded-[var(--radius-control)] bg-[var(--bg-surface)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] border border-[var(--border-hairline)] hover:border-[var(--border-strong)] cursor-pointer transition-all duration-150 active:scale-[0.97] flex items-center gap-2 text-[13px] font-medium disabled:opacity-60 disabled:cursor-wait"
        :title="`手动刷新教务数据（上次同步：${lastSyncText}）`"
        :disabled="syncing"
        @click="handleRefresh"
      >
        <Icon name="refresh" customClass="w-4 h-4 shrink-0" :class="syncing ? 'animate-spin text-[var(--accent)]' : ''" />
        <span class="hidden sm:inline">{{ syncing ? '同步中' : '同步数据' }}</span>
      </button>

      <button
        type="button"
        class="w-10 h-10 rounded-[var(--radius-control)] bg-[var(--bg-surface)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] border border-[var(--border-hairline)] hover:border-[var(--border-strong)] cursor-pointer transition-all duration-150 active:scale-95 flex items-center justify-center shrink-0 group"
        :title="isDark ? '切换浅色模式' : '切换深色模式'"
        :aria-label="isDark ? '切换浅色模式' : '切换深色模式'"
        @click="toggleTheme"
      >
        <Icon :name="isDark ? 'sun' : 'moon'" customClass="w-[18px] h-[18px] transition-transform duration-200 group-hover:rotate-12" />
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue';
import { useTheme } from '@/composables/useTheme.js';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import { useToast } from '@/composables/useToast.js';

const { isDark, toggleTheme } = useTheme();
const {
  isLoggedIn,
  isSessionExpired,
  currentWeek,
  isSchoolHoliday,
  shouldShowSessionBanner,
  dismissSessionPrompt,
  openLoginModal,
  navigateTo
} = useSession();
const { syncing, lastSyncText, refreshAll } = useAcademicData();
const { showToast } = useToast();

const props = defineProps({
  isCollapsed: {
    type: Boolean,
    default: false
  },
  activeTab: {
    type: String,
    default: 'dashboard'
  }
});

defineEmits(['toggle-sidebar', 'navigate']);

const TAB_TITLES = {
  dashboard: '概览',
  timetable: '智能课程表',
  score: '成绩与GPA分析',
  evaluation: '教学评价助手',
  exam: '考试日程与倒计时',
  program: '培养方案与学分',
  classroom: '空教室与自习',
  course: '我的课程名录',
  resources: '资料查找',
  profile: '学籍档案与隐私',
  notice: '教学公告与校历',
  feedback: '意见反馈',
  settings: '设置与系统状态',
  login: '账号认证'
};

const activeTitle = computed(() => TAB_TITLES[props.activeTab] || 'BetterHRBUST');

async function handleRefresh() {
  const res = await refreshAll();
  if (res.expired) {
    showToast({
      title: '登录状态已失效',
      message: '请重新登录教务在线后再刷新数据',
      type: 'warning'
    });
    navigateTo('login');
    return;
  }
  showToast({
    title: res.success ? '数据已同步' : '部分数据同步失败',
    message: res.message,
    type: res.success ? 'success' : 'warning'
  });
}
</script>
