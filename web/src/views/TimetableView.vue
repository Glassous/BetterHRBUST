<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="showLoginPrompt" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="timetable" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1.5">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以同步您的个人专属课程表</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <!-- 工具栏：周次定位、筛选与导出 -->
      <UiCard bodyClass="p-4 sm:p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <!-- 左侧：周次控制 + 本周筛选 -->
          <div class="flex flex-wrap items-center gap-2.5">
            <div ref="weekPickerAnchorRef" class="relative flex items-center gap-1.5">
              <button
                type="button"
                aria-label="上一周"
                :disabled="selectedWeek <= 1"
                class="w-9 h-9 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] hover:bg-[var(--bg-hover)] disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] flex items-center justify-center transition-all duration-150 active:scale-95"
                @click="prevWeek"
              >
                <Icon name="chevron-left" customClass="w-4 h-4" />
              </button>

              <!-- 触发器近邻的上下文周次选择浮层 -->
              <button
                type="button"
                aria-haspopup="true"
                :aria-expanded="showWeekPicker"
                class="h-9 px-3.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] hover:border-[var(--border-strong)] cursor-pointer min-w-[7rem] flex items-center justify-center gap-1.5 transition-all duration-150 active:scale-[0.98]"
                title="点击选择教学周次"
                @click="showWeekPicker = !showWeekPicker"
              >
                <span class="text-[13px] sm:text-sm font-semibold text-[var(--ink-primary)] num-tabular">
                  第 {{ selectedWeek }} 周
                </span>
                <Icon
                  name="chevron-down"
                  customClass="w-3.5 h-3.5 text-[var(--ink-muted)] transition-transform duration-150"
                  :class="showWeekPicker ? 'rotate-180' : ''"
                />
              </button>

              <button
                type="button"
                aria-label="下一周"
                :disabled="selectedWeek >= semesterTeachingWeeks"
                class="w-9 h-9 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] hover:bg-[var(--bg-hover)] disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] flex items-center justify-center transition-all duration-150 active:scale-95"
                @click="nextWeek"
              >
                <Icon name="chevron-right" customClass="w-4 h-4" />
              </button>

              <!-- 上下文选周弹出面板（近邻触发器，不打断全局视口） -->
              <Transition
                enter-active-class="transition duration-160 ease-out"
                enter-from-class="opacity-0 -translate-y-1 scale-95"
                enter-to-class="opacity-100 translate-y-0 scale-100"
                leave-active-class="transition duration-110 ease-in"
                leave-from-class="opacity-100 translate-y-0 scale-100"
                leave-to-class="opacity-0 -translate-y-1 scale-95"
              >
                <div
                  v-if="showWeekPicker"
                  role="dialog"
                  aria-label="选择教学周次"
                  class="absolute left-0 top-[calc(100%+8px)] z-30 w-[min(320px,calc(100vw-2rem))] rounded-[var(--radius-panel)] border border-[var(--border-strong)] bg-[var(--bg-surface)] p-3.5 shadow-[var(--shadow-float)]"
                >
                  <div class="flex items-center justify-between gap-2 mb-2.5">
                    <span class="text-xs font-semibold text-[var(--ink-primary)]">选择教学周次</span>
                    <span class="text-xs text-[var(--ink-muted)] num-tabular">
                      {{ calendarWeekCountKnown ? `共 ${semesterTeachingWeeks} 周` : `暂列 ${semesterTeachingWeeks} 周` }}
                    </span>
                  </div>
                  <div class="grid grid-cols-5 gap-1.5">
                    <button
                      v-for="w in semesterTeachingWeeks"
                      :key="w"
                      type="button"
                      :class="[
                        'h-10 rounded-[var(--radius-control)] border flex flex-col items-center justify-center gap-0.5 cursor-pointer select-none transition-colors num-tabular',
                        w === selectedWeek
                          ? 'bg-[var(--accent)] text-[var(--accent-ink)] border-transparent font-semibold'
                          : w === currentWeek
                            ? 'bg-[var(--sem-success-soft)] text-[var(--sem-success)] border-[var(--sem-success-border)] font-semibold'
                            : 'border-[var(--border-hairline)] text-[var(--ink-primary)] hover:bg-[var(--bg-subtle)]'
                      ]"
                      @click="pickWeek(w)"
                    >
                      <span class="text-xs leading-none">{{ w }}</span>
                      <span v-if="w === currentWeek && w !== selectedWeek" class="w-1 h-1 rounded-full bg-[var(--sem-success)]" />
                    </button>
                  </div>
                </div>
              </Transition>
            </div>

            <button
              type="button"
              :class="[
                'h-9 px-3 text-[13px] font-medium rounded-[var(--radius-control)] border cursor-pointer transition-all duration-150 active:scale-[0.98]',
                onlyCurrentWeek
                  ? 'bg-[var(--accent)] text-[var(--accent-ink)] border-transparent'
                  : 'border-[var(--border-hairline)] bg-[var(--bg-surface)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-subtle)]'
              ]"
              @click="onlyCurrentWeek = !onlyCurrentWeek"
            >
              仅看本周课程
            </button>

            <span
              v-if="selectedWeek === currentWeek"
              class="h-9 px-3 text-[13px] rounded-full border border-[var(--sem-success-border)] bg-[var(--sem-success-soft)] text-[var(--sem-success)] inline-flex items-center gap-1.5 select-none font-medium"
            >
              <span class="w-1.5 h-1.5 rounded-full bg-[var(--sem-success)]"></span>
              {{ isSchoolHoliday ? '假期中' : '当前周' }}
            </span>
            <button
              v-else
              type="button"
              class="h-9 px-3 text-[13px] rounded-full border border-[var(--accent-border)] bg-[var(--accent-soft)] text-[var(--accent)] hover:opacity-85 cursor-pointer transition-all duration-150 active:scale-[0.98] inline-flex items-center gap-1.5 font-medium"
              @click="backToCurrentWeek"
            >
              <Icon name="refresh" customClass="w-3.5 h-3.5" />
              回到本周
            </button>
          </div>

          <!-- 右侧：视图切换、移动端日/周切换、导出 ICS -->
          <div class="flex flex-wrap items-center gap-2">
            <UiTabs
              :items="[
                { label: '大节课表', value: 'combine' },
                { label: '小节明细', value: 'base' }
              ]"
              v-model="viewMode"
            />

            <UiTabs
              class="md:hidden"
              :items="[
                { label: '按日', value: 'day' },
                { label: '整周', value: 'week' }
              ]"
              v-model="mobileLayout"
            />

            <UiButton size="sm" variant="outline" @click="exportIcs">
              <template #prefix>
                <Icon name="download" customClass="w-3.5 h-3.5" />
              </template>
              导出 ICS
            </UiButton>
          </div>
        </div>

        <div class="mt-2.5 pt-2.5 border-t border-[var(--border-hairline)] flex flex-wrap items-center justify-between gap-2 text-xs text-[var(--ink-muted)]">
          <span>上次同步：<span class="num-tabular">{{ lastSyncText }}</span></span>
          <span>点击任意课程块查看任课教师与周次详情</span>
        </div>
      </UiCard>

      <!-- 加载状态 -->
      <div v-if="loading" class="p-16 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] text-center text-xs text-[var(--ink-muted)]">
        <Icon name="refresh" customClass="w-6 h-6 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
        正在从哈理工教务系统拉取最新课表...
      </div>

      <template v-else>
        <!-- 移动端日视图（默认 < md 显示，免除手机端 980px 横拖墙） -->
        <div v-if="mobileLayout === 'day'" class="md:hidden space-y-3.5">
          <!-- 星期选择条 -->
          <div class="grid grid-cols-7 gap-1 p-1.5 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)]">
            <button
              v-for="(dayName, dIdx) in shortDaysOfWeek"
              :key="dayName"
              type="button"
              :class="[
                'py-2 rounded-[var(--radius-control)] flex flex-col items-center justify-center gap-1 cursor-pointer transition-colors num-tabular',
                mobileSelectedDay === dIdx + 1
                  ? 'bg-[var(--accent)] text-[var(--accent-ink)] font-semibold'
                  : isTodayColumn(dIdx + 1)
                    ? 'bg-[var(--accent-soft)] text-[var(--accent)] font-semibold'
                    : 'text-[var(--ink-secondary)] hover:bg-[var(--bg-subtle)]'
              ]"
              @click="mobileSelectedDay = dIdx + 1"
            >
              <span class="text-xs leading-none">{{ dayName }}</span>
              <span class="text-xs leading-none opacity-80">{{ dateForDay(dIdx + 1).slice(0, 5) }}</span>
              <span
                class="w-1.5 h-1.5 rounded-full"
                :class="dayHasCourses(dIdx + 1)
                  ? (mobileSelectedDay === dIdx + 1 ? 'bg-[var(--accent-ink)]' : 'bg-[var(--accent)]')
                  : 'bg-transparent'"
              />
            </button>
          </div>

          <!-- 当日节次时间轴（空节次保留时间刻度高度） -->
          <div class="rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] divide-y divide-[var(--border-hairline)] overflow-hidden">
            <div
              v-for="slot in activeSlots"
              :key="slot.period"
              class="flex items-stretch min-h-[56px]"
            >
              <div class="w-20 shrink-0 px-2.5 py-2.5 border-r border-[var(--border-hairline)] bg-[var(--bg-subtle)]/40 flex flex-col justify-center text-center num-tabular">
                <span class="text-xs font-semibold text-[var(--ink-primary)]">
                  {{ viewMode === 'combine' ? `第${slot.period}大节` : `第${slot.period}节` }}
                </span>
                <span class="text-xs text-[var(--ink-muted)] font-mono mt-0.5">{{ slot.time }}</span>
              </div>
              <div class="flex-1 p-2 flex flex-col gap-2 justify-center min-w-0">
                <template v-if="getVisibleCoursesForSlot(mobileSelectedDay, slot.period).length > 0">
                  <TimetableCourseCard
                    v-for="course in getVisibleCoursesForSlot(mobileSelectedDay, slot.period)"
                    :key="course.courseName + course.id"
                    :course="course"
                    :active="isCourseActiveThisWeek(course)"
                    :expanded="expandedLayout === 'mobile-day' && expandedCourseKey === courseKey(course)"
                    :view-mode="viewMode"
                    @open="onCourseOpen($event, 'mobile-day')"
                    @close="onCourseClose"
                  />
                </template>
                <span v-else class="text-[13px] text-[var(--ink-muted)]/65 px-1">无课</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 桌面整周课表矩阵（md+ 默认展示，或手机端切换整周） -->
        <div
          :class="[
            'rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] overflow-x-auto',
            mobileLayout === 'day' ? 'hidden md:block' : 'block'
          ]"
        >
          <div class="min-w-[980px]">
            <!-- 星期表头 -->
            <div
              class="grid border-b border-[var(--border-hairline)] bg-[var(--bg-subtle)]/65 text-[13px] font-semibold text-[var(--ink-secondary)]"
              :style="{ gridTemplateColumns: gridTemplate }"
            >
              <div class="p-3 text-center text-[var(--ink-muted)] border-r border-[var(--border-hairline)] flex items-center justify-center">
                节次 / 时间
              </div>
              <div
                v-for="(dayName, dIdx) in daysOfWeek"
                :key="dayName"
                :class="[
                  'p-2.5 text-center border-r border-[var(--border-hairline)] last:border-r-0 transition-colors relative',
                  isTodayColumn(dIdx + 1) ? 'bg-[var(--accent-soft)] text-[var(--accent)]' : ''
                ]"
              >
                <div class="flex items-center justify-center gap-1.5 text-sm font-semibold">
                  <span>{{ dayName }}</span>
                  <span v-if="isTodayColumn(dIdx + 1)" class="w-1.5 h-1.5 rounded-full bg-[var(--accent)]"></span>
                </div>
                <div class="mt-0.5 text-xs font-normal num-tabular opacity-80">{{ dateForDay(dIdx + 1) }}</div>
                <span
                  v-if="isTodayColumn(dIdx + 1)"
                  class="absolute inset-x-0 bottom-0 h-[2px] bg-[var(--accent)]"
                />
              </div>
            </div>

            <!-- 节次网格行 -->
            <div
              v-for="slot in activeSlots"
              :key="slot.period"
              :class="[
                'grid border-b border-[var(--border-hairline)] last:border-b-0',
                isRowOccupied(slot.period) ? 'min-h-[118px]' : 'min-h-[48px]'
              ]"
              :style="{ gridTemplateColumns: gridTemplate }"
            >
              <!-- 节次与时间 -->
              <div
                class="p-2 border-r border-[var(--border-hairline)] bg-[var(--bg-subtle)]/35 flex flex-col justify-center items-center text-center num-tabular"
              >
                <span class="text-xs font-semibold text-[var(--ink-primary)]">
                  {{ viewMode === 'combine' ? `第 ${slot.period} 大节` : `第 ${slot.period} 节` }}
                </span>
                <span class="text-xs text-[var(--ink-muted)] font-mono mt-0.5 leading-tight">
                  {{ slot.time }}
                </span>
              </div>

              <!-- 7 天单元格 -->
              <div
                v-for="dayNum in 7"
                :key="dayNum"
                :class="[
                  'p-1.5 border-r border-[var(--border-hairline)] last:border-r-0 flex flex-col gap-1.5 relative min-w-0 transition-colors',
                  isTodayColumn(dayNum) ? 'bg-[var(--accent-soft)]/50' : ''
                ]"
              >
                <!-- 每节课就是一张卡片：点开后同一节点由 GSAP Flip 展开成详情大卡 -->
                <TimetableCourseCard
                  v-for="course in getVisibleCoursesForSlot(dayNum, slot.period)"
                  :key="course.courseName + course.id"
                  :course="course"
                  :active="isCourseActiveThisWeek(course)"
                  :expanded="expandedLayout === 'desktop-grid' && expandedCourseKey === courseKey(course)"
                  :view-mode="viewMode"
                  @open="onCourseOpen($event, 'desktop-grid')"
                  @close="onCourseClose"
                />
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- 未固定节次课程 -->
      <UiCard v-if="unarrangedCourses.length > 0" title="未安排固定节次 / 地点的课程（实践 / 实验 / 重修）" bodyClass="p-0">
        <div class="divide-y divide-[var(--border-hairline)] text-[13px]">
          <div
            v-for="item in unarrangedCourses"
            :key="item.courseId"
            class="px-5 py-3.5 flex flex-wrap items-center justify-between gap-2"
          >
            <div>
              <span class="text-[14px] font-medium text-[var(--ink-primary)]">{{ item.courseName }}</span>
              <span class="text-[var(--ink-muted)] ml-2 font-mono num-tabular">{{ item.courseId }}</span>
            </div>
            <div class="text-[var(--ink-secondary)] flex flex-wrap gap-x-4 gap-y-1 num-tabular">
              <span>教师：{{ item.teacher || '待定' }}</span>
              <span>周次：{{ item.weeks || '全学期' }}</span>
            </div>
          </div>
        </div>
      </UiCard>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import TimetableCourseCard from '@/components/timetable/TimetableCourseCard.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';
import { useToast } from '@/composables/useToast.js';
import { BASE_SLOT_TIMES, COMBINE_SLOT_TIMES } from '@/utils/periodTimes.js';

import { timetableDayDate } from '@/utils/timetableDates.js';
import { isCourseActiveInWeek } from '@/utils/courseWeeks.js';
import { buildIcsCalendar, downloadIcs } from '@/utils/ics.js';

const { isLoggedIn, isSessionExpired, currentWeek, semesterTeachingWeeks, calendarWeekCountKnown, isSchoolHoliday, openLoginModal } = useSession();
const { timetableCombine, timetableBase, syncing, lastSyncText } = useAcademicData();
const { showToast } = useToast();

const showLoginPrompt = computed(() => !isLoggedIn.value && !isSessionExpired.value);

const selectedWeek = ref(currentWeek.value);
const onlyCurrentWeek = ref(false);
const viewMode = ref('combine'); // 'combine' | 'base'
const currentDayIndex = ref(new Date().getDay() || 7);
const mobileSelectedDay = ref(new Date().getDay() || 7);
const mobileLayout = ref('day'); // 'day' | 'week'

const loading = computed(() => syncing.value && activeTimetable.value.cells.length === 0);

const activeTimetable = computed(() =>
  viewMode.value === 'combine' ? timetableCombine.value : timetableBase.value
);
const courses = computed(() => activeTimetable.value.cells || []);
const unarrangedCourses = computed(() => activeTimetable.value.unarranged || []);

/** 当前展开为详情大卡的课程（过渡与关闭动作由卡片自身完成） */
const selectedCourse = ref(null);
/**
 * 课程稳定标识：缓存数据可能被响应式代理包裹，直接比较对象引用不可靠，
 * 因此用「课程名 + 排课 ID」判断哪张卡处于展开态。
 */
function courseKey(course) {
  return course ? `${course.courseName}｜${course.id ?? ''}` : '';
}
const expandedCourseKey = computed(() => courseKey(selectedCourse.value));
const expandedLayout = ref('');
const showWeekPicker = ref(false);
const weekPickerAnchorRef = ref(null);

const daysOfWeek = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const shortDaysOfWeek = ['一', '二', '三', '四', '五', '六', '日'];

function dateForDay(day) {
  return timetableDayDate(currentWeek.value, selectedWeek.value, day);
}

const activeSlots = computed(() => {
  return viewMode.value === 'combine' ? COMBINE_SLOT_TIMES : BASE_SLOT_TIMES;
});

function prevWeek() {
  if (selectedWeek.value > 1) selectedWeek.value -= 1;
}

function nextWeek() {
  if (selectedWeek.value < semesterTeachingWeeks.value) selectedWeek.value += 1;
}

function isCourseActiveThisWeek(course) {
  return isCourseActiveInWeek(course, selectedWeek.value);
}

function pickWeek(week) {
  selectedWeek.value = week;
  showWeekPicker.value = false;
}

function backToCurrentWeek() {
  selectedWeek.value = currentWeek.value;
}

function isTodayColumn(dayNum) {
  return !isSchoolHoliday.value && selectedWeek.value === currentWeek.value && dayNum === currentDayIndex.value;
}


const visibleCourses = computed(() =>
  courses.value.filter(c => !onlyCurrentWeek.value || isCourseActiveThisWeek(c))
);

const hasAnyCourse = computed(() => visibleCourses.value.length > 0);
const occupiedSections = computed(() => new Set(visibleCourses.value.map(c => c.sectionIndex)));
const occupiedDays = computed(() => new Set(visibleCourses.value.map(c => c.day)));

function dayHasCourses(day) {
  return visibleCourses.value.some(c => c.day === day);
}

function isRowOccupied(period) {
  return !hasAnyCourse.value || occupiedSections.value.has(period);
}

function isDayOccupied(day) {
  return !hasAnyCourse.value || occupiedDays.value.has(day);
}

const gridTemplate = computed(() => {
  const dayCols = daysOfWeek.map((_, idx) =>
    isDayOccupied(idx + 1) ? 'minmax(124px, 1fr)' : 'minmax(56px, 0.28fr)'
  );
  return `96px ${dayCols.join(' ')}`;
});

function getVisibleCoursesForSlot(day, period) {
  return visibleCourses.value.filter(c => c.day === day && c.sectionIndex === period);
}

/** 卡片请求展开：只是标记「这张卡处于展开态」，过渡与关闭都由卡片自己完成 */
function onCourseOpen(course, layout = '') {
  expandedLayout.value = layout;
  selectedCourse.value = course;
}

/** 卡片请求收起：清掉展开标记，节点按正常流回到课表网格 */
function onCourseClose() {
  selectedCourse.value = null;
  expandedLayout.value = '';
}

function slotDate(week, day, hour, minute) {
  const now = new Date();
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - ((now.getDay() + 6) % 7));
  const date = new Date(monday);
  date.setDate(date.getDate() + (week - currentWeek.value) * 7 + (day - 1));
  date.setHours(hour, minute, 0, 0);
  return date;
}

function parseSlotRange(range) {
  const m = String(range || '').match(/(\d{1,2}):(\d{2})\D+(\d{1,2}):(\d{2})/);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])] : null;
}

async function exportIcs() {
  const weekCourses = courses.value.filter(c => isCourseActiveInWeek(c, selectedWeek.value));
  if (weekCourses.length === 0) {
    showToast({ title: '本周无可导出的课程', type: 'warning' });
    return;
  }
  const slotTimes = viewMode.value === 'combine' ? COMBINE_SLOT_TIMES : BASE_SLOT_TIMES;
  const events = [];
  for (const c of weekCourses) {
    const range = parseSlotRange(slotTimes[c.sectionIndex - 1]?.time);
    if (!range) continue;
    const [sh, sm, eh, em] = range;
    events.push({
      summary: c.courseName,
      location: c.location || '',
      description: `教师: ${c.teacher || ''} 周次: ${c.weeks || '全学期'}`,
      start: slotDate(selectedWeek.value, c.day, sh, sm),
      end: slotDate(selectedWeek.value, c.day, eh, em)
    });
  }
  if (events.length === 0) {
    showToast({ title: '暂无可导出的课程数据', type: 'warning' });
    return;
  }
  const ics = buildIcsCalendar(events, { prodId: '-//BetterHRBUST//Timetable//CN' });
  const result = await downloadIcs(`HRBUST_课表_第${selectedWeek.value}周.ics`, ics);
  if (result === 'saved') {
    showToast({ title: '日历已导出', message: `已保存第 ${selectedWeek.value} 周 ${events.length} 个课次`, type: 'success' });
  } else if (result === 'fallback') {
    showToast({ title: '日历已开始下载', message: `第 ${selectedWeek.value} 周 ${events.length} 个课次，存入浏览器默认下载目录`, type: 'success' });
  }
}

function onDocumentPointerDown(event) {
  if (!showWeekPicker.value) return;
  if (weekPickerAnchorRef.value && !weekPickerAnchorRef.value.contains(event.target)) {
    showWeekPicker.value = false;
  }
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape' && showWeekPicker.value) {
    showWeekPicker.value = false;
  }
}

watch(showWeekPicker, (open) => {
  if (typeof document === 'undefined') return;
  if (open) {
    document.addEventListener('pointerdown', onDocumentPointerDown, true);
    document.addEventListener('keydown', onDocumentKeydown);
  } else {
    document.removeEventListener('pointerdown', onDocumentPointerDown, true);
    document.removeEventListener('keydown', onDocumentKeydown);
  }
});

onMounted(() => {});
onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('pointerdown', onDocumentPointerDown, true);
    document.removeEventListener('keydown', onDocumentKeydown);
  }
});
</script>
