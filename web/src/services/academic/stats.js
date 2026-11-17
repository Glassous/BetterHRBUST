/**
 * 学业统计模块（哈尔滨理工大学官方口径）
 *
 * 数据来源与算法依据：
 * 《哈尔滨理工大学学生管理规定》第二十一条（校发规定，2017-09-01 起施行）
 *   - 学分绩点 = 成绩绩点 × 学分
 *   - 平均学分绩点 = Σ(成绩绩点 × 学分) / Σ学分
 *   - 百分制：60 分 = 1 绩点；60 分以上每增 1 分绩点 +0.1（即 (成绩-50)/10，100 分 = 5.0）；低于 60 分绩点为 0
 *   - 五级记分制：不及格 0 / 及格 1.5 / 中等 2.5 / 良好 3.5 / 优秀 4.5
 * 相关门槛（同文件）：
 *   - 学位证：必修课平均学分绩点 ≥ 1.5
 *   - 学业处理：累计挂科 > 15 学分留降级；> 25 学分退学
 *   - 推免：必修课成绩全部合格，且补考与重修课程累计不超过两门
 *
 * 基础 GPA 与风险指标统计必修课；学位绩点和培养方案学分分别使用独立课程范围。
 *
 * 重要：各学院 / 各年度细则可能存在差异，门槛均为可配置常量，界面须标注"以学校教务处口径为准"。
 *
 * 本模块为纯函数模块，无副作用，供成绩页 / 概览页 / 培养方案页共享，保证统计口径唯一。
 */

/** 学位绩点门槛（必修课平均学分绩点下限，以学校教务处口径为准） */
export const DEGREE_GPA_THRESHOLD = 1.5;
/** 历史兼容常量：不作为学校政策或资格判定展示 */
export const EARLY_GRAD_GPA_THRESHOLD = 4.0;
/** 累计挂科学分上限（超过 → 留降级） */
export const RISK_DOWNGRADE_CREDITS = 15;
/** 累计挂科学分上限（超过 → 退学处理） */
export const RISK_EXPEL_CREDITS = 25;
/** 推免：补考 + 重修课程累计门数上限 */
export const RECOMMEND_RETAKE_LIMIT = 2;
/**
 * 已获得学分是否只统计必修课（限选 / 任选不计）。
 * 与培养方案页、概览页共用一个开关，避免两侧口径再次分叉。
 */
export const EARNED_CREDITS_REQUIRED_ONLY = false;

/** 五级记分制 → 成绩绩点（官方折算） */
const LEVEL_GPA = {
  优秀: 4.5,
  优: 4.5,
  良好: 3.5,
  良: 3.5,
  中等: 2.5,
  中: 2.5,
  及格: 1.5,
  合格: 1.5,
  及格线: 1.5,
  不及格: 0,
  不合格: 0
};

/** 五级记分制 → 折算百分制（仅用于加权平均分估算） */
const LEVEL_SCORE = {
  优秀: 95,
  优: 95,
  良好: 85,
  良: 85,
  中等: 75,
  中: 75,
  及格: 65,
  合格: 65,
  及格线: 65,
  不及格: 0,
  不合格: 0
};

/** 补考 / 重修 关键字（用于推免资格自检的补考重修门数统计） */
const RETAKE_KEYWORDS = ['补考', '重修', '重考', '清考'];

function round1(n) {
  return parseFloat((Math.round((Number(n) || 0) * 10) / 10).toFixed(1));
}

function round2(n) {
  return parseFloat((Math.round((Number(n) || 0) * 100) / 100).toFixed(2));
}

/**
 * 将成绩文本归一化为百分制数值与官方成绩绩点
 * @param {string|number} raw 总评成绩（百分制数字或五级制等级）
 * @returns {{ numeric: number|null, gradePoint: number, estimated: number|null }}
 */
export function parseScoreValue(raw) {
  if (raw === null || raw === undefined) {
    return { numeric: null, gradePoint: 0, estimated: null };
  }
  const text = String(raw).trim();
  if (!text) return { numeric: null, gradePoint: 0, estimated: null };

  const num = parseFloat(text);
  if (!isNaN(num) && /^-?\d+(\.\d+)?$/.test(text)) {
    // 官方五分制：60 分 = 1 绩点，每增 1 分 +0.1，上限 5.0；低于 60 分为 0
    const gradePoint = num >= 60 ? Math.min(5.0, (num - 50) / 10) : 0;
    return { numeric: num, gradePoint, estimated: num };
  }

  const key = text.replace(/\s/g, '');
  if (key in LEVEL_GPA) {
    return { numeric: null, gradePoint: LEVEL_GPA[key], estimated: LEVEL_SCORE[key] };
  }
  return { numeric: null, gradePoint: 0, estimated: null };
}

/**
 * 单门课程成绩绩点（0 ~ 5.0）
 */
export function gradePoint(raw) {
  return parseScoreValue(raw).gradePoint;
}

/**
 * 选课属性归一化
 * @returns {'required'|'limited'|'elective'|'other'}
 */
export function resolveProperty(property) {
  const p = (property || '').trim();
  if (!p) return 'other';
  if (p.includes('必修')) return 'required';
  if (p.includes('任选') || p.includes('公选') || p.includes('通识')) return 'elective';
  if (p.includes('限选') || p.includes('选')) return 'limited';
  return 'other';
}

/** 是否必修课 */
export function isRequired(property) {
  return resolveProperty(property) === 'required';
}

/** 是否任选课 */
export function isElective(property) {
  return resolveProperty(property) === 'elective';
}

/**
 * 是否参与学业统计的课程。
 * 基础 GPA 与风险指标只统计必修课。
 */
export function isCountedCourse(property) {
  return isRequired(property);
}

/** 是否学位课（必修 + 限选） */
export function isDegreeCourse(property) {
  const p = resolveProperty(property);
  return p === 'required' || p === 'limited';
}

/**
 * 加权平均学分绩点（统一聚合口径）
 * GPA = Σ(成绩绩点 × 学分) / Σ学分，学分缺失或为 0 的记录忽略
 * 供学期走势图等需要"分维度聚合"的场景复用，避免各页各写一份公式
 * @param {Array} records 成绩记录（建议先经 dedupeScores 去重）
 */
export function gpaOf(records) {
  let totalCredits = 0;
  let totalGpaWeight = 0;
  (records || []).forEach(item => {
    const cr = Number(item.credit) || 0;
    if (cr <= 0 || parseScoreValue(item.score).estimated === null) return;
    totalCredits += cr;
    totalGpaWeight += gradePoint(item.score) * cr;
  });
  return totalCredits > 0 ? totalGpaWeight / totalCredits : 0;
}

/** 若干成绩记录中是否存在有效学分（用于趋势图过滤空学期） */
export function hasCredits(records) {
  return (records || []).some(item => (Number(item.credit) || 0) > 0);
}

/**
 * 同一公历年份内学期先后：春 → 夏 → 秋；未知学期值排在该年份最后。
 * 兼容教务下拉框序号（`1` = 春，`2` = 秋，见 api.js 以及 docs/api/02-scores.md §1.8）。
 */
const TERM_RANK = new Map([
  ['春', 0],
  ['春季', 0],
  ['1', 0],
  ['夏', 1],
  ['夏季', 1],
  ['秋', 2],
  ['秋季', 2],
  ['2', 2]
]);

/**
 * 学期时间轴排序键（走势图等需要按时间先后排列的场景使用）
 *
 * 教务成绩单的「学年」列是**该学期所在的公历年份**：如 2023 秋 → 2024 春 → 2024 秋
 * 是连续三个学期（2023 级大一上的下一学期记为 2024 春），
 * 因此同一「学年」值内必须先春后秋，跨学年按年份升序。
 * 返回数值键，可直接相减比较；教务接口的返回顺序不可依赖，排序必须显式做。
 */
export function semesterSortKey(year, term) {
  const yearNum = Number((String(year ?? '').match(/\d{4}/) || [])[0]) || 0;
  const key = String(term ?? '').trim().replace(/\s/g, '');
  const rank = TERM_RANK.has(key) ? TERM_RANK.get(key) : 3;
  return yearNum * 10 + rank;
}

function courseKey(item) {
  return (item.courseId && item.courseId.trim()) || item.courseName || '';
}

function isRetakeRecord(item) {
  const text = `${item.examType || ''}${item.remark || ''}`;
  return RETAKE_KEYWORDS.some(k => text.includes(k));
}

/**
 * 成绩记录合并去重：同一门课（courseId，空则回退 courseName）只保留一条
 * 规则：优先保留"通过且折算分最高"的记录；若该课从未通过，则保留折算分最高的一条用于挂科统计
 * @param {Array} scores 原始成绩记录
 * @returns {Array} 去重后的成绩记录（保持首次出现的顺序），附加 isRetake 标记
 */
export function dedupeScores(scores) {
  const order = [];
  const groups = new Map();

  (scores || []).forEach(item => {
    if (!item) return;
    const key = courseKey(item);
    if (!key) return;
    if (!groups.has(key)) {
      groups.set(key, { items: [], firstIndex: order.length });
      order.push(key);
    }
    groups.get(key).items.push(item);
  });

  const result = [];
  order.forEach(key => {
    const { items } = groups.get(key);
    // 通过优先，其次折算分高
    const sorted = items.toSorted((a, b) => {
      if (a.passed !== b.passed) return a.passed ? -1 : 1;
      return (parseScoreValue(b.score).estimated || 0) - (parseScoreValue(a.score).estimated || 0);
    });
    const best = sorted[0];
    const attempts = new Set(items.map(s => `${s.year}|${s.term}`));
    const retake = attempts.size > 1 || items.some(isRetakeRecord);
    result.push({ ...best, isRetake: retake, recordCount: items.length });
  });

  return result;
}

export function electiveCategory(item) {
  if (isRequired(item.property) || /专业/.test(item.courseGroup || '')) return null;
  const text = `${item.courseGroup || ''} ${item.property || ''} ${isElective(item.property) ? item.courseName || '' : ''}`.toUpperCase();
  const match = text.match(/([ABCDE])\s*类|[（(]([ABCDE])[）)]/);
  return match ? match[1] || match[2] : null;
}

export function degreeCourses(scores) {
  const courses = dedupeScores(scores).filter(s => Number(s.credit) > 0);
  const electives = courses.filter(s => electiveCategory(s));
  const rank = (a, b) => (parseScoreValue(b.score).estimated ?? -Infinity) - (parseScoreValue(a.score).estimated ?? -Infinity) || String(a.courseId).localeCompare(String(b.courseId));
  const academic = courses.filter(s => !electiveCategory(s) && (isDegreeCourse(s.property) || /专业/.test(s.courseGroup || '')));
  const firstE = electives.filter(s => electiveCategory(s) === 'E').sort(rank)[0];
  const second = firstE ? electives.filter(s => s !== firstE).sort(rank)[0] : undefined;
  return [...academic, firstE, second].filter(Boolean);
}

export function isLowScore(score) { return (parseScoreValue(score).estimated ?? Infinity) < 70; }

/**
 * 取出参与学业统计的课程记录（仅必修课，已合并去重）
 * 供学期走势、成绩分段等派生统计复用，保证口径与总览完全一致
 */
export function countedCourses(scores) {
  return dedupeScores(scores).filter(s => isCountedCourse(s.property) && Number(s.credit) > 0);
}

/**
 * 计算一组课程的平均学分绩点与加权平均分
 */
function calcWeighted(courses) {
  let creditSum = 0;
  let scoreWeight = 0;
  let gpaWeight = 0;
  let excCount = 0;

  courses.forEach(item => {
    const cr = Number(item.credit) || 0;
    if (cr <= 0) return;
    const { numeric, gradePoint: gp, estimated } = parseScoreValue(item.score);
    if (estimated === null) return;

    creditSum += cr;
    scoreWeight += estimated * cr;
    gpaWeight += gp * cr;
    if ((numeric !== null && numeric >= 90) || (!numeric && gp >= 4.5)) excCount += 1;
  });

  return {
    creditSum,
    gpa: creditSum > 0 ? round2(gpaWeight / creditSum) : 0,
    weightedAvg: creditSum > 0 ? round1(scoreWeight / creditSum) : 0,
    excCount
  };
}

/**
 * 学业统计总入口
 * @param {Array} scores 原始成绩记录（未去重）
 * @returns {Object} 统一统计结果
 */
export function buildAcademicStats(scores) {
  const list = scores || [];
  const deduped = dedupeScores(list);

  // 统计口径：仅统计必修课（限选 / 任选均不参与任何计算）
  // 注意：dedupeScores 返回的是「原始课程对象 + isRetake/recordCount」的扁平结构（非 { item } 包装）
  const requiredCourses = deduped.filter(s => isRequired(s.property) && Number(s.credit) > 0);

  const totalCredits = round1(
    requiredCourses.reduce((acc, s) => acc + (Number(s.credit) || 0), 0)
  );
  // pending（未出分/缓考等无结论行）不计入已获学分（保守口径：未确认通过 ≠ 已通过）
  const earnedCredits = round1(
    requiredCourses.filter(s => s.passed && !s.pending).reduce((acc, s) => acc + (Number(s.credit) || 0), 0)
  );

  // 挂科同样只统计有明确结论的行：pending 不算挂科
  const failedCourses = requiredCourses.filter(s => !s.passed && !s.pending);
  const failedCount = failedCourses.length;
  const failedCredits = round1(failedCourses.reduce((acc, s) => acc + (Number(s.credit) || 0), 0));

  // 必修课加权：GPA / 加权平均分 / 优秀率
  const overall = calcWeighted(requiredCourses);

  const selectedDegreeCourses = degreeCourses(list);
  const degreeWeight = calcWeighted(selectedDegreeCourses);
  const degreeAllPassed = selectedDegreeCourses.length > 0 && selectedDegreeCourses.every(s => s.passed);
  const degreeQualified = hasCredits(selectedDegreeCourses) && gpaOf(selectedDegreeCourses) >= DEGREE_GPA_THRESHOLD;

  // ---- 特色算法 ② 推免 / 保研（必修课口径，统计补考 + 重修门数） ----
  const retakeCount = requiredCourses.filter(s => s.isRetake).length;
  const recommendAllPassed = requiredCourses.length > 0 && failedCount === 0;
  const recommendRetakeOk = retakeCount <= RECOMMEND_RETAKE_LIMIT;
  const recommendQualified = requiredCourses.length > 0 && recommendAllPassed && recommendRetakeOk;

  // ---- 特色算法 ③ 学业风险预警 ----
  let riskLevel = 'none';
  if (failedCredits > RISK_EXPEL_CREDITS) riskLevel = 'expel';
  else if (failedCredits > RISK_DOWNGRADE_CREDITS) riskLevel = 'downgrade';

  // ---- 特色算法 ④ 提前毕业 ----
  const earlyGraduationQualified = overall.creditSum > 0 && overall.gpa >= EARLY_GRAD_GPA_THRESHOLD;

  return {
    // 学业总览（必修课，去重后）
    gpa: overall.gpa,
    weightedAvg: overall.weightedAvg,
    courseCount: requiredCourses.length,
    rawCourseCount: list.length,
    dedupedCount: deduped.length,
    retakeCount: deduped.filter(s => s.isRetake && Number(s.credit) > 0).length,
    excellentRate: requiredCourses.length ? Math.round((overall.excCount / requiredCourses.length) * 100) : 0,

    // 学分（必修课口径：选修课不计数；重修/补考已合并去重）
    totalCredits,
    earnedCredits,
    earnedRatio: totalCredits > 0 ? Math.min(1, earnedCredits / totalCredits) : 0,

    // 挂科（必修课口径）
    failedCount,
    failedCredits,

    // 特色算法 ① 学位证
    degree: {
      gpa: degreeWeight.gpa,
      courseCount: selectedDegreeCourses.length,
      requiredCredits: round1(selectedDegreeCourses.reduce((sum, s) => sum + Number(s.credit), 0)),
      earnedCredits: round1(selectedDegreeCourses.filter(s => s.passed && !s.pending).reduce((sum, s) => sum + Number(s.credit), 0)),
      allPassed: degreeAllPassed,
      threshold: DEGREE_GPA_THRESHOLD,
      qualified: degreeQualified
    },

    // 特色算法 ② 推免 / 保研
    recommend: {
      gpa: overall.gpa,
      courseCount: requiredCourses.length,
      allPassed: recommendAllPassed,
      retakeCount,
      retakeLimit: RECOMMEND_RETAKE_LIMIT,
      retakeWithinLimit: recommendRetakeOk,
      qualified: recommendQualified
    },

    // 特色算法 ③ 学业风险预警
    risk: {
      failedCredits,
      downgradeLine: RISK_DOWNGRADE_CREDITS,
      expelLine: RISK_EXPEL_CREDITS,
      level: riskLevel,
      label: riskLevel === 'expel' ? '退学风险' : riskLevel === 'downgrade' ? '留降级风险' : '正常',
      description:
        riskLevel === 'expel'
          ? `累计挂科 ${failedCredits} 学分，已超过 ${RISK_EXPEL_CREDITS} 学分退学警戒线`
          : riskLevel === 'downgrade'
            ? `累计挂科 ${failedCredits} 学分，已超过 ${RISK_DOWNGRADE_CREDITS} 学分留降级警戒线`
            : `累计挂科 ${failedCredits} 学分，未触及 ${RISK_DOWNGRADE_CREDITS} 学分留降级警戒线`
    },

    // 特色算法 ④ 提前毕业
    earlyGraduation: {
      gpa: overall.gpa,
      threshold: EARLY_GRAD_GPA_THRESHOLD,
      qualified: earlyGraduationQualified
    }
  };
}

/**
 * 计算培养方案课组学分完成度（概览页与培养方案页共用，保证两侧口径一致）
 * @param {Array} scores 原始成绩记录
 * @param {Array} groups 培养方案课组（parseCurriculumPlan 产出）
 * @param {Object} [options]
 * @param {boolean} [options.requiredOnly] 是否只统计必修课，默认读取 EARNED_CREDITS_REQUIRED_ONLY
 * @returns {{ categories: Array, earnedTotal: number, requiredTotal: number, completionPercent: number, requiredOnly: boolean }}
 */
export function isProfessionalElectiveGroup(group) { return /专业(?:方向)?(?:选修|限选)/.test(group.name); }

export function planGroupRequiredCredits(group) { return Number(group.requiredCredits) || 0; }


export function computeCreditsProgress(scores, groups, options = {}) {
  const requiredOnly = options.requiredOnly !== undefined ? !!options.requiredOnly : EARNED_CREDITS_REQUIRED_ONLY;
  const deduped = dedupeScores(scores);
  const eligible = deduped.filter(s => s.passed && (!requiredOnly || isRequired(s.property)));
  const matched = new Set();

  const planGroups = [...new Map((groups || []).filter(Boolean).map(g => [g.id || g.name, g])).values()];

  let categories = [];
  if (planGroups.length > 0) {
    categories = planGroups.map(g => {
      let earned = 0;
      eligible.forEach(s => {
        const key = courseKey(s);
        if (matched.has(key)) return;
        const inGroup = (g.courses || []).some(c => c.code === s.courseId || c.name === s.courseName);
        const nameMatch =
          s.courseGroup &&
          (g.name.includes(s.courseGroup) ||
            s.courseGroup.includes(g.name) ||
            g.name.slice(0, 3) === s.courseGroup.slice(0, 3));
        if (inGroup || nameMatch) {
          earned += Number(s.credit) || 0;
          matched.add(key);
        }
      });
      return {
        name: g.name,
        property: g.property,
        required: round1(planGroupRequiredCredits(g)),
        requiredCourses: isProfessionalElectiveGroup(g) ? 4 : Number(g.requiredCourses) || 0,
        electiveCandidates: isProfessionalElectiveGroup(g) ? (g.courses || []).length : null,
        earned: round1(earned)
      };
    });
  } else {
    // 降级：无培养方案数据时，按成绩单中的课组归集（全部标记为已归属，避免重复计入"方案外课程"）
    const groupMap = new Map();
    eligible.forEach(s => {
      const grp = s.courseGroup || '其它';
      groupMap.set(grp, (groupMap.get(grp) || 0) + (Number(s.credit) || 0));
      matched.add(courseKey(s));
    });
    for (const [grpName, cr] of groupMap.entries()) {
      categories.push({
        name: grpName,
        property: '必修',
        required: 0,
        earned: round1(cr)
      });
    }
  }

  // 汇总未归属到方案课组的学分（方案外课程）
  let leftover = 0;
  eligible.forEach(s => {
    const key = courseKey(s);
    if (!matched.has(key)) leftover += Number(s.credit) || 0;
  });
  if (leftover > 0) {
    categories.push({
      name: planGroups.length > 0 ? '方案外课程（通识选修 / 实践拓展）' : '其它课程',
      property: '任选',
      required: 0,
      earned: round1(leftover)
    });
  }

  const earnedTotal = round1(categories.reduce((acc, c) => acc + c.earned, 0));
  const summary = planGroups.find(g => ['总计', '合计', '全部课程', '毕业要求', '培养方案总计'].includes(g.name.trim()));
  const requiredTotal = Number(options.planTotalCredits) > 0 ? Number(options.planTotalCredits) : Number(summary?.requiredCredits) > 0 ? Number(summary.requiredCredits) : round1(categories.reduce((acc, c) => acc + c.required, 0));

  return {
    categories,
    earnedTotal,
    requiredTotal,
    completionPercent: requiredTotal > 0 ? Math.min(100, Math.round((earnedTotal / requiredTotal) * 100)) : 0,
    requiredOnly
  };
}

/** 学业统计口径说明文案（供各页面统一展示） */
export const STATS_SCOPE_NOTE =
  '学位绩点：学业课 + E 类最高 1 门 + 排除该课后剩余 A–E 类最高 1 门；0 学分不计。风险与推免按必修课统计';

/** 已获得学分口径说明文案（供各页面统一展示） */
export const EARNED_CREDITS_NOTE = EARNED_CREDITS_REQUIRED_ONLY
  ? '已获得学分仅统计必修课，重修/补考及格后合并计一次'
  : '已获得学分按全部课程统计，重修/补考及格后合并计一次';
