//! 学业统计（命令行版）
//!
//! **口径来源：`web/src/services/academic/stats.js`，逐条对照实现。**
//! 命令行与图形界面必须给出同一组数字，否则用户会怀疑两个端的数据可信度，
//! 因此这里不"重新设计"算法，只做等价移植：
//!  - 五分制绩点：60 分 = 1 绩点，每增 1 分 +0.1（即 (成绩-50)/10，上限 5.0），60 分以下为 0；
//!  - 五级记分制：优秀/优 4.5、良好/良 3.5、中等/中 2.5、及格/合格 1.5、不及格 0；
//!  - 基础 GPA / 已获学分 / 挂科 / 推免 / 风险预警只统计**必修课**，重修补考合并去重；
//!  - 学位绩点单独使用"学业课 + E 类最高一门 + 其余 A–E 类最高一门"的课程集合。
//!
//! 一致性由单元测试守住：`web/scripts/test-academic-corrections.mjs` 里的断言
//! 在本文底部逐条移植（含 3.07 / 7 学分 / pending 不计学分 / 等级制折算等）。
//! 修改任一端算法时，必须同步另一端的测试。

use std::cmp::Ordering;
use std::collections::BTreeSet;

use serde::Serialize;

use super::store::ScoreRecord;

/// 学位绩点门槛（必修课平均学分绩点下限，以学校教务处口径为准）
pub const DEGREE_GPA_THRESHOLD: f64 = 1.5;
/// 历史兼容常量：不作为学校政策或资格判定展示
pub const EARLY_GRAD_GPA_THRESHOLD: f64 = 4.0;
/// 累计挂科学分上限（超过 → 留降级）
pub const RISK_DOWNGRADE_CREDITS: f64 = 15.0;
/// 累计挂科学分上限（超过 → 退学处理）
pub const RISK_EXPEL_CREDITS: f64 = 25.0;
/// 推免：补考 + 重修课程累计门数上限
pub const RECOMMEND_RETAKE_LIMIT: u32 = 2;
/// 已获得学分是否只统计必修课（与前端同名开关保持一致）
pub const EARNED_CREDITS_REQUIRED_ONLY: bool = false;

const RETAKE_KEYWORDS: [&str; 4] = ["补考", "重修", "重考", "清考"];

pub fn round1(value: f64) -> f64 {
    (value * 10.0).round() / 10.0
}

pub fn round2(value: f64) -> f64 {
    (value * 100.0).round() / 100.0
}

/// 成绩文本 → 百分制数值与官方绩点
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScoreValue {
    pub numeric: Option<f64>,
    pub grade_point: f64,
    pub estimated: Option<f64>,
}

impl ScoreValue {
    const EMPTY: Self = Self { numeric: None, grade_point: 0.0, estimated: None };
}

/// `/^-?\d+(\.\d+)?$/` 的等价判断（JS 用它区分"百分制数字"与"等级文本"）
fn is_plain_decimal(text: &str) -> bool {
    let bytes = text.as_bytes();
    if bytes.is_empty() {
        return false;
    }
    let mut index = 0;
    if bytes[0] == b'-' {
        index = 1;
        if bytes.len() == 1 {
            return false;
        }
    }
    let mut digits = 0;
    while index < bytes.len() && bytes[index].is_ascii_digit() {
        index += 1;
        digits += 1;
    }
    if digits == 0 {
        return false;
    }
    if index < bytes.len() && bytes[index] == b'.' {
        index += 1;
        let mut fraction = 0;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
            fraction += 1;
        }
        if fraction == 0 {
            return false;
        }
    }
    index == bytes.len()
}

pub fn parse_score_value(raw: &str) -> ScoreValue {
    let text = raw.trim();
    if text.is_empty() {
        return ScoreValue::EMPTY;
    }
    if is_plain_decimal(text) {
        let Ok(number) = text.parse::<f64>() else {
            return ScoreValue::EMPTY;
        };
        let grade_point = if number >= 60.0 { ((number - 50.0) / 10.0).min(5.0) } else { 0.0 };
        return ScoreValue { numeric: Some(number), grade_point, estimated: Some(number) };
    }
    let key: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    let (grade_point, estimated) = match key.as_str() {
        "优秀" | "优" => (4.5, 95.0),
        "良好" | "良" => (3.5, 85.0),
        "中等" | "中" => (2.5, 75.0),
        "及格" | "合格" | "及格线" => (1.5, 65.0),
        "不及格" | "不合格" => (0.0, 0.0),
        _ => return ScoreValue::EMPTY,
    };
    ScoreValue { numeric: None, grade_point, estimated: Some(estimated) }
}

pub fn grade_point(raw: &str) -> f64 {
    parse_score_value(raw).grade_point
}

/// 选课属性归一化
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Property {
    Required,
    Limited,
    Elective,
    Other,
}

pub fn resolve_property(property: &str) -> Property {
    let text = property.trim();
    if text.is_empty() {
        return Property::Other;
    }
    if text.contains("必修") {
        return Property::Required;
    }
    if text.contains("任选") || text.contains("公选") || text.contains("通识") {
        return Property::Elective;
    }
    if text.contains("限选") || text.contains("选") {
        return Property::Limited;
    }
    Property::Other
}

/// `--property` 取值映射：只接受四类明确口径，其余返回 None 供参数校验报错
pub fn property_filter(text: &str) -> Option<Property> {
    match text.trim() {
        "必修" | "required" => Some(Property::Required),
        "限选" | "limited" => Some(Property::Limited),
        "任选" | "elective" | "公选" | "通识" => Some(Property::Elective),
        "其他" | "other" => Some(Property::Other),
        _ => None,
    }
}

pub fn is_required(property: &str) -> bool {
    resolve_property(property) == Property::Required
}

pub fn is_elective(property: &str) -> bool {
    resolve_property(property) == Property::Elective
}

/// 是否学位课（必修 + 限选）
pub fn is_degree_course(property: &str) -> bool {
    matches!(resolve_property(property), Property::Required | Property::Limited)
}

fn course_key(record: &ScoreRecord) -> String {
    let id = record.course_id.trim();
    if !id.is_empty() {
        return id.to_string();
    }
    record.course_name.clone()
}

fn is_retake_record(record: &ScoreRecord) -> bool {
    let text = format!("{}{}", record.exam_type, record.remark);
    RETAKE_KEYWORDS.iter().any(|keyword| text.contains(keyword))
}

/// 成绩去重合并：同一门课（course_id，缺失回退课程名）只保留一条。
/// 规则：优先保留"通过且折算分最高"的记录；从未通过时保留折算分最高的一条用于挂科统计。
pub fn dedupe_scores(scores: &[ScoreRecord]) -> Vec<ScoreRecord> {
    let mut order: Vec<String> = Vec::new();
    let mut groups: Vec<(String, Vec<&ScoreRecord>)> = Vec::new();

    for record in scores {
        let key = course_key(record);
        if key.is_empty() {
            continue;
        }
        match groups.iter_mut().find(|(existing, _)| *existing == key) {
            Some((_, items)) => items.push(record),
            None => {
                order.push(key.clone());
                groups.push((key, vec![record]));
            }
        }
    }

    let mut result = Vec::with_capacity(groups.len());
    for key in order {
        let Some((_, items)) = groups.iter().find(|(existing, _)| *existing == key) else {
            continue;
        };
        let mut sorted: Vec<&&ScoreRecord> = items.iter().collect();
        // 稳定排序：通过优先，其次折算分高（与 JS 的 Array.prototype.sort 一致）
        sorted.sort_by(|a, b| {
            b.passed.cmp(&a.passed).then_with(|| {
                let left = parse_score_value(&b.score).estimated.unwrap_or(0.0);
                let right = parse_score_value(&a.score).estimated.unwrap_or(0.0);
                left.partial_cmp(&right).unwrap_or(Ordering::Equal)
            })
        });
        let best: &ScoreRecord = sorted.first().map(|item| **item).expect("分组内至少一条记录");
        let attempts: BTreeSet<(String, String)> =
            items.iter().map(|item| (item.year.clone(), item.term.clone())).collect();
        let retake = attempts.len() > 1 || items.iter().any(|item| is_retake_record(item));
        let mut merged = best.clone();
        merged.is_retake = retake;
        merged.record_count = items.len();
        result.push(merged);
    }
    result
}

/// 选修类别（A–E 类），非选修课返回 None
pub fn elective_category(record: &ScoreRecord) -> Option<char> {
    if is_required(&record.property) || record.course_group.contains("专业") {
        return None;
    }
    let elective_name = if is_elective(&record.property) { record.course_name.as_str() } else { "" };
    let text = format!("{} {} {}", record.course_group, record.property, elective_name).to_uppercase();
    find_category(&text)
}

/// 等价于 `/([ABCDE])\s*类|[（(]([ABCDE])[）)]/`
fn find_category(text: &str) -> Option<char> {
    let chars: Vec<char> = text.chars().collect();
    for index in 0..chars.len() {
        // 第一个分支：字母 + 可选空白 + 「类」
        if matches!(chars[index], 'A'..='E') {
            let mut next = index + 1;
            while next < chars.len() && chars[next].is_whitespace() {
                next += 1;
            }
            if next < chars.len() && chars[next] == '类' {
                return Some(chars[index]);
            }
        }
        // 第二个分支：「(字母)」或「（字母）」
        if matches!(chars[index], '(' | '（') && index + 2 < chars.len() {
            if matches!(chars[index + 1], 'A'..='E') && matches!(chars[index + 2], ')' | '）') {
                return Some(chars[index + 1]);
            }
        }
    }
    None
}

/// 学位课集合：学业课 + E 类最高一门 + 其余 A–E 类最高一门（0 学分不计）
pub fn degree_courses(scores: &[ScoreRecord]) -> Vec<ScoreRecord> {
    let courses: Vec<ScoreRecord> =
        dedupe_scores(scores).into_iter().filter(|item| item.credit > 0.0).collect();
    let categories: Vec<Option<char>> = courses.iter().map(elective_category).collect();

    let rank = |left: &ScoreRecord, right: &ScoreRecord| {
        let left_score = parse_score_value(&left.score).estimated;
        let right_score = parse_score_value(&right.score).estimated;
        right_score
            .partial_cmp(&left_score)
            .unwrap_or(Ordering::Equal)
            .then_with(|| left.course_id.cmp(&right.course_id))
    };

    let mut sorted_electives: Vec<usize> =
        (0..courses.len()).filter(|index| categories[*index].is_some()).collect();
    sorted_electives.sort_by(|a, b| rank(&courses[*a], &courses[*b]));

    let first_e = sorted_electives
        .iter()
        .copied()
        .find(|index| categories[*index] == Some('E'));
    let second = first_e.and_then(|first| {
        let mut rest: Vec<usize> =
            sorted_electives.iter().copied().filter(|index| *index != first).collect();
        rest.sort_by(|a, b| rank(&courses[*a], &courses[*b]));
        rest.first().copied()
    });

    let mut selected: Vec<ScoreRecord> = Vec::new();
    for (index, course) in courses.iter().enumerate() {
        let category = categories[index];
        let academic = category.is_none()
            && (is_degree_course(&course.property) || course.course_group.contains("专业"));
        if academic {
            selected.push(course.clone());
        }
    }
    for index in [first_e, second].into_iter().flatten() {
        selected.push(courses[index].clone());
    }
    selected
}

#[derive(Debug, Clone, Copy, Default)]
struct Weighted {
    credit_sum: f64,
    gpa: f64,
    weighted_avg: f64,
    exc_count: u32,
}

fn calc_weighted(courses: &[ScoreRecord]) -> Weighted {
    let mut credit_sum = 0.0;
    let mut score_weight = 0.0;
    let mut gpa_weight = 0.0;
    let mut exc_count = 0u32;
    for item in courses {
        if item.credit <= 0.0 {
            continue;
        }
        let value = parse_score_value(&item.score);
        let Some(estimated) = value.estimated else {
            continue;
        };
        credit_sum += item.credit;
        score_weight += estimated * item.credit;
        gpa_weight += value.grade_point * item.credit;
        let excellent = match value.numeric {
            Some(numeric) => numeric >= 90.0,
            None => value.grade_point >= 4.5,
        };
        if excellent {
            exc_count += 1;
        }
    }
    Weighted {
        credit_sum,
        gpa: if credit_sum > 0.0 { round2(gpa_weight / credit_sum) } else { 0.0 },
        weighted_avg: if credit_sum > 0.0 { round1(score_weight / credit_sum) } else { 0.0 },
        exc_count,
    }
}

/// 加权平均学分绩点（分维度聚合场景复用）
pub fn gpa_of(records: &[ScoreRecord]) -> f64 {
    let mut total_credits = 0.0;
    let mut total_weight = 0.0;
    for item in records {
        if item.credit <= 0.0 || parse_score_value(&item.score).estimated.is_none() {
            continue;
        }
        total_credits += item.credit;
        total_weight += grade_point(&item.score) * item.credit;
    }
    if total_credits > 0.0 {
        total_weight / total_credits
    } else {
        0.0
    }
}

/// 若干成绩记录中是否存在有效学分
pub fn has_credits(records: &[ScoreRecord]) -> bool {
    records.iter().any(|item| item.credit > 0.0)
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DegreeStats {
    pub gpa: f64,
    pub course_count: usize,
    pub required_credits: f64,
    pub earned_credits: f64,
    pub all_passed: bool,
    pub threshold: f64,
    pub qualified: bool,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecommendStats {
    pub gpa: f64,
    pub course_count: usize,
    pub all_passed: bool,
    pub retake_count: u32,
    pub retake_limit: u32,
    pub retake_within_limit: bool,
    pub qualified: bool,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RiskStats {
    pub failed_credits: f64,
    pub downgrade_line: f64,
    pub expel_line: f64,
    /// `none` / `downgrade` / `expel`
    pub level: String,
    pub label: String,
    pub description: String,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EarlyGraduationStats {
    pub gpa: f64,
    pub threshold: f64,
    pub qualified: bool,
}

/// 学业统计总览（必修课口径）
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AcademicStats {
    pub gpa: f64,
    pub weighted_avg: f64,
    pub course_count: usize,
    pub raw_course_count: usize,
    pub deduped_count: usize,
    pub retake_count: u32,
    pub excellent_rate: u32,
    pub total_credits: f64,
    pub earned_credits: f64,
    pub earned_ratio: f64,
    pub failed_count: usize,
    pub failed_credits: f64,
    pub degree: DegreeStats,
    pub recommend: RecommendStats,
    pub risk: RiskStats,
    pub early_graduation: EarlyGraduationStats,
}

pub fn build_academic_stats(scores: &[ScoreRecord]) -> AcademicStats {
    let deduped = dedupe_scores(scores);
    let required: Vec<ScoreRecord> = deduped
        .iter()
        .filter(|item| is_required(&item.property) && item.credit > 0.0)
        .cloned()
        .collect();

    let total_credits = round1(required.iter().map(|item| item.credit).sum());
    let earned_credits = round1(
        required
            .iter()
            .filter(|item| item.passed && !item.pending)
            .map(|item| item.credit)
            .sum(),
    );
    let failed: Vec<&ScoreRecord> =
        required.iter().filter(|item| !item.passed && !item.pending).collect();
    let failed_count = failed.len();
    let failed_credits = round1(failed.iter().map(|item| item.credit).sum());

    let overall = calc_weighted(&required);

    let degree_selected = degree_courses(scores);
    let degree_weight = calc_weighted(&degree_selected);
    let degree_all_passed = !degree_selected.is_empty() && degree_selected.iter().all(|s| s.passed);
    let degree_qualified =
        has_credits(&degree_selected) && gpa_of(&degree_selected) >= DEGREE_GPA_THRESHOLD;

    let retake_count = required.iter().filter(|item| item.is_retake).count() as u32;
    let recommend_all_passed = !required.is_empty() && failed_count == 0;
    let recommend_retake_ok = retake_count <= RECOMMEND_RETAKE_LIMIT;
    let recommend_qualified = !required.is_empty() && recommend_all_passed && recommend_retake_ok;

    let (risk_level, risk_label, risk_description) = if failed_credits > RISK_EXPEL_CREDITS {
        (
            "expel",
            "退学风险",
            format!("累计挂科 {failed_credits} 学分，已超过 {RISK_EXPEL_CREDITS} 学分退学警戒线"),
        )
    } else if failed_credits > RISK_DOWNGRADE_CREDITS {
        (
            "downgrade",
            "留降级风险",
            format!(
                "累计挂科 {failed_credits} 学分，已超过 {RISK_DOWNGRADE_CREDITS} 学分留降级警戒线"
            ),
        )
    } else {
        (
            "none",
            "正常",
            format!("累计挂科 {failed_credits} 学分，未触及 {RISK_DOWNGRADE_CREDITS} 学分留降级警戒线"),
        )
    };

    AcademicStats {
        gpa: overall.gpa,
        weighted_avg: overall.weighted_avg,
        course_count: required.len(),
        raw_course_count: scores.len(),
        deduped_count: deduped.len(),
        retake_count: deduped.iter().filter(|s| s.is_retake && s.credit > 0.0).count() as u32,
        excellent_rate: if required.is_empty() {
            0
        } else {
            ((overall.exc_count as f64 / required.len() as f64) * 100.0).round() as u32
        },
        total_credits,
        earned_credits,
        earned_ratio: if total_credits > 0.0 {
            (earned_credits / total_credits).min(1.0)
        } else {
            0.0
        },
        failed_count,
        failed_credits,
        degree: DegreeStats {
            gpa: degree_weight.gpa,
            course_count: degree_selected.len(),
            required_credits: round1(degree_selected.iter().map(|s| s.credit).sum()),
            earned_credits: round1(
                degree_selected
                    .iter()
                    .filter(|s| s.passed && !s.pending)
                    .map(|s| s.credit)
                    .sum(),
            ),
            all_passed: degree_all_passed,
            threshold: DEGREE_GPA_THRESHOLD,
            qualified: degree_qualified,
        },
        recommend: RecommendStats {
            gpa: overall.gpa,
            course_count: required.len(),
            all_passed: recommend_all_passed,
            retake_count,
            retake_limit: RECOMMEND_RETAKE_LIMIT,
            retake_within_limit: recommend_retake_ok,
            qualified: recommend_qualified,
        },
        risk: RiskStats {
            failed_credits,
            downgrade_line: RISK_DOWNGRADE_CREDITS,
            expel_line: RISK_EXPEL_CREDITS,
            level: risk_level.to_string(),
            label: risk_label.to_string(),
            description: risk_description,
        },
        early_graduation: EarlyGraduationStats {
            gpa: overall.gpa,
            threshold: EARLY_GRAD_GPA_THRESHOLD,
            qualified: overall.credit_sum > 0.0 && overall.gpa >= EARLY_GRAD_GPA_THRESHOLD,
        },
    }
}

/// 学期时间轴排序键：教务「学年」列是学期所在公历年份，同年内先春后秋
pub fn semester_sort_key(year: &str, term: &str) -> i64 {
    let year_num = year
        .as_bytes()
        .windows(4)
        .find(|window| window.iter().all(u8::is_ascii_digit))
        .and_then(|window| std::str::from_utf8(window).ok())
        .and_then(|text| text.parse::<i64>().ok())
        .unwrap_or(0);
    let rank = match term.trim().replace(' ', "").as_str() {
        "春" | "春季" | "1" => 0,
        "夏" | "夏季" => 1,
        "秋" | "秋季" | "2" => 2,
        _ => 3,
    };
    year_num * 10 + rank
}

/// 课组学分完成度分类
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryProgress {
    pub name: String,
    pub property: String,
    pub required: f64,
    pub earned: f64,
    pub required_courses: Option<u32>,
    pub elective_candidates: Option<usize>,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreditsProgress {
    pub categories: Vec<CategoryProgress>,
    pub earned_total: f64,
    pub required_total: f64,
    pub completion_percent: u32,
    pub required_only: bool,
}

pub fn is_professional_elective_group(name: &str) -> bool {
    name.contains("专业") && (name.contains("选修") || name.contains("限选"))
}

/// 培养方案学分完成度（与概览页 / 培养方案页共用口径）
pub fn compute_credits_progress(
    scores: &[ScoreRecord],
    groups: &[super::store::PlanGroup],
    plan_total_credits: Option<f64>,
    required_only: bool,
) -> CreditsProgress {
    let deduped = dedupe_scores(scores);
    let eligible: Vec<ScoreRecord> =
        deduped.into_iter().filter(|item| item.passed && (!required_only || is_required(&item.property))).collect();
    let mut matched: BTreeSet<String> = BTreeSet::new();

    let mut plan_groups: Vec<&super::store::PlanGroup> = Vec::new();
    for group in groups {
        let key = if group.id.is_empty() { &group.name } else { &group.id };
        if plan_groups
            .iter()
            .any(|existing| if existing.id.is_empty() { &existing.name } else { &existing.id } == key)
        {
            continue;
        }
        plan_groups.push(group);
    }

    let mut categories: Vec<CategoryProgress> = Vec::new();
    if !plan_groups.is_empty() {
        for group in &plan_groups {
            let mut earned = 0.0;
            for record in &eligible {
                let key = course_key(record);
                if matched.contains(&key) {
                    continue;
                }
                let in_group = group
                    .courses
                    .iter()
                    .any(|course| course.code == record.course_id || course.name == record.course_name);
                let name_match = !record.course_group.is_empty()
                    && (group.name.contains(&record.course_group)
                        || record.course_group.contains(&group.name)
                        || first_chars(&group.name, 3) == first_chars(&record.course_group, 3));
                if in_group || name_match {
                    earned += record.credit;
                    matched.insert(key);
                }
            }
            let professional = is_professional_elective_group(&group.name);
            categories.push(CategoryProgress {
                name: group.name.clone(),
                property: group.property.clone(),
                required: round1(group.required_credits),
                earned: round1(earned),
                required_courses: Some(if professional { 4 } else { group.required_courses }),
                elective_candidates: if professional { Some(group.courses.len()) } else { None },
            });
        }
    } else {
        for record in &eligible {
            let group_name =
                if record.course_group.trim().is_empty() { "其它".to_string() } else { record.course_group.clone() };
            match categories.iter_mut().find(|category| category.name == group_name) {
                Some(category) => category.earned += record.credit,
                None => categories.push(CategoryProgress {
                    name: group_name,
                    property: "必修".to_string(),
                    required: 0.0,
                    earned: record.credit,
                    required_courses: None,
                    elective_candidates: None,
                }),
            }
            matched.insert(course_key(record));
        }
        for category in &mut categories {
            category.earned = round1(category.earned);
        }
    }

    let mut leftover = 0.0;
    for record in &eligible {
        if !matched.contains(&course_key(record)) {
            leftover += record.credit;
        }
    }
    if leftover > 0.0 {
        categories.push(CategoryProgress {
            name: if plan_groups.is_empty() {
                "其它课程".to_string()
            } else {
                "方案外课程（通识选修 / 实践拓展）".to_string()
            },
            property: "任选".to_string(),
            required: 0.0,
            earned: round1(leftover),
            required_courses: None,
            elective_candidates: None,
        });
    }

    let earned_total = round1(categories.iter().map(|category| category.earned).sum());
    let summary = plan_groups.iter().find(|group| {
        matches!(
            group.name.trim(),
            "总计" | "合计" | "全部课程" | "毕业要求" | "培养方案总计"
        )
    });
    let required_total = match plan_total_credits.filter(|value| *value > 0.0) {
        Some(total) => total,
        None => match summary.map(|group| group.required_credits).filter(|value| *value > 0.0) {
            Some(total) => total,
            None => round1(categories.iter().map(|category| category.required).sum()),
        },
    };

    CreditsProgress {
        categories,
        earned_total,
        required_total,
        completion_percent: if required_total > 0.0 {
            ((earned_total / required_total) * 100.0).round().clamp(0.0, 100.0) as u32
        } else {
            0
        },
        required_only,
    }
}

fn first_chars(text: &str, count: usize) -> String {
    text.chars().take(count).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 与 `web/scripts/test-academic-corrections.mjs` 的 `course()` 夹具同构
    fn course(
        course_id: &str,
        score: &str,
        credit: f64,
        course_group: &str,
        property: &str,
        exam_type: &str,
    ) -> ScoreRecord {
        let numeric: f64 = score.parse().unwrap_or(0.0);
        ScoreRecord {
            course_id: course_id.to_string(),
            course_name: course_id.to_string(),
            score: score.to_string(),
            credit,
            course_group: course_group.to_string(),
            property: property.to_string(),
            exam_type: exam_type.to_string(),
            year: "2024".to_string(),
            term: "1".to_string(),
            passed: numeric >= 60.0,
            ..Default::default()
        }
    }

    fn req(course_id: &str, score: &str, credit: f64) -> ScoreRecord {
        course(course_id, score, credit, "", "必修", "正常考试")
    }

    fn ids(records: &[ScoreRecord]) -> Vec<&str> {
        records.iter().map(|record| record.course_id.as_str()).collect()
    }

    /// 学位课：学业课 + E 类最高一门 + 其余 A–E 类最高一门；0 学分不计
    #[test]
    fn degree_courses_follow_two_elective_rules() {
        let scores = vec![
            req("REQ", "65", 3.0),
            req("ZERO", "0", 0.0),
            course("E1", "95", 2.0, "E类", "任选", "正常考试"),
            course("E2", "90", 2.0, "E类", "任选", "正常考试"),
            course("A1", "89", 2.0, "A类", "任选", "正常考试"),
            course("D1", "60", 2.0, "D类", "任选", "正常考试"),
        ];
        assert_eq!(ids(&degree_courses(&scores)), vec!["REQ", "E1", "E2"]);

        let without_e: Vec<ScoreRecord> =
            scores.iter().filter(|s| s.course_group != "E类").cloned().collect();
        assert_eq!(ids(&degree_courses(&without_e)), vec!["REQ"]);

        let stats = build_academic_stats(&scores);
        assert_eq!(stats.degree.required_credits, 7.0);
        assert!((stats.degree.gpa - 3.07).abs() < 1e-9, "学位绩点 {}", stats.degree.gpa);
    }

    /// 重复修读与补考要合并成一条，并计入推免的补考重修门数
    #[test]
    fn dedupe_merges_retakes_and_counts_them() {
        let normal = req("NORMAL", "90", 3.0);
        assert_eq!(build_academic_stats(&[normal.clone(), normal.clone()]).retake_count, 0);

        let mut retake = normal.clone();
        retake.exam_type = "补考".to_string();
        assert_eq!(build_academic_stats(&[normal, retake]).retake_count, 1);
    }

    /// pending（缓考/未出分）不计入已获学分与挂科，且不污染学位绩点
    #[test]
    fn pending_records_are_excluded_from_credits_and_failures() {
        let mut defer = req("DEFER", "0", 5.0);
        defer.score = "缓考".to_string();
        defer.passed = false;
        defer.pending = true;
        let mut ungraded = req("UNGRADED", "0", 2.0);
        ungraded.score = String::new();
        ungraded.passed = false;
        ungraded.pending = true;

        let stats = build_academic_stats(&[req("OK", "70", 3.0), defer, ungraded]);
        assert_eq!(stats.earned_credits, 3.0, "pending 行不计入已获学分");
        assert_eq!(stats.failed_count, 0, "pending 行不计入挂科门数");
        assert_eq!(stats.failed_credits, 0.0, "pending 行不计入挂科学分");

        let ungraded_stats = build_academic_stats(&[
            req("VALID", "90", 3.0),
            {
                let mut item = req("PENDING", "0", 10.0);
                item.score = "缓考".to_string();
                item.pending = true;
                item.passed = false;
                item
            },
        ]);
        assert_eq!(ungraded_stats.degree.gpa, 4.0);
        assert!(ungraded_stats.degree.qualified);
    }

    /// 等级制成绩：合格/优秀/免修计学分，"不通过"计挂科
    #[test]
    fn level_scores_map_to_official_grade_points() {
        let mut pass = req("GRADE_PASS", "0", 4.0);
        pass.score = "合格".to_string();
        pass.passed = true;
        let mut excellent = req("GRADE_EXCL", "0", 1.5);
        excellent.score = "优秀".to_string();
        excellent.passed = true;
        let mut exempt = req("GRADE_EXEMPT", "0", 2.0);
        exempt.score = "免修".to_string();
        exempt.passed = true;
        let mut fail = req("GRADE_FAIL", "0", 2.0);
        fail.score = "不通过".to_string();
        fail.passed = false;

        let stats = build_academic_stats(&[
            req("GRADE_NUM", "70", 3.0),
            pass,
            excellent,
            exempt,
            fail,
        ]);
        assert_eq!(stats.earned_credits, 10.5, "等级制通过成绩计入已获学分");
        assert_eq!(stats.failed_count, 1);
        assert_eq!(stats.failed_credits, 2.0);

        assert_eq!(grade_point("优秀"), 4.5);
        assert_eq!(grade_point("合格"), 1.5);
        assert_eq!(grade_point("59"), 0.0);
        assert_eq!(grade_point("90"), 4.0);
        assert_eq!(grade_point("100"), 5.0);
        assert_eq!(grade_point("免修"), 0.0, "未收录文本不臆造绩点");
    }

    /// 课组学分完成度：重复课组去重、方案总学分优先、专业限选按 4 门
    #[test]
    fn credits_progress_matches_plan_rules() {
        use super::super::store::PlanGroup;
        let group = PlanGroup {
            id: "1".to_string(),
            name: "基础课".to_string(),
            required_credits: 20.0,
            ..Default::default()
        };
        assert_eq!(
            compute_credits_progress(&[], &[group.clone(), group.clone()], None, false).required_total,
            20.0
        );
        assert_eq!(
            compute_credits_progress(&[], &[group], Some(158.5), false).required_total,
            158.5
        );
        assert_eq!(compute_credits_progress(&[], &[], None, false).required_total, 0.0);

        let direction = PlanGroup {
            id: "direction".to_string(),
            name: "专业限选".to_string(),
            property: "限选".to_string(),
            required_credits: 10.0,
            courses: (0..10)
                .map(|index| super::super::store::PlanCourse {
                    code: format!("DIR{index}"),
                    name: format!("方向课程{index}"),
                    credit: 2.5,
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        let progress = compute_credits_progress(&[], &[direction], None, false);
        assert_eq!(progress.required_total, 10.0);
        assert_eq!(progress.categories[0].required, 10.0);
        assert_eq!(progress.categories[0].required_courses, Some(4));
        assert_eq!(progress.categories[0].elective_candidates, Some(10));
    }

    /// 推免门数是独立上限检查：不影响"必修课是否全部通过"的判定
    #[test]
    fn recommend_retake_limit_is_independent() {
        for count in 0..=3u32 {
            let mut records = vec![req("FAIL", "50", 3.0)];
            for index in 0..count {
                records.push(course(
                    &format!("RET{index}"),
                    "80",
                    3.0,
                    "",
                    "必修",
                    "重修",
                ));
            }
            let result = build_academic_stats(&records).recommend;
            assert_eq!(result.retake_count, count);
            assert_eq!(result.retake_within_limit, count <= 2);
            assert!(!result.qualified, "存在必修挂科时不能判定推免合格");
        }
    }

    /// 学期排序：同一学年内先春后秋，跨学年按年份升序（下拉序号 1=春、2=秋）
    #[test]
    fn semester_sort_key_orders_spring_before_autumn() {
        assert!(semester_sort_key("2024", "春") < semester_sort_key("2024", "秋"));
        assert!(semester_sort_key("2023", "秋") < semester_sort_key("2024", "春"));
        assert!(semester_sort_key("2025", "秋") < semester_sort_key("2026", "春"));
        assert!(semester_sort_key("2024", "1") < semester_sort_key("2024", "2"));
    }

    /// 专业类课组即便选课属性写作"任选"，也按学业课计入学位集合
    #[test]
    fn professional_group_is_academic_not_elective() {
        let mut major = course("MAJOR", "90", 2.5, "专业选修", "任选", "正常考试");
        major.course_name = "专业方向课".to_string();
        let mut math = req("MATH", "85", 3.0);
        math.course_name = "高等数学(A)".to_string();
        assert_eq!(ids(&degree_courses(&[math, major])), vec!["MATH", "MAJOR"]);
    }

    /// 风险预警阈值：>15 留降级、>25 退学
    #[test]
    fn risk_thresholds_follow_school_rules() {
        let mut credits = vec![req("FAIL", "50", 15.0)];
        credits[0].passed = false;
        assert_eq!(build_academic_stats(&credits).risk.level, "none");

        credits[0].credit = 16.0;
        assert_eq!(build_academic_stats(&credits).risk.level, "downgrade");

        credits[0].credit = 26.0;
        assert_eq!(build_academic_stats(&credits).risk.level, "expel");
    }
}
