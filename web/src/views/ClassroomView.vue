<template>
  <div class="space-y-6">
    <!-- 未登录引导 -->
    <UiCard v-if="!isLoggedIn" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-12 h-12 rounded-2xl bg-zinc-100 dark:bg-zinc-800 flex items-center justify-center mx-auto mb-3 text-zinc-600 dark:text-zinc-300">
        <Icon name="classroom" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-bold text-zinc-900 dark:text-zinc-100 mb-1">未登录教务系统</h3>
      <p class="text-xs text-zinc-500 mb-4">请登录哈理工教务在线以查询教学楼与教室的真实排课占用</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <!-- 会话校验中：校验通过前不开放查询 -->
    <UiCard v-else-if="!authChecked" class="py-12 text-center max-w-lg mx-auto">
      <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2 text-zinc-500" />
      <p class="text-xs text-zinc-500">正在校验登录状态，请稍候…</p>
    </UiCard>

    <template v-else>
      <!-- 筛选条 -->
      <UiCard>
        <div class="flex flex-col gap-4">
          <div class="flex flex-wrap items-end gap-x-4 gap-y-3">
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] font-medium text-zinc-500 dark:text-zinc-400">教学区</span>
              <UiSelect
                :model-value="selectedArea"
                :items="areaItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="教学区"
                custom-class="min-w-[7.5rem]"
                @update:model-value="onAreaChange"
              />
            </label>

            <label class="flex flex-col gap-1.5">
              <span class="flex items-center gap-1 text-[11px] font-medium text-zinc-500 dark:text-zinc-400">
                教学楼
                <span
                  v-if="isCurrentDefaultBuilding"
                  class="rounded bg-amber-50 dark:bg-amber-500/10 px-1 py-px text-[10px] font-semibold text-amber-600 dark:text-amber-400"
                >默认</span>
              </span>
              <div class="flex items-center gap-1.5">
                <UiSelect
                  :model-value="selectedBuilding"
                  :items="buildingItems"
                  :disabled="optionsLoading || !buildingItems.length"
                  size="sm"
                  aria-label="教学楼"
                  custom-class="min-w-[9rem]"
                  @update:model-value="onBuildingChange"
                />
                <button
                  type="button"
                  :disabled="!selectedBuilding"
                  :title="isCurrentDefaultBuilding ? '取消默认教学楼' : '设为默认教学楼'"
                  :aria-label="isCurrentDefaultBuilding ? '取消默认教学楼' : '设为默认教学楼'"
                  :aria-pressed="isCurrentDefaultBuilding"
                  :class="[
                    'flex h-8 w-8 items-center justify-center rounded-lg border transition-all duration-150 cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed',
                    isCurrentDefaultBuilding
                      ? 'border-amber-300/80 bg-amber-50 text-amber-500 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-400'
                      : 'border-zinc-200 text-zinc-400 hover:border-zinc-300 hover:text-amber-500 dark:border-zinc-700/60 dark:text-zinc-500 dark:hover:text-amber-400'
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
              <span class="text-[11px] font-medium text-zinc-500 dark:text-zinc-400">教学周</span>
              <UiSelect
                :model-value="selectedWeek"
                :items="weekItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="教学周"
                custom-class="min-w-[6.5rem]"
                @update:model-value="onWeekChange"
              />
            </label>

            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] font-medium text-zinc-500 dark:text-zinc-400">星期</span>
              <UiSelect
                :model-value="selectedDay"
                :items="dayItems"
                :disabled="optionsLoading"
                size="sm"
                aria-label="星期"
                custom-class="min-w-[5.5rem]"
                @update:model-value="onDayChange"
              />
            </label>

            <div class="flex items-center gap-2 pb-0.5">
              <UiButton
                variant="primary"
                size="sm"
                :loading="scheduleLoading"
                :disabled="!selectedBuilding || optionsLoading"
                @click="ensureDaySchedule(true)"
              >
                <template #prefix><Icon name="search" customClass="w-3.5 h-3.5 mr-1.5" /></template>
                查询
              </UiButton>
              <UiButton variant="ghost" size="sm" :disabled="optionsLoading" @click="backToNow">
                <template #prefix><Icon name="clock" customClass="w-3.5 h-3.5 mr-1.5" /></template>
                今天
              </UiButton>
            </div>

            <div class="ml-auto pb-0.5">
              <UiButton
                variant="secondary"
                size="sm"
                :loading="weekLoading"
                :disabled="!selectedBuilding || weekLoaded || scheduleLoading"
                @click="loadWeekChecked"
              >
                <template #prefix><Icon name="calendar" customClass="w-3.5 h-3.5 mr-1.5" /></template>
                {{ weekLoaded ? '整周已加载' : weekLoading ? `加载中 ${weekProgress.done}/${weekProgress.total}` : '加载整周空闲' }}
              </UiButton>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2 text-[11px] text-zinc-500 dark:text-zinc-400">
            <UiBadge v-if="selectedBuildingName" variant="outline" size="sm">{{ selectedBuildingName }}</UiBadge>
            <UiBadge variant="outline" size="sm">第 {{ selectedWeek }} 周</UiBadge>
            <UiBadge v-if="dayLabel" variant="outline" size="sm">{{ dayLabel }}{{ currentSchedule?.date ? `（${currentSchedule.date}）` : '' }}</UiBadge>
            <span v-if="currentSchedule" class="text-zinc-400">
              共 {{ summaryRooms.length }} 间 · 整日无课 {{ allDayFreeCount }} 间 · 可自习 {{ freeRoomCount }} 间
            </span>
            <span v-if="lastLoadedText" class="text-zinc-400">· 更新于 {{ lastLoadedText }}</span>
            <span class="text-zinc-400">· 数据来自教务「教室时间占用」实时排课</span>
          </div>
        </div>
      </UiCard>

      <!-- 错误态 -->
      <UiCard v-if="error" :custom-class="errorKind === 'session' ? 'border-rose-200/70 dark:border-rose-900/60' : ''">
        <div class="flex items-start gap-3">
          <div class="w-9 h-9 rounded-xl bg-rose-50 dark:bg-rose-950/40 text-rose-600 dark:text-rose-300 flex items-center justify-center shrink-0">
            <Icon name="alert-triangle" customClass="w-4.5 h-4.5" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              {{ errorKind === 'session' ? '会话已失效' : '查询未返回数据' }}
            </div>
            <p class="text-xs text-zinc-500 mt-1">{{ error }}</p>
          </div>
          <UiButton v-if="errorKind === 'session'" variant="primary" size="sm" @click="openLoginModal">重新登录</UiButton>
          <UiButton v-else variant="secondary" size="sm" @click="ensureDaySchedule(true)">重试</UiButton>
        </div>
      </UiCard>

      <!-- 周课表矩阵 -->
      <UiCard title="教室占用矩阵" body-class="p-0">
        <template #header-action>
          <div class="flex items-center gap-3 text-[11px] text-zinc-400">
            <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-full bg-rose-500"></span>课程</span>
            <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-full bg-amber-500"></span>考试</span>
            <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-full bg-sky-500"></span>其它</span>
            <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-[3px] bg-emerald-400/70"></span>空闲</span>
          </div>
        </template>

        <!-- 骨架 -->
        <div v-if="optionsLoading || (scheduleLoading && !currentSchedule)" class="p-5">
          <div class="animate-pulse space-y-2">
            <div class="h-6 rounded-md bg-zinc-100 dark:bg-zinc-800/70"></div>
            <div v-for="i in 8" :key="i" class="h-8 rounded-md bg-zinc-100/80 dark:bg-zinc-800/50"></div>
          </div>
        </div>

        <!-- 空结果 -->
        <div v-else-if="!currentSchedule" class="py-16 text-center">
          <Icon name="classroom" customClass="w-7 h-7 mx-auto mb-2 text-zinc-300 dark:text-zinc-600" />
          <p class="text-xs text-zinc-500">选择教学区与教学楼后点击「查询」</p>
        </div>

        <div v-else>
          <!-- 桌面：教室 × 12 小节矩阵（固定首列宽度，窄屏横向滚动） -->
          <UiScrollArea
            axis="both"
            class="hidden max-h-[68vh] rounded-b-xl md:block"
            viewport-class="max-h-[68vh]"
          >
            <table class="w-full table-fixed border-collapse text-xs min-w-[620px]">
              <caption class="sr-only">
                {{ selectedBuildingName }} 第 {{ selectedWeek }} 周 {{ dayLabel }} 教室小节占用情况
              </caption>
              <colgroup>
                <col class="w-[8.5rem]" />
                <col v-for="slot in sectionColumns" :key="slot.period" />
              </colgroup>
              <thead>
                <tr>
                  <th
                    scope="col"
                    class="sticky left-0 top-0 z-30 bg-white/95 dark:bg-[#1c1e24] backdrop-blur px-2.5 py-2 text-left font-semibold text-zinc-500 dark:text-zinc-400 border-b border-zinc-200/80 dark:border-zinc-800 whitespace-nowrap"
                  >
                    教室
                  </th>
                  <th
                    v-for="slot in sectionColumns"
                    :key="slot.period"
                    scope="col"
                    :class="[
                      'sticky top-0 z-20 bg-white/95 dark:bg-[#1c1e24] backdrop-blur px-1 py-1.5 text-center font-medium text-zinc-500 dark:text-zinc-400 border-b border-zinc-200/80 dark:border-zinc-800 whitespace-nowrap',
                      groupSeparatorClass(slot.period)
                    ]"
                  >
                    <div class="text-[11px] tabular-nums">第{{ slot.period }}节</div>
                    <div class="text-[9px] font-normal text-zinc-400/90 tabular-nums">{{ slot.time.slice(0, 5) }}</div>
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
                      ? 'bg-zinc-900/[0.04] dark:bg-zinc-100/[0.06]'
                      : 'hover:bg-zinc-50/80 dark:hover:bg-zinc-900/40'
                  ]"
                  @click="focusRoom(room)"
                >
                  <th
                    scope="row"
                    class="sticky left-0 z-10 bg-white dark:bg-[#1c1e24] text-left px-2.5 py-1 border-b border-zinc-100 dark:border-zinc-800/70 cursor-pointer"
                  >
                    <div class="truncate text-[12px] font-semibold text-zinc-800 dark:text-zinc-100">{{ room.name }}</div>
                    <div class="truncate text-[10px] font-normal text-zinc-400">
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
                      'border-b border-l border-zinc-300/60 dark:border-zinc-700/70 text-center h-7 outline-none focus-visible:ring-2 focus-visible:ring-zinc-400/60',
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
          <div class="divide-y divide-zinc-100 md:hidden dark:divide-zinc-800/70">
            <div
              v-for="room in visibleRooms"
              :key="room.id || room.name"
              :class="[
                'px-4 py-2.5 transition-colors duration-150 cursor-pointer',
                highlightRoomId === (room.id || room.name)
                  ? 'bg-zinc-900/[0.04] dark:bg-zinc-100/[0.06]'
                  : 'active:bg-zinc-50 dark:active:bg-zinc-900/40'
              ]"
              @click="focusRoom(room)"
            >
              <div class="flex items-baseline justify-between gap-2">
                <span class="min-w-0 truncate text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">
                  {{ room.name }}
                </span>
                <span class="shrink-0 text-[10px] text-zinc-400">
                  {{ room.roomType || '类型未知' }}<template v-if="room.seats"> · {{ room.seats }} 座</template>
                </span>
              </div>
              <div class="mt-1.5 flex gap-[2px]">
                <div
                  v-for="slot in sectionColumns"
                  :key="slot.period"
                  :aria-label="cellLabel(room, slot.period)"
                  :class="[
                    'h-5 flex-1 rounded-[3px]',
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
              <div class="mt-1 flex justify-between text-[9px] tabular-nums text-zinc-400">
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
              class="group inline-flex items-center gap-2 rounded-full border border-zinc-200/90 bg-white px-6 py-2.5 text-[13px] font-medium text-zinc-700 shadow-[0_1px_2px_rgba(0,0,0,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-zinc-300 hover:shadow-md active:translate-y-0 active:scale-[0.98] cursor-pointer dark:border-zinc-700/80 dark:bg-zinc-800/60 dark:text-zinc-200 dark:hover:border-zinc-600"
              @click="showMoreRooms"
            >
              <Icon
                name="chevron-down"
                customClass="w-4 h-4 text-zinc-400 transition-transform duration-200 group-hover:translate-y-0.5"
              />
              <span>再显示 {{ Math.min(60, summaryRooms.length - visibleLimit) }} 间</span>
              <span class="text-[11px] font-normal text-zinc-400">共 {{ summaryRooms.length }} 间</span>
            </button>
            <p class="text-center text-[11px] leading-relaxed text-zinc-500 dark:text-zinc-400">
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
              <Icon name="search" customClass="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-zinc-400" />
              <input
                v-model="summaryQuery"
                type="search"
                placeholder="筛选教室"
                class="w-36 text-xs pl-7 pr-2 py-1.5 rounded-lg border border-zinc-200 dark:border-zinc-700 bg-zinc-50/60 dark:bg-[#1f2127] text-zinc-700 dark:text-zinc-200 placeholder:text-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-400/20"
              />
            </div>
            <UiSelect
              v-model="minSpan"
              :items="minSpanItems"
              size="sm"
              aria-label="最小连续节次"
              custom-class="min-w-[7rem]"
            />
            <button
              type="button"
              :class="[
                'text-[11px] px-2.5 py-1.5 rounded-lg border transition-colors cursor-pointer select-none',
                onlyWithFree
                  ? 'bg-zinc-900 text-white border-transparent dark:bg-zinc-100 dark:text-zinc-900'
                  : 'border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800'
              ]"
              @click="onlyWithFree = !onlyWithFree"
            >
              只看有空档
            </button>
          </div>
        </template>

        <div v-if="scheduleLoading && !currentSchedule" class="py-10 text-center text-xs text-zinc-400">
          <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2 text-zinc-500" />
          正在读取教务排课…
        </div>

        <div v-else-if="!filteredSummary.length" class="py-12 text-center">
          <Icon name="inbox" customClass="w-6 h-6 mx-auto mb-2 text-zinc-300 dark:text-zinc-600" />
          <p class="text-xs text-zinc-500">
            {{ summaryRooms.length ? '当前筛选条件下没有匹配的教室' : '暂无可展示的教室数据' }}
          </p>
        </div>

        <ul v-else class="divide-y divide-zinc-100 dark:divide-zinc-800/70 -my-1">
          <li
            v-for="room in filteredSummary"
            :key="room.id || room.name"
            :class="[
              'py-2.5 flex items-start justify-between gap-4 px-1 -mx-1 rounded-lg transition-colors duration-150 cursor-pointer',
              highlightRoomId === (room.id || room.name)
                ? 'bg-zinc-900/[0.04] dark:bg-zinc-100/[0.06]'
                : 'hover:bg-zinc-50/80 dark:hover:bg-zinc-900/40'
            ]"
            @click="focusRoom(room)"
          >
            <div class="min-w-0">
              <div class="text-[13px] font-semibold text-zinc-900 dark:text-zinc-100 truncate">{{ room.name }}</div>
              <div class="text-[11px] text-zinc-400 truncate">
                {{ room.roomType || '类型未知' }}
                <template v-if="room.seats"> · {{ room.seats }} 座</template>
                <template v-if="room.occupancy.length"> · 已排 {{ room.occupiedCount }} 节</template>
              </div>
            </div>

            <div class="flex flex-wrap items-center justify-end gap-1 shrink-0 max-w-[62%]">
              <template v-if="room.visibleSlotLabels.length">
                <UiBadge
                  v-for="(label, index) in room.visibleSlotLabels.slice(0, 5)"
                  :key="label + index"
                  variant="success"
                  size="sm"
                >
                  {{ label }}
                </UiBadge>
                <span v-if="room.visibleSlotLabels.length > 5" class="text-[11px] text-zinc-400">
                  +{{ room.visibleSlotLabels.length - 5 }}
                </span>
              </template>
              <span v-else class="text-[11px] text-zinc-400">已排满</span>
            </div>
          </li>
        </ul>

        <template #footer>
          <div class="text-[11px] text-zinc-500 leading-relaxed">
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
  occupied: 'bg-zinc-400 dark:bg-zinc-500'
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
    ? 'bg-zinc-200/70 dark:bg-zinc-700/60 hover:bg-zinc-200 dark:hover:bg-zinc-700'
    : 'bg-emerald-50 dark:bg-emerald-500/[0.08]';
}

/** 上午(1-4) / 下午(5-8) / 晚上(9-12) 的分组竖线 */
function groupSeparatorClass(period) {
  return period === 5 || period === 9
    ? 'border-l-2 border-l-zinc-300/80 dark:border-l-zinc-600/70'
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
