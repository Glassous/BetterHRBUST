/**
 * 空教室查询状态层
 *
 * 数据来源（见 docs/api/07-classroom.md 与 tools/probe/classroom-probe.mjs 实测）：
 *   · 教学区 / 教学楼选项：级联 POST roomschedulequery.jsdo
 *   · 占用数据：roomschedule_week.jsdo，一次返回「一栋楼 × 一个星期 × 12 小节」
 *   · 按教室查询（roomschedule.jsdo）只返回 26 周空骨架，取不到占用，未接入
 *
 * 因此整周视图需要按 day 1-7 逐日请求（并发 2，带进度），按期缓存到内存。
 * 只把「变动极少」的教学区/教学楼选项写入本地存储（当天有效），占用数据不落盘。
 */

import { computed, reactive, ref } from 'vue';
import { academicApi } from '@/services/academic/api.js';
import { useSession } from '@/composables/useSession.js';
import { registerDataCacheCleaner } from '@/composables/dataCacheBridge.js';
import { storageGetItem, storageSetItem, storageRemoveItem } from '@/services/storage.js';
import {
  DAY_COUNT,
  SECTION_COUNT,
  dayFreeRanges,
  findFreeSlots,
  formatSlot,
  normalizeOccupancy
} from '@/services/academic/classroomSchedule.js';

const CACHE_KEY = 'better_hrbust_cache_classroom_options';
const CACHE_VERSION = 2;
/** 整周聚合时的并发上限：教务系统压力有限，保持轻量 */
const WEEK_CONCURRENCY = 2;

function todayKey() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 今天对应的星期几（1=周一） */
export function todayWeekday() {
  return new Date().getDay() || 7;
}

// ---------------- 单例状态 ----------------

const areas = ref([]);
const buildings = ref([]);
const weeks = ref([]);
const days = ref([]);
const selectedArea = ref('');
const selectedBuilding = ref('');
const selectedWeek = ref(0);
const selectedDay = ref(todayWeekday());

const optionsLoading = ref(false);
const scheduleLoading = ref(false);
const weekLoading = ref(false);
const weekProgress = reactive({ done: 0, total: DAY_COUNT });
const error = ref('');
const errorKind = ref('');
const lastLoadedAt = ref(0);
const initialized = ref(false);

/** `${buildingId}-${week}-${day}` → 该日整楼排课结果 */
const schedules = reactive({});

/** 按教学区缓存的教学楼列表，避免来回切换时重复请求 */
const buildingsByArea = reactive({});
/** 教学区 / 星期选项很少变动，随楼栋一起缓存 */
let cachedAreas = [];
let cachedWeeks = [];
let cachedDays = [];

let initPromise = null;

function readCache() {
  try {
    const raw = storageGetItem(CACHE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (!data || data.version !== CACHE_VERSION) return null;
    return data;
  } catch {
    return null;
  }
}

function writeCache() {
  try {
    storageSetItem(
      CACHE_KEY,
      JSON.stringify({
        version: CACHE_VERSION,
        date: todayKey(),
        areas: cachedAreas,
        weeks: cachedWeeks,
        days: cachedDays,
        buildingsByArea,
        lastArea: selectedArea.value,
        lastBuilding: selectedBuilding.value
      })
    );
  } catch {
    // 存储配额不足时静默忽略（下次进入页面重新请求即可）
  }
}

function scheduleKey(buildingId, week, day) {
  return `${buildingId}-${week}-${day}`;
}

function handleError(err) {
  const { markSessionExpired } = useSession();
  if (err?.isSessionExpired) {
    errorKind.value = 'session';
    error.value = '教务会话已失效，请重新登录后再查询空教室';
    markSessionExpired?.();
    return;
  }
  if (err?.isUrupError) {
    errorKind.value = 'urup';
    error.value = err.message || '教务拒绝了本次查询';
    return;
  }
  errorKind.value = 'network';
  error.value = err?.message || '查询失败，请检查网络后重试';
}

function clearSchedules() {
  Object.keys(schedules).forEach(key => {
    delete schedules[key];
  });
  weekProgress.done = 0;
  weekProgress.total = DAY_COUNT;
  lastLoadedAt.value = 0;
}

/** 当前选择对应的缓存键前缀 */
function currentPrefix() {
  return selectedBuilding.value && selectedWeek.value
    ? `${selectedBuilding.value}-${selectedWeek.value}-`
    : '';
}

/** 当前星期是否有已加载的数据 */
const currentSchedule = computed(() => {
  if (!selectedBuilding.value || !selectedWeek.value) return null;
  return schedules[scheduleKey(selectedBuilding.value, selectedWeek.value, selectedDay.value)] || null;
});

/** 当前教学楼的这一周是否 7 天都加载完成 */
const weekLoaded = computed(() => {
  const prefix = currentPrefix();
  if (!prefix) return false;
  return Array.from({ length: DAY_COUNT }, (_, i) => prefix + (i + 1)).every(key => Boolean(schedules[key]));
});

/** 整周合并后的教室列表；未加载整周时退化为当天 */
const mergedRooms = computed(() => {
  const prefix = currentPrefix();
  if (!prefix) return [];

  if (!weekLoaded.value) {
    return currentSchedule.value?.rooms || [];
  }

  const byId = new Map();
  for (let day = 1; day <= DAY_COUNT; day += 1) {
    const daySchedule = schedules[prefix + day];
    for (const room of daySchedule?.rooms || []) {
      const key = room.id || room.name;
      if (!byId.has(key)) byId.set(key, { ...room, occupancy: [] });
      byId.get(key).occupancy.push(...(room.occupancy || []));
    }
  }
  return [...byId.values()];
});

/**
 * 教室 + 空闲时段汇总
 *
 * 单日模式只在「这一天」内扫描，避免未查询的日子被误判为整天空闲；
 * 整周模式才按 7 天合并统计。
 */
const summaryRooms = computed(() => {
  const weekScope = weekLoaded.value;
  const day = selectedDay.value;

  const list = mergedRooms.value.map(room => {
    const occupancy = normalizeOccupancy(room.occupancy || [], { dayCount: DAY_COUNT, sectionCount: SECTION_COUNT });
    // 统一保留最短 1 节的空档，具体展示多少连续节由页面的筛选条件决定
    const freeSlots = weekScope
      ? findFreeSlots(occupancy, { minSpan: 1 })
      : dayFreeRanges(occupancy, day, { minSpan: 1 });
    const freeSpan = freeSlots.reduce((sum, slot) => sum + slot.span, 0);
    const occupiedCount = occupancy.reduce((sum, item) => sum + item.span, 0);

    return {
      ...room,
      freeSlots,
      freeSlotLabels: freeSlots.map(formatSlot),
      freeSpan,
      occupiedCount
    };
  });

  // 空闲时间多的排前面，占用少的优先，最后按名称稳定排序
  return list.sort((a, b) => {
    if (b.freeSpan !== a.freeSpan) return b.freeSpan - a.freeSpan;
    if (a.occupiedCount !== b.occupiedCount) return a.occupiedCount - b.occupiedCount;
    return String(a.name).localeCompare(String(b.name), 'zh-Hans-CN');
  });
});

/** 当前结果里「有连续 2 节以上空档、可用于自习」的教室数量 */
const freeRoomCount = computed(
  () => summaryRooms.value.filter(room => room.freeSlots.some(slot => slot.span >= 2)).length
);

/** 本周（或当天）完全没有排课的教室数量 */
const allDayFreeCount = computed(() => summaryRooms.value.filter(room => room.occupiedCount === 0).length);

const selectedBuildingName = computed(() => {
  return buildings.value.find(item => item.id === selectedBuilding.value)?.name
    || currentSchedule.value?.buildingName
    || '';
});

// ---------------- 行为 ----------------

async function loadBuildings(aid) {
  const cached = buildingsByArea[aid];
  if (Array.isArray(cached) && cached.length) {
    buildings.value = cached;
    return;
  }
  const res = await academicApi.getClassroomBuildings({ aid });
  buildings.value = res.buildings || [];
  buildingsByArea[aid] = buildings.value;
  writeCache();
}

/**
 * 初始化：拉取（或复用当天缓存的）教学区与教学楼选项
 */
function init() {
  if (initialized.value) return Promise.resolve();
  if (initPromise) return initPromise;

  initPromise = (async () => {
    optionsLoading.value = true;
    error.value = '';
    errorKind.value = '';

    try {
      const cached = readCache();
      const cacheFresh = cached && cached.date === todayKey();

      if (cacheFresh && Array.isArray(cached.areas) && cached.areas.length) {
        cachedAreas = cached.areas;
        cachedWeeks = cached.weeks || [];
        cachedDays = cached.days || [];
        areas.value = cachedAreas;
        weeks.value = cachedWeeks;
        days.value = cachedDays;
        Object.assign(buildingsByArea, cached.buildingsByArea || {});
        selectedArea.value = cachedAreas.some(item => item.id === cached.lastArea)
          ? cached.lastArea
          : cachedAreas[0].id;
      } else {
        const res = await academicApi.getClassroomQueryOptions();
        cachedAreas = res.areas || [];
        cachedWeeks = res.weeks || [];
        cachedDays = res.days || [];
        areas.value = cachedAreas;
        weeks.value = cachedWeeks;
        days.value = cachedDays;
        selectedArea.value = cachedAreas[0]?.id || '';
      }

      // 默认周次：当前教学周（限制在教务给出的可选范围内）
      const { currentWeek } = useSession();
      const availableWeeks = weeks.value.map(item => item.id);
      const preferredWeek = Number(currentWeek?.value) || 0;
      selectedWeek.value = availableWeeks.includes(preferredWeek)
        ? preferredWeek
        : (availableWeeks[0] || 0);

      if (selectedArea.value) {
        await loadBuildings(selectedArea.value);
        const lastBuilding = cached?.lastBuilding;
        selectedBuilding.value = buildings.value.some(item => item.id === lastBuilding)
          ? lastBuilding
          : (buildings.value[0]?.id || '');
      }

      if (!days.value.length) {
        days.value = Array.from({ length: DAY_COUNT }, (_, i) => ({ id: i + 1, name: `周${'一二三四五六日'[i]}` }));
      }

      initialized.value = true;
      writeCache();
    } catch (err) {
      handleError(err);
    } finally {
      optionsLoading.value = false;
      initPromise = null;
    }
  })();

  return initPromise;
}

/** 切换教学区：刷新教学楼列表并作废旧结果 */
async function selectArea(aid) {
  if (!aid || aid === selectedArea.value) return;
  selectedArea.value = aid;
  selectedBuilding.value = '';
  buildings.value = [];
  clearSchedules();
  error.value = '';
  errorKind.value = '';

  optionsLoading.value = true;
  try {
    await loadBuildings(aid);
    selectedBuilding.value = buildings.value[0]?.id || '';
    writeCache();
  } catch (err) {
    handleError(err);
  } finally {
    optionsLoading.value = false;
  }
}

/** 切换教学楼：作废已加载的排课结果 */
function selectBuilding(id) {
  if (!id || id === selectedBuilding.value) return;
  selectedBuilding.value = id;
  clearSchedules();
  error.value = '';
  errorKind.value = '';
  writeCache();
}

/** 切换周次：作废已加载的排课结果 */
function selectWeek(week) {
  const value = Number(week);
  if (!value || value === selectedWeek.value) return;
  selectedWeek.value = value;
  clearSchedules();
  error.value = '';
  errorKind.value = '';
}

/** 切换星期：若有缓存则秒开，不自动联网（由页面决定是否查询） */
function selectDay(day) {
  const value = Number(day);
  if (!value || value === selectedDay.value) return;
  selectedDay.value = value;
}

/** 单日查询（内部函数，不带加载态） */
async function fetchDay(day) {
  const key = scheduleKey(selectedBuilding.value, selectedWeek.value, day);
  if (schedules[key]) return schedules[key];

  const data = await academicApi.getClassroomDaySchedule({
    aid: selectedArea.value,
    buildingId: selectedBuilding.value,
    week: selectedWeek.value,
    day
  });
  schedules[key] = data;
  lastLoadedAt.value = Date.now();
  return data;
}

/** 查询当前选择的这一天（已缓存则直接返回） */
async function queryDay(day = selectedDay.value) {
  if (!selectedBuilding.value || !selectedWeek.value) return null;
  if (schedules[scheduleKey(selectedBuilding.value, selectedWeek.value, day)]) {
    return schedules[scheduleKey(selectedBuilding.value, selectedWeek.value, day)];
  }

  scheduleLoading.value = true;
  error.value = '';
  errorKind.value = '';
  try {
    return await fetchDay(day);
  } catch (err) {
    handleError(err);
    return null;
  } finally {
    scheduleLoading.value = false;
  }
}

/**
 * 加载整周：按 day 1-7 并发 2 请求，带进度
 * 已经查询过的星期会跳过，因此重复点击不会重复抓取
 */
async function loadWeek() {
  if (!selectedBuilding.value || !selectedWeek.value || weekLoading.value) return;

  const prefix = currentPrefix();
  const missing = Array.from({ length: DAY_COUNT }, (_, i) => i + 1)
    .filter(day => !schedules[prefix + day]);

  if (!missing.length) return;

  weekLoading.value = true;
  weekProgress.total = missing.length;
  weekProgress.done = 0;
  error.value = '';
  errorKind.value = '';

  const queue = [...missing];
  let firstError = null;

  async function worker() {
    while (queue.length) {
      const day = queue.shift();
      try {
        await fetchDay(day);
      } catch (err) {
        if (!firstError) firstError = err;
      } finally {
        weekProgress.done += 1;
      }
    }
  }

  try {
    await Promise.all(
      Array.from({ length: Math.min(WEEK_CONCURRENCY, queue.length) }, () => worker())
    );
    if (firstError) handleError(firstError);
  } finally {
    weekLoading.value = false;
  }
}

/** 强制刷新当前星期（忽略缓存） */
async function refreshDay() {
  const key = scheduleKey(selectedBuilding.value, selectedWeek.value, selectedDay.value);
  delete schedules[key];
  return queryDay(selectedDay.value);
}

/** 清理本地选项缓存（登出 / 切换账号时由 useSession 触发） */
export function clearClassroomOptionsCache() {
  storageRemoveItem(CACHE_KEY);
  cachedAreas = [];
  cachedWeeks = [];
  cachedDays = [];
  Object.keys(buildingsByArea).forEach(key => {
    delete buildingsByArea[key];
  });
  areas.value = [];
  buildings.value = [];
  weeks.value = [];
  days.value = [];
  selectedArea.value = '';
  selectedBuilding.value = '';
  selectedWeek.value = 0;
  clearSchedules();
  initialized.value = false;
  initPromise = null;
  error.value = '';
  errorKind.value = '';
}

registerDataCacheCleaner(clearClassroomOptionsCache);

export function useClassroomQuery() {
  return {
    // 选项
    areas,
    buildings,
    weeks,
    days,
    selectedArea,
    selectedBuilding,
    selectedWeek,
    selectedDay,

    // 结果
    currentSchedule,
    summaryRooms,
    freeRoomCount,
    allDayFreeCount,
    weekLoaded,
    selectedBuildingName,
    lastLoadedAt,

    // 状态
    optionsLoading,
    scheduleLoading,
    weekLoading,
    weekProgress,
    error,
    errorKind,
    initialized,

    // 行为
    init,
    selectArea,
    selectBuilding,
    selectWeek,
    selectDay,
    queryDay,
    loadWeek,
    refreshDay
  };
}
