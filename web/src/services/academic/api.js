/**
 * 教务在线业务 API 汇聚层
 * 提供直接调用的异步服务方法
 */

import { request, getCaptchaUrl, checkCaptcha, postLogin, postLogout } from './client.js';
import {
  parseStudentContext,
  parsePersonalInfo,
  parseScores,
  parseTimetable,
  parseExams,
  parseCalendarInfo,
  parseCurriculumPlan,
  parseClassroomQueryOptions,
  parseClassroomDaySchedule,
  parseUrupError
} from './parser.js';

/** 教室查询页的默认表单值（与教务页面 hidden/占位项一致） */
const CLASSROOM_FORM_DEFAULTS = {
  aid: '-1',
  buildingid: '-1',
  room: '-1',
  whichweek: '-1',
  week: '1'
};

/**
 * 教务会用 HTTP 200 返回「提示信息」错误页，必须在这里拦下来，
 * 否则错误页会被当成「没有教室」的空数据。
 */
function assertNoClassroomError(html) {
  const error = parseUrupError(html);
  if (!error) return;
  const err = new Error(error.message);
  err.isUrupError = true;
  err.urupCode = error.code;
  throw err;
}

/** POST 教室查询页（级联刷新：选教学区 / 选教学楼都提交本页） */
async function postClassroomQueryPage(body) {
  const res = await request('teacher/teachresource/roomschedulequery.jsdo', {
    method: 'POST',
    body: { ...CLASSROOM_FORM_DEFAULTS, ...body },
    encoding: 'gbk'
  });
  assertNoClassroomError(res.html);
  return parseClassroomQueryOptions(res.html);
}

export const academicApi = {
  getCaptchaUrl,
  checkCaptcha,
  login: postLogin,
  logout: postLogout,

  /**
   * 校验会话有效性并拉取核心上下文
   */
  async getStudentContext() {
    const res = await request('student/currcourse/currcourse.jsdo', { encoding: 'gbk' });
    return parseStudentContext(res.html);
  },

  /**
   * 获取个人详细信息档案与学籍异动
   */
  async getPersonalInfo() {
    const res = await request('showPersonalInfo.do', { encoding: 'utf-8' });
    return parsePersonalInfo(res.html);
  },

  /**
   * 获取成绩单
   * @param {Object} [params]
   * @param {string} [params.year] 学年序号（留空为全部）
   * @param {string} [params.term] 学期序号（1=春，2=秋，留空为全部）
   * @param {string} [params.prop] 属性筛选（0=必修，1=限选，2=任选）
   */
  async getScores(params = {}) {
    const query = new URLSearchParams({
      year: params.year || '',
      term: params.term || '',
      prop: params.prop || '',
      groupName: params.groupName || '',
      para: '0',
      sortColumn: params.sortColumn || ''
    });

    if (params.failedStatus) query.append('failedStatus', '1');
    if (params.passedStatus) query.append('passedStatus', '1');
    if (params.maxStatus) query.append('maxStatus', '1');

    const res = await request(`manager/score/studentOwnScore.do?${query.toString()}`, {
      encoding: 'utf-8'
    });
    return parseScores(res.html);
  },

  /**
   * 获取课表
   * @param {Object} params
   * @param {string} params.studentId 教务内部学生 ID
   * @param {string} params.yearId 学年序号
   * @param {string} params.termId 学期序号 (1 或 2)
   * @param {string} [params.sectionType] 'BASE' 或 'COMBINE'
   */
  async getTimetable({ studentId, yearId, termId, sectionType = 'COMBINE' }) {
    const res = await request(
      `manager/coursearrange/showTimetable.do?id=${studentId}&yearid=${yearId}&termid=${termId}&timetableType=STUDENT&sectionType=${sectionType}`,
      { encoding: 'gbk' }
    );
    return parseTimetable(res.html);
  },

  /**
   * 获取考试安排
   */
  async getExams() {
    try {
      // 优先拉取全部考试
      const res = await request('manager/examstu/studentQueryAllExam.do', { encoding: 'utf-8' });
      const exams = parseExams(res.html);
      if (exams.length > 0) return exams;
    } catch {
      // 容错降级
    }

    // 备用接口：近期考试
    const recentRes = await request('student/exam/index.jsdo', { encoding: 'gbk' });
    return parseExams(recentRes.html);
  },

  /**
   * 获取教学周、学期及公告信息
   */
  async getCalendarInfo(week) {
    const url = week ? `calendarinfo/viewCalendarInfo.do?week=${week}` : 'calendarinfo/viewCalendarInfo.do';
    const res = await request(url, { encoding: 'utf-8' });
    return parseCalendarInfo(res.html);
  },

  /**
   * 获取培养方案及学分要求
   */
  async getCurriculumPlan({ studentId = '' } = {}) {
    const entry = await request('manager/studyschedule/studentSelfSchedule.jsdo', { encoding: 'gbk' });
    const match = entry.html.match(/studentId=([^&"'<>\s]+)/);
    let id = match?.[1] || studentId;
    if (!id) throw new Error('培养方案未返回学生查询参数，请重新同步');
    try { id = decodeURIComponent(id); } catch { /* 保留原站未编码参数 */ }
    const res = await request(`manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId=${encodeURIComponent(id)}`, { encoding: 'gbk' });
    const plan = parseCurriculumPlan(res.html);
    if (!plan.groups.length && !plan.totalRequiredCredits) throw new Error('学校未返回培养方案学分要求，未覆盖已有缓存');
    return plan;
  },

  /**
   * 获取教室查询的初始选项（教学区 / 教学楼 / 周次 / 星期）
   */
  async getClassroomQueryOptions() {
    const res = await request('teacher/teachresource/roomschedulequery.jsdo', {
      encoding: 'gbk'
    });
    assertNoClassroomError(res.html);
    return parseClassroomQueryOptions(res.html);
  },

  /**
   * 选定教学区后取该区的教学楼列表
   * @param {{aid: string}} params
   */
  async getClassroomBuildings({ aid }) {
    const options = await postClassroomQueryPage({ aid, buildingid: '-1', room: '-1' });
    return options;
  },

  /**
   * 选定教学楼后取该楼的教室列表
   * @param {{aid: string, buildingId: string}} params
   */
  async getClassroomRooms({ aid, buildingId }) {
    return postClassroomQueryPage({ aid, buildingid: buildingId, room: '-1' });
  },

  /**
   * 按周查询整栋教学楼的占用（一天 × 全部教室 × 12 小节）
   *
   * 教务只提供「指定星期」的整楼数据，整周需要由调用方按 day 1-7 聚合。
   *
   * @param {{aid: string, buildingId: string, week: number, day: number}} params
   * @returns {Promise<ReturnType<typeof parseClassroomDaySchedule>>}
   */
  async getClassroomDaySchedule({ aid, buildingId, week, day }) {
    const res = await request('teacher/teachresource/roomschedule_week.jsdo', {
      method: 'POST',
      body: {
        aid,
        buildingid: buildingId,
        room: '-1',
        whichweek: String(week),
        week: String(day)
      },
      encoding: 'gbk'
    });
    assertNoClassroomError(res.html);
    return parseClassroomDaySchedule(res.html, day);
  }
};
