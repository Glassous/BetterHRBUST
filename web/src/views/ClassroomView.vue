<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="!isLoggedIn" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="classroom" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以查询教学楼与教室的真实排课占用</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <!-- 会话校验中：校验通过前不开放查询 -->
    <UiCard v-else-if="!authChecked" class="py-12 text-center max-w-lg mx-auto">
      <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
      <p class="text-[13px] text-[var(--ink-muted)]">正在校验登录状态，请稍候…</p>
    </UiCard>

    <template v-else>
      <!-- 筛选条 -->
      <UiCard>
        <div class="flex flex-col gap-4.5">
          <div class="flex flex-wrap items-end gap-x-4 gap-y-3.5">
            <label class="flex flex-col gap-1.5">
              <span class="text-xs font-medium text-[var(--ink-secondary)]">教学区</span>
              <UiSelect
                :model-value="selectedArea"
                :items="areaItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="教学区"
                custom-class="min-w-[8rem]"
                @update:model-value="onAreaChange"
              />
            </label>

            <label class="flex flex-col gap-1.5">
              <span class="flex items-center gap-1.5 text-xs font-medium text-[var(--ink-secondary)]">
                教学楼
                <span
                  v-if="isCurrentDefaultBuilding"
                  class="rounded-full bg-[var(--sem-warning-soft)] border border-[var(--sem-warning-border)] px-1.5 py-px text-[11px] font-semibold text-[var(--sem-warning)]"
                >默认</span>
              </span>
              <div class="flex items-center gap-1.5">
                <UiSelect
                  :model-value="selectedBuilding"
                  :items="buildingItems"
                  :disabled="optionsLoading || !buildingItems.length"
                  size="sm"
                  aria-label="教学楼"
                  custom-class="min-w-[9.5rem]"
                  @update:model-value="onBuildingChange"
                />
                <button
                  type="button"
                  :disabled="!selectedBuilding"
                  :title="isCurrentDefaultBuilding ? '取消默认教学楼' : '设为默认教学楼'"
                  :aria-label="isCurrentDefaultBuilding ? '取消默认教学楼' : '设为默认教学楼'"
                  :aria-pressed="isCurrentDefaultBuilding"
                  :class="[
                    'flex h-9 w-9 items-center justify-center rounded-[var(--radius-control)] border transition-all duration-150 active:scale-95 cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed',
                    isCurrentDefaultBuilding
                      ? 'border-[var(--sem-warning-border)] bg-[var(--sem-warning-soft)] text-[var(--sem-warning)]'
                      : 'border-[var(--border-hairline)] bg-[var(--bg-subtle)] text-[var(--ink-muted)] hover:border-[var(--border-strong)] hover:text-[var(--sem-warning)]'
                  ]"
                  @click="toggleDefaultBuilding"
                >
                  <Icon
                    :name="isCurrentDefaultBuilding ? 'star-filled' : 'star'"
                    customClass="w-4 h-4"
                  />
                </button>
              </div>
            </label>

            <label class="flex flex-col gap-1.5">
              <span class="text-xs font-medium text-[var(--ink-secondary)]">教学周</span>
              <UiSelect
                :model-value="selectedWeek"
                :items="weekItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="教学周"
                custom-class="min-w-[7rem]"
                @update:model-value="onWeekChange"
              />
            </label>

            <label class="flex flex-col gap-1.5">
              <span class="text-xs font-medium text-[var(--ink-secondary)]">星期</span>
              <UiSelect
                :model-value="selectedDay"
                :items="dayItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="星期"
                custom-class="min-w-[6rem]"
                @update:model-value="onDayChange"
              />
            </label>

            <div class="flex items-center gap-2">
              <UiButton
                variant="primary"
                size="sm"
                :loading="scheduleLoading"
                :disabled="!selectedBuilding || optionsLoading"
                @click="ensureDaySchedule(true)"
              >
                <template #prefix><Icon name="search" customClass="w-3.5 h-3.5" /></template>
                查询
              </UiButton>
              <UiButton variant="ghost" size="sm" :disabled="optionsLoading" @click="backToNow">
                <template #prefix><Icon name="clock" customClass="w-3.5 h-3.5" /></template>
                今天
              </UiButton>
            </div>

            <div class="ml-auto">
              <UiButton
                variant="secondary"
                size="sm"
                :loading="weekLoading"
                :disabled="!selectedBuilding || weekLoaded || scheduleLoading"
                @click="loadWeekChecked"
              >
                <template #prefix><Icon name="calendar" customClass="w-3.5 h-3.5" /></template>
                {{ weekLoaded ? '整周已加载' : weekLoading ? `加载中 ${weekProgress.done}/${weekProgress.total}` : '加载整周空闲' }}
              </UiButton>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2 text-xs text-[var(--ink-muted)]">
            <UiBadge v-if="selectedBuildingName" variant="outline" size="sm">{{ selectedBuildingName }}</UiBadge>
            <UiBadge variant="outline" size="sm">第 {{ selectedWeek }} 周</UiBadge>
            <UiBadge v-if="dayLabel" variant="outline" size="sm">{{ dayLabel }}{{ currentSchedule?.date ? `（${currentSchedule.date}）` : '' }}</UiBadge>
            <span v-if="currentSchedule" class="num-tabular text-[var(--ink-secondary)]">
              共 {{ summaryRooms.length }} 间 · 整日无课 {{ allDayFreeCount }} 间 · 可自习 {{ freeRoomCount }} 间
            </span>
            <span v-if="lastLoadedText" class="num-tabular">· 更新于 {{ lastLoadedText }}</span>
            <span>· 数据来自教务「教室时间占用」实时排课</span>
          </div>
        </div>
      </UiCard>

      <!-- 错误态 -->
      <UiCard v-if="error" :custom-class="errorKind === 'session' ? '!border-[var(--sem-danger-border)]' : ''">
        <div class="flex items-start gap-3.5">
          <div class="w-10 h-10 rounded-xl bg-[var(--sem-danger-soft)] border border-[var(--sem-danger-border)] text-[var(--sem-danger)] flex items-center justify-center shrink-0">
            <Icon name="alert-triangle" customClass="w-4.5 h-4.5" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-[15px] font-semibold text-[var(--ink-primary)]">
              {{ errorKind === 'session' ? '会话已失效' : '查询未返回数据' }}
            </div>
            <p class="text-[13px] text-[var(--ink-secondary)] mt-1 leading-relaxed">{{ error }}</p>
          </div>
          <UiButton v-if="errorKind === 'session'" variant="primary" size="sm" @click="openLoginModal">重新登录</UiButton>
          <UiButton v-else variant="secondary" size="sm" @click="ensureDaySchedule(true)">重试</UiButton>
        </div>
      </UiCard>

      <!-- 周课表矩阵 -->
      <UiCard title="教室占用矩阵" body-class="p-0">
        <template #header-action>
          <div class="flex items-center gap-3 text-xs text-[var(--ink-secondary)]">
            <span class="inline-flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-rose-500"></span>课程</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-amber-500"></span>考试</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-sky-500"></span>其它</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-2 h-2 rounded-[3px] bg-emerald-400/75"></span>空闲</span>
          </div>
        </template>

        <!-- 骨架 -->
        <div v-if="optionsLoading || (scheduleLoading && !currentSchedule)" class="p-6">
          <div class="animate-pulse space-y-2.5">
            <div class="h-6 rounded-md bg-[var(--bg-subtle)]"></div>
            <div v-for="i in 8" :key="i" class="h-8 rounded-md bg-[var(--bg-subtle)]/75"></div>
          </div>
        </div>

        <!-- 空结果 -->
        <div v-else-if="!currentSchedule" class="py-16 text-center">
          <Icon name="classroom" customClass="w-8 h-8 mx-auto mb-2.5 text-[var(--ink-muted)]/60" />
          <p class="text-[13px] text-[var(--ink-muted)]">选择教学区与教学楼后点击「查询」</p>
        </div>

        <div v-else>
          <!-- 桌面：教室 × 12 小节矩阵（固定首列宽度，窄屏横向滚动） -->
          <UiScrollArea
            axis="both"
            class="hidden max-h-[68vh] rounded-b-[var(--radius-card)] md:block"
            viewport-class="max-h-[68vh]"
          >
            <table class="w-full table-fixed border-collapse text-xs min-w-[620px]">
              <caption class="sr-only">
                {{ selectedBuildingName }} 第 {{ selectedWeek }} 周 {{ dayLabel }} 教室小节占用情况
              </caption>
              <colgroup>
                <col class="w-[9rem]" />
                <col v-for="slot in sectionColumns" :key="slot.period" />
              </colgroup>
              <thead>
                <tr>
                  <th
                    scope="col"
                    class="sticky left-0 top-0 z-30 bg-[var(--bg-surface)]/95 backdrop-blur px-3 py-2.5 text-left font-semibold text-[var(--ink-secondary)] border-b border-[var(--border-hairline)] whitespace-nowrap"
                  >
                    教室
                  </th>
                  <th
                    v-for="slot in sectionColumns"
                    :key="slot.period"
                    scope="col"
                    :class="[
                      'sticky top-0 z-20 bg-[var(--bg-surface)]/95 backdrop-blur px-1 py-2 text-center font-medium text-[var(--ink-secondary)] border-b border-[var(--border-hairline)] whitespace-nowrap',
                      groupSeparatorClass(slot.period)
                    ]"
                  >
                    <div class="text-xs font-semibold tabular-nums">第{{ slot.period }}节</div>
                    <div class="text-[10px] font-normal text-[var(--ink-muted)] tabular-nums">{{ slot.time.slice(0, 5) }}</div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="room in visibleRooms"
                  :key="room.id || room.name"
                  :class="[
                    'transition-colors duration-150',
                    highlightRoomId === (room.id || room.name)
                      ? 'bg-[var(--accent-soft)]'
                      : 'hover:bg-[var(--bg-subtle)]/70'
                  ]"
                  @click="focusRoom(room)"
                >
                  <th
                    scope="row"
                    class="sticky left-0 z-10 bg-[var(--bg-surface)] text-left px-3 py-1.5 border-b border-[var(--border-hairline)] cursor-pointer"
                  >
                    <div class="truncate text-[13px] font-semibold text-[var(--ink-primary)]">{{ room.name }}</div>
                    <div class="truncate text-[11px] font-normal text-[var(--ink-muted)]">
                      {{ room.roomType || '类型未知' }}<template v-if="room.seats"> · {{ room.seats }} 座</template>
                    </div>
                  </th>
                  <td
                    v-for="slot in sectionColumns"
                    :key="slot.period"
                    tabindex="0"
                    :aria-label="cellLabel(room, slot.period)"
                    :title="cellLabel(room, slot.period)"
                    :class="[
                      'border-b border-l border-[var(--border-hairline)] text-center h-8 outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]',
                      groupSeparatorClass(slot.period),
                      cellClass(room, slot.period)
                    ]"
                  >
                    <span
                      v-if="occupancyAt(room, slot.period)"
                      :class="['inline-block w-2 h-2 rounded-full', typeDotClass(occupancyAt(room, slot.period).type)]"
                    ></span>
                  </td>
                </tr>
              </tbody>
            </table>
          </UiScrollArea>

          <!-- 移动端：每间教室一张卡片，12 小节铺满一行，无需横向滚动 -->
          <div class="divide-y divide-[var(--border-hairline)] md:hidden">
            <div
              v-for="room in visibleRooms"
              :key="room.id || room.name"
              :class="[
                'px-4 py-3 transition-colors duration-150 cursor-pointer',
                highlightRoomId === (room.id || room.name)
                  ? 'bg-[var(--accent-soft)]'
                  : 'active:bg-[var(--bg-subtle)]'
              ]"
              @click="focusRoom(room)"
            >
              <div class="flex items-baseline justify-between gap-2">
                <span class="min-w-0 truncate text-[14px] font-semibold text-[var(--ink-primary)]">
                  {{ room.name }}
                </span>
                <span class="shrink-0 text-xs text-[var(--ink-muted)]">
                  {{ room.roomType || '类型未知' }}<template v-if="room.seats"> · {{ room.seats }} 座</template>
                </span>
              </div>
              <div class="mt-2 flex gap-[2px]">
                <div
                  v-for="slot in sectionColumns"
                  :key="slot.period"
                  :aria-label="cellLabel(room, slot.period)"
                  :class="[
                    'h-5 flex-1 rounded-[4px]',
                    mobileGroupClass(slot.period),
                    cellClass(room, slot.period)
                  ]"
                >
                  <span
                    v-if="occupancyAt(room, slot.period)"
                    :class="[
                      'mx-auto mt-[5px] block w-1.5 h-1.5 rounded-full',
                      typeDotClass(occupancyAt(room, slot.period).type)
                    ]"
                  ></span>
                </div>
              </div>
              <div class="mt-1 flex justify-between text-[10px] tabular-nums text-[var(--ink-muted)]">
                <span>第1节</span>
                <span>第6节</span>
                <span>第12节</span>
              </div>
            </div>
          </div>
        </div>

        <template #footer>
          <div class="flex flex-col items-center gap-2.5 py-0.5">
            <button
              v-if="hasMoreRooms"
              type="button"
              class="group inline-flex items-center gap-2 rounded-full border border-[var(--border-hairline)] bg-[var(--bg-subtle)] px-6 py-2.5 text-[13px] font-medium text-[var(--ink-primary)] hover:border-[var(--border-strong)] hover:bg-[var(--bg-hover)] hover:-translate-y-0.5 active:scale-[0.98] transition-all duration-150 cursor-pointer"
              @click="showMoreRooms"
            >
              <Icon
                name="chevron-down"
                customClass="w-4 h-4 text-[var(--ink-muted)] transition-transform duration-200 group-hover:translate-y-0.5"
              />
              <span>再显示 {{ Math.min(60, summaryRooms.length - visibleLimit) }} 间</span>
              <span class="text-xs font-normal text-[var(--ink-muted)]">共 {{ summaryRooms.length }} 间</span>
            </button>
            <p class="text-center text-xs leading-relaxed text-[var(--ink-muted)]">
              占用标记由教务「教室时间占用」页面给出（课程 / 课程考试 / 其它），教务不返回具体课程名。
            </p>
          </div>
        </template>
      </UiCard>

      <!-- 空闲时段 -->
      <UiCard title="空闲时段">
        <template #header-action>
          <div class="flex flex-wrap items-center gap-2">
            <div class="relative">
              <Icon name="search" customClass="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[var(--ink-muted)]" />
              <input
                v-model="summaryQuery"
                type="search"
                placeholder="筛选教室"
                class="w-36 min-h-[36px] text-[13px] pl-8 pr-2.5 py-1.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)] focus:bg-[var(--bg-surface)] focus:border-[var(--accent)] focus:outline-none focus:ring-2 focus:ring-[var(--accent-soft)] transition-all duration-150"
              />
            </div>
            <UiSelect
              v-model="minSpan"
              :items="minSpanItems"
              size="sm"
              aria-label="最小连续节次"
              custom-class="min-w-[7.5rem]"
            />
            <button
              type="button"
              :class="[
                'text-[13px] min-h-[36px] px-3 py-1.5 rounded-[var(--radius-control)] border font-medium transition-all duration-150 active:scale-[0.97] cursor-pointer select-none',
                onlyWithFree
                  ? 'bg-[var(--accent)] text-[var(--accent-ink)] border-transparent'
                  : 'border-[var(--border-hairline)] bg-[var(--bg-subtle)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)]'
              ]"
              @click="onlyWithFree = !onlyWithFree"
            >
              只看有空档
            </button>
          </div>
        </template>

        <div v-if="scheduleLoading && !currentSchedule" class="py-10 text-center text-[13px] text-[var(--ink-muted)]">
          <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
          正在读取教务排课…
        </div>

        <div v-else-if="!filteredSummary.length" class="py-12 text-center">
          <Icon name="inbox" customClass="w-7 h-7 mx-auto mb-2.5 text-[var(--ink-muted)]/60" />
          <p class="text-[13px] text-[var(--ink-muted)]">
            {{ summaryRooms.length ? '当前筛选条件下没有匹配的教室' : '暂无可展示的教室数据' }}
          </p>
        </div>

        <ul v-else class="divide-y divide-[var(--border-hairline)] -my-1">
          <li
            v-for="room in filteredSummary"
            :key="room.id || room.name"
            :class="[
              'py-3 flex items-start justify-between gap-4 px-2.5 -mx-2.5 rounded-[var(--radius-control)] transition-colors duration-150 cursor-pointer',
              highlightRoomId === (room.id || room.name)
                ? 'bg-[var(--accent-soft)]'
                : 'hover:bg-[var(--bg-subtle)]/70'
            ]"
            @click="focusRoom(room)"
          >
            <div class="min-w-0">
              <div class="text-[14px] font-semibold text-[var(--ink-primary)] truncate">{{ room.name }}</div>
              <div class="text-xs text-[var(--ink-muted)] mt-0.5 truncate">
                {{ room.roomType || '类型未知' }}
                <template v-if="room.seats"> · {{ room.seats }} 座</template>
                <template v-if="room.occupancy.length"> · 已排 {{ room.occupiedCount }} 节</template>
              </div>
            </div>

            <div class="flex flex-wrap items-center justify-end gap-1.5 shrink-0 max-w-[62%]">
              <template v-if="room.visibleSlotLabels.length">
                <UiBadge
                  v-for="(label, index) in room.visibleSlotLabels.slice(0, 5)"
                  :key="label + index"
                  variant="success"
                  size="sm"
                >
                  {{ label }}
                </UiBadge>
                <span v-if="room.visibleSlotLabels.length > 5" class="text-xs text-[var(--ink-muted)]">
                  +{{ room.visibleSlotLabels.length - 5 }}
                </span>
              </template>
              <span v-else class="text-xs text-[var(--ink-muted)]">已排满</span>
            </div>
          </li>
        </ul>

        <template #footer>
          <div class="text-xs text-[var(--ink-muted)] leading-relaxed">
            <template v-if="weekLoaded">
              已加载整周（周一至周日），空闲时段为连续合并后的结果，最少 {{ minSpan }} 节。
            </template>
            <template v-else>
              当前为单日结果（{{ dayLabel }}）。点击「加载整周空闲」可补齐周一至周日，用于规划整周自习。
            </template>
            座位数取自教务登记值，未登记时留空。
          </div>
        </template>
      </UiCard>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiScrollArea from '@/components/ui/UiScrollArea.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useToast } from '@/composables/useToast.js';
import { useClassroomQuery, todayWeekday } from '@/composables/useClassroomQuery.js';
import { OCCUPANCY_TYPE_LABELS } from '@/services/academic/parser.js';
import { formatSlot } from '@/services/academic/classroomSchedule.js';
import { BASE_SLOT_TIMES } from '@/utils/periodTimes.js';

const {
  isLoggedIn,
  isSessionExpired,
  authChecked,
  isCheckingAuth,
  checkAuth,
  openLoginModal,
  currentWeek
} = useSession();
const { showToast } = useToast();
const {
  areas,
  buildings,
  weeks,
  days,
  selectedArea,
  selectedBuilding,
  selectedWeek,
  selectedDay,
  currentSchedule,
  summaryRooms,
  freeRoomCount,
  allDayFreeCount,
  weekLoaded,
  selectedBuildingName,
  isCurrentDefaultBuilding,
  setDefaultBuilding,
  clearDefaultBuilding,
  lastLoadedAt,
  optionsLoading,
  scheduleLoading,
  weekLoading,
  weekProgress,
  error,
  errorKind,
  init,
  selectArea,
  selectBuilding,
  selectWeek,
  selectDay,
  queryDay,
  loadWeek,
  refreshDay
} = useClassroomQuery();

const sectionColumns = BASE_SLOT_TIMES;

const areaItems = computed(() => areas.value.map(item => ({ label: item.name, value: item.id })));
const buildingItems = computed(() => buildings.value.map(item => ({ label: item.name, value: item.id })));
const weekItems = computed(() => {
  const source = weeks.value.length
    ? weeks.value
    : Array.from({ length: 26 }, (_, index) => ({ id: index + 1, name: `第 ${index + 1} 周` }));
  return source.map(item => ({ label: `第 ${item.id} 周`, value: item.id }));
});
const dayItems = computed(() => {
  const source = days.value.length
    ? days.value
    : Array.from({ length: 7 }, (_, index) => ({ id: index + 1, name: `周${'一二三四五六日'[index]}` }));
  return source.map(item => ({ label: item.name, value: item.id }));
});

const minSpanItems = [
  { label: '连续≥1节', value: 1 },
  { label: '连续≥2节', value: 2 },
  { label: '连续≥3节', value: 3 }
];

const dayLabel = computed(() => days.value.find(item => item.id === selectedDay.value)?.name || `周${'一二三四五六日'[selectedDay.value - 1] || ''}`);
const lastLoadedText = computed(() => {
  if (!lastLoadedAt.value) return '';
  const date = new Date(lastLoadedAt.value);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
});

/* ---------------------- 登录校验 ---------------------- */

/**
 * 所有查询的前置闸门：先确认登录状态，再放行请求。
 * 会话已失效 / 未登录 → 直接跳登录；尚未做过启动校验 → 先轻量校验一次。
 */
async function ensureSession() {
  if (!isLoggedIn.value || isSessionExpired.value) {
    openLoginModal();
    return false;
  }
  if (!authChecked.value) {
    if (isCheckingAuth.value) {
      // 启动校验已在进行：等它结束，避免重复请求与「未校验先查询」
      await new Promise(resolve => {
        const stop = watch(authChecked, value => {
          if (value) {
            stop();
            resolve();
          }
        });
        setTimeout(() => {
          stop();
          resolve();
        }, 8000);
      });
    } else {
      await checkAuth({ light: true });
    }
    if (!isLoggedIn.value) {
      openLoginModal();
      return false;
    }
  }
  return true;
}

/* ---------------------- 交互 ---------------------- */

async function onAreaChange(value) {
  if (!(await ensureSession())) return;
  await selectArea(value);
  await queryDay(selectedDay.value);
}

async function onBuildingChange(value) {
  if (!(await ensureSession())) return;
  selectBuilding(value);
  await queryDay(selectedDay.value);
}

async function onWeekChange(value) {
  if (!(await ensureSession())) return;
  selectWeek(value);
  await queryDay(selectedDay.value);
}

async function onDayChange(value) {
  if (!(await ensureSession())) return;
  selectDay(value);
  await queryDay(value);
}

async function ensureDaySchedule(force = false) {
  if (!selectedBuilding.value || !selectedWeek.value) return;
  if (!(await ensureSession())) return;
  if (force) await refreshDay();
  else await queryDay(selectedDay.value);
}

async function backToNow() {
  if (!(await ensureSession())) return;
  const week = Number(currentWeek?.value) || selectedWeek.value;
  // 当前教学周不在教务可选范围内时不改周次，只回到今天
  if (!weeks.value.length || weeks.value.some(item => item.id === week)) {
    if (week !== selectedWeek.value) selectWeek(week);
  }
  const day = todayWeekday();
  if (day !== selectedDay.value) selectDay(day);
  await ensureDaySchedule(false);
}

async function loadWeekChecked() {
  if (!(await ensureSession())) return;
  await loadWeek();
}

/** 设置 / 取消默认教学楼 */
function toggleDefaultBuilding() {
  if (!selectedArea.value || !selectedBuilding.value) return;
  if (isCurrentDefaultBuilding.value) {
    clearDefaultBuilding();
    showToast({ title: '已取消默认教学楼', type: 'info' });
    return;
  }
  setDefaultBuilding({
    areaId: selectedArea.value,
    buildingId: selectedBuilding.value,
    name: selectedBuildingName.value
  });
  showToast({
    title: '已设为默认教学楼',
    message: `${selectedBuildingName.value} · 下次进入自动选中`,
    type: 'success'
  });
}

/* ---------------------- 矩阵渲染 ---------------------- */

const visibleLimit = ref(60);
watch([selectedBuilding, selectedWeek, selectedDay], () => {
  visibleLimit.value = 60;
});

const visibleRooms = computed(() => summaryRooms.value.slice(0, visibleLimit.value));
const hasMoreRooms = computed(() => summaryRooms.value.length > visibleLimit.value);

function showMoreRooms() {
  visibleLimit.value += 60;
}

const TYPE_DOT_CLASS = {
  course: 'bg-rose-500',
  exam: 'bg-amber-500',
  other: 'bg-sky-500',
  occupied: 'bg-[var(--ink-muted)]'
};

function typeDotClass(type) {
  return TYPE_DOT_CLASS[type] || TYPE_DOT_CLASS.occupied;
}

function occupancyAt(room, period) {
  return (room.occupancy || []).find(
    item => item.day === selectedDay.value && item.start === period
  ) || null;
}

function cellClass(room, period) {
  return occupancyAt(room, period)
    ? 'bg-[var(--bg-hover)] hover:bg-[var(--ink-muted)]/25'
    : 'bg-[var(--sem-success-soft)] hover:bg-[var(--sem-success-border)]';
}

/** 上午(1-4) / 下午(5-8) / 晚上(9-12) 的分组竖线 */
function groupSeparatorClass(period) {
  return period === 5 || period === 9
    ? 'border-l-2 border-l-[var(--border-strong)]'
    : '';
}

/** 移动端卡片内的分组留白（替代边框竖线） */
function mobileGroupClass(period) {
  return period === 5 || period === 9 ? 'ml-1' : '';
}

function cellLabel(room, period) {
  const item = occupancyAt(room, period);
  const base = `${room.name} 第${period}节`;
  if (!item) return `${base} 空闲`;
  const type = OCCUPANCY_TYPE_LABELS[item.type] || '占用';
  return item.reason ? `${base} ${type}（${item.reason}）` : `${base} ${type}`;
}

/* ---------------------- 空闲列表 ---------------------- */

const summaryQuery = ref('');
const onlyWithFree = ref(false);
const minSpan = ref(2);
const highlightRoomId = ref('');
let highlightTimer = null;

const filteredSummary = computed(() => {
  const keyword = summaryQuery.value.trim();
  return summaryRooms.value
    .filter(room => {
      if (!keyword) return true;
      return room.name.includes(keyword) || (room.roomType || '').includes(keyword);
    })
    .map(room => {
      const slots = room.freeSlots.filter(slot => slot.span >= minSpan.value);
      return {
        ...room,
        visibleSlots: slots,
        visibleSlotLabels: slots.map(formatSlot)
      };
    })
    .filter(room => !onlyWithFree.value || room.visibleSlotLabels.length > 0);
});

function focusRoom(room) {
  const key = room.id || room.name;
  highlightRoomId.value = key;
  if (highlightTimer) clearTimeout(highlightTimer);
  highlightTimer = setTimeout(() => {
    highlightRoomId.value = '';
  }, 1600);
}

/* ---------------------- 生命周期 ---------------------- */

async function boot() {
  if (!isLoggedIn.value) return;
  if (!(await ensureSession())) return;
  await init();
  if (!error.value && selectedBuilding.value) {
    await queryDay(selectedDay.value);
  }
}

onMounted(boot);

watch(isLoggedIn, value => {
  if (value) boot();
});
</script>
