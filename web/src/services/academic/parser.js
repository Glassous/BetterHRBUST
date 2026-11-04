import { semesterOn, teachingWeekOn } from '../../utils/schoolCalendar.js';
/**
 * 教务在线 HTML 响应解析器
 * 基于 DOMParser 与精确字段抽取，实现现代化前端结构化数据输出
 */

/**
 * 等级制成绩文本（二级制 / 五级制 / 免修类）：总评列本身即是明确的"通过"结论，
 * 反之为明确的不通过；其余文本（缓考 / 缺考 / 未出分）仍按 pending 保守处理。
 */
const PASS_GRADE_TEXTS = new Set(['优秀', '良好', '中等', '及格', '合格', '通过', '免修', '免听', '免考']);
const FAIL_GRADE_TEXTS = new Set(['不及格', '不合格', '不通过']);

/**
 * 将 HTML 文本解析为 DOM 树
 * @param {string} html
 * @returns {Document}
 */
export function createDom(html) {
  const parser = new DOMParser();
  return parser.parseFromString(html, 'text/html');
}

/**
 * 剥离 HTML 注释，防止注释内的单元格导致表格解析列错位
 * @param {string} html
 * @returns {string}
 */
export function stripHtmlComments(html) {
  return (html || '').replace(/<!--[\s\S]*?-->/g, '');
}

/**
 * 常见命名实体映射
 */
const NAMED_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
  copy: '©', reg: '®', trade: '™', hellip: '…',
  mdash: '—', ndash: '–', middot: '·', deg: '°',
  laquo: '«', raquo: '»',
  ldquo: '“', rdquo: '”', lsquo: '‘', rsquo: '’'
};

function fromCodePoint(num, fallback) {
  return Number.isFinite(num) && num > 0 && num <= 0x10ffff ? String.fromCodePoint(num) : fallback;
}

/**
 * 解码 HTML 实体（支持命名实体与十进制/十六进制数字实体）。
 * 教务系统部分页面存在双重编码（源码为 &amp;lt;，textContent/innerHTML
 * 提取后仍是 "&lt;"），因此循环解码直到结果稳定（最多 3 轮）。
 * @param {string} text
 * @returns {string}
 */
export function decodeEntities(text) {
  if (!text || !text.includes('&')) return text || '';
  const pattern = /&(?:#x([0-9a-fA-F]+)|#([0-9]+)|([a-zA-Z][a-zA-Z0-9]*));/g;
  let out = text;
  for (let i = 0; i < 3; i++) {
    const next = out.replace(pattern, (raw, hex, dec, name) => {
      if (hex) return fromCodePoint(parseInt(hex, 16), raw);
      if (dec) return fromCodePoint(parseInt(dec, 10), raw);
      const mapped = NAMED_ENTITIES[name.toLowerCase()];
      return mapped !== undefined ? mapped : raw;
    });
    if (next === out) break;
    out = next;
  }
  return out;
}

/**
 * 解析学生上下文（/academic/student/currcourse/currcourse.jsdo）
 */
export function parseStudentContext(html) {
  const doc = createDom(html);

  // 1. 内部学生 ID、学年
  let studentId = '';
  let year = '';
  const ctrtMatch = html.match(/studentid=["']?(\d+)["']?[\s\S]*?year=["']?(\d+)["']?/i);
  if (ctrtMatch) {
    studentId = ctrtMatch[1];
    year = ctrtMatch[2];
  } else {
    const sidM = html.match(/studentid\s*=\s*["']?(\d+)["']?/i);
    const yrM = html.match(/year\s*=\s*["']?(\d+)["']?/i);
    if (sidM) studentId = sidM[1];
    if (yrM) year = yrM[1];
  }

  // 2. 学期
  let term = '2';
  const termSelect = doc.querySelector('select[name="term"]');
  if (termSelect) {
    const selectedOption = termSelect.querySelector('option[selected]') || termSelect.selectedOptions?.[0];
    if (selectedOption) {
      term = selectedOption.value;
    }
  }

  // 3. 本学期课程列表
  const courses = [];
  const table = doc.querySelector('table.infolist_tab');
  if (table) {
    const rows = table.querySelectorAll('tr.infolist_common');
    rows.forEach(tr => {
      const tds = tr.querySelectorAll('td');
      if (tds.length >= 6) {
        courses.push({
          courseId: tds[0]?.textContent.trim() || '',
          courseSeq: tds[1]?.textContent.trim() || '',
          courseName: tds[2]?.textContent.trim() || '',
          teacher: tds[3]?.textContent.trim() || '',
          credit: parseFloat(tds[4]?.textContent.trim()) || 0,
          property: tds[5]?.textContent.trim() || '',
          examWay: tds[6]?.textContent.trim() || '',
          examType: tds[7]?.textContent.trim() || '',
          timeAndPlace: tds[9]?.textContent.trim() || ''
        });
      }
    });
  }

  return {
    studentId,
    year,
    term,
    courses
  };
}

/**
 * 解析个人信息与学籍异动（/academic/showPersonalInfo.do）
 */
export function parsePersonalInfo(html) {
  const doc = createDom(html);
  const info = {
    studentNumber: '',
    realName: '',
    college: '',
    major: '',
    direction: '',
    studentType: '',
    grade: '',
    className: '',
    idCard: '',
    email: '',
    phone: '',
    address: '',
    postalCode: '',
    status: '在籍（注册）',
    photoUrl: '',
    changes: []
  };

  // 1. 头像图片
  const photoImg = doc.querySelector('img[src*="loadphoto_added.jsdo"]') || doc.querySelector('img[src*="showStudentImage.jsp"]');
  if (photoImg) {
    info.photoUrl = photoImg.getAttribute('src') || '';
  }

  // 2. 基本信息键值表
  const formTable = doc.querySelector('table.form');
  if (formTable) {
    const rows = formTable.querySelectorAll('tr');
    rows.forEach(row => {
      const ths = row.querySelectorAll('th');
      const tds = row.querySelectorAll('td');
      for (let i = 0; i < ths.length; i++) {
        const key = ths[i]?.textContent.trim();
        const val = tds[i]?.textContent.replace(/&nbsp;/g, '').trim();
        if (!key || val === undefined) continue;

        if (key.includes('用户名')) info.studentNumber = val;
        else if (key.includes('真实姓名')) info.realName = val;
        else if (key.includes('所在院系')) info.college = val;
        else if (key.includes('专业') && !key.includes('方向')) info.major = val;
        else if (key.includes('方向')) info.direction = val;
        else if (key.includes('学生类别')) info.studentType = val;
        else if (key.includes('年级')) info.grade = val;
        else if (key.includes('班级')) info.className = val;
        else if (key.includes('证件号码')) info.idCard = val;
        else if (key.includes('电子邮箱')) info.email = val;
        else if (key.includes('联系电话')) info.phone = val;
        else if (key.includes('通讯地址')) info.address = val;
        else if (key.includes('邮政编码')) info.postalCode = val;
      }
    });
  }

  // 3. 学籍异动表
  const changesTable = doc.querySelector('table.datalist');
  if (changesTable) {
    const rows = changesTable.querySelectorAll('tr.infolist_hr_common, tr.infolist_common');
    rows.forEach(tr => {
      const tds = tr.querySelectorAll('td');
      if (tds.length >= 4) {
        info.changes.push({
          type: tds[0]?.textContent.replace(/&nbsp;/g, '').trim() || '',
          date: tds[1]?.textContent.replace(/&nbsp;/g, '').trim() || '',
          reason: tds[2]?.textContent.replace(/&nbsp;/g, '').trim() || '',
          remark: tds[3]?.textContent.replace(/&nbsp;/g, '').trim() || ''
        });
      }
    });
  }

  return info;
}

/**
 * 解析成绩查询列表（/academic/manager/score/studentOwnScore.do）
 */
export function parseScores(html) {
  const doc = createDom(html);

  // 1. 可用学年与学期
  const yearOptions = [];
  const yearSelect = doc.querySelector('select[name="year"]');
  if (yearSelect) {
    yearSelect.querySelectorAll('option').forEach(opt => {
      const val = opt.value.trim();
      const txt = opt.textContent.trim();
      if (val) yearOptions.push({ value: val, label: txt });
    });
  }

  // 2. 成绩数据表
  const table = doc.querySelector('table.datalist');
  const scores = [];

  if (table) {
    const trList = table.querySelectorAll('tr');
    if (trList.length > 1) {
      // 解析表头
      const headerThs = trList[0].querySelectorAll('th');
      const headers = Array.from(headerThs).map(th => th.textContent.trim());

      const colYear = headers.findIndex(h => h === '学年');
      const colTerm = headers.findIndex(h => h === '学期');
      const colCode = headers.findIndex(h => h === '课程号');
      const colName = headers.findIndex(h => h === '课程名');
      const colSeq = headers.findIndex(h => h === '课序号');
      const colGroup = headers.findIndex(h => h === '课组');
      const colTotal = headers.findIndex(h => h === '总评');
      const colCredit = headers.findIndex(h => h === '学分');
      const colHours = headers.findIndex(h => h === '学时');
      const colProp = headers.findIndex(h => h === '选课属性');
      const colRemark = headers.findIndex(h => h === '备注');
      const colExamType = headers.findIndex(h => h === '考试性质');
      const colPassMark = headers.findIndex(h => h === '及格标志');

      for (let i = 1; i < trList.length; i++) {
        const tds = trList[i].querySelectorAll('td');
        if (tds.length === 0) continue;

        const year = tds[colYear]?.textContent.trim() || '';
        const term = tds[colTerm]?.textContent.trim() || '';
        const courseId = tds[colCode]?.textContent.trim() || '';
        const courseName = tds[colName]?.textContent.trim() || '';
        const courseSeq = tds[colSeq]?.textContent.trim() || '';
        const courseGroup = tds[colGroup]?.textContent.trim() || '';
        const rawScore = tds[colTotal]?.textContent.trim() || '';
        const credit = parseFloat(tds[colCredit]?.textContent.trim()) || 0;
        const hours = parseInt(tds[colHours]?.textContent.trim(), 10) || 0;
        const property = tds[colProp]?.textContent.trim() || '';
        const remark = tds[colRemark]?.textContent.trim() || '';
        const examType = tds[colExamType]?.textContent.trim() || '';
        const passMark = tds[colPassMark]?.textContent.trim() || '';

        // 判断是否及格。保守方向：未确认通过 ≠ 已通过——
        // 未出分（无成绩无标志）与缓考/缺考等无明确结论的行标记 pending，
        // 既不计入已获学分，也不计入挂科统计
        const numScore = parseFloat(rawScore);
        let passed = false;
        let pending = false;
        if (!isNaN(numScore)) {
          passed = numScore >= 60;
        } else if (passMark) {
          passed = !passMark.includes('不') && passMark !== '不及格';
        } else if (PASS_GRADE_TEXTS.has(rawScore)) {
          // 等级制成绩（合格 / 优秀 / 免修…）是明确的通过结论，必须计入已获学分
          passed = true;
        } else if (FAIL_GRADE_TEXTS.has(rawScore)) {
          passed = false;
        } else {
          pending = true;
        }

        scores.push({
          year,
          term,
          courseId,
          courseName,
          courseSeq,
          courseGroup,
          score: rawScore,
          credit,
          hours,
          property,
          remark,
          examType,
          passMark,
          passed,
          pending
        });
      }
    }
  }

  return {
    scores,
    yearOptions
  };
}

/**
 * 解析课程表（/academic/manager/coursearrange/showTimetable.do）
 */
export function parseTimetable(html) {
  const doc = createDom(html);
  const table = doc.querySelector('table#timetable');
  const cells = [];
  const unarranged = [];

  if (table) {
    const rows = table.querySelectorAll('tr.infolist_hr_common');
    rows.forEach((tr, rowIndex) => {
      const th = tr.querySelector('th');
      const sectionLabel = th
        ? decodeEntities(th.textContent.replace(/<br>/g, '').trim())
        : `第${rowIndex + 1}大节`;

      const tds = tr.querySelectorAll('td');
      tds.forEach((td, dayIndex) => {
        const id = td.getAttribute('id') || '';
        const rawHtml = td.innerHTML.trim();

        if (!rawHtml || rawHtml === '&nbsp;' || td.textContent.trim() === '') {
          return;
        }

        // 以 <br> 分割课程详情，先剥残留标签再解码 HTML 实体（&lt; 等）
        const lines = rawHtml
          .split(/<br\s*\/?>/i)
          .map(l => decodeEntities(l.replace(/<[^>]+>/g, '')).trim())
          .filter(Boolean);
        if (lines.length === 0) return;

        // 第一行通常是 <<课程名>>;课序号
        let courseName = lines[0] || '';
        let courseSeq = '';
        const nameMatch = courseName.match(/<<?(.*?)(?:>>|;)?(?:\s*;\s*(\d+))?$/);
        if (nameMatch) {
          courseName = nameMatch[1] || courseName;
          courseSeq = nameMatch[2] || '';
        }
        courseName = courseName.replace(/^[<《]+|[>》]+$/g, '').trim();

        const location = lines[1] || '';
        const teacher = lines[2] || '';
        const weeks = lines[3] || '';
        const hoursType = lines[4] || '';

        // id 格式形如 "1-4367" 或 "1-1"，前缀为星期几 (1~7)，后缀为课程排课 ID
        let day = dayIndex + 1;
        let courseId = '';
        if (id) {
          const parts = id.split('-');
          const parsedDay = parseInt(parts[0], 10);
          if (parsedDay >= 1 && parsedDay <= 7) day = parsedDay;
          if (parts[1] && /^\d+$/.test(parts[1])) courseId = parts[1];
        }

        cells.push({
          id,
          courseId,
          day, // 1~7 (周一到周日)
          sectionIndex: rowIndex + 1, // 1~6 大节
          sectionLabel,
          courseName,
          courseSeq,
          location,
          teacher,
          weeks,
          hoursType,
          rawLines: lines
        });
      });
    });
  }

  // 没有具体上课时间或地点的课程
  const noArrTable = doc.querySelector('table#noArrangement');
  if (noArrTable) {
    const rows = noArrTable.querySelectorAll('tr');
    for (let i = 1; i < rows.length; i++) {
      const tds = rows[i].querySelectorAll('td');
      if (tds.length >= 8) {
        unarranged.push({
          courseId: decodeEntities(tds[0]?.textContent.trim()) || '',
          courseName: decodeEntities(tds[1]?.textContent.trim()) || '',
          courseSeq: decodeEntities(tds[2]?.textContent.trim()) || '',
          teacher: decodeEntities(tds[3]?.textContent.trim()) || '',
          mergeClass: decodeEntities(tds[4]?.textContent.trim()) || '',
          weeks: decodeEntities(tds[5]?.textContent.trim()) || '',
          day: decodeEntities(tds[6]?.textContent.trim()) || '',
          location: decodeEntities(tds[7]?.textContent.trim()) || ''
        });
      }
    }
  }

  return {
    cells,
    unarranged
  };
}

/**
 * 解析考试安排（/academic/manager/examstu/studentQueryAllExam.do 或 student/exam/index.jsdo）
 */
export function parseExams(html) {
  // 先剥离 HTML 注释，防止注释导致的表头与单元格错位
  const cleanHtml = stripHtmlComments(html);
  const doc = createDom(cleanHtml);

  const exams = [];
  const table = doc.querySelector('table.datalist') || doc.querySelector('table.infolist_tab');

  if (table) {
    const trList = table.querySelectorAll('tr');
    if (trList.length > 1) {
      const ths = trList[0].querySelectorAll('th');
      const headers = Array.from(ths).map(th => th.textContent.trim());

      const colCode = headers.findIndex(h => h.includes('课程号'));
      const colName = headers.findIndex(h => h.includes('课程名称'));
      const colTime = headers.findIndex(h => h.includes('考试时间'));
      const colLoc = headers.findIndex(h => h.includes('考试地点'));
      const colProp = headers.findIndex(h => h.includes('考试性质'));

      for (let i = 1; i < trList.length; i++) {
        const tds = trList[i].querySelectorAll('td');
        if (tds.length < 4) continue;

        const courseId = tds[colCode >= 0 ? colCode : 0]?.textContent.trim() || '';
        const courseName = tds[colName >= 0 ? colName : 1]?.textContent.trim() || '';
        const time = tds[colTime >= 0 ? colTime : 2]?.textContent.trim() || '';
        const location = tds[colLoc >= 0 ? colLoc : 3]?.textContent.trim() || '';
        const property = tds[colProp >= 0 ? colProp : 4]?.textContent.trim() || '正常考试';

        if (!courseName && !courseId) continue;

        exams.push({
          courseId,
          courseName,
          time,
          location,
          property
        });
      }
    }
  }

  return exams;
}

/**
 * 解析教学周与校历信息（/academic/calendarinfo/viewCalendarInfo.do）
 */
export function parseCalendarInfo(html) {
  const doc = createDom(html);

  // 1. 当前周次
  let currentWeek = 1;
  const curTd = doc.querySelector('.curweek strong') || doc.querySelector('.week td.cur span');
  if (curTd) {
    const num = parseInt(curTd.textContent.trim(), 10);
    if (!isNaN(num)) currentWeek = num;
  }

  // 2. 学期名称
  let semesterName = '';
  const curWeekDiv = doc.querySelector('.curweek');
  if (curWeekDiv) {
    semesterName = curWeekDiv.textContent.replace(/\s+/g, ' ').replace(/第.*周/, '').trim();
  }

  // 通用1..26导航不是学期长度，只接受明确写出的学期总周数。
  const semesterText = doc.querySelector('#semesterWeeks, .semester-weeks, .semesterweeks, .semester-calendar')?.textContent || doc.querySelector('#textwrapper')?.textContent || '';
  const count = semesterText.match(/(?:共|总计|总周数[:：]?)\s*(\d{1,2})\s*(?:个)?(?:教学)?周/);
  const teachingWeeks = count && Number(count[1]) >= 1 && Number(count[1]) <= 26 ? Number(count[1]) : null;

  // 3. 公告内容
  // 教务源页面不含发布日期；留空而非用当天日期伪造（错误日期永不纠正），
  // UI 对空日期隐藏日期字段
  const notices = [];
  const textWrapper = doc.querySelector('#textwrapper');
  if (textWrapper) {
    const items = textWrapper.querySelectorAll('p, div, li');
    items.forEach(el => {
      const text = el.textContent.trim();
      if (text) {
        notices.push({
          title: text.slice(0, 50),
          content: text,
          date: ''
        });
      }
    });
  }

  return {
    currentWeek: semesterOn() ? (teachingWeekOn() ?? 0) : currentWeek,
    semesterName: semesterOn()?.label || semesterName,
    teachingWeeks: semesterOn()?.teachingWeeks ?? teachingWeeks,
    notices
  };
}

/**
 * 解析培养方案与学分完成度（/academic/manager/studyschedule/studentScheduleShowByTerm.do）
 */
export function parseCurriculumPlan(html) {
  const doc = createDom(html);
  const groups = [];

  // 从下拉选项或表格中提取课组学分要求
  const select = doc.querySelector('select#syt12') || doc.querySelector('select[name="syt"]');
  if (select) {
    select.querySelectorAll('option').forEach(opt => {
      const text = opt.textContent.trim();
      // 匹配形如："学科基础课程（2023） 选课属性：必修 学分要求=26.5 门数要求=8"
      const m = text.match(/^(.*?)\s+选课属性\s*[：:]\s*(.*?)?\s*学分要求\s*[=：:]\s*([\d.]+)\s*门数要求\s*[=：:]\s*(\d+)/);
      if (m) {
        groups.push({
          id: opt.value,
          name: m[1].trim(),
          property: m[2]?.trim() || '必修',
          requiredCredits: parseFloat(m[3]) || 0,
          requiredCourses: parseInt(m[4], 10) || 0,
          earnedCredits: 0,
          passedCourses: 0,
          courses: []
        });
      }
    });
  }

  // 提取课程明细
  const detailTable = doc.querySelector('table#output_ctx') || doc.querySelector('table.output_ctx');
  if (detailTable) {
    const rows = detailTable.querySelectorAll('tr');
    let currentGroupName = '';

    rows.forEach(tr => {
      const th = tr.querySelector('th');
      if (th && th.textContent.includes('课组')) {
        currentGroupName = th.textContent.replace(/.*课组[：:]\s*/, '').trim();
      }

      const tds = tr.querySelectorAll('td');
      if (tds.length >= 7) {
        const cCode = tds[1]?.textContent.trim();
        const cName = tds[2]?.textContent.trim();
        const cCredit = parseFloat(tds[3]?.textContent.trim()) || 0;
        const cHours = parseInt(tds[4]?.textContent.trim(), 10) || 0;
        const cProp = tds[5]?.textContent.trim() || '';

        if (cCode && cName && !isNaN(cCredit)) {
          const group = groups.find(g => g.name === currentGroupName) || groups[0];
          if (group) {
            group.courses.push({
              code: cCode,
              name: cName,
              credit: cCredit,
              hours: cHours,
              property: cProp
            });
          }
        }
      }
    });
  }

  return {
    groups: [...new Map(groups.map(g => [g.id || g.name, g])).values()],
    totalRequiredCredits: Number(doc.body.textContent.match(/(?:方案总学分|毕业总学分|总学分要求|要求总学分|总学分)\s*[：:=]?\s*([0-9]+(?:\.[0-9]+)?)/)?.[1]) || null
  };
}

/* ------------------------------------------------------------------ *
 * 空教室查询（teacher/teachresource/）
 *
 * 实测结构（2026-11-04，见 tools/probe/classroom-probe.mjs）：
 *   · 查询页 roomschedulequery.jsdo 的级联刷新是 **POST 表单自身**，
 *     字段 aid / buildingid / room / whichweek / week；
 *   · roomschedule_week.jsdo（按周）返回「教室 × 12 小节」矩阵：
 *       外层 table.infolist_tab，每个教室一行，第 7 个 td 内嵌一张 2 行表，
 *       第二行的 12 个格子中：
 *         空    = <td valign="top">&nbsp;</td>
 *         占用  = <td><a title="..."><font color="#FF0000">●</font></a></td>
 *       颜色语义：#FF0000 课程 / #FF6600 课程考试 / #006600 其它；
 *   · roomschedule.jsdo（按教室）返回 26 周骨架但**不含占用标记**，无法用于取空闲。
 *
 * 注意：`whichweek` 是第几周，`week` 是星期几，两者不可互换。
 * ------------------------------------------------------------------ */

/** 周几文本 → 序号（1=周一） */
const WEEKDAY_TEXTS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/** 占用标记颜色 → 语义 */
const OCCUPANCY_COLORS = {
  '#ff0000': 'course',
  '#ff6600': 'exam',
  '#006600': 'other'
};

export const OCCUPANCY_TYPE_LABELS = {
  course: '课程',
  exam: '考试',
  other: '其它',
  occupied: '占用'
};

/**
 * 是否为教务「提示信息」错误页
 *
 * 这类页面 HTTP 状态是 200，只能靠内容判定。标题 `<title>提示信息</title>`
 * 是 URP 错误页的固定特征；正常表单页里的 `class="error"` 只是必填提示占位，
 * 不能作为判据。
 */
export function isUrupErrorPage(html) {
  return /<title>\s*提示信息\s*<\/title>/i.test(String(html || ''));
}

/** 错误码 → 用户可读文案（不同教务版本返回码不统一，未知码保留原文） */
function describeUrupErrorCode(raw) {
  const text = String(raw || '').trim();
  if (!text) return '教务系统返回了提示信息页，未取到具体原因';
  if (/^For input string/i.test(text)) return '查询参数为空或格式不正确';
  if (text === 'null') return '教务未返回数据，请更换筛选条件重试';
  if (text === '-1') return '教务会话无效，请重新登录后重试';
  if (text === '-2') return '查询条件不合法（周次、星期需为有效值）';
  if (/^-?\d+$/.test(text)) return `教务拒绝了本次查询（返回码 ${text}）`;
  return text;
}

/**
 * 解析「提示信息」错误页内容
 * @returns {{code:string,message:string}|null} 非错误页返回 null
 */
export function parseUrupError(html) {
  if (!isUrupErrorPage(html)) return null;
  const doc = createDom(stripHtmlComments(html));
  const cell = doc.querySelector('#content_margin');
  const raw = (cell?.textContent || '').replace(/\s+/g, ' ').trim();
  // 去掉尾部的时间戳与节点名（-2 2026-11-04 23:20:05 TA1）
  const code = raw
    .replace(/\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}/g, '')
    .replace(/\bTA\d+\b/g, '')
    .trim();
  return { code, message: describeUrupErrorCode(code) };
}

/** 读取某个 select 的选项（过滤「请选择」占位项 -1） */
function readSelectOptions(doc, name) {
  const select = doc.querySelector(`select[name="${name}"]`);
  if (!select) return [];
  return Array.from(select.querySelectorAll('option'))
    .map(opt => ({
      id: (opt.getAttribute('value') ?? opt.textContent).trim(),
      name: opt.textContent.replace(/\s+/g, ' ').trim()
    }))
    .filter(item => item.id && item.id !== '-1' && !/^请选择/.test(item.name));
}

/**
 * 解析教室时间占用查询页（roomschedulequery.jsdo）
 *
 * 返回结构与旧版保持兼容（areas / buildings / rooms），额外提供
 * weeks（第几周 1-26）与 days（星期几 1-7），供页面直接生成筛选项。
 */
export function parseClassroomQueryOptions(html) {
  const doc = createDom(stripHtmlComments(html));
  const weeks = readSelectOptions(doc, 'whichweek')
    .map(item => ({ id: Number(item.id), name: item.name }))
    .filter(item => Number.isFinite(item.id) && item.id > 0);
  const days = readSelectOptions(doc, 'week')
    .map(item => ({
      id: WEEKDAY_TEXTS.indexOf(item.name) + 1 || Number(item.id),
      name: item.name
    }))
    .filter(item => item.id >= 1 && item.id <= 7);

  return {
    areas: readSelectOptions(doc, 'aid'),
    buildings: readSelectOptions(doc, 'buildingid'),
    rooms: readSelectOptions(doc, 'room'),
    weeks,
    days
  };
}

/** 单元格文本归一化 */
function cellText(cell) {
  return (cell?.textContent || '').replace(/\s+/g, ' ').trim();
}

/** 从占用标记的 title 中提取占用原因 / 备注（跳过「占用人」，避免展示个人信息） */
function parseOccupancyTitle(anchor) {
  const title = anchor?.getAttribute('title') || '';
  if (!title) return '';
  const parts = title
    .split(/\r?\n|&#13;|\s{2,}/)
    .map(part => part.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .filter(part => !/^占用人/.test(part));
  return parts.join(' · ');
}

/** 判定单个小节格子的占用类型；空闲返回空串 */
function detectOccupancyType(cell) {
  const marker = cell.querySelector('font[color]');
  if (marker) {
    const color = (marker.getAttribute('color') || '').toLowerCase();
    return OCCUPANCY_COLORS[color] || 'occupied';
  }
  const text = cellText(cell);
  if (!text) return '';
  // 未带颜色的 ● 或其它文字，同样视为占用
  return 'occupied';
}

/**
 * 解析内嵌的 12 小节占用表 → 逐小节占用记录
 * @param {HTMLTableElement|null} nested
 * @param {number} day 星期几（1-7）
 */
function parseNestedOccupancy(nested, day) {
  if (!nested) return [];
  const rows = Array.from(nested.rows || []);
  // 第 1 行是「第1节…第12节」标签，第 2 行才是占用标记
  const dataRow = rows[1];
  if (!dataRow) return [];

  const items = [];
  Array.from(dataRow.cells).forEach((cell, index) => {
    const type = detectOccupancyType(cell);
    if (!type) return;
    items.push({
      day,
      start: index + 1,
      span: 1,
      type,
      label: OCCUPANCY_TYPE_LABELS[type] || '占用',
      reason: parseOccupancyTitle(cell.querySelector('a[title]'))
    });
  });
  return items;
}

/** 解析页面标题「2026秋国际 第10周  周一(11-02) 教室占用维护」 */
function parseScheduleHeading(doc, fallbackDay) {
  const heading = (doc.querySelector('table#title td div')?.textContent || '')
    .replace(/\s+/g, ' ')
    .trim();

  const week = Number((heading.match(/第\s*(\d+)\s*周/) || [])[1]) || 0;
  const dayMatch = heading.match(/(周[一二三四五六日])\s*[（(]\s*([\d]{1,2}-[\d]{1,2})\s*[)）]/);
  const day = dayMatch ? WEEKDAY_TEXTS.indexOf(dayMatch[1]) + 1 : 0;
  const date = dayMatch ? dayMatch[2] : '';

  // 标题前半段是「学期 + 教学楼」，如 2026秋国际
  const head = heading.replace(/\s*第\s*\d+\s*周[\s\S]*$/, '').trim();
  const semesterMatch = head.match(/^(\d{4}(?:-\d{4})?\s*(?:春|夏|秋|冬)?)(.*)$/);

  return {
    heading,
    semester: semesterMatch ? semesterMatch[1].trim() : '',
    buildingName: semesterMatch ? semesterMatch[2].trim() : head,
    week,
    day: day || fallbackDay || 0,
    date
  };
}

/**
 * 解析按周查询的整楼占用（roomschedule_week.jsdo）
 *
 * @param {string} html
 * @param {number} [fallbackDay] 页面未标明星期时使用的兜底值
 * @returns {{
 *   semester:string, buildingName:string, week:number, day:number, date:string,
 *   rooms: Array<{id:string,name:string,seats:number|null,scheduleCapacity:number|null,
 *     examCapacity:number|null,deskType:string,roomType:string,
 *     occupancy:Array<{day:number,start:number,span:number,type:string,label:string,reason:string}>}>
 * }}
 */
export function parseClassroomDaySchedule(html, fallbackDay = 0) {
  const doc = createDom(stripHtmlComments(html));
  const meta = parseScheduleHeading(doc, fallbackDay);
  const table = doc.querySelector('table.infolist_tab');
  const rooms = [];

  if (table) {
    Array.from(table.rows || []).forEach(row => {
      // 表头是 th
      if (row.querySelector('th')) return;
      const cells = Array.from(row.cells || []);
      if (cells.length < 7) return;

      const name = cellText(cells[0]);
      if (!name) return;

      const id = (row.id || '').replace(/^tr/, '');
      const nested = cells[6].querySelector('table');
      rooms.push({
        id,
        name,
        seats: Number(cellText(cells[1])) || null,
        scheduleCapacity: Number(cellText(cells[2])) || null,
        examCapacity: Number(cellText(cells[3])) || null,
        deskType: cellText(cells[4]),
        roomType: cellText(cells[5]),
        occupancy: parseNestedOccupancy(nested, meta.day || fallbackDay)
      });
    });
  }

  return { ...meta, rooms };
}
