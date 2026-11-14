<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="showLoginPrompt" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="exam" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以查询您的考试日程安排与考场位置</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <div class="flex flex-wrap items-center justify-between gap-2 text-[13px] text-[var(--ink-secondary)] num-tabular">
        <span>
          共获取到 <strong class="font-semibold text-[var(--ink-primary)]">{{ exams.length }}</strong> 门考试安排
        </span>
        <span class="text-[var(--ink-muted)]">上次同步：{{ lastSyncText }}</span>
      </div>

      <!-- 临近考试倒计时 -->
      <UiCard title="临近考试倒计时雷达" :bodyClass="upcomingExams.length === 0 ? 'px-6 py-5' : 'p-5 sm:p-6'">
        <div v-if="loading" class="py-8 text-center text-[13px] text-[var(--ink-muted)]">
          <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
          正在加载考试日程...
        </div>

        <div v-else-if="upcomingExams.length === 0" class="flex items-center gap-2.5 text-[13px] text-[var(--ink-muted)]">
          <Icon name="check-circle" customClass="w-4 h-4 shrink-0" />
          <span>当前暂无近期考试安排，请关注教务在线通知。</span>
        </div>

        <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          <div
            v-for="ex in upcomingExams"
            :key="ex.courseId + ex.time"
            class="p-5 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/55 hover:border-[var(--border-strong)] hover:-translate-y-0.5 transition-all duration-150 flex flex-col justify-between gap-4"
          >
            <div class="space-y-3">
              <div class="flex items-baseline justify-between gap-2">
                <span
                  class="text-2xl font-bold num-tabular"
                  :class="ex.countdownDays <= 3 ? 'text-[var(--sem-danger)]' : ex.countdownDays <= 7 ? 'text-[var(--sem-warning)]' : 'text-[var(--accent)]'"
                >
                  {{ ex.countdownDays <= 0 ? '今日或已考' : `${ex.countdownDays} 天` }}
                </span>
                <span class="text-[13px] text-[var(--ink-muted)] font-mono num-tabular">{{ ex.courseId }}</span>
              </div>

              <h4 class="text-[15px] font-semibold text-[var(--ink-primary)] leading-snug">{{ ex.courseName }}</h4>

              <div class="space-y-2 text-[13px] text-[var(--ink-secondary)]">
                <div class="flex items-center gap-2 num-tabular">
                  <Icon name="clock" customClass="w-4 h-4 text-[var(--ink-muted)] shrink-0" />
                  <span>{{ ex.time }}</span>
                </div>
                <div class="flex items-center gap-2">
                  <Icon name="map-pin" customClass="w-4 h-4 text-[var(--accent)] shrink-0" />
                  <span class="font-medium text-[var(--ink-primary)]">{{ ex.location || '考场待定' }}</span>
                </div>
              </div>
            </div>

            <div class="pt-3.5 border-t border-[var(--border-hairline)] flex items-center justify-between gap-2">
              <UiBadge size="sm" variant="default">{{ ex.property || '考试' }}</UiBadge>
              <UiButton size="sm" variant="outline" @click="exportExamIcs(ex)">
                加到日历
              </UiButton>
            </div>
          </div>
        </div>
      </UiCard>

      <!-- 全部考试日程列表 -->
      <UiCard title="全部考试日程列表" bodyClass="p-0">
        <template #header-action>
          <UiTabs
            :items="[
              { label: '全部', value: 'all' },
              { label: '待考试', value: 'upcoming' },
              { label: '已结束', value: 'finished' }
            ]"
            v-model="examFilter"
          />
        </template>

        <div class="overflow-x-auto">
          <table class="w-full min-w-[720px] text-left border-collapse text-[13px]">
            <thead>
              <tr class="border-b border-[var(--border-hairline)] bg-[var(--bg-subtle)]/75 text-[var(--ink-secondary)] font-semibold">
                <th class="p-4 pl-6">课程代码</th>
                <th class="p-4">课程名称</th>
                <th class="p-4">考试时间</th>
                <th class="p-4">考场地点</th>
                <th class="p-4">性质</th>
                <th class="p-4 pr-6 text-right">状态</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-[var(--border-hairline)]">
              <tr
                v-for="e in filteredExams"
                :key="e.courseId + e.time"
                class="hover:bg-[var(--bg-subtle)]/55 transition-colors"
              >
                <td class="p-4 pl-6 font-mono text-[var(--ink-muted)] num-tabular">{{ e.courseId }}</td>
                <td class="p-4 text-[14px] font-medium text-[var(--ink-primary)]">{{ e.courseName }}</td>
                <td class="p-4 text-[var(--ink-secondary)] num-tabular">{{ e.time }}</td>
                <td class="p-4 font-medium text-[var(--ink-primary)]">{{ e.location }}</td>
                <td class="p-4"><UiBadge size="sm" variant="default">{{ e.property }}</UiBadge></td>
                <td class="p-4 pr-6 text-right">
                  <UiBadge size="sm" :variant="e.timeUnknown ? 'default' : e.isUpcoming ? 'warning' : 'outline'">
                    {{ e.timeUnknown ? '时间未知' : e.isUpcoming ? '待开考' : '已结束' }}
                  </UiBadge>
                </td>
              </tr>
              <tr v-if="filteredExams.length === 0">
                <td colspan="6" class="p-10 text-center text-[13px] text-[var(--ink-muted)]">
                  暂无匹配的考试记录
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </UiCard>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import { useToast } from '@/composables/useToast.js';
import { parseExamDateTime } from '@/utils/examTime.js';
import { buildIcsCalendar, downloadIcs } from '@/utils/ics.js';

const { isLoggedIn, isSessionExpired, openLoginModal } = useSession();
const { exams: cachedExams, syncing, lastSyncText } = useAcademicData();
const { showToast } = useToast();

const showLoginPrompt = computed(() => !isLoggedIn.value && !isSessionExpired.value);
const examFilter = ref('all');

const loading = computed(() => syncing.value && cachedExams.value.length === 0);

const exams = computed(() => {
  const now = new Date();
  return (cachedExams.value || []).map(e => {
    let countdownDays = null;
    let isUpcoming = true;
    let timeUnknown = true;

    const parsed = parseExamDateTime(e.time);
    if (parsed) {
      timeUnknown = false;
      countdownDays = Math.ceil((parsed.date.getTime() - now.getTime()) / (1000 * 3600 * 24));
      isUpcoming = countdownDays >= 0;
    }

    return {
      ...e,
      countdownDays: countdownDays !== null ? countdownDays : 0,
      isUpcoming,
      timeUnknown
    };
  });
});

const upcomingExams = computed(() => {
  return exams.value.filter(e => e.isUpcoming);
});

const filteredExams = computed(() => {
  if (examFilter.value === 'upcoming') return exams.value.filter(e => e.isUpcoming);
  if (examFilter.value === 'finished') return exams.value.filter(e => !e.isUpcoming);
  return exams.value;
});

async function exportExamIcs(ex) {
  const parsed = parseExamDateTime(ex.time);
  if (!parsed) {
    showToast({ title: '无法导出', message: '该考试的时间无法解析，请核对教务原文', type: 'warning' });
    return;
  }
  const start = parsed.start || new Date(new Date(parsed.date).setHours(8, 0, 0, 0));
  const end = parsed.end || new Date(start.getTime() + 2 * 60 * 60 * 1000);
  const ics = buildIcsCalendar(
    [
      {
        summary: `考试: ${ex.courseName}`,
        location: ex.location || '',
        description: `${ex.property || ''} 时间:${ex.time || ''}`,
        start,
        end,
        alarmMinutes: 30
      }
    ],
    { prodId: '-//BetterHRBUST//Exam//CN' }
  );
  const result = await downloadIcs(`考试_${ex.courseName}.ics`, ics);
  if (result === 'saved') {
    showToast({ title: '日历已导出', message: `${ex.courseName} (${ex.location})`, type: 'success' });
  } else if (result === 'fallback') {
    showToast({ title: '日历已开始下载', message: `${ex.courseName} (${ex.location})，存入浏览器默认下载目录`, type: 'success' });
  }
}
</script>
