<template>
  <AppLayout
    :activeTab="activeTab"
    @update:activeTab="navigateTo($event)"
  >
    <div :key="activeTab" class="view-enter">
      <component
        :is="currentViewComponent"
        @navigate="navigateTo($event)"
      />
    </div>
  </AppLayout>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue';
import AppLayout from '@/components/layout/AppLayout.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import { useUpdate } from '@/composables/useUpdate.js';
import DashboardView from '@/views/DashboardView.vue';
import TimetableView from '@/views/TimetableView.vue';
import ScoreView from '@/views/ScoreView.vue';
import ExamView from '@/views/ExamView.vue';
import ProgramView from '@/views/ProgramView.vue';
import ClassroomView from '@/views/ClassroomView.vue';
import ProfileView from '@/views/ProfileView.vue';
import NoticeView from '@/views/NoticeView.vue';
import CourseView from '@/views/CourseView.vue';
import SettingsView from '@/views/SettingsView.vue';
import LoginView from '@/views/LoginView.vue';
import EvaluationView from '@/views/EvaluationView.vue';
import ResourcesView from '@/views/ResourcesView.vue';
import FeedbackView from '@/views/FeedbackView.vue';

const { activeTab, navigateTo, isLoggedIn, authChecked, checkAuth } = useSession();
const { ensureDailySync, needsDailySync, syncAll } = useAcademicData();
const { maybeAutoCheck } = useUpdate();

// 启动阶段标记：避免启动时的自动同步与"登录成功后同步"重复触发
let bootstrapped = false;

onMounted(async () => {
  // 桌面端（Tauri）专属：后台静默检查一次版本更新（按天节流，失败静默，不阻塞界面）
  maybeAutoCheck();

  try {
    // 首次安装或扩展缓存没有会话标记时，检查浏览器已有的教务 Cookie。
    // 无论认证成功与否都结束初始校验，避免概览永远停在加载状态。
    if (!isLoggedIn.value) {
      await checkAuth({ light: true });
    } else {
      authChecked.value = true;
    }
    // 数据策略：登录后数据已全量持久化，日常打开一律只读本地缓存；
    // 仅在"每天首次打开"时才自动向教务获取一次全量数据
    if (isLoggedIn.value && needsDailySync.value) {
      await ensureDailySync();
    }
  } catch {
    // 启动异常不阻塞界面渲染，离线缓存仍可正常展示
  } finally {
    bootstrapped = true;
  }
});

// 用户主动登录成功后立即执行一次全量同步
watch(isLoggedIn, val => {
  if (val && bootstrapped) {
    syncAll({ markManual: false });
  }
});

const views = {
  resources: ResourcesView,
  evaluation: EvaluationView,
  dashboard: DashboardView,
  timetable: TimetableView,
  score: ScoreView,
  exam: ExamView,
  program: ProgramView,
  classroom: ClassroomView,
  profile: ProfileView,
  notice: NoticeView,
  course: CourseView,
  settings: SettingsView,
  feedback: FeedbackView,
  login: LoginView
};

const currentViewComponent = computed(() => views[activeTab.value] || DashboardView);
</script>
