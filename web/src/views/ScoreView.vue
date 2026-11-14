<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="showLoginPrompt" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="score" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1.5">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以查询您的全部学期成绩与GPA分析</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <!-- 数据口径概要 -->
      <div class="flex flex-wrap items-center justify-between gap-2 text-[13px] text-[var(--ink-secondary)]">
        <div class="num-tabular">
          共 <strong class="font-semibold text-[var(--ink-primary)]">{{ rawScores.length }}</strong> 条记录，
          去重后 <strong class="font-semibold text-[var(--ink-primary)]">{{ stats.courseCount }}</strong> 门必修课
          <span class="mx-2 text-[var(--border-strong)]">·</span>
          <span class="text-[var(--ink-muted)]">{{ statsScopeNote }}</span>
        </div>
        <div class="text-[var(--ink-muted)] num-tabular shrink-0">上次同步：{{ lastSyncText }}</div>
      </div>

      <!-- 顶部 4 栏关键指标（修复 3+1 落单排版） -->
      <div class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4 sm:gap-5">
        <UiCard hoverable>
          <div class="text-[13px] text-[var(--ink-muted)] font-medium">平均学分绩点 (五分制 GPA)</div>
          <div class="text-[32px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-2">{{ stats.gpa.toFixed(2) }}</div>
          <div class="text-[13px] text-[var(--ink-muted)] mt-2">60 分 = 1 绩点，每分 +0.1</div>
        </UiCard>

        <UiCard hoverable>
          <div class="text-[13px] text-[var(--ink-muted)] font-medium">加权平均分</div>
          <div class="text-[32px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-2">{{ stats.weightedAvg.toFixed(1) }}</div>
          <div class="text-[13px] text-[var(--sem-success)] mt-2 num-tabular font-medium">优秀率 {{ stats.excellentRate }}%</div>
        </UiCard>

        <UiCard hoverable>
          <div class="text-[13px] text-[var(--ink-muted)] font-medium">已获得学分 / 修读总学分</div>
          <div class="text-[32px] leading-tight font-bold text-[var(--ink-primary)] num-tabular mt-2">
            {{ stats.earnedCredits }} <span class="text-sm font-normal text-[var(--ink-muted)]">/ {{ stats.totalCredits }}</span>
          </div>
          <div class="text-[13px] text-[var(--ink-muted)] mt-2 leading-snug">{{ earnedCreditsNote }}</div>
        </UiCard>

        <UiCard hoverable>
          <div class="text-[13px] text-[var(--ink-muted)] font-medium">不及格课程门数</div>
          <div
            :class="[
              'text-[32px] leading-tight font-bold num-tabular mt-2',
              stats.failedCount > 0 ? 'text-[var(--sem-danger)]' : 'text-[var(--ink-primary)]'
            ]"
          >
            {{ stats.failedCount }}
          </div>
          <div class="text-[13px] text-[var(--ink-muted)] mt-2 num-tabular">
            累计挂科 {{ stats.failedCredits }} 学分
          </div>
        </UiCard>
      </div>

      <!-- 特色学业算法：横向无叠框三栏索引 -->
      <UiCard title="特色学业算法（哈理工口径）" bodyClass="p-0">
        <template #header-action>
          <span class="text-[13px] text-[var(--ink-muted)]">门槛以学校教务处当期文件为准</span>
        </template>

        <div class="grid grid-cols-1 md:grid-cols-3 divide-y md:divide-y-0 md:divide-x divide-[var(--border-hairline)]">
          <!-- ① 学位证算法 -->
          <div class="p-6 space-y-2.5">
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-semibold text-[var(--ink-primary)]">学位证算法</span>
              <UiBadge size="sm" :variant="stats.degree.qualified ? 'success' : 'warning'">
                {{ stats.degree.qualified ? '达标' : '未达标' }}
              </UiBadge>
            </div>
            <div class="text-[26px] leading-tight font-bold text-[var(--ink-primary)] num-tabular">{{ stats.degree.gpa.toFixed(2) }}</div>
            <div class="text-[13px] text-[var(--ink-secondary)] leading-relaxed num-tabular">
              学业课 + E 类最高一门 + 剩余 A–E 类最高一门，门槛 {{ stats.degree.threshold.toFixed(1) }}
            </div>
            <div class="text-[13px]" :class="stats.degree.allPassed ? 'text-[var(--sem-success)]' : 'text-[var(--sem-danger)]'">
              0 学分及其余通识选修不参与加权
            </div>
            <div class="pt-1">
              <GpaCalculationHelp />
            </div>
          </div>

          <!-- ② 推免资格自检 -->
          <div class="p-6 space-y-2.5">
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-semibold text-[var(--ink-primary)]">推免资格自检</span>
              <UiBadge size="sm" :variant="stats.recommend.retakeWithinLimit ? 'success' : 'warning'">
                {{ stats.recommend.retakeWithinLimit ? '良好' : '超过上限' }}
              </UiBadge>
            </div>
            <div class="text-[26px] leading-tight font-bold text-[var(--ink-primary)] num-tabular">
              {{ stats.recommend.retakeCount }} <span class="text-sm font-normal text-[var(--ink-muted)]">/ {{ stats.recommend.retakeLimit }}</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] leading-relaxed num-tabular">
              仅统计必修课的补考 + 重修累计门数，上限 {{ stats.recommend.retakeLimit }} 门
            </div>
            <div class="text-[13px]" :class="stats.recommend.courseCount === 0 ? 'text-[var(--ink-muted)]' : stats.recommend.allPassed ? 'text-[var(--sem-success)]' : 'text-[var(--sem-danger)]'">
              {{ stats.recommend.courseCount === 0 ? '暂无必修成绩记录' : stats.recommend.allPassed ? '必修课成绩全部合格' : '存在不合格的必修课' }}
            </div>
          </div>

          <!-- ③ 学业风险预警 -->
          <div class="p-6 space-y-2.5">
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-semibold text-[var(--ink-primary)]">学业风险预警</span>
              <UiBadge
                size="sm"
                :variant="stats.risk.level === 'none' ? 'success' : 'danger'"
              >
                {{ stats.risk.label }}
              </UiBadge>
            </div>
            <div
              class="text-[26px] leading-tight font-bold num-tabular"
              :class="stats.risk.level === 'none' ? 'text-[var(--ink-primary)]' : 'text-[var(--sem-danger)]'"
            >
              {{ stats.risk.failedCredits }} <span class="text-sm font-normal text-[var(--ink-muted)]">学分</span>
            </div>
            <div class="text-[13px] text-[var(--ink-secondary)] leading-relaxed">
              {{ stats.risk.description }}
            </div>
          </div>
        </div>
      </UiCard>

      <!-- 走势图与成绩分布 -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-5 sm:gap-6">
        <!-- 各学期 GPA 走向趋势（可交互探查点位） -->
        <UiCard title="各学期 GPA 走向趋势（五分制 · 仅必修课）" class="lg:col-span-2">
          <template v-if="activeTrendPoint" #header-action>
            <div class="text-[13px] text-[var(--ink-secondary)] num-tabular bg-[var(--bg-subtle)] px-3 py-1 rounded-full border border-[var(--border-hairline)]">
              {{ activeTrendPoint.label }} · GPA <strong class="text-[var(--accent)] font-semibold">{{ activeTrendPoint.val }}</strong>（{{ activeTrendPoint.count }} 门 / {{ activeTrendPoint.credits }} 学分）
            </div>
          </template>

          <div v-if="trendPoints.length === 0" class="h-52 flex items-center justify-center text-[13px] text-[var(--ink-muted)]">
            暂无足够的学期数据生成走势曲线
          </div>
          <div v-else class="h-58 w-full flex flex-col justify-end">
            <svg class="w-full h-50 overflow-visible" viewBox="0 0 540 180">
              <defs>
                <linearGradient id="gpaAreaFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.24" />
                  <stop offset="100%" stop-color="var(--accent)" stop-opacity="0.01" />
                </linearGradient>
              </defs>

              <!-- 水平刻度格线 -->
              <g v-for="tick in yTicks" :key="tick.label">
                <line
                  x1="44"
                  :y1="tick.y"
                  x2="516"
                  :y2="tick.y"
                  stroke="var(--border-hairline)"
                  :stroke-dasharray="tick.label === '1.0' ? undefined : '4 4'"
                />
                <text x="36" :y="tick.y + 4" text-anchor="end" class="text-xs num-tabular" fill="var(--ink-muted)">{{ tick.label }}</text>
              </g>

              <!-- 面积填充 -->
              <polygon
                v-if="trendPoints.length > 1"
                class="chart-area-reveal"
                :points="trendAreaPolygon"
                fill="url(#gpaAreaFill)"
              />

              <!-- 折线 -->
              <polyline
                v-if="trendPoints.length > 1"
                class="chart-line-draw"
                :points="trendPolyline"
                fill="none"
                stroke="var(--accent)"
                stroke-width="2.75"
                stroke-linecap="round"
                stroke-linejoin="round"
              />

              <!-- 数据点与交互 -->
              <g
                v-for="(p, i) in trendPoints"
                :key="p.label"
                class="cursor-pointer outline-none"
                tabindex="0"
                :aria-label="`${p.label} GPA ${p.val}`"
                @mouseenter="hoveredTrendIndex = i"
                @focus="hoveredTrendIndex = i"
                @mouseleave="hoveredTrendIndex = -1"
              >
                <circle :cx="p.x" :cy="p.y" r="15" fill="transparent" />
                <circle
                  v-if="activeTrendIndex === i"
                  :cx="p.x"
                  :cy="p.y"
                  r="11"
                  fill="var(--accent-soft)"
                />
                <circle
                  :cx="p.x"
                  :cy="p.y"
                  :r="activeTrendIndex === i ? 6 : 4.5"
                  fill="var(--bg-surface)"
                  stroke="var(--accent)"
                  :stroke-width="activeTrendIndex === i ? 3 : 2.25"
                  class="transition-all duration-150"
                />
                <text
                  :x="p.x"
                  :y="p.y - 13"
                  text-anchor="middle"
                  class="text-xs font-semibold num-tabular transition-colors duration-150"
                  :fill="activeTrendIndex === i ? 'var(--accent)' : 'var(--ink-primary)'"
                >
                  {{ p.val }}
                </text>
                <text x="p.x" y="168" text-anchor="middle" class="text-xs num-tabular" fill="var(--ink-muted)">
                  {{ p.label }}
                </text>
              </g>
            </svg>
          </div>
        </UiCard>

        <!-- 必修课成绩分段分布 -->
        <UiCard title="必修课成绩分段分布">
          <div class="h-58 flex flex-col justify-center space-y-4">
            <div v-for="seg in distribution" :key="seg.label">
              <div class="flex justify-between text-[13px] mb-1.5">
                <span class="text-[var(--ink-secondary)] font-medium">{{ seg.label }}</span>
                <span class="text-[var(--ink-primary)] font-semibold num-tabular">{{ seg.count }} 门 ({{ seg.percent }}%)</span>
              </div>
              <div class="w-full h-2.5 rounded-full bg-[var(--bg-subtle)] overflow-hidden">
                <div
                  class="h-full rounded-full transition-[width] duration-500 ease-out"
                  :class="seg.colorClass"
                  :style="{ width: `${seg.percent}%` }"
                />
              </div>
            </div>
          </div>
        </UiCard>
      </div>

      <!-- 搜索与筛选控制区 -->
      <UiCard bodyClass="p-4 sm:p-5">
        <div class="flex flex-col lg:flex-row lg:items-center justify-between gap-3.5">
          <div class="flex flex-wrap items-center gap-3">
            <div class="w-full sm:w-68">
              <UiInput
                v-model="searchQuery"
                placeholder="搜索课程名或课号..."
                size="sm"
                clearable
              >
                <template #prefix>
                  <Icon name="search" customClass="w-4 h-4" />
                </template>
              </UiInput>
            </div>

            <div class="flex items-center gap-2">
              <span class="text-[13px] text-[var(--ink-muted)] shrink-0">学期：</span>
              <UiSelect
                v-model="selectedSemester"
                :items="semesterItems"
                aria-label="学期筛选"
                size="sm"
                custom-class="min-w-[8.5rem]"
              />
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2.5">
            <UiTabs
              :items="[
                { label: '全部属性', value: 'all' },
                { label: '必修', value: 'required' },
                { label: '限选', value: 'limited' },
                { label: '任选', value: 'elective' },
                { label: '其它', value: 'other' }
              ]"
              v-model="selectedProperty"
            />

            <UiTabs
              :items="[
                { label: '全部状态', value: 'all' },
                { label: '仅及格', value: 'passed' },
                { label: '未通过', value: 'failed' }
              ]"
              v-model="selectedPassStatus"
            />
          </div>
        </div>
      </UiCard>

      <!-- 成绩总表 -->
      <div class="rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] overflow-x-auto">
        <div v-if="syncing && scores.length === 0" class="p-12 text-center text-[13px] text-[var(--ink-muted)]">
          <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2 text-[var(--accent)]" />
          正在拉取教务成绩单...
        </div>
        <table v-else class="w-full min-w-[1040px] text-left border-collapse text-[13px]">
          <thead>
            <tr class="border-b border-[var(--border-hairline)] bg-[var(--bg-subtle)]/75 text-[var(--ink-secondary)] font-semibold">
              <th class="whitespace-nowrap p-4 pl-6">学年 / 学期</th>
              <th class="whitespace-nowrap p-4">课程代码</th>
              <th class="whitespace-nowrap p-4">课程名称</th>
              <th class="whitespace-nowrap p-4">课组</th>
              <th class="whitespace-nowrap p-4 text-center">学分</th>
              <th class="whitespace-nowrap p-4 text-center">绩点</th>
              <th class="whitespace-nowrap p-4">属性</th>
              <th class="whitespace-nowrap p-4">考试性质</th>
              <th class="whitespace-nowrap p-4 pr-6 text-right font-semibold text-[var(--ink-primary)]">总评成绩</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-[var(--border-hairline)]">
            <tr
              v-for="row in filteredScores"
              :key="row.rowKey"
              class="hover:bg-[var(--bg-subtle)]/55 transition-colors"
            >
              <td class="p-4 pl-6 text-[var(--ink-secondary)] whitespace-nowrap num-tabular">
                <div>{{ row.year }} ({{ row.term }})</div>
                <div v-if="row.recordCount > 1" class="text-xs text-[var(--ink-muted)] mt-0.5">
                  共 {{ row.recordCount }} 次记录 · 已合并
                </div>
              </td>
              <td class="p-4 font-mono text-[var(--ink-secondary)] num-tabular">{{ row.courseId }}</td>
              <td class="p-4 text-sm font-medium text-[var(--ink-primary)]">
                {{ row.courseName }}
                <UiBadge v-if="row.isRetake" size="sm" variant="outline" class="ml-1.5">补考/重修</UiBadge>
              </td>
              <td class="p-4 text-[var(--ink-secondary)]">{{ row.courseGroup || '—' }}</td>
              <td class="p-4 text-center font-medium num-tabular text-[var(--ink-primary)]">{{ row.credit }}</td>
              <td class="p-4 text-center font-mono num-tabular text-[var(--ink-secondary)]">{{ row.gradePoint.toFixed(1) }}</td>
              <td class="p-4">
                <UiBadge size="sm" :variant="isRequired(row.property) ? 'default' : 'outline'">{{ row.property }}</UiBadge>
              </td>
              <td class="p-4 text-[var(--ink-secondary)] whitespace-nowrap">{{ row.examType }}</td>
              <td class="p-4 pr-6 text-right whitespace-nowrap">
                <span
                  :class="[
                    'px-3 py-1 rounded-full text-[13px] font-semibold num-tabular inline-block',
                    getScoreClass(row.score)
                  ]"
                >
                  {{ row.score }}
                </span>
              </td>
            </tr>
            <tr v-if="filteredScores.length === 0">
              <td colspan="9" class="p-10 text-center text-[13px] text-[var(--ink-muted)]">
                未找到匹配的课程成绩记录
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import GpaCalculationHelp from '@/components/GpaCalculationHelp.vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import {
  buildAcademicStats,
  countedCourses,
  dedupeScores,
  gpaOf,
  hasCredits,
  gradePoint,
  isRequired,
  parseScoreValue,
  resolveProperty,
  semesterSortKey,
  EARNED_CREDITS_NOTE,
  STATS_SCOPE_NOTE
} from '@/services/academic/stats.js';

const { isLoggedIn, isSessionExpired, openLoginModal } = useSession();
const { scores: rawScores, syncing, lastSyncText } = useAcademicData();

const searchQuery = ref('');
const selectedSemester = ref('all');
const selectedProperty = ref('all');
const selectedPassStatus = ref('all');
const hoveredTrendIndex = ref(-1);

const showLoginPrompt = computed(() => !isLoggedIn.value && !isSessionExpired.value);

const earnedCreditsNote = EARNED_CREDITS_NOTE;
const statsScopeNote = STATS_SCOPE_NOTE;

const scores = computed(() => {
  return dedupeScores(rawScores.value).map(item => ({
    ...item,
    rowKey: `${item.courseId || item.courseName}-${item.courseSeq || ''}-${item.year}${item.term}`,
    propertyKey: resolveProperty(item.property),
    gradePoint: gradePoint(item.score)
  }));
});

const stats = computed(() => buildAcademicStats(rawScores.value));

const countedRecords = computed(() => countedCourses(rawScores.value));

const yTicks = [
  { label: '5.0', y: 24 },
  { label: '4.0', y: 54 },
  { label: '3.0', y: 84 },
  { label: '2.0', y: 114 },
  { label: '1.0', y: 144 }
];

const trendPoints = computed(() => {
  const termMap = new Map();

  countedRecords.value.forEach(s => {
    const key = `${s.year} ${s.term}`;
    if (!termMap.has(key)) termMap.set(key, { label: key, year: s.year, term: s.term, list: [] });
    termMap.get(key).list.push(s);
  });

  const sortedTerms = Array.from(termMap.values())
    .filter(({ list }) => hasCredits(list))
    .sort((a, b) => semesterSortKey(a.year, a.term) - semesterSortKey(b.year, b.term))
    .slice(-6);
  if (sortedTerms.length === 0) return [];

  const left = 64;
  const width = 432;
  const step = sortedTerms.length > 1 ? width / (sortedTerms.length - 1) : 0;

  return sortedTerms.map(({ label, list }, idx) => {
    const gpaVal = gpaOf(list).toFixed(2);
    const num = parseFloat(gpaVal);
    const clamped = Math.max(1.0, Math.min(5.0, num));
    const y = 144 - ((clamped - 1.0) / 4.0) * 120;
    const x = sortedTerms.length === 1 ? 280 : left + idx * step;
    const credits = list.reduce((sum, item) => sum + (Number(item.credit) || 0), 0);
    return { label, val: gpaVal, x: Math.round(x), y: Math.round(y), count: list.length, credits: Number(credits.toFixed(1)) };
  });
});

const activeTrendIndex = computed(() => {
  if (trendPoints.value.length === 0) return -1;
  if (hoveredTrendIndex.value >= 0 && hoveredTrendIndex.value < trendPoints.value.length) {
    return hoveredTrendIndex.value;
  }
  return trendPoints.value.length - 1;
});

const activeTrendPoint = computed(() =>
  activeTrendIndex.value >= 0 ? trendPoints.value[activeTrendIndex.value] : null
);

const trendPolyline = computed(() => {
  return trendPoints.value.map(p => `${p.x},${p.y}`).join(' ');
});

const trendAreaPolygon = computed(() => {
  if (trendPoints.value.length < 2) return '';
  const first = trendPoints.value[0];
  const last = trendPoints.value[trendPoints.value.length - 1];
  return `${first.x},144 ${trendPolyline.value} ${last.x},144`;
});

const distribution = computed(() => {
  const scored = countedRecords.value.filter(s => parseScoreValue(s.score).estimated !== null);
  const total = scored.length || 1;
  const segs = { '90分及以上 (优秀)': 0, '80-89分 (良好)': 0, '70-79分 (中等)': 0, '60-69分 (及格)': 0, '60分以下 (不及格)': 0 };
  scored.forEach(s => {
    const n = parseFloat(s.score);
    if (n >= 90 || s.score === '优秀' || s.score === '优') segs['90分及以上 (优秀)'] += 1;
    else if (n >= 80 || s.score === '良好' || s.score === '良') segs['80-89分 (良好)'] += 1;
    else if (n >= 70 || s.score === '中等' || s.score === '中') segs['70-79分 (中等)'] += 1;
    else if (n >= 60 || s.score === '及格' || s.score === '合格') segs['60-69分 (及格)'] += 1;
    else segs['60分以下 (不及格)'] += 1;
  });

  return [
    { label: '90分及以上 (优秀)', count: segs['90分及以上 (优秀)'], percent: Math.round((segs['90分及以上 (优秀)'] / total) * 100), colorClass: 'bg-[var(--accent)]' },
    { label: '80-89分 (良好)', count: segs['80-89分 (良好)'], percent: Math.round((segs['80-89分 (良好)'] / total) * 100), colorClass: 'bg-[var(--accent)]/75' },
    { label: '70-79分 (中等)', count: segs['70-79分 (中等)'], percent: Math.round((segs['70-79分 (中等)'] / total) * 100), colorClass: 'bg-[var(--accent)]/50' },
    { label: '60-69分 (及格)', count: segs['60-69分 (及格)'], percent: Math.round((segs['60-69分 (及格)'] / total) * 100), colorClass: 'bg-[var(--ink-muted)]/55' },
    { label: '60分以下 (不及格)', count: segs['60分以下 (不及格)'], percent: Math.round((segs['60分以下 (不及格)'] / total) * 100), colorClass: 'bg-[var(--sem-danger)]' }
  ];
});

const semesterItems = computed(() => {
  const map = new Map();
  scores.value.forEach(item => {
    const key = `${item.year} ${item.term}`;
    if (!map.has(key)) map.set(key, { value: key, label: key, year: item.year, term: item.term });
  });
  const list = Array.from(map.values())
    .sort((a, b) => semesterSortKey(b.year, b.term) - semesterSortKey(a.year, a.term))
    .map(({ value, label }) => ({ value, label }));
  return [{ value: 'all', label: '全部学期' }, ...list];
});

watch(semesterItems, items => {
  if (selectedSemester.value !== 'all' && !items.some(i => i.value === selectedSemester.value)) {
    selectedSemester.value = 'all';
  }
});

const filteredScores = computed(() => {
  return scores.value.filter(item => {
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase();
      const matchName = (item.courseName || '').toLowerCase().includes(q);
      const matchId = (item.courseId || '').toLowerCase().includes(q);
      if (!matchName && !matchId) return false;
    }
    if (selectedSemester.value !== 'all' && `${item.year} ${item.term}` !== selectedSemester.value) {
      return false;
    }
    if (selectedProperty.value !== 'all' && item.propertyKey !== selectedProperty.value) {
      return false;
    }
    if (selectedPassStatus.value === 'passed' && !item.passed) return false;
    if (selectedPassStatus.value === 'failed' && item.passed) return false;

    return true;
  });
});

// 严格保留：低于 70 分带 text-rose-* 语义类，70 分及以上绝不带 text-rose-*
function getScoreClass(score) {
  const n = parseFloat(score);
  if (!isNaN(n)) {
    if (n >= 90) return 'bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)]';
    if (n >= 80) return 'bg-[var(--bg-subtle)] text-[var(--ink-primary)]';
    if (n >= 70) return 'bg-[var(--bg-subtle)] text-[var(--ink-secondary)]';
    return 'bg-[var(--sem-danger-soft)] text-rose-600 dark:text-rose-400';
  }
  if (score === '优秀' || score === '优') return 'bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)]';
  if (score === '良好' || score === '良') return 'bg-[var(--bg-subtle)] text-[var(--ink-primary)]';
  if (score === '中等' || score === '中') return 'bg-[var(--bg-subtle)] text-[var(--ink-secondary)]';
  return 'bg-[var(--sem-danger-soft)] text-rose-600 dark:text-rose-400';
}
</script>
