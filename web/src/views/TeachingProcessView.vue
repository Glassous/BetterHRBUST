<template>
  <div class="space-y-6">
    <!-- 工具栏：学期 / 校区 -->
    <UiCard title="本科教学进程" subtitle="教务处制 · 按学期、校区与阶段重新整理">
      <template #header-action>
        <a
          :href="source"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--accent)] hover:underline"
        >
          <span class="hidden sm:inline">查看教务处原表</span>
          <span class="sm:hidden">原表</span>
          <Icon name="external-link" customClass="w-3.5 h-3.5" />
        </a>
      </template>

      <div class="flex flex-wrap items-center gap-2.5">
        <div class="flex min-w-0 items-center gap-2.5">
          <UiButton
            size="sm"
            variant="outline"
            aria-label="更早的学期"
            :disabled="semesterIndex >= semesters.length - 1"
            @click="stepSemester(1)"
          >
            <Icon name="chevron-left" customClass="w-4 h-4" />
          </UiButton>

          <UiSelect
            v-model="semesterModel"
            :items="semesterItems"
            aria-label="选择学期"
            custom-class="min-w-[8.5rem] flex-1 sm:min-w-[13rem] sm:flex-none"
          />

          <UiButton
            size="sm"
            variant="outline"
            aria-label="更新的学期"
            :disabled="semesterIndex <= 0"
            @click="stepSemester(-1)"
          >
            <Icon name="chevron-right" customClass="w-4 h-4" />
          </UiButton>
        </div>

        <UiButton v-if="!isCurrentSemester" size="sm" variant="ghost" @click="goToCurrentSemester">
          回到本学期
        </UiButton>

        <UiTabs v-if="campusItems.length > 1" v-model="campusModel" :items="campusItems" />
      </div>

      <template #footer>
        <div class="flex flex-wrap items-center justify-between gap-2 text-[12px] text-[var(--ink-muted)] num-tabular">
          <span>{{ semesterSpan }} · {{ weekCount }} 周 · {{ campuses.length }} 校区 / {{ totalClasses }} 个班</span>
          <span>
            原站更新 {{ freshness.sourceUpdatedAt || '未标注' }} · 本页整理 {{ freshness.updatedAt || '未标注' }}
            <span v-if="freshness.stale" class="ml-2 rounded-[var(--radius-badge)] bg-[var(--sem-warning-soft)] px-1.5 py-0.5 text-[var(--sem-warning)]">
              可能已过期，请以原表为准
            </span>
          </span>
        </div>
        <p v-if="calendarBehind" class="mt-1.5 text-[12px] text-[var(--sem-warning)]">
          校历已进入新学期，本页尚未收录新学期的进程表
        </p>
      </template>
    </UiCard>

    <!-- 主视觉：我这个班现在处于什么阶段 -->
    <UiCard v-if="heroEntry" body-class="p-0">
      <!-- 推测身份时先请用户确认，确认后写入本地 -->
      <div
        v-if="heroIsGuess"
        class="flex flex-wrap items-center justify-between gap-2 rounded-t-[var(--radius-card)] border-b border-[var(--accent-border)] bg-[var(--accent-soft)]/60 px-5 py-2.5 text-[12px] sm:px-6"
      >
        <span class="text-[var(--ink-secondary)]">
          根据学籍档案推测你是
          <span class="font-semibold text-[var(--ink-primary)]">{{ heroEntry.name }}</span>
          <span class="text-[var(--ink-muted)]">（{{ heroEntry.campus }}）</span>
        </span>
        <span class="flex items-center gap-1">
          <button
            type="button"
            class="cursor-pointer rounded-[var(--radius-badge)] px-2 py-0.5 font-medium text-[var(--accent)] hover:bg-[var(--accent-soft)]"
            @click="confirmGuess"
          >
            是我的班级
          </button>
          <button
            type="button"
            class="cursor-pointer rounded-[var(--radius-badge)] px-2 py-0.5 text-[var(--ink-secondary)] hover:bg-[var(--bg-subtle)]"
            @click="openPicker"
          >
            换一个
          </button>
        </span>
      </div>

      <div class="px-5 pt-4 sm:px-6">
        <div class="flex items-center justify-between gap-2.5">
          <div class="flex min-w-0 flex-wrap items-center gap-2.5">
            <span class="text-[15px] font-semibold text-[var(--ink-primary)]">{{ heroEntry.name }}</span>
            <UiBadge size="sm" variant="default">{{ heroEntry.campus }}</UiBadge>
            <span class="text-[12px] text-[var(--ink-muted)]">周学时 {{ heroEntry.hours || '—' }}</span>
          </div>
          <UiButton v-if="!heroIsGuess" size="sm" variant="ghost" custom-class="shrink-0" @click="openPicker">
            换一个班级
          </UiButton>
        </div>

        <div class="mt-3 grid gap-5 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
          <!-- 现在处于 -->
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2 text-[12px]">
              <span
                v-if="currentWeek"
                class="inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-soft)] px-2.5 py-1 font-medium text-[var(--accent)] num-tabular"
              >
                <Icon name="process" customClass="w-3.5 h-3.5" />
                第 {{ currentWeek }} / {{ weekCount }} 周
              </span>
              <span class="text-[var(--ink-muted)]">{{ semester.label }}</span>
              <span v-if="weekSpanText" class="text-[var(--ink-muted)] num-tabular">{{ weekSpanText }}</span>
            </div>

            <h2 class="mt-2.5 text-[24px] font-bold leading-tight tracking-tight text-[var(--ink-primary)] sm:text-[28px]">
              {{ currentStageTitle }}
            </h2>
            <p class="mt-1.5 text-[13px] leading-relaxed text-[var(--ink-secondary)]">{{ currentStageDetail }}</p>
          </div>

          <!-- 三个一眼能读的指标 -->
          <dl v-if="currentWeek" class="grid grid-cols-3 gap-2 lg:w-[22rem]">
            <div
              v-for="metric in metrics"
              :key="metric.label"
              class="rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/45 px-2.5 py-2"
            >
              <dt class="text-[11px] text-[var(--ink-muted)]">{{ metric.label }}</dt>
              <dd class="mt-0.5 text-[15px] font-semibold leading-tight text-[var(--ink-primary)]">{{ metric.value }}</dd>
              <dd v-if="metric.hint" class="mt-0.5 text-[11px] leading-snug text-[var(--ink-muted)]">{{ metric.hint }}</dd>
            </div>
          </dl>
        </div>

        <!-- 整学期色带：向两侧多伸出一截，近乎通栏；阶段名在带子外面 -->
        <div class="mt-5 -mx-5 sm:-mx-6">
          <ProcessTimeline
            :segments="heroStages"
            :week-count="weekCount"
            :current-week="currentWeek"
            :first-date="firstMonday"
            :last-date="lastSunday"
            show-labels
          />
        </div>
      </div>

      <!-- 我这个班的学期阶段 -->
      <div class="mt-4 border-t border-[var(--border-hairline)] px-5 py-4 sm:px-6">
        <div class="mb-1.5 flex items-center justify-between">
          <h3 class="text-[13px] font-semibold text-[var(--ink-primary)]">我这学期的阶段</h3>
          <span class="text-[12px] text-[var(--ink-muted)]">{{ heroStages.length }} 段 · 共 {{ weekCount }} 周</span>
        </div>
        <ProcessStageList :segments="heroStages" :weeks="semester.weeks" :current-week="currentWeek" />
      </div>
    </UiCard>

    <UiCard v-else-if="myClass" title="我的班级">
      <p class="text-[13px] leading-relaxed text-[var(--ink-secondary)]">
        本学期的进程表里没有「{{ myClass.name }}」，可能该学期未收录这个班级，或班级已经调整。
      </p>
      <div class="mt-4 flex flex-wrap gap-2">
        <UiButton size="sm" variant="primary" @click="openPicker">换一个班级</UiButton>
        <UiButton size="sm" variant="ghost" @click="clearMyClass">清除</UiButton>
      </div>
    </UiCard>

    <!-- 未指定班级：先给出本校区本周的整体情况 -->
    <UiCard v-else title="现在处于什么阶段？">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div class="min-w-0">
          <div v-if="currentWeek" class="flex flex-wrap items-center gap-2 text-[12px] text-[var(--ink-muted)]">
            <span class="inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-soft)] px-2.5 py-1 font-medium text-[var(--accent)] num-tabular">
              <Icon name="process" customClass="w-3.5 h-3.5" />
              第 {{ currentWeek }} / {{ weekCount }} 周
            </span>
            <span>{{ campus?.name }} · {{ semester.label }}</span>
          </div>

          <h2 class="mt-2.5 text-[22px] font-bold leading-tight text-[var(--ink-primary)] sm:text-[26px]">
            {{ campusTopStage && currentWeek ? campusTopStage.label : '本学期还未开始' }}
          </h2>
          <p class="mt-1.5 text-[13px] leading-relaxed text-[var(--ink-secondary)]">
            <template v-if="campusTopStage && currentWeek">
              {{ campus?.name }}有 {{ campusTopStage.count }} 个班处于这个安排。指定你的班级，就能看到自己的军训、实习、考试与放假时间。
            </template>
            <template v-else>
              指定你的班级后，这里会显示你所在班级的教学阶段。数据来自教务处公开的本科教学进程表，不需要登录。
            </template>
          </p>

          <div v-if="weekSnapshot.length" class="mt-3 flex flex-wrap gap-1.5">
            <span
              v-for="item in weekSnapshot.slice(0, 5)"
              :key="item.key"
              class="inline-flex items-center gap-1.5 rounded-full border border-[var(--border-hairline)] px-2.5 py-1 text-[12px] text-[var(--ink-secondary)]"
            >
              <span :class="['h-2 w-2 rounded-full', stageDotClass(item.group)]" :style="stageDotStyle(item.group, item.key)"></span>
              {{ item.label }}
              <span class="font-semibold text-[var(--ink-primary)] num-tabular">{{ item.count }}</span>
            </span>
          </div>
        </div>

        <UiButton size="sm" variant="primary" @click="openPicker">指定我的班级</UiButton>
      </div>
    </UiCard>

    <!-- 全部班级：默认收起，需要横向比较时再打开 -->
    <UiCard body-class="p-0">
      <details class="group" @toggle="allOpen = $event.target.open">
        <summary
          class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 [&::-webkit-details-marker]:hidden sm:px-6"
        >
          <div>
            <h3 class="text-base font-semibold text-[var(--ink-primary)]">全部班级的进程</h3>
            <p class="mt-0.5 text-[13px] text-[var(--ink-muted)]">
              {{ patternCount }} 种进程 · {{ totalClasses }} 个班 · 按进度分组或逐班对照
            </p>
          </div>
          <Icon name="chevron-down" customClass="w-4 h-4 shrink-0 text-[var(--ink-muted)] transition-transform group-open:rotate-180" />
        </summary>

        <div v-if="allOpen" class="border-t border-[var(--border-hairline)]">
          <div class="flex flex-wrap items-center gap-3 px-5 py-3.5 sm:px-6">
            <div class="min-w-56 flex-1">
              <UiInput v-model="queryModel" aria-label="搜索班级" placeholder="搜索班级，例如：材型、软件、电气" clearable>
                <template #prefix>
                  <Icon name="search" customClass="w-4 h-4" />
                </template>
              </UiInput>
            </div>
            <UiTabs v-model="viewModel" :items="viewItems" />
            <span class="text-[12px] text-[var(--ink-muted)] num-tabular">
              {{ groups.length }} 组 · {{ filteredClasses.length }} 个班
            </span>
          </div>

          <p v-if="!filteredClasses.length" class="px-6 py-12 text-center text-[13px] text-[var(--ink-muted)]">
            没有匹配的班级，试试年级数字（如 26）或专业缩写（如 材型）。
          </p>

          <!-- 按进程 -->
          <div v-else-if="view === 'pattern'" class="divide-y divide-[var(--border-hairline)] border-t border-[var(--border-hairline)]">
            <details v-for="group in groups" :key="group.patternIndex" class="group">
              <summary
                class="flex cursor-pointer list-none items-center gap-3 px-5 py-3 transition-colors hover:bg-[var(--bg-subtle)]/50 [&::-webkit-details-marker]:hidden sm:px-6"
              >
                <div class="min-w-0 flex-1 space-y-1.5">
                  <ProcessTimeline compact :segments="group.segments" :week-count="weekCount" :current-week="currentWeek" />
                  <div class="truncate text-[12px] text-[var(--ink-muted)]">{{ group.summary }}</div>
                </div>
                <span class="shrink-0 text-[12px] text-[var(--ink-muted)] num-tabular">{{ group.classes.length }} 个班</span>
                <Icon name="chevron-down" customClass="w-4 h-4 shrink-0 text-[var(--ink-muted)] transition-transform group-open:rotate-180" />
              </summary>

              <div class="grid gap-5 border-t border-[var(--border-hairline)] bg-[var(--bg-subtle)]/30 px-5 py-4 md:grid-cols-2 sm:px-6">
                <ProcessStageList :segments="group.segments" :weeks="semester.weeks" :current-week="currentWeek" />
                <div>
                  <div class="mb-2 text-[12px] font-medium text-[var(--ink-muted)]">同进度的班级</div>
                  <div class="flex flex-wrap gap-1.5">
                    <span
                      v-for="item in group.classes"
                      :key="item.name"
                      :class="[
                        'rounded-[var(--radius-badge)] border px-2 py-0.5 text-[12px]',
                        item.name === heroEntry?.name
                          ? 'border-[var(--accent-border)] bg-[var(--accent-soft)] text-[var(--accent)] font-semibold'
                          : 'border-[var(--border-hairline)] bg-[var(--bg-surface)] text-[var(--ink-secondary)]'
                      ]"
                    >
                      {{ item.name }}
                    </span>
                  </div>
                </div>
              </div>
            </details>
          </div>

          <!-- 按班级 -->
          <div v-else class="divide-y divide-[var(--border-hairline)] border-t border-[var(--border-hairline)]">
            <div
              v-for="item in filteredClasses"
              :key="item.name"
              class="flex flex-wrap items-center gap-3 px-5 py-2.5 sm:px-6"
            >
              <span
                :class="[
                  'min-w-0 flex-1 truncate text-[13px] sm:w-40 sm:flex-none',
                  item.name === heroEntry?.name ? 'font-semibold text-[var(--accent)]' : 'text-[var(--ink-primary)]'
                ]"
              >
                {{ item.name }}
                <span v-if="item.name === heroEntry?.name" class="ml-1 text-[11px]">★</span>
              </span>
              <span class="w-14 shrink-0 text-[12px] text-[var(--ink-muted)] num-tabular">{{ item.hours || '—' }} 学时</span>
              <div class="min-w-56 flex-1">
                <ProcessTimeline compact :segments="segmentsOf(item.patternIndex)" :week-count="weekCount" :current-week="currentWeek" />
              </div>
              <span class="w-full text-[12px] text-[var(--ink-muted)] lg:w-auto lg:max-w-[24rem] lg:truncate">
                {{ summaryOf(segmentsOf(item.patternIndex)) }}
              </span>
            </div>
          </div>
        </div>
      </details>
    </UiCard>

    <!-- 全表对照：默认收起，需要逐格核对原表时再打开 -->
    <UiCard body-class="p-0">
      <details class="group" @toggle="rawOpen = $event.target.open">
        <summary
          class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 [&::-webkit-details-marker]:hidden sm:px-6"
        >
          <div>
            <h3 class="text-base font-semibold text-[var(--ink-primary)]">全表对照</h3>
            <p class="mt-0.5 text-[13px] text-[var(--ink-muted)]">
              与教务处原表逐格对应；「/」表示这一周跨两个阶段，斜杠前后各占半周
            </p>
          </div>
          <Icon name="chevron-down" customClass="w-4 h-4 shrink-0 text-[var(--ink-muted)] transition-transform group-open:rotate-180" />
        </summary>

        <div v-if="rawOpen" class="border-t border-[var(--border-hairline)] p-4 sm:p-5">
          <ProcessRawTable
            :classes="filteredClasses"
            :patterns="patterns"
            :weeks="semester.weeks"
            :current-week="currentWeek"
            :mine="heroEntry?.name || ''"
          />
        </div>
      </details>
    </UiCard>

    <!-- 符号说明：默认收起 -->
    <UiCard body-class="p-0">
      <details class="group">
        <summary
          class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 [&::-webkit-details-marker]:hidden sm:px-6"
        >
          <div>
            <h3 class="text-base font-semibold text-[var(--ink-primary)]">符号与阶段</h3>
            <p class="mt-0.5 text-[13px] text-[var(--ink-muted)]">原表用单个字形表示整周安排，这里按含义分组并配色</p>
          </div>
          <Icon name="chevron-down" customClass="w-4 h-4 shrink-0 text-[var(--ink-muted)] transition-transform group-open:rotate-180" />
        </summary>

        <div class="border-t border-[var(--border-hairline)] px-5 py-4 sm:px-6">
          <div class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            <div v-for="group in legends" :key="group.group">
              <div class="mb-2 flex items-center gap-2">
                <span :class="['h-2.5 w-2.5 rounded-full', stageDotClass(group.group)]" :style="stageDotStyle(group.group)"></span>
                <span class="text-[13px] font-semibold text-[var(--ink-primary)]">{{ group.label }}</span>
              </div>
              <div class="flex flex-wrap gap-1.5">
                <span
                  v-for="symbol in group.symbols"
                  :key="symbol.key"
                  class="inline-flex items-center gap-1 rounded-[var(--radius-badge)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/60 px-2 py-0.5 text-[12px] text-[var(--ink-secondary)]"
                >
                  <span class="h-1.5 w-1.5 rounded-full" :style="stageDotStyle(group.group, symbol.key)"></span>
                  <span class="font-mono text-[var(--ink-primary)]">{{ symbol.glyph }}</span>
                  {{ symbol.label }}
                </span>
              </div>
            </div>
          </div>

          <div class="mt-5 space-y-2 border-t border-[var(--border-hairline)] pt-4 text-[12px] text-[var(--ink-muted)]">
            <p>
              原表只标注军训、实习、考试、假期等特殊安排，其余周次留空并按「常规教学」显示，表示按课表正常上课；
              未收录字形（<span class="font-mono">{{ undocumented.join(' ') }}</span>）以虚线提示，含义请以原表图例为准。
            </p>
            <p>
              本页是随应用打包的公开快照，不联网也不会过期校验；与原文不一致时以
              <a :href="source" target="_blank" rel="noopener noreferrer" class="text-[var(--accent)] hover:underline">教务处原表</a>
              为准。
            </p>
            <details v-if="freshness.warnings.length">
              <summary class="cursor-pointer list-none text-[var(--accent)] [&::-webkit-details-marker]:hidden">
                整理时发现 {{ freshness.warnings.length }} 处对不上的地方
              </summary>
              <ul class="mt-2 list-disc space-y-1 rounded-[var(--radius-panel)] bg-[var(--bg-subtle)] p-3 pl-6 leading-relaxed">
                <li v-for="(item, index) in freshness.warnings" :key="index">{{ item }}</li>
              </ul>
            </details>
            <details class="group">
              <summary class="cursor-pointer list-none text-[var(--accent)] [&::-webkit-details-marker]:hidden">
                查看教务处原表图例原文
              </summary>
              <div class="mt-2 space-y-1 rounded-[var(--radius-panel)] bg-[var(--bg-subtle)] p-3 leading-relaxed">
                <p v-for="(line, index) in semester.legend || []" :key="index" class="break-words">{{ line }}</p>
                <p v-if="!(semester.legend || []).length">该学期原表没有附图例。</p>
              </div>
            </details>
          </div>
        </div>
      </details>
    </UiCard>

    <!-- 选班弹窗 -->
    <UiModal v-model="pickerOpen" title="指定我的班级">
      <div class="space-y-3.5">
        <UiInput v-model="pickerQuery" aria-label="搜索班级" placeholder="搜索班级名，例如：材型、软件、电气" clearable>
          <template #prefix>
            <Icon name="search" customClass="w-4 h-4" />
          </template>
        </UiInput>

        <UiTabs v-if="campuses.length > 1" v-model="pickerCampus" :items="campusItems" />

        <div v-overlay-scroll class="max-h-[52vh] divide-y divide-[var(--border-hairline)] overflow-y-auto rounded-[var(--radius-panel)] border border-[var(--border-hairline)]">
          <button
            v-for="item in pickerResults"
            :key="item.name"
            type="button"
            class="flex w-full cursor-pointer items-center justify-between gap-3 px-4 py-2.5 text-left text-[13px] transition-colors hover:bg-[var(--bg-subtle)]"
            @click="chooseClass(item)"
          >
            <span class="truncate text-[var(--ink-primary)]">{{ item.name }}</span>
            <span class="shrink-0 text-[12px] text-[var(--ink-muted)] num-tabular">{{ item.hours || '—' }} 学时</span>
          </button>
          <p v-if="!pickerResults.length" class="px-4 py-8 text-center text-[13px] text-[var(--ink-muted)]">
            没有匹配的班级
          </p>
        </div>
      </div>
    </UiModal>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useTeachingProcess } from '@/composables/useTeachingProcess.js';
import { stageInsights, summaryOf, weekIndexOn } from '@/utils/teachingProcess.js';
import { UNDOCUMENTED_GLYPHS, legendGroups, stageDotClass, stageDotStyle } from '@/utils/teachingProcessSymbols.js';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiModal from '@/components/ui/UiModal.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import Icon from '@/components/icons/Icon.vue';
import ProcessTimeline from '@/components/teaching-process/ProcessTimeline.vue';
import ProcessStageList from '@/components/teaching-process/ProcessStageList.vue';
import ProcessRawTable from '@/components/teaching-process/ProcessRawTable.vue';

const {
  source,
  patterns,
  semesters,
  semester,
  semesterId,
  campuses,
  campus,
  filteredClasses,
  query,
  view,
  groups,
  patternCount,
  weekCount,
  currentWeek,
  currentSemesterId,
  isCurrentSemester,
  myClass,
  myClassEntry,
  myClassGuess,
  weekSnapshot,
  freshness,
  calendarBehind,
  segmentsOf,
  stagesOfEntry,
  setSemester,
  setCampus,
  setQuery,
  setView,
  setMyClass,
  clearMyClass,
  confirmGuess,
  goToCurrentSemester
} = useTeachingProcess();

const semesterIndex = computed(() => semesters.findIndex(item => item.id === semesterId.value));

const semesterModel = computed({
  get: () => semesterId.value,
  set: value => setSemester(value)
});

const semesterItems = computed(() =>
  semesters.map(item => {
    const isNow = weekIndexOn(item.weeks) !== null;
    const suffix = isNow ? '当前' : item.id === currentSemesterId ? '最新' : '';
    return {
      label: suffix ? `${item.label} · ${suffix}` : item.label,
      value: item.id
    };
  })
);

const campusModel = computed({
  get: () => campus.value?.name || '',
  set: value => setCampus(value)
});

const campusItems = computed(() => campuses.value.map(item => ({ label: item.name, value: item.name })));

const queryModel = computed({
  get: () => query.value,
  set: value => setQuery(value)
});

const viewModel = computed({
  get: () => view.value,
  set: value => setView(value)
});

const viewItems = [
  { label: '按进程', value: 'pattern' },
  { label: '按班级', value: 'class' }
];

const legends = computed(() => legendGroups());
const undocumented = UNDOCUMENTED_GLYPHS;

const totalClasses = computed(() => campuses.value.reduce((sum, item) => sum + item.classes.length, 0));

const semesterSpan = computed(() => {
  const weeks = semester.value?.weeks || [];
  if (!weeks.length) return '—';
  return `${dayOf(weeks[0].mon)} – ${dayOf(weeks[weeks.length - 1].sun)}`;
});

const firstMonday = computed(() => (semester.value?.weeks[0] ? dayOf(semester.value.weeks[0].mon) : ''));
const lastSunday = computed(() => {
  const weeks = semester.value?.weeks || [];
  return weeks.length ? dayOf(weeks[weeks.length - 1].sun) : '';
});

function dayOf(iso) {
  if (!iso) return '';
  const [, month, day] = iso.split('-');
  return `${Number(month)}/${day}`;
}

function stepSemester(delta) {
  const target = semesters[semesterIndex.value + delta];
  if (target) setSemester(target.id);
}

/* ---------------- 主视觉：我的班级 / 推测的班级 ---------------- */

/** 学籍档案推测出的班级记录（找得到才用），只用于展示，确认后才落库 */
const guessEntry = computed(() => {
  const guess = myClassGuess.value;
  if (!guess) return null;
  for (const item of campuses.value) {
    const found = item.classes.find(entry => entry[0] === guess.name);
    if (found) return { name: found[0], hours: found[1], patternIndex: found[2], campus: item.name };
  }
  return null;
});

const heroEntry = computed(() => myClassEntry.value || guessEntry.value);
const heroIsGuess = computed(() => !myClassEntry.value && Boolean(guessEntry.value));
const heroStages = computed(() => (heroEntry.value ? segmentsOf(heroEntry.value.patternIndex) : []));

const insights = computed(() => stageInsights(heroStages.value, currentWeek.value || 0, weekCount.value));
const heroActiveStages = computed(() => stagesOfEntry(heroEntry.value, currentWeek.value));

const currentStageTitle = computed(() => {
  if (!currentWeek.value) return `${semester.value.label} 已结束`;
  const labels = heroActiveStages.value.map(part => part.label).filter(Boolean);
  return labels.length ? labels.join(' / ') : '常规教学';
});

const currentStageDetail = computed(() => {
  const segment = insights.value.current;
  if (!currentWeek.value) return '该学期进程已结束，下面是完整阶段安排。';
  if (!segment) return '这一周原表没有标注安排。';
  const total = Math.max(1, Math.round(segment.weekCount));
  if (segment.group === 'idle') return `第 ${segment.startWeek}–${segment.endWeek} 周 · 共 ${total} 周 · 按课表正常上课`;
  return `第 ${segment.startWeek}–${segment.endWeek} 周 · 共 ${total} 周 · 本周第 ${insights.value.currentElapsed} 周`;
});

const weekSpanText = computed(() => {
  const week = semester.value?.weeks?.[currentWeek.value - 1];
  if (!week) return '';
  return `${dayOf(week.mon)} – ${dayOf(week.sun)}`;
});

const metrics = computed(() => {
  const info = insights.value;
  if (!currentWeek.value) return [];

  const vacationValue = info.vacation ? (info.vacationIn > 0 ? `${info.vacationIn} 周后` : '假期中') : '未标注';

  return [
    {
      label: info.current?.key === 'vacation' ? '假期还剩' : '本阶段还剩',
      value: info.currentRemaining ? `${info.currentRemaining} 周` : '—',
      hint: info.current ? `到第 ${info.current.endWeek} 周` : ''
    },
    {
      label: '下一阶段',
      value: info.next ? info.next.short : '已无',
      hint: info.next ? `${info.nextIn} 周后 · 第 ${info.next.startWeek} 周` : '本学期最后一个阶段'
    },
    {
      label: '距离放假',
      value: vacationValue,
      hint: info.vacation ? `第 ${info.vacation.startWeek} 周起` : '原表未标注假期'
    }
  ];
});

/** 未指定班级时，本校区本周最多的安排 */
const campusTopStage = computed(
  () => weekSnapshot.value.find(item => item.group !== 'idle') || weekSnapshot.value[0] || null
);

/* ---------------- 选班弹窗 ---------------- */

const pickerOpen = ref(false);
const pickerQuery = ref('');
const pickerCampus = ref('');

const pickerResults = computed(() => {
  const target = campuses.value.find(item => item.name === pickerCampus.value) || campuses.value[0];
  const keyword = pickerQuery.value.trim().toLowerCase();
  const list = (target?.classes || []).map(([name, hours]) => ({ name, hours }));
  return (keyword ? list.filter(item => item.name.toLowerCase().includes(keyword)) : list).slice(0, 80);
});

function openPicker() {
  pickerQuery.value = '';
  pickerCampus.value = campus.value?.name || campuses.value[0]?.name || '';
  pickerOpen.value = true;
}

function chooseClass(item) {
  setMyClass(item.name, pickerCampus.value);
  pickerOpen.value = false;
}

/* ---------------- 折叠区懒渲染 ---------------- */

const allOpen = ref(false);
const rawOpen = ref(false);
</script>
