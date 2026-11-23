/**
 * AI 只读工具集（9 个，与 TUI `internal/query/toolset.go` 同名同参）
 *
 * 设计前提：工具读的是**页面渲染用的同一批缓存数据**，因此模型给出的数字
 * 与界面完全一致；任何字段离开进程前都必须已经打码（mask.js）。
 *
 * 两条硬规则：
 *  1. `send_profile=false` 时把 `query_profile` 从定义里**摘掉**（不是运行时拒绝）——
 *     模型看不到工具，也就不可能调用它；
 *  2. 返回记录数上限 40（MaxToolRecords），超出用 meta.truncated 标注，不要悄悄截断。
 */

import { useAcademicData } from '@/composables/useAcademicData.js';
import { useSession } from '@/composables/useSession.js';
import { buildAcademicStats, computeCreditsProgress, dedupeScores, isCountedCourse } from '@/services/academic/stats.js';
import { isCourseActiveInWeek } from '@/utils/courseWeeks.js';
import { buildEffectiveTimetableCells } from '@/utils/timetableOverrides.js';
import { maskProfile } from './mask.js';

/** 单次工具结果的记录上限（与 TUI 一致） */
export const MAX_TOOL_RECORDS = 40;

/** 工具参数里允许的课程属性取值 */
const PROPERTY_VALUES = ['必修', '限选', '任选', '其他'];

function clampInt(value, low, high, fallback) {
  const number = Math.trunc(Number(value));
  if (!Number.isFinite(number)) return fallback;
  return Math.min(high, Math.max(low, number));
}

function todayIso() {
  const now = new Date();
  const pad = n => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** ISO 日期 → 星期几（1=周一）；非法输入返回今天 */
function weekdayOf(iso) {
  const text = String(iso || '').trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  const date = match
    ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
    : new Date();
  if (Number.isNaN(date.getTime())) return new Date().getDay() || 7;
  return date.getDay() || 7;
}

/** 考试时间是「2026-01-05 14:00-16:00」这类字符串，取日期部分 */
function examDate(time) {
  const match = /(\d{4})-(\d{2})-(\d{2})/.exec(String(time || ''));
  if (!match) return null;
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
}

function daysBetween(from, to) {
  const day = 24 * 60 * 60 * 1000;
  const start = new Date(from.getFullYear(), from.getMonth(), from.getDate());
  const end = new Date(to.getFullYear(), to.getMonth(), to.getDate());
  return Math.round((end - start) / day);
}

/** 记录裁剪：显式给出 total / truncated，避免模型把"被截断"当成"只有这些" */
function limit(items, max = MAX_TOOL_RECORDS) {
  const list = Array.isArray(items) ? items : [];
  return {
    records: list.slice(0, max),
    total: list.length,
    truncated: list.length > max
  };
}

/** 当前学期的展示信息 */
function semesterInfo(session) {
  const { currentSemester, currentWeek, semesterTeachingWeeks, calendarWeekCountKnown } = session;
  return {
    name: currentSemester?.name || '',
    yearId: currentSemester?.yearId || '',
    termId: currentSemester?.termId || '',
    week: Number(currentWeek?.value ?? currentWeek) || 0,
    teachingWeeks: Number(semesterTeachingWeeks?.value ?? semesterTeachingWeeks) || 0,
    weeksKnown: !!calendarWeekCountKnown?.value
  };
}

/** 数据就绪状态：ready=已登录且有缓存 / cached=离线缓存 / empty=无数据 */
function readinessOf(loggedIn, hasCachedData) {
  if (loggedIn) return hasCachedData ? 'ready' : 'empty';
  return hasCachedData ? 'cached' : 'empty';
}

/** 统一包装：工具结果 = data + meta（TUI wrap() 的等价物） */
function wrap(data, context, total) {
  return {
    data,
    meta: {
      generatedAt: new Date().toISOString(),
      lastSyncText: context.lastSyncText,
      offline: !context.loggedIn,
      readiness: context.readiness,
      total: typeof total === 'number' ? total : undefined,
      truncated: typeof total === 'number' ? total > MAX_TOOL_RECORDS : false
    }
  };
}

/** 课表投影：按周过滤单元格（含单周临时调课与手动实验课），按天分组 */
function timetableData({ week, base }, context) {
  const source = base ? context.timetableBase : context.timetableCombine;
  const activeWeek = week || context.semester.week || 1;
  const merged = buildEffectiveTimetableCells({
    rawCells: source?.cells || [],
    week: activeWeek,
    viewMode: base ? 'base' : 'combine',
    reschedules: context.timetableReschedules || [],
    customCourses: context.timetableCustomCourses || []
  });
  const cells = merged.filter(cell => !cell.isMovedOut && isCourseActiveInWeek(cell, activeWeek));

  const days = new Map();
  for (const cell of cells) {
    const day = Number(cell.day) || 0;
    if (!days.has(day)) days.set(day, []);
    days.get(day).push({
      section: cell.sectionIndex || 0,
      sectionLabel: cell.sectionLabel || '',
      name: cell.courseName || '',
      teacher: cell.teacher || '',
      room: cell.location || '',
      weeks: cell.weeks || '',
      hoursType: cell.hoursType || '',
      customTime: cell.customTimeText || undefined,
      rescheduled: cell.isRescheduled || undefined,
      category: cell.category || undefined
    });
  }

  const dayList = [...days.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([day, courses]) => ({
      day,
      courses: courses.sort((a, b) => a.section - b.section)
    }));

  const unarranged = (source?.unarranged || [])
    .filter(item => !item.weeks || isCourseActiveInWeek(item, activeWeek))
    .map(item => ({
      name: item.courseName || '',
      teacher: item.teacher || '',
      room: item.location || '',
      weeks: item.weeks || '',
      day: item.day || ''
    }));

  return {
    week: activeWeek,
    slotMode: base ? 'base' : 'combine',
    days: dayList,
    unarranged
  };
}

/** 成绩投影：默认按课程名去重后输出，失败课程可单独筛 */
function scoresData({ course, property, failed, limit: max }, context) {
  let list = dedupeScores(context.scores);
  if (course) {
    const keyword = String(course).trim().toLowerCase();
    list = list.filter(item => String(item.courseName || '').toLowerCase().includes(keyword));
  }
  if (property) {
    list = list.filter(item => String(item.property || '').includes(property));
  }
  if (failed) {
    list = list.filter(item => !item.passed && !item.pending);
  }
  const capped = limit(list, clampInt(max, 1, 500, MAX_TOOL_RECORDS));
  return {
    stats: context.stats,
    courses: capped.records.map(item => ({
      name: item.courseName || '',
      property: item.property || '',
      credit: Number(item.credit) || 0,
      score: item.score || '',
      passed: !!item.passed,
      pending: !!item.pending,
      retake: !!item.isRetake,
      term: [item.year, item.term].filter(Boolean).join(' ')
    })),
    total: capped.total,
    truncated: capped.truncated
  };
}

/** 考试投影：days 窗口（默认 7 天，负数表示回看），all 返回全部 */
function examsData({ days, all, course }, context) {
  const now = new Date();
  let list = [...(context.exams || [])];
  if (course) {
    const keyword = String(course).trim().toLowerCase();
    list = list.filter(item => String(item.courseName || '').toLowerCase().includes(keyword));
  }
  if (!all) {
    const window = clampInt(days, -3650, 3650, 7);
    list = list.filter(item => {
      const date = examDate(item.time);
      if (!date) return true; // 时间无法解析的保留，避免静默丢数据
      const offset = daysBetween(now, date);
      return offset >= -Math.abs(window) && offset <= Math.abs(window);
    });
  }
  const capped = limit(list);
  return {
    exams: capped.records.map(item => ({
      name: item.courseName || '',
      time: item.time || '',
      room: item.location || '',
      type: item.property || ''
    })),
    total: capped.total,
    truncated: capped.truncated
  };
}

/**
 * 构建工具集
 * @param {{sendProfile?: boolean}} [options]
 */
export function buildToolset(options = {}) {
  const data = useAcademicData();
  const session = useSession();

  const allDefinitions = [
    {
      type: 'function',
      function: {
        name: 'query_summary',
        description:
          '查询学业概览：身份（已打码）、当前学期与教学周、GPA 与学分指标、指定日期的课程、近期考试。回答「我今天有什么课」「我绩点多少」这类问题优先用它。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            date: { type: 'string', description: '查询哪一天的课程，格式 YYYY-MM-DD，缺省为今天' },
            days: { type: 'integer', description: '近期考试窗口天数，1..3650，默认 7' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_timetable',
        description: '查询某一教学周的课表，按星期返回课程、地点、教师与周次。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            week: { type: 'integer', description: '教学周 1..26，缺省为当前周' },
            base: { type: 'boolean', description: 'true 使用小节课表，默认大节（COMBINE）' },
            date: { type: 'string', description: '可选的基准日期 YYYY-MM-DD（仅用于确定默认周）' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_scores',
        description: '查询成绩与 GPA：按课程名 / 属性筛选，或只看挂科。数值口径与页面一致。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            course: { type: 'string', description: '课程名关键字' },
            property: { type: 'string', enum: PROPERTY_VALUES, description: '课程属性' },
            failed: { type: 'boolean', description: '只看挂科' },
            limit: { type: 'integer', description: '最多返回多少条，默认 40' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_exams',
        description: '查询考试日程：默认未来（与过去）7 天内，可用 days 调整窗口。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            days: { type: 'integer', description: '窗口天数，默认 7' },
            all: { type: 'boolean', description: '返回全部考试' },
            course: { type: 'string', description: '课程名关键字' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_plan',
        description: '查询培养方案各课组的学分完成度，可按课组名筛选。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            group: { type: 'string', description: '课组名关键字' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_notices',
        description: '查询教学公告（本地缓存）。公告没有发布日期，不要编造日期。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            week: { type: 'integer', description: '教学周（当前仅使用本地缓存）' },
            query: { type: 'string', description: '标题 / 正文关键字' },
            limit: { type: 'integer', description: '最多返回多少条，默认 10' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_profile',
        description: '查询学籍档案。证件号、手机号、邮箱、地址已打码，保持打码原样。',
        parameters: { type: 'object', additionalProperties: false, properties: {} }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_courses',
        description: '查询本学期课程名录（课程号、教师、学分、属性、考试方式）。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            query: { type: 'string', description: '课程名 / 教师关键字' },
            limit: { type: 'integer', description: '最多返回多少条，默认 40' }
          }
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'query_check',
        description: '检查本地数据完整性：登录态、数据条数、上次同步时间、同步失败项。',
        parameters: {
          type: 'object',
          additionalProperties: false,
          properties: {
            strict: { type: 'boolean', description: '严格模式：任何注意项都算失败' }
          }
        }
      }
    }
  ];

  // 关闭「发送学籍」时直接摘掉工具定义：模型根本没有调用入口
  const definitions = options.sendProfile
    ? allDefinitions
    : allDefinitions.filter(item => item.function.name !== 'query_profile');

  function context() {
    const loggedIn = !!session.isLoggedIn.value;
    const hasCachedData = !!data.hasCachedData.value;
    return {
      loggedIn,
      readiness: readinessOf(loggedIn, hasCachedData),
      lastSyncText: data.lastSyncText.value,
      scores: data.scores.value,
      exams: data.exams.value,
      notices: data.notices.value,
      currentCourses: data.currentCourses.value,
      plan: data.plan.value,
      timetableCombine: data.timetableCombine.value,
      timetableBase: data.timetableBase.value,
      timetableReschedules: data.timetableReschedules?.value || [],
      timetableCustomCourses: data.timetableCustomCourses?.value || [],
      stats: buildAcademicStats(data.scores.value),
      semester: semesterInfo(session)
    };
  }

  /** 同步执行（数据都在内存里）；未知工具 / 参数非法 → 抛错，由会话循环回填给模型 */
  function invoke(name, args = {}) {
    const ctx = context();
    const input = args && typeof args === 'object' ? args : {};

    switch (name) {
      case 'query_summary': {
        const day = weekdayOf(input.date || todayIso());
        const week = ctx.semester.week;
        const today = timetableData({ week }, ctx).days.find(item => item.day === day);
        const exams = examsData({ days: input.days, all: false }, ctx);
        return wrap(
          {
            profile: maskProfile(session.userProfile),
            semester: ctx.semester,
            stats: ctx.stats,
            date: input.date || todayIso(),
            weekday: day,
            coursesToday: today?.courses || [],
            upcomingExams: exams.exams
          },
          ctx
        );
      }
      case 'query_timetable': {
        const payload = timetableData(
          {
            week: input.week ? clampInt(input.week, 1, 26, ctx.semester.week || 1) : ctx.semester.week,
            base: !!input.base
          },
          ctx
        );
        const total = payload.days.reduce((sum, item) => sum + item.courses.length, 0);
        return wrap(payload, ctx, total);
      }
      case 'query_scores': {
        const payload = scoresData(input, ctx);
        return wrap(payload, ctx, payload.total);
      }
      case 'query_exams': {
        const payload = examsData(input, ctx);
        return wrap(payload, ctx, payload.total);
      }
      case 'query_plan': {
        const groups = (ctx.plan?.groups || []).filter(group =>
          input.group
            ? String(group.name || '').toLowerCase().includes(String(input.group).toLowerCase())
            : true
        );
        const progress = computeCreditsProgress(ctx.scores, groups);
        const capped = limit(groups);
        return wrap(
          {
            completionPercent: progress.completionPercent,
            earnedTotal: progress.earnedTotal,
            requiredTotal: progress.requiredTotal,
            groups: capped.records.map(group => ({
              name: group.name || '',
              property: group.property || '',
              requiredCredits: Number(group.requiredCredits) || 0,
              earnedCredits: Number(group.earnedCredits) || 0,
              requiredCourses: Number(group.requiredCourses) || 0,
              passedCourses: Number(group.passedCourses) || 0
            })),
            total: capped.total,
            truncated: capped.truncated
          },
          ctx,
          capped.total
        );
      }
      case 'query_notices': {
        let list = [...(ctx.notices || [])];
        if (input.query) {
          const keyword = String(input.query).toLowerCase();
          list = list.filter(
            item =>
              String(item.title || '').toLowerCase().includes(keyword) ||
              String(item.content || '').toLowerCase().includes(keyword)
          );
        }
        const capped = limit(list, clampInt(input.limit, 1, 500, 10));
        return wrap(
          {
            notices: capped.records.map(item => ({
              title: item.title || '',
              content: String(item.content || '').slice(0, 400)
            })),
            total: capped.total,
            truncated: capped.truncated
          },
          ctx,
          capped.total
        );
      }
      case 'query_profile': {
        if (!options.sendProfile) {
          throw new Error('query_profile 已被关闭：设置页「发送学籍」未开启');
        }
        return wrap({ profile: maskProfile(session.userProfile) }, ctx);
      }
      case 'query_courses': {
        let list = [...(ctx.currentCourses || [])];
        if (input.query) {
          const keyword = String(input.query).toLowerCase();
          list = list.filter(
            item =>
              String(item.courseName || '').toLowerCase().includes(keyword) ||
              String(item.teacher || '').toLowerCase().includes(keyword)
          );
        }
        const capped = limit(list, clampInt(input.limit, 1, 500, MAX_TOOL_RECORDS));
        return wrap(
          {
            courses: capped.records.map(item => ({
              name: item.courseName || '',
              teacher: item.teacher || '',
              credit: Number(item.credit) || 0,
              property: item.property || '',
              examWay: item.examWay || '',
              timeAndPlace: item.timeAndPlace || ''
            })),
            total: capped.total,
            truncated: capped.truncated
          },
          ctx,
          capped.total
        );
      }
      case 'query_check': {
        const counted = dedupeScores(ctx.scores).filter(item => isCountedCourse(item.property));
        const issues = [];
        if (!ctx.loggedIn) issues.push('当前未登录，展示的是上次登录时的缓存');
        if (!ctx.lastSyncText || ctx.lastSyncText === '尚未同步') issues.push('尚未同步过任何数据');
        if (ctx.scores.length === 0) issues.push('本地没有成绩数据');
        if ((ctx.timetableCombine?.cells || []).length === 0) issues.push('本地没有课表数据');
        const payload = {
          loggedIn: ctx.loggedIn,
          offline: !ctx.loggedIn,
          lastSyncText: ctx.lastSyncText,
          readiness: ctx.readiness,
          counts: {
            scores: ctx.scores.length,
            countedCourses: counted.length,
            exams: ctx.exams.length,
            timetableCells: (ctx.timetableCombine?.cells || []).length,
            notices: ctx.notices.length,
            currentCourses: ctx.currentCourses.length,
            planGroups: ctx.plan?.groups?.length || 0
          },
          issues
        };
        if (input.strict && issues.length > 0) {
          payload.verdict = 'attention';
        } else {
          payload.verdict = issues.length > 0 ? 'notice' : 'ok';
        }
        return wrap(payload, ctx);
      }
      default:
        throw new Error(`未知工具：${name}（可用：${definitions.map(d => d.function.name).join('、')}）`);
    }
  }

  return {
    definitions,
    invoke,
    names: definitions.map(item => item.function.name)
  };
}
