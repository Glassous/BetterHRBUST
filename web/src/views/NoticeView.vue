<template>
  <div class="space-y-6">
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-5 sm:gap-6 items-start">
      <!-- 教学运行公告列表 -->
      <div class="lg:col-span-2">
        <UiCard title="教学运行公告" bodyClass="p-0">
          <template #header-action>
            <div class="flex items-center gap-2.5">
              <span class="text-[13px] text-[var(--ink-muted)] hidden sm:inline">周次筛选：</span>
              <UiSelect
                v-model="selectedWeek"
                :items="weekItems"
                aria-label="周次筛选"
                size="sm"
                custom-class="min-w-[9rem]"
                @change="fetchNotices"
              />
            </div>
          </template>

          <div v-if="loading" class="py-12 text-center text-[13px] text-[var(--ink-muted)]">
            <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
            正在拉取教务运行公告与校历...
          </div>

          <div v-else-if="loadFailed" class="py-10 px-6 text-center text-[13px] text-[var(--ink-muted)] space-y-3">
            <p>本周公告加载失败，请检查网络后重试</p>
            <UiButton variant="outline" size="sm" @click="fetchNotices">重新加载</UiButton>
          </div>

          <div v-else-if="notices.length === 0" class="py-12 px-6 text-center text-[13px] text-[var(--ink-muted)]">
            本周教务在线暂无最新运行公告通知
          </div>

          <div v-else class="divide-y divide-[var(--border-hairline)]">
            <button
              v-for="(n, idx) in notices"
              :key="idx"
              type="button"
              class="w-full text-left px-5 sm:px-6 py-4 flex items-start justify-between gap-4 hover:bg-[var(--bg-subtle)]/55 cursor-pointer transition-colors group"
              @click="readNotice(n)"
            >
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2.5 mb-1.5">
                  <UiBadge size="sm" variant="default">教务运行</UiBadge>
                  <span v-if="n.date" class="text-[13px] text-[var(--ink-muted)] font-mono num-tabular">{{ n.date }}</span>
                </div>
                <h4 class="text-[15px] font-medium text-[var(--ink-primary)] group-hover:text-[var(--accent)] transition-colors line-clamp-2 leading-relaxed">
                  {{ n.title }}
                </h4>
              </div>
              <span class="inline-flex items-center gap-1 text-[13px] font-medium text-[var(--accent)] shrink-0 pt-0.5">
                <span>详情</span>
                <Icon name="chevron-right" customClass="w-3.5 h-3.5 group-hover:translate-x-0.5 transition-transform" />
              </span>
            </button>
          </div>
        </UiCard>
      </div>

      <!-- 校历关键周次节点 -->
      <div>
        <UiCard title="校历关键周次节点 (全校日历)">
          <div class="space-y-4 text-[13px]">
            <a
              :href="calendarSource"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-1.5 text-[var(--accent)] hover:underline font-medium"
            >
              <span>查看教务处官方校历</span>
              <Icon name="external-link" customClass="w-3.5 h-3.5" />
            </a>

            <div class="space-y-5 pt-1.5">
              <div
                v-for="m in milestones"
                :key="m.title"
                class="relative pl-5 border-l-2"
                :class="m.isPassed ? 'border-[var(--accent)]' : 'border-[var(--border-strong)]'"
              >
                <div
                  class="absolute -left-[6px] top-1.5 w-2.5 h-2.5 rounded-full transition-colors"
                  :class="m.isPassed ? 'bg-[var(--accent)]' : 'bg-[var(--border-strong)]'"
                />
                <div class="font-semibold text-[var(--ink-primary)] text-[14px]">{{ m.title }}</div>
                <div class="text-[13px] text-[var(--ink-muted)] font-mono num-tabular mt-0.5">{{ m.time }}</div>
                <div class="text-[13px] text-[var(--ink-secondary)] mt-1 leading-relaxed">{{ m.desc }}</div>
              </div>
            </div>
          </div>
        </UiCard>
      </div>
    </div>

    <!-- 公告正文弹窗 -->
    <UiModal
      v-model="showModal"
      :title="activeNotice ? activeNotice.title : '通知详情'"
    >
      <div v-if="activeNotice" class="space-y-4.5 text-[14px] text-[var(--ink-secondary)] leading-relaxed">
        <div class="px-4 py-3 rounded-[var(--radius-control)] bg-[var(--bg-subtle)] border border-[var(--border-hairline)] flex flex-wrap justify-between gap-2 text-[13px] text-[var(--ink-muted)]">
          <span>来源：教务在线教学运行发布</span>
          <span v-if="activeNotice.date" class="num-tabular font-mono">日期：{{ activeNotice.date }}</span>
        </div>
        <div class="whitespace-pre-wrap leading-relaxed text-[var(--ink-primary)]">
          {{ activeNotice.content || activeNotice.title }}
        </div>
      </div>
    </UiModal>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { semesterOn, calendarSource } from '@/utils/schoolCalendar.js';
import UiCard from '@/components/ui/UiCard.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiModal from '@/components/ui/UiModal.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';

const { currentWeek, semesterTeachingWeeks } = useSession();
const { notices: cachedNotices, loadNoticesForWeek } = useAcademicData();

const loading = ref(false);
const loadFailed = ref(false);
const weekNotices = ref([]);
const selectedWeek = ref(0);
const showModal = ref(false);
const activeNotice = ref(null);

const notices = computed(() =>
  selectedWeek.value === 0 ? cachedNotices.value || [] : weekNotices.value
);

const weekItems = computed(() => [
  { label: `当前周 (第 ${currentWeek.value} 周)`, value: 0 },
  ...Array.from({ length: semesterTeachingWeeks.value }, (_, i) => ({ label: `第 ${i + 1} 周`, value: i + 1 }))
]);

let fetchSeq = 0;

async function fetchNotices() {
  const seq = ++fetchSeq;
  if (selectedWeek.value === 0) {
    weekNotices.value = [];
    loadFailed.value = false;
    loading.value = false;
    return;
  }
  loading.value = true;
  loadFailed.value = false;
  const requestedWeek = selectedWeek.value;
  try {
    const list = await loadNoticesForWeek(requestedWeek);
    if (seq !== fetchSeq || selectedWeek.value !== requestedWeek) return;
    weekNotices.value = list;
  } catch {
    if (seq !== fetchSeq || selectedWeek.value !== requestedWeek) return;
    weekNotices.value = [];
    loadFailed.value = true;
  } finally {
    if (seq === fetchSeq) loading.value = false;
  }
}

function readNotice(n) {
  activeNotice.value = n;
  showModal.value = true;
}

const milestones = computed(() => {
  const semester = semesterOn();
  if (!semester) return [];
  const today = new Date().toLocaleDateString('en-CA');
  return [
    { title: '学期开始上课', time: semester.startDate, desc: `${semester.label} · 教务处公布的学期起点`, isPassed: today >= semester.startDate },
    { title: '教学结束（含考试）', time: semester.teachingEndDate, desc: `共 ${semester.teachingWeeks} 个教学周，个人考试时间以排考为准`, isPassed: today > semester.teachingEndDate }
  ];
});
</script>
