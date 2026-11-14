<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="showLoginPrompt" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="program" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1.5">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以核查您的培养方案与毕业学分完成度</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <div class="flex flex-wrap items-center justify-between gap-2 text-[13px] text-[var(--ink-secondary)]">
        <span>基于教务培养方案与已修成绩单动态核对 · {{ earnedCreditsNote }}</span>
        <span class="text-[var(--ink-muted)] num-tabular shrink-0">上次同步：{{ lastSyncText }}</span>
      </div>

      <!-- 总学分完成度概览 -->
      <UiCard>
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="min-w-0">
            <div class="text-[13px] text-[var(--ink-muted)] font-medium">毕业方案总学分达成进度</div>
            <div class="text-[32px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-1.5">
              {{ creditsProgress.earnedTotal }} <span class="text-sm font-normal text-[var(--ink-muted)]">/ {{ creditsProgress.requiredTotal || '待同步' }} 学分</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] mt-2 break-words">
              学生专业：<strong class="font-medium text-[var(--ink-primary)]">{{ userProfile.college || '—' }} · {{ userProfile.major || '—' }} ({{ gradeLabel }})</strong>
            </div>
          </div>

          <div class="text-left sm:text-right shrink-0">
            <div class="text-[28px] leading-tight font-bold text-[var(--accent)] num-tabular">{{ creditsProgress.completionPercent }}%</div>
            <div class="text-[13px] text-[var(--ink-muted)] mt-1">总学分达成率</div>
          </div>
        </div>

        <!-- 总进度条 -->
        <div class="w-full h-3 rounded-full bg-[var(--bg-subtle)] overflow-hidden mt-5">
          <div
            class="h-full rounded-full transition-[width] duration-500 ease-out"
            :class="creditsProgress.completionPercent >= 100 ? 'bg-[var(--sem-success)]' : 'bg-[var(--accent)]'"
            :style="{ width: `${creditsProgress.completionPercent}%` }"
          />
        </div>
      </UiCard>

      <!-- 加载或空态 -->
      <div v-if="syncing && categories.length === 0" class="p-14 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] text-center text-[13px] text-[var(--ink-muted)]">
        <Icon name="refresh" customClass="w-6 h-6 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
        正在拉取全套教学计划并计算课组完成度...
      </div>

      <div v-else-if="categories.length === 0" class="p-14 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] text-center text-[13px] text-[var(--ink-muted)]">
        暂未获取到培养方案数据，请点击顶栏「同步数据」手动拉取。
      </div>

      <!-- 课组达标明细 + 可展开组内课程列表 -->
      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-5 items-start">
        <UiCard
          v-for="cat in categories"
          :key="cat.name"
          :title="cat.name"
        >
          <template #header-action>
            <UiBadge
              size="sm"
              :variant="cat.required > 0 && cat.earned >= cat.required ? 'success' : 'default'"
              :dot="cat.required > 0 && cat.earned >= cat.required"
            >
              {{ cat.required > 0 && cat.earned >= cat.required ? '已达标' : (cat.required > 0 ? '修读中' : '未设要求') }}
            </UiBadge>
          </template>

          <div class="space-y-3.5">
            <div class="flex justify-between items-baseline text-[13px]">
              <span class="text-[var(--ink-muted)]">学分进度</span>
              <span class="text-sm font-semibold text-[var(--ink-primary)] num-tabular">
                {{ cat.earned }} / {{ cat.required }} 学分
              </span>
            </div>

            <div class="w-full h-2.5 rounded-full bg-[var(--bg-subtle)] overflow-hidden">
              <div
                :class="[
                  'h-full rounded-full transition-[width] duration-500 ease-out',
                  cat.required > 0 && cat.earned >= cat.required ? 'bg-[var(--sem-success)]' : 'bg-[var(--accent)]'
                ]"
                :style="{ width: `${cat.required ? Math.min(100, Math.round((cat.earned / cat.required) * 100)) : (cat.earned > 0 ? 100 : 0)}%` }"
              />
            </div>

            <div class="flex flex-wrap justify-between gap-2 text-[13px] text-[var(--ink-secondary)] pt-0.5 num-tabular">
              <span>
                要求修读：{{ cat.required }} 学分<span v-if="cat.electiveCandidates !== null"> · {{ cat.electiveCandidates > 0 ? cat.electiveCandidates + ' 选 4' : '需选 4 门' }}</span>
              </span>
              <span :class="cat.required > 0 && cat.earned >= cat.required ? 'text-[var(--sem-success)] font-medium' : ''">
                已获通过：{{ cat.earned }} 学分
              </span>
            </div>

            <div v-if="creditsProgress.requiredOnly && isElectiveCategory(cat.property)" class="text-xs text-[var(--ink-muted)] leading-relaxed">
              选修类课程学分不计入“已获得学分”，此处仅展示课组要求
            </div>

            <!-- 利用已解析的组内课程数据提供展开核查 -->
            <div v-if="coursesForCategory(cat.name).length > 0" class="pt-2.5 border-t border-[var(--border-hairline)]">
              <button
                type="button"
                class="w-full flex items-center justify-between py-1 text-[13px] font-medium text-[var(--accent)] hover:underline cursor-pointer select-none"
                @click="toggleExpand(cat.name)"
              >
                <span>{{ expandedCategories.has(cat.name) ? '收起课组课程明细' : `查看课组内课程（${coursesForCategory(cat.name).length} 门）` }}</span>
                <Icon
                  name="chevron-down"
                  customClass="w-4 h-4 transition-transform duration-200"
                  :class="expandedCategories.has(cat.name) ? 'rotate-180' : ''"
                />
              </button>

              <div v-if="expandedCategories.has(cat.name)" class="panel-expand mt-2.5 divide-y divide-[var(--border-hairline)] border-t border-[var(--border-hairline)] max-h-64 overflow-y-auto">
                <div
                  v-for="course in coursesForCategory(cat.name)"
                  :key="(course.code || '') + course.name"
                  class="py-2.5 flex items-center justify-between gap-2 text-[13px]"
                >
                  <div class="min-w-0 flex-1">
                    <div class="text-sm font-medium text-[var(--ink-primary)] truncate">{{ course.name }}</div>
                    <div class="text-xs text-[var(--ink-muted)] font-mono num-tabular truncate mt-0.5">
                      {{ course.code || '无代码' }}<span v-if="course.property"> · {{ course.property }}</span>
                    </div>
                  </div>
                  <div class="shrink-0 flex items-center gap-2.5 num-tabular">
                    <span class="text-[var(--ink-secondary)]">{{ course.credit }} 学分</span>
                    <UiBadge
                      size="sm"
                      :variant="isCoursePassed(course) ? 'success' : 'default'"
                    >
                      {{ isCoursePassed(course) ? '已通过' : '未修/未过' }}
                    </UiBadge>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </UiCard>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import {
  computeCreditsProgress,
  dedupeScores,
  resolveProperty,
  EARNED_CREDITS_NOTE
} from '@/services/academic/stats.js';

const { isLoggedIn, isSessionExpired, userProfile, openLoginModal } = useSession();
const { scores, plan, syncing, lastSyncText } = useAcademicData();

const showLoginPrompt = computed(() => !isLoggedIn.value && !isSessionExpired.value);
const earnedCreditsNote = EARNED_CREDITS_NOTE;

const gradeLabel = computed(() => {
  const g = userProfile.grade;
  return g ? String(g).trim().replace(/(?:\s*级)+$/, '') + '级' : '—';
});

const creditsProgress = computed(() =>
  computeCreditsProgress(scores.value, plan.value?.groups || [], { planTotalCredits: plan.value?.totalRequiredCredits })
);
const categories = computed(() => creditsProgress.value.categories);

const groupCoursesMap = computed(() => {
  const map = new Map();
  for (const g of plan.value?.groups || []) {
    if (g?.name && Array.isArray(g.courses) && g.courses.length > 0) {
      map.set(g.name, g.courses);
    }
  }
  return map;
});

const passedKeys = computed(() => {
  const codes = new Set();
  const names = new Set();
  for (const s of dedupeScores(scores.value || [])) {
    if (!s.passed) continue;
    if (s.courseId) codes.add(String(s.courseId).trim());
    if (s.courseName) names.add(String(s.courseName).trim());
  }
  return { codes, names };
});

function coursesForCategory(name) {
  return groupCoursesMap.value.get(name) || [];
}

function isCoursePassed(course) {
  if (course.code && passedKeys.value.codes.has(String(course.code).trim())) return true;
  if (course.name && passedKeys.value.names.has(String(course.name).trim())) return true;
  return false;
}

const expandedCategories = ref(new Set());
function toggleExpand(name) {
  const next = new Set(expandedCategories.value);
  if (next.has(name)) next.delete(name);
  else next.add(name);
  expandedCategories.value = next;
}

function isElectiveCategory(property) {
  const p = resolveProperty(property);
  return p === 'limited' || p === 'elective';
}
</script>
