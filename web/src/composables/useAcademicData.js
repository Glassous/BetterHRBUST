/**
 * 教务数据集中层（全量加载 + 本地持久化）
 *
 * 策略（与用户约定一致）：
 * 1. 登录成功后一次性全量拉取（上下文 / 档案 / 校历 / 课表 / 成绩 / 考试 / 培养方案 / 公告）并持久化到本地存储；
 * 2. 之后进入任何页面只读本地缓存，不再自动联网；
 * 3. 仅在【每天首次打开】与【用户手动刷新】两种情况下触网；
 * 4. 手动刷新时若会话已失效，则要求用户重新登录（交由 useSession 的节流判定处理）。
 */

import { ref, reactive, computed } from 'vue';
import { academicApi } from '@/services/academic/api.js';
import { useSession } from '@/composables/useSession.js';
import { registerDataCacheCleaner, clearRegisteredDataCaches } from '@/composables/dataCacheBridge.js';
import { registerCourseColors } from '@/utils/courseColors.js';
import { splitCourseCellFields } from '@/utils/courseCell.js';
import { storageGetItem, storageSetItem, storageRemoveItem } from '@/services/storage.js';

const CACHE_KEYS = {
  meta: 'better_hrbust_cache_sync_meta',
  scores: 'better_hrbust_cache_scores',
  yearOptions: 'better_hrbust_cache_score_years',
  plan: 'better_hrbust_cache_program_plan',
  timetableCombine: 'better_hrbust_cache_timetable',
  timetableBase: 'better_hrbust_cache_timetable_base',
  exams: 'better_hrbust_cache_exams',
  notices: 'better_hrbust_cache_notices',
  noticeWeeks: 'better_hrbust_cache_notices_weeks',
  currentCourses: 'better_hrbust_cache_current_courses'
};

function todayKey() {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

function readJson(key, fallback) {
  try {
    const raw = storageGetItem(key);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw);
    return parsed === null || parsed === undefined ? fallback : parsed;
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  try {
    storageSetItem(key, JSON.stringify(value));
  } catch {
    // 存储配额不足时静默忽略，不影响内存态
  }
}

/**
 * 修复历史缓存中的课表单元格。
 *
 * 旧解析器按固定行号读取「地点/教师/周次/学时类型」，遇到没有教师行的课程
 * （军训、体育等）会整体错位，把学时类型当成周次 —— 周次判断因此失效，
 * 已结束的课程在后续教学周仍显示为正常颜色。
 * rawLines 一直随缓存持久化，这里按内容重算字段，无需等待下一次全量同步。
 */
function normalizeTimetable(cached) {
  const data = cached && Array.isArray(cached.cells) ? cached : { cells: [], unarranged: [] };
  return {
    ...data,
    cells: data.cells.map(cell => {
      if (!cell || !Array.isArray(cell.rawLines) || cell.rawLines.length < 2) return cell;
      return { ...cell, ...splitCourseCellFields(cell.rawLines) };
    })
  };
}

/** 历史版本曾用当天日期伪造公告日期（源页面并无该信息）：读取缓存时统一清除 */
function sanitizeNotices(list) {
  if (!Array.isArray(list) || !list.some(n => n && n.date)) return list;
  return list.map(n => (n && n.date ? { ...n, date: '' } : n));
}

// ---------------- 单例状态 ----------------

const scores = ref([]);
const yearOptions = ref([]);
const plan = ref({ groups: [] });
const timetableCombine = ref({ cells: [], unarranged: [] });
const timetableBase = ref({ cells: [], unarranged: [] });
const exams = ref([]);
const notices = ref([]);
const currentCourses = ref([]);
const syncMeta = reactive({
  lastSyncAt: 0,
  lastSyncDate: '',
  lastError: ''
});

let cacheGeneration = 0;
const syncing = ref(false);
const syncError = ref('');

function loadCache() {
  scores.value = readJson(CACHE_KEYS.scores, []);
  yearOptions.value = readJson(CACHE_KEYS.yearOptions, []);
  plan.value = readJson(CACHE_KEYS.plan, { groups: [] }) || { groups: [] };
  timetableCombine.value = normalizeTimetable(readJson(CACHE_KEYS.timetableCombine, { cells: [], unarranged: [] }));
  timetableBase.value = normalizeTimetable(readJson(CACHE_KEYS.timetableBase, { cells: [], unarranged: [] }));
  exams.value = readJson(CACHE_KEYS.exams, []);
  notices.value = sanitizeNotices(readJson(CACHE_KEYS.notices, []));
  currentCourses.value = readJson(CACHE_KEYS.currentCourses, []);

  const meta = readJson(CACHE_KEYS.meta, null);
  if (meta) {
    syncMeta.lastSyncAt = meta.lastSyncAt || 0;
    syncMeta.lastSyncDate = meta.lastSyncDate || '';
    syncMeta.lastError = meta.lastError || '';
  }

  // 为缓存中的全部课程注册色相槽位：保证每门课颜色唯一，且课表/概览等页面取色一致
  registerCourseColors(timetableCombine.value.cells);
  registerCourseColors(timetableBase.value.cells);
}

loadCache();

function persistMeta() {
  writeJson(CACHE_KEYS.meta, {
    lastSyncAt: syncMeta.lastSyncAt,
    lastSyncDate: syncMeta.lastSyncDate,
    lastError: syncMeta.lastError
  });
}

/** 是否已有可用的本地数据 */
const hasCachedData = computed(() => {
  return (
    scores.value.length > 0 ||
    (plan.value?.groups?.length || 0) > 0 ||
    (timetableCombine.value?.cells?.length || 0) > 0 ||
    exams.value.length > 0
  );
});

/** 今天是否尚未做过全量同步 */
const needsDailySync = computed(() => syncMeta.lastSyncDate !== todayKey());

/** 上次同步时间的人类可读文本 */
const lastSyncText = computed(() => {
  if (!syncMeta.lastSyncAt) return '尚未同步';
  const d = new Date(syncMeta.lastSyncAt);
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
});

function isSessionExpiredError(err) {
  return !!(err && (err.isSessionExpired || err.status === 401));
}

/**
 * 执行一次全量同步（并发）
 * @returns {Promise<{ success: boolean, expired: boolean, message?: string }>}
 */
async function syncAll({ markManual = false } = {}) {
  const { studentId, studentNumber, currentSemester, currentWeek, semesterTeachingWeeks, calendarWeekCountKnown, isLoggedIn, userProfile, markSessionExpired, resetProfile } =
    useSession();

  if (!isLoggedIn.value) {
    // 手动刷新时未登录 → 要求重新登录（无条件提示）
    if (markManual) markSessionExpired({ manual: true });
    return { success: false, expired: true, message: '当前未登录，请重新登录后再刷新数据' };
  }
  if (syncing.value) {
    return { success: false, expired: false, message: '正在同步中，请稍候' };
  }

  syncing.value = true;
  syncError.value = '';
  const generation = cacheGeneration;
  const account = studentNumber.value;
  const expectedStudentId = studentId.value;
  const isCurrent = () => generation === cacheGeneration && account === studentNumber.value && isLoggedIn.value;

  let expired = false;
  const failures = [];

  try {
    // 先取上下文：内部学生 ID 与当前学年学期是课表请求的必要参数
    try {
      const ctx = await academicApi.getStudentContext();
      if (!isCurrent()) return { success: false, expired: false, message: '账号已切换，旧同步结果已丢弃' };
      if (expectedStudentId && ctx.studentId && ctx.studentId !== expectedStudentId) {
        // 与 checkAuth 对同一事件的处理保持一致：完整清理旧账号的数据缓存与档案 PII
        // （clearRegisteredDataCaches 会连带调用本模块的 clearDataCache）
        clearRegisteredDataCaches();
        resetProfile();
        studentId.value = '';
        markSessionExpired({ manual: markManual });
        return { success: false, expired: true, message: '教务会话账号已变化，请重新登录' };
      }
      if (ctx.studentId) studentId.value = ctx.studentId;
      if (ctx.year) currentSemester.yearId = ctx.year;
      if (ctx.term) currentSemester.termId = ctx.term;
      currentCourses.value = ctx.courses || [];
      writeJson(CACHE_KEYS.currentCourses, currentCourses.value);
    } catch (err) {
      if (!isCurrent()) return { success: false, expired: false, message: '账号已切换，旧同步结果已丢弃' };
      if (isSessionExpiredError(err)) {
        markSessionExpired({ manual: markManual });
        return { success: false, expired: true, message: '会话已失效，请重新登录' };
      }
      failures.push(`上下文：${err.message}`);
    }

    // 不使用硬编码学期兜底：上下文未取到真实学年学期时跳过课表请求，避免用错误学期覆盖本地正确缓存
    const yearId = currentSemester.yearId;
    const termId = currentSemester.termId;
    const sid = studentId.value;
    if (!yearId || !termId) {
      failures.push('课表：未获取到当前学年学期，已跳过本次课表同步');
    }

    const run = async (label, fn, apply) => {
      try {
        const res = await fn();
        if (!isCurrent()) return false;
        apply(res);
        return true;
      } catch (err) {
        if (!isCurrent()) return false;
        if (isSessionExpiredError(err)) {
          expired = true;
          return false;
        }
        failures.push(`${label}：${err.message}`);
        return false;
      }
    };

    const tasks = [
      run(
        '成绩',
        () => academicApi.getScores(),
        res => {
          scores.value = res.scores || [];
          yearOptions.value = res.yearOptions || [];
          writeJson(CACHE_KEYS.scores, scores.value);
          writeJson(CACHE_KEYS.yearOptions, yearOptions.value);
        }
      ),
      run(
        '培养方案',
        () => academicApi.getCurriculumPlan({ studentId: sid }),
        res => {
          plan.value = res || { groups: [] };
          writeJson(CACHE_KEYS.plan, plan.value);
        }
      ),
      run(
        '考试',
        () => academicApi.getExams(),
        res => {
          exams.value = Array.isArray(res) ? res : [];
          writeJson(CACHE_KEYS.exams, exams.value);
        }
      ),
      run(
        '个人档案',
        () => academicApi.getPersonalInfo(),
        res => {
          if (String(res.studentNumber || '').trim() !== account) throw new Error('档案账号与当前登录账号不一致，已拒绝更新');
          Object.assign(userProfile, res);
          userProfile.internalId = sid;
          if (res.studentNumber) studentNumber.value = res.studentNumber;
          writeJson('better_hrbust_cached_profile', { ...userProfile });
        }
      ),
      run(
        '公告',
        () => academicApi.getCalendarInfo(currentWeek.value),
        res => {
          // 学期总周数来自校历（shared 校历数据）；旧导航的 26 不作为当前教学周上限
          if (res.teachingWeeks) {
            semesterTeachingWeeks.value = res.teachingWeeks;
            calendarWeekCountKnown.value = true;
          }
          if (res.currentWeek >= 1 && res.currentWeek <= semesterTeachingWeeks.value) currentWeek.value = res.currentWeek;
          if (res.semesterName) currentSemester.name = res.semesterName;
          notices.value = res.notices || [];
          writeJson(CACHE_KEYS.notices, notices.value);
        }
      )
    ];

    // 课表：大节 (COMBINE) 与小节 (BASE) 各缓存一份，供课表页切换视图时离线使用
    if (sid && yearId && termId) {
      tasks.push(
        run(
          '课表(大节)',
          () => academicApi.getTimetable({ studentId: sid, yearId, termId, sectionType: 'COMBINE' }),
          res => {
            timetableCombine.value = { cells: res.cells || [], unarranged: res.unarranged || [] };
            registerCourseColors(timetableCombine.value.cells);
            writeJson(CACHE_KEYS.timetableCombine, timetableCombine.value);
          }
        )
      );
      tasks.push(
        run(
          '课表(小节)',
          () => academicApi.getTimetable({ studentId: sid, yearId, termId, sectionType: 'BASE' }),
          res => {
            timetableBase.value = { cells: res.cells || [], unarranged: res.unarranged || [] };
            registerCourseColors(timetableBase.value.cells);
            writeJson(CACHE_KEYS.timetableBase, timetableBase.value);
          }
        )
      );
    }

    await Promise.allSettled(tasks);
    if (!isCurrent()) return { success: false, expired: false, message: '账号已切换，旧同步结果已丢弃' };

    if (expired) {
      markSessionExpired({ manual: markManual });
      return { success: false, expired: true, message: '会话已失效，请重新登录' };
    }

    syncMeta.lastSyncAt = Date.now();
    syncMeta.lastSyncDate = todayKey();
    syncMeta.lastError = failures.join('；');
    syncError.value = syncMeta.lastError;
    persistMeta();

    return {
      success: failures.length === 0,
      expired: false,
      message: failures.length === 0 ? '数据已全部同步' : `部分数据同步失败：${failures.join('；')}`
    };
  } finally {
    // 三条 isCurrent() 早退路径与会话失效路径统一在此复位：
    // 否则并发会话失效后 syncing 永久为 true，刷新按钮永久禁用
    syncing.value = false;
  }
}

/**
 * 每天首次打开时自动同步
 * @returns {Promise<{ skipped: boolean }>}
 */
async function ensureDailySync() {
  const { isLoggedIn } = useSession();
  if (!isLoggedIn.value) return { skipped: true };
  if (!needsDailySync.value) return { skipped: true };
  await syncAll({ markManual: false });
  return { skipped: false };
}

/**
 * 用户手动刷新（顶部工具栏 / 页面刷新按钮）
 * 会话失效时无条件要求重新登录
 */
async function refreshAll() {
  return syncAll({ markManual: true });
}

/**
 * 读取指定周次的公告（优先本地缓存，未命中时按需拉取一次）
 * @throws 网络失败时抛出原始错误，调用方据此区分「本周无公告」与「加载失败」；
 *         会话失效错误在抛出前已触发 markSessionExpired
 */
async function loadNoticesForWeek(week) {
  const generation = cacheGeneration;
  if (!week) return notices.value;
  const weekMap = readJson(CACHE_KEYS.noticeWeeks, {}) || {};
  const weekKey = String(week);
  // 已查询过的周次直接返回（含"该周确实无公告"的空结果，避免反复联网）
  if (Object.prototype.hasOwnProperty.call(weekMap, weekKey)) {
    return sanitizeNotices(weekMap[weekKey]);
  }
  try {
    const res = await academicApi.getCalendarInfo(week);
    if (generation !== cacheGeneration) return [];
    const list = res.notices || [];
    weekMap[weekKey] = list;
    writeJson(CACHE_KEYS.noticeWeeks, weekMap);
    return list;
  } catch (err) {
    if (isSessionExpiredError(err)) {
      useSession().markSessionExpired({ manual: false });
    }
    throw err;
  }
}

/** 清空全部本地数据缓存（退出登录 / 切换账号时调用） */
function clearDataCache() {
  ++cacheGeneration;
  syncing.value = false;
  Object.values(CACHE_KEYS).forEach(key => {
    try {
      storageRemoveItem(key);
    } catch {
      // 忽略
    }
  });
  scores.value = [];
  yearOptions.value = [];
  plan.value = { groups: [] };
  timetableCombine.value = { cells: [], unarranged: [] };
  timetableBase.value = { cells: [], unarranged: [] };
  exams.value = [];
  notices.value = [];
  currentCourses.value = [];
  syncMeta.lastSyncAt = 0;
  syncMeta.lastSyncDate = '';
  syncMeta.lastError = '';
  syncError.value = '';
}

// 供 useSession 在登出 / 切换账号时清理集中式数据缓存
registerDataCacheCleaner(clearDataCache);

export function useAcademicData() {
  return {
    // 数据态
    scores,
    yearOptions,
    plan,
    timetableCombine,
    timetableBase,
    exams,
    notices,
    currentCourses,
    hasCachedData,
    needsDailySync,
    lastSyncText,
    syncing,
    syncError,

    // 行为
    syncAll,
    ensureDailySync,
    refreshAll,
    loadNoticesForWeek,
    clearDataCache
  };
}
