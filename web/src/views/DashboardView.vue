<template>
  <div class="space-y-6">
    <!-- 初始认证探测中：轻量骨架屏 -->
    <div v-if="!authChecked && !hasCachedData" class="space-y-6">
      <div class="rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] p-6 space-y-3">
        <div class="h-4 w-44 bg-[var(--bg-subtle)] animate-pulse rounded" />
        <div class="h-14 w-full bg-[var(--bg-subtle)] animate-pulse rounded-[var(--radius-panel)]" />
      </div>
      <div class="academic-summary-grid grid gap-5">
        <div
          v-for="i in 4"
          :key="i"
          class="p-5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] space-y-3"
        >
          <div class="h-3.5 w-20 bg-[var(--bg-subtle)] animate-pulse rounded" />
          <div class="h-7 w-24 bg-[var(--bg-subtle)] animate-pulse rounded" />
        </div>
      </div>
      <div class="p-12 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] text-center text-[13px] text-[var(--ink-muted)]">
        <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
        正在连接教务在线网关并验证身份...
      </div>
    </div>

    <!-- 未登录且无离线缓存：集中登录引导 -->
    <div v-else-if="authChecked && !isLoggedIn && !hasCachedData" class="max-w-xl mx-auto py-8">
      <LoginCard />
    </div>

    <!-- 已登录或持离线缓存：工具型日常工作台 -->
    <template v-else>
      <!-- 第一视觉焦点：今日日程与教学周定位 + 快捷工具 -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-5 sm:gap-6 items-stretch">
        <!-- 主工作区：今日剩余课程 & 下节提示 -->
        <UiCard
          class="lg:col-span-2"
          customClass="h-full flex flex-col"
          :title="`今日课程安排 (${currentDayName})`"
          :subtitle="isSchoolHoliday ? `${todayDateLabel} · 当前处于假期` : `${todayDateLabel} · 当前教学第 ${currentWeek} 周`"
          bodyClass="p-0 flex-1 flex flex-col justify-center"
        >
          <template #header-action>
            <UiButton size="sm" variant="ghost" @click="$emit('navigate', 'timetable')">
              <span>完整课表</span>
              <template #suffix>
                <Icon name="chevron-right" customClass="w-3.5 h-3.5" />
              </template>
            </UiButton>
          </template>

          <div v-if="todayCourses.length === 0" class="px-6 py-7 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div class="flex items-center gap-3.5 min-w-0">
              <div class="w-11 h-11 rounded-2xl bg-[var(--bg-subtle)] text-[var(--ink-muted)] flex items-center justify-center shrink-0">
                <Icon name="calendar" customClass="w-5 h-5" />
              </div>
              <div class="min-w-0">
                <div class="text-[15px] font-semibold text-[var(--ink-primary)]">今日暂无待上课程</div>
                <div class="text-[13px] text-[var(--ink-muted)] mt-0.5 leading-relaxed">
                  {{ isSchoolHoliday ? '当前为假期时段，可前往课表或成绩页核对下学期安排' : '今日排课已全部结束或今日无课，可自由安排自习' }}
                </div>
              </div>
            </div>
            <UiButton size="sm" variant="outline" @click="$emit('navigate', 'classroom')">
              自习教室参考
            </UiButton>
          </div>

          <div v-else class="divide-y divide-[var(--border-hairline)]">
            <div
              v-for="(item, idx) in todayCourses"
              :key="item.courseName + item.sectionIndex + idx"
              class="px-5 sm:px-6 py-4.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3 hover:bg-[var(--bg-subtle)]/45 transition-colors"
            >
              <div class="flex items-start gap-4 sm:gap-5 min-w-0">
                <div class="w-30 shrink-0 pt-0.5 num-tabular">
                  <div class="text-[15px] font-semibold text-[var(--ink-primary)]">
                    {{ item.sectionLabel || `第 ${item.sectionIndex} 大节` }}
                  </div>
                  <div
                    v-if="getCombineSlotTime(item.sectionIndex)"
                    class="text-[13px] font-mono text-[var(--ink-muted)] mt-0.5"
                  >
                    {{ getCombineSlotTime(item.sectionIndex) }}
                  </div>
                </div>

                <div class="min-w-0 flex-1">
                  <div class="flex items-center flex-wrap gap-2">
                    <span class="w-2.5 h-2.5 rounded-full shrink-0 course-dot" :style="getCourseColor(item).style"></span>
                    <h4 class="text-[15px] sm:text-base font-semibold text-[var(--ink-primary)] truncate">{{ item.courseName }}</h4>
                    <UiBadge v-if="idx === 0" size="sm" variant="accent">下一节</UiBadge>
                    <UiBadge v-if="item.courseSeq" size="sm" variant="outline">序号 {{ item.courseSeq }}</UiBadge>
                  </div>
                  <div class="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[13px] text-[var(--ink-secondary)] mt-2">
                    <span v-if="item.location" class="flex items-center gap-1.5 font-medium text-[var(--ink-primary)]">
                      <Icon name="map-pin" customClass="w-4 h-4 text-[var(--accent)] shrink-0" />
                      {{ item.location }}
                    </span>
                    <span v-if="item.teacher" class="flex items-center gap-1.5">
                      <Icon name="profile" customClass="w-4 h-4 text-[var(--ink-muted)] shrink-0" />
                      {{ item.teacher }}
                    </span>
                    <span v-if="item.weeks" class="text-[13px] text-[var(--ink-muted)] num-tabular">
                      {{ item.weeks }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </UiCard>

        <!-- 右侧栏：常用工具入口 -->
        <UiCard title="快捷工具" customClass="h-full flex flex-col" bodyClass="p-4 sm:p-5 flex-1 flex items-center">
          <div class="grid grid-cols-2 gap-3 w-full">
            <button
              v-for="tool in quickTools"
              :key="tool.label"
              type="button"
              class="p-3 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/55 hover:bg-[var(--bg-subtle)] hover:border-[var(--border-strong)] hover:-translate-y-0.5 active:scale-[0.98] transition-all duration-150 ease-out text-left cursor-pointer flex items-center gap-2.5 min-h-[54px] group"
              @click="$emit('navigate', tool.target)"
            >
              <div class="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-hairline)] flex items-center justify-center text-[var(--accent)] shrink-0 transition-transform duration-150 group-hover:scale-105">
                <Icon :name="tool.icon" customClass="w-[18px] h-[18px]" />
              </div>
              <div class="min-w-0">
                <div class="text-[13px] font-semibold text-[var(--ink-primary)] truncate">{{ tool.label }}</div>
                <div class="text-xs text-[var(--ink-muted)] truncate mt-0.5">{{ tool.desc }}</div>
              </div>
            </button>
          </div>
        </UiCard>
      </div>

      <!-- 第二层：核心遥测索引条（保留 .academic-summary-grid 响应容器查询与 2 列缩放校验） -->
      <div class="academic-summary-grid grid gap-4 sm:gap-5">
        <!-- 今日剩余课程（严格维持文本结构以保障 E2E /0 门/ 校验） -->
        <div class="p-5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] hover:border-[var(--border-strong)] transition-colors flex items-start justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="text-[13px] font-medium text-[var(--ink-muted)]">今日剩余课程</div>
            <div class="text-[28px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-1.5">
              {{ todayCourses.length }} <span class="text-[13px] font-normal text-[var(--ink-muted)]">门待上</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5">
              {{ isSchoolHoliday ? '当前处于假期' : `当前教学周第 ${currentWeek} 周` }}
            </div>
          </div>
          <div class="w-10 h-10 rounded-xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center shrink-0">
            <Icon name="timetable" customClass="w-5 h-5" />
          </div>
        </div>

        <!-- 近期考试（无考试时保持沉稳中性，绝不用红色报错误导） -->
        <div class="p-5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] hover:border-[var(--border-strong)] transition-colors flex items-start justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="text-[13px] font-medium text-[var(--ink-muted)]">近期考试</div>
            <div
              class="text-[28px] leading-tight font-bold num-tabular mt-1.5"
              :class="upcomingExams.length > 0 ? 'text-[var(--sem-warning)]' : 'text-[var(--ink-primary)]'"
            >
              {{ upcomingExams.length }} <span class="text-[13px] font-normal text-[var(--ink-muted)]">门</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5 truncate">
              {{ upcomingExams[0]?.courseName || '暂无近期排考' }}
            </div>
          </div>
          <div
            class="w-10 h-10 rounded-xl flex items-center justify-center shrink-0"
            :class="upcomingExams.length > 0 ? 'bg-[var(--sem-warning-soft)] text-[var(--sem-warning)]' : 'bg-[var(--bg-subtle)] text-[var(--ink-muted)]'"
          >
            <Icon name="exam" customClass="w-5 h-5" />
          </div>
        </div>

        <!-- GPA Card -->
        <div class="p-5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] hover:border-[var(--border-strong)] transition-colors flex items-start justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="text-[13px] font-medium text-[var(--ink-muted)]">平均学分绩点 (五分制)</div>
            <div class="text-[28px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-1.5">
              {{ stats.gpa.toFixed(2) }}
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5 num-tabular">
              加权均分 {{ stats.weightedAvg.toFixed(1) }} · 仅必修
            </div>
          </div>
          <div class="w-10 h-10 rounded-xl bg-[var(--bg-subtle)] text-[var(--ink-secondary)] flex items-center justify-center shrink-0">
            <Icon name="score" customClass="w-5 h-5" />
          </div>
        </div>

        <!-- Credits Card -->
        <div class="p-5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] hover:border-[var(--border-strong)] transition-colors flex items-start justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="text-[13px] font-medium text-[var(--ink-muted)]">已获得学分 / 方案总学分</div>
            <div class="text-[28px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-1.5">
              {{ creditsProgress.earnedTotal }} <span class="text-[13px] font-normal text-[var(--ink-muted)]">/ {{ creditsProgress.requiredTotal || '待同步' }}</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5 num-tabular truncate">
              必修已修 {{ stats.totalCredits }} 学分 ({{ stats.courseCount }} 门)
            </div>
          </div>
          <div class="w-10 h-10 rounded-xl bg-[var(--bg-subtle)] text-[var(--ink-secondary)] flex items-center justify-center shrink-0">
            <Icon name="program" customClass="w-5 h-5" />
          </div>
        </div>
      </div>

      <!-- 第三层：学业口径核对 + 考试与公告紧凑区 -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-5 sm:gap-6">
        <div class="lg:col-span-2 space-y-5 sm:space-y-6">
          <!-- 学业指标摘要（已修复原版缺少 title 导致 header-action 丢失的问题） -->
          <UiCard title="学业口径核对" subtitle="与成绩分析、培养方案共用同一去重规则">
            <template #header-action>
              <UiButton size="sm" variant="ghost" @click="$emit('navigate', 'score')">
                <span>查看成绩分析</span>
                <template #suffix>
                  <Icon name="chevron-right" customClass="w-3.5 h-3.5" />
                </template>
              </UiButton>
            </template>

            <div class="space-y-4">
              <div class="flex flex-wrap items-center justify-between gap-4">
                <div class="flex flex-wrap items-center gap-x-9 gap-y-3">
                  <div>
                    <div class="text-[13px] text-[var(--ink-muted)]">学位绩点</div>
                    <div
                      class="text-xl font-bold num-tabular mt-1"
                      :class="stats.courseCount === 0
                        ? 'text-[var(--ink-muted)]'
                        : stats.degree.qualified ? 'text-[var(--sem-success)]' : 'text-[var(--sem-danger)]'"
                    >
                      {{ stats.degree.gpa.toFixed(2) }}
                    </div>
                  </div>
                  <div>
                    <div class="text-[13px] text-[var(--ink-muted)]">补考 / 重修</div>
                    <div
                      class="text-xl font-bold num-tabular mt-1"
                      :class="stats.courseCount === 0
                        ? 'text-[var(--ink-muted)]'
                        : stats.recommend.qualified ? 'text-[var(--sem-success)]' : 'text-[var(--sem-danger)]'"
                    >
                      {{ stats.retakeCount }}
                    </div>
                  </div>
                  <div>
                    <div class="text-[13px] text-[var(--ink-muted)]">累计挂科学分</div>
                    <div
                      class="text-xl font-bold num-tabular mt-1"
                      :class="stats.courseCount === 0
                        ? 'text-[var(--ink-muted)]'
                        : stats.risk.level === 'none' ? 'text-[var(--ink-primary)]' : 'text-[var(--sem-danger)]'"
                    >
                      {{ stats.risk.failedCredits }}
                    </div>
                  </div>
                </div>
                <UiBadge
                  size="sm"
                  :variant="stats.courseCount === 0 ? 'default' : stats.risk.level === 'none' ? 'success' : 'danger'"
                  dot
                >
                  {{ stats.courseCount === 0 ? '待同步成绩' : stats.risk.label }}
                </UiBadge>
              </div>

              <GpaCalculationHelp />
            </div>
          </UiCard>

          <!-- 考试安排与考场（无考试时紧致收口） -->
          <UiCard title="考试安排与考场" :bodyClass="upcomingExams.length === 0 ? 'px-6 py-5' : 'p-0'">
            <template #header-action>
              <UiButton size="sm" variant="ghost" @click="$emit('navigate', 'exam')">
                <span>全部考试</span>
                <template #suffix>
                  <Icon name="chevron-right" customClass="w-3.5 h-3.5" />
                </template>
              </UiButton>
            </template>

            <div v-if="upcomingExams.length === 0" class="flex items-center gap-2.5 text-[13px] text-[var(--ink-muted)]">
              <Icon name="check-circle" customClass="w-4 h-4 text-[var(--ink-muted)] shrink-0" />
              <span>当前暂无近期考试安排，或本学期考试尚未发布排考考场。</span>
            </div>

            <div v-else class="divide-y divide-[var(--border-hairline)]">
              <div
                v-for="exam in upcomingExams"
                :key="exam.courseId + exam.time"
                class="px-5 sm:px-6 py-4 flex flex-col sm:flex-row sm:items-center justify-between gap-2 hover:bg-[var(--bg-subtle)]/45 transition-colors"
              >
                <div class="min-w-0">
                  <div class="flex items-center gap-2.5">
                    <h4 class="text-[15px] font-semibold text-[var(--ink-primary)] truncate">{{ exam.courseName }}</h4>
                    <UiBadge variant="warning" size="sm">{{ exam.property || '考试' }}</UiBadge>
                  </div>
                  <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5 flex flex-wrap gap-x-5 gap-y-1 num-tabular">
                    <span>时间：{{ exam.time }}</span>
                    <span>考场：<strong class="font-medium text-[var(--ink-primary)]">{{ exam.location }}</strong></span>
                  </div>
                </div>
              </div>
            </div>
          </UiCard>
        </div>

        <!-- 最新教学运行公告：高度交由左侧两卡决定（lg 下绝对定位铺满该列），公告过多时卡内滚动 -->
        <div class="relative">
          <UiCard
            title="教学运行公告"
            customClass="lg:absolute lg:inset-0 flex flex-col"
            :bodyClass="latestNotices.length === 0 ? 'px-6 py-5 flex-1' : 'p-2.5 flex-1 min-h-0 overflow-y-auto'"
          >
            <template #header-action>
              <UiButton size="sm" variant="ghost" @click="$emit('navigate', 'notice')">
                <span>查看更多</span>
                <template #suffix>
                  <Icon name="chevron-right" customClass="w-3.5 h-3.5" />
                </template>
              </UiButton>
            </template>

            <div v-if="latestNotices.length === 0" class="flex items-center gap-2.5 text-[13px] text-[var(--ink-muted)]">
              <Icon name="notice" customClass="w-4 h-4 text-[var(--ink-muted)] shrink-0" />
              <span>本周暂无发布的教学运行新公告</span>
            </div>

            <div v-else class="divide-y divide-[var(--border-hairline)]">
              <button
                v-for="(n, idx) in latestNotices"
                :key="idx"
                type="button"
                class="w-full text-left p-3.5 rounded-[var(--radius-control)] hover:bg-[var(--bg-subtle)] transition-colors cursor-pointer"
                @click="$emit('navigate', 'notice')"
              >
                <div class="flex items-center justify-between gap-2 mb-1.5">
                  <UiBadge size="sm" variant="default">教务通知</UiBadge>
                  <span v-if="n.date" class="text-xs text-[var(--ink-muted)] num-tabular font-mono">{{ n.date }}</span>
                </div>
                <h5 class="text-[13px] font-medium text-[var(--ink-primary)] line-clamp-2 leading-relaxed">
                  {{ n.title }}
                </h5>
              </button>
            </div>
          </UiCard>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, onUnmounted } from 'vue';
import GpaCalculationHelp from '@/components/GpaCalculationHelp.vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import LoginCard from '@/components/auth/LoginCard.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import { buildAcademicStats, computeCreditsProgress } from '@/services/academic/stats.js';
import { getCombineSlotTime, hasCombineSlotEnded } from '@/utils/periodTimes.js';
import { getCourseColor } from '@/utils/courseColors.js';
import { isCourseActiveInWeek } from '@/utils/courseWeeks.js';
import { parseExamDateTime } from '@/utils/examTime.js';

defineEmits(['navigate']);

const { isLoggedIn, authChecked, currentWeek, isSchoolHoliday } = useSession();
// 登录后数据全部走本地缓存，仅顶栏刷新按钮与每日首次打开会触网
const { scores, plan, timetableCombine, exams, notices, hasCachedData } = useAcademicData();

const stats = computed(() => buildAcademicStats(scores.value));

// 与"培养方案与学分"页共用同一函数，保证两页数据完全一致
const creditsProgress = computed(() => computeCreditsProgress(scores.value, plan.value?.groups || [], { planTotalCredits: plan.value?.totalRequiredCredits }));

const dayMap = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
const now = ref(new Date());
const clock = setInterval(() => { now.value = new Date(); }, 30_000);
onUnmounted(() => clearInterval(clock));
const currentDayIndex = computed(() => now.value.getDay() || 7);
const currentDayName = computed(() => dayMap[now.value.getDay()]);
const todayDateLabel = computed(() => {
  const m = String(now.value.getMonth() + 1).padStart(2, '0');
  const d = String(now.value.getDate()).padStart(2, '0');
  return `${now.value.getFullYear()}-${m}-${d}`;
});

const todayCourses = computed(() => {
  if (isSchoolHoliday.value) return [];
  const cells = timetableCombine.value?.cells || [];
  return cells.filter(
    c => c.day === currentDayIndex.value && isCourseActiveInWeek(c, currentWeek.value) && !hasCombineSlotEnded(c.sectionIndex, now.value)
  );
});

function isRecentExam(exam) {
  if (!exam.time) return true;
  const parsed = parseExamDateTime(exam.time);
  if (!parsed) return true;

  const today = new Date(now.value.getFullYear(), now.value.getMonth(), now.value.getDate());
  const sevenDaysAgo = new Date(today.getTime() - 7 * 24 * 60 * 60 * 1000);

  // 严格只显示未来的考试和过去 7 天以内的考试
  return parsed.date >= sevenDaysAgo;
}

const upcomingExams = computed(() => {
  return (exams.value || [])
    .filter(isRecentExam)
    .slice()
    .sort((a, b) => {
      const ta = parseExamDateTime(a.time)?.date.getTime() || 0;
      const tb = parseExamDateTime(b.time)?.date.getTime() || 0;
      return ta - tb;
    });
});

const latestNotices = computed(() => (notices.value || []).slice(0, 4));

const quickTools = [
  { label: '自习空教室', desc: '校区与楼宇索引', icon: 'classroom', target: 'classroom' },
  { label: '成绩与GPA', desc: '历年成绩分析', icon: 'score', target: 'score' },
  { label: '导出课表', desc: '生成 ICS 日历', icon: 'download', target: 'timetable' },
  { label: '培养方案', desc: '毕业学分核对', icon: 'program', target: 'program' }
];
</script>
