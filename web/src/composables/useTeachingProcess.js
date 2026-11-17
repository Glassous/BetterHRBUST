/**
 * 本科教学进程的状态层：学期 / 校区 / 我的班级 / 搜索 / 视图模式。
 *
 * 数据是构建期内置的静态快照（shared/teaching-process.json），离线可用、不落网。
 * 模块级单例，与 useSession / useAcademicData 的用法保持一致。
 */
import { computed, ref } from 'vue';
import raw from '../../../shared/teaching-process.json';
import { useSession } from '@/composables/useSession.js';
import { storageGetItem, storageRemoveItem, storageSetItem } from '@/services/storage.js';
import { pickBestClass } from '@/utils/classMatcher.js';
import {
  buildSegments,
  decodePattern,
  groupByPattern,
  snapshotAt,
  weekIndexOn
} from '@/utils/teachingProcess.js';
import { cellParts } from '@/utils/teachingProcessSymbols.js';
import { semesterOn } from '@/utils/schoolCalendar.js';

const MY_CLASS_KEY = 'better_hrbust_process_class';
const STALE_DAYS = 210;

const data = raw;

/** 学期按学年学期倒序，最新的在前 */
const semesters = data.semesters.toSorted(
  (left, right) => Number(right.academicYear.slice(0, 4)) - Number(left.academicYear.slice(0, 4)) || right.term - left.term
);

const currentSemesterId =
  semesters.find(semester => weekIndexOn(semester.weeks) !== null)?.id || semesters[0]?.id || '';

const sourceUpdatedAt = data.sourceUpdatedAt || '';
const updatedAt = data.updatedAt || '';
const ageDays = sourceUpdatedAt
  ? Math.floor((Date.now() - new Date(`${sourceUpdatedAt}T00:00:00`).getTime()) / 86400000)
  : 0;

const segmentCache = new Map();

function segmentsOf(patternIndex) {
  if (!segmentCache.has(patternIndex)) {
    segmentCache.set(patternIndex, buildSegments(decodePattern(data.patterns[patternIndex])));
  }
  return segmentCache.get(patternIndex);
}

function readStoredClass() {
  try {
    const parsed = JSON.parse(storageGetItem(MY_CLASS_KEY) || 'null');
    if (parsed && typeof parsed.name === 'string' && parsed.name) {
      return { name: parsed.name, campus: String(parsed.campus || '') };
    }
  } catch {
    // 存储损坏时按未指定处理
  }
  return null;
}

const semesterId = ref(currentSemesterId);
const campusName = ref('');
const query = ref('');
const view = ref('pattern');
const myClass = ref(readStoredClass());
const guessDismissed = ref(false);

const semester = computed(() => semesters.find(item => item.id === semesterId.value) || semesters[0]);
const campuses = computed(() => semester.value?.campuses || []);
const campus = computed(() => campuses.value.find(item => item.name === campusName.value) || campuses.value[0] || null);
const classes = computed(() =>
  (campus.value?.classes || []).map(([name, hours, patternIndex]) => ({ name, hours, patternIndex }))
);
const patternCount = computed(() => new Set(classes.value.map(item => item.patternIndex)).size);
const weekCount = computed(() => semester.value?.weeks.length || 0);
const currentWeek = computed(() => weekIndexOn(semester.value?.weeks));
const isCurrentSemester = computed(() => semesterId.value === currentSemesterId);
const latestWeek = computed(() => semester.value?.weeks[semester.value.weeks.length - 1] || null);

/** 我的班级在本学期的记录；跨学期按名字重新定位，找不到时返回 null */
const myClassEntry = computed(() => {
  const target = myClass.value?.name;
  if (!target) return null;
  for (const item of campuses.value) {
    const matched = item.classes.find(entry => entry[0] === target);
    if (matched) return { name: matched[0], hours: matched[1], patternIndex: matched[2], campus: item.name };
  }
  return null;
});

/** 未指定班级时，按档案推测一个候选，但只作为建议展示 */
const myClassGuess = computed(() => {
  if (myClass.value || guessDismissed.value) return null;
  const profile = useSession().userProfile;
  if (!profile.className && !profile.major) return null;
  const candidates = campuses.value.flatMap(item => item.classes.map(entry => ({ name: entry[0], campus: item.name })));
  const best = pickBestClass(
    { className: profile.className, major: profile.major, grade: profile.grade, college: profile.college },
    candidates
  );
  return best && best.confident ? best : null;
});

const normalizedQuery = computed(() => query.value.trim().toLowerCase());

const filteredClasses = computed(() => {
  if (!normalizedQuery.value) return classes.value;
  return classes.value.filter(item => item.name.toLowerCase().includes(normalizedQuery.value));
});

const groups = computed(() =>
  groupByPattern(filteredClasses.value, data.patterns, myClassEntry.value?.name || '')
);

const weekSnapshot = computed(() => {
  if (!currentWeek.value) return [];
  return snapshotAt(classes.value, data.patterns, currentWeek.value);
});

function setSemester(id) {
  if (!semesters.some(item => item.id === id)) return;
  semesterId.value = id;
  campusName.value = '';
}

function setCampus(name) {
  campusName.value = name;
}

function setQuery(value) {
  query.value = value;
}

function setView(value) {
  view.value = value;
}

function setMyClass(name, campusValue = '') {
  myClass.value = { name, campus: campusValue };
  guessDismissed.value = true;
  try {
    storageSetItem(MY_CLASS_KEY, JSON.stringify(myClass.value));
  } catch {
    // 存储不可用时仅本次会话生效
  }
}

function clearMyClass() {
  myClass.value = null;
  guessDismissed.value = true;
  try {
    storageRemoveItem(MY_CLASS_KEY);
  } catch {
    // 忽略
  }
}

function confirmGuess() {
  if (myClassGuess.value) setMyClass(myClassGuess.value.name, myClassGuess.value.campus);
}

function goToCurrentSemester() {
  setSemester(currentSemesterId);
}

/** 指定班级在某一周的阶段 */
function stagesOfEntry(entry, week) {
  if (!entry || !week) return [];
  const cells = decodePattern(data.patterns[entry.patternIndex]);
  return cellParts(cells[week - 1]);
}

/** 某一周的阶段：优先我的班级，否则用本校区该周最主要的安排 */
function stagesOfWeek(week) {
  const safeWeek = Number(week);
  if (!safeWeek || safeWeek < 1 || safeWeek > weekCount.value) return [];
  const entry = myClassEntry.value;
  if (entry) return stagesOfEntry(entry, safeWeek);
  return snapshotAt(classes.value, data.patterns, safeWeek).slice(0, 2);
}

const calendarBehind = computed(() => {
  const official = semesterOn();
  const latest = latestWeek.value;
  if (!official || !latest) return false;
  return official.startDate > latest.mon;
});

const freshness = computed(() => ({
  sourceUpdatedAt,
  updatedAt,
  ageDays,
  stale: ageDays > STALE_DAYS,
  warnings: data.warnings || []
}));

export function useTeachingProcess() {
  return {
    source: data.source,
    patterns: data.patterns,
    semesters,
    semester,
    semesterId,
    campuses,
    campus,
    classes,
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
    setSemester,
    setCampus,
    setQuery,
    setView,
    setMyClass,
    clearMyClass,
    confirmGuess,
    goToCurrentSemester,
    stagesOfWeek,
    stagesOfEntry
  };
}
