//! 本地缓存读取层：数据目录解析、键值快照 → 强类型视图、登录态与数据量判定
//!
//! 数据来源与图形界面完全一致：Rust 后端落盘的 `storage.json`
//! （`services/storage.js` 在桌面端经 `/__app/storage` 镜像写入同一份文件）。
//! 本模块**只读**，不创建目录、不改名损坏文件、不写回任何内容。
//!
//! 缓存字段由前端解析器（`web/src/services/academic/parser.js`）产出，
//! 这里用 `serde_json::Value` 中转的宽松反序列化：缺字段、类型漂移
//! （数字/字符串混用）都退化为默认值，而不是让命令直接失败——
//! 教务系统改版或缓存结构演进时，命令行应给出"字段缺失"而不是崩溃。

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::de::Deserializer;
use serde::{Deserialize, Serialize};

// ---------------- 存储键（与前端保持一致） ----------------

pub const KEY_SESSION_FLAG: &str = "better_hrbust_has_session";
pub const KEY_SESSION_EXPIRED: &str = "better_hrbust_session_expired";
pub const KEY_LAST_LOGIN: &str = "better_hrbust_last_login_at";
pub const KEY_PROFILE: &str = "better_hrbust_cached_profile";
pub const KEY_SAVED_NUMBER: &str = "saved_student_number";
/// 保存的账号密码（XOR + Base64 混淆）：命令行永不输出
pub const KEY_SAVED_CREDENTIAL: &str = "better_hrbust_saved_credential";
pub const KEY_SYNC_META: &str = "better_hrbust_cache_sync_meta";
pub const KEY_SCORES: &str = "better_hrbust_cache_scores";
pub const KEY_SCORE_YEARS: &str = "better_hrbust_cache_score_years";
pub const KEY_PLAN: &str = "better_hrbust_cache_program_plan";
pub const KEY_TIMETABLE_COMBINE: &str = "better_hrbust_cache_timetable";
pub const KEY_TIMETABLE_BASE: &str = "better_hrbust_cache_timetable_base";
pub const KEY_EXAMS: &str = "better_hrbust_cache_exams";
pub const KEY_NOTICES: &str = "better_hrbust_cache_notices";
pub const KEY_NOTICE_WEEKS: &str = "better_hrbust_cache_notices_weeks";
pub const KEY_CURRENT_COURSES: &str = "better_hrbust_cache_current_courses";

/// 敏感键：诊断命令只报告存在性与长度，绝不打印内容
pub fn is_sensitive_key(key: &str) -> bool {
    matches!(key, KEY_SAVED_CREDENTIAL)
}

// ---------------- 宽松反序列化 ----------------

fn de_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<String, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::String(text) => text,
        serde_json::Value::Null => String::new(),
        serde_json::Value::Bool(flag) => flag.to_string(),
        serde_json::Value::Number(number) => number.to_string(),
        _ => String::new(),
    })
}

fn de_f64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<f64, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Number(number) => number.as_f64().unwrap_or(0.0),
        serde_json::Value::String(text) => text.trim().parse().unwrap_or(0.0),
        serde_json::Value::Bool(flag) => {
            if flag {
                1.0
            } else {
                0.0
            }
        }
        _ => 0.0,
    })
}

fn de_u32<'de, D: Deserializer<'de>>(deserializer: D) -> Result<u32, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Number(number) => number.as_u64().unwrap_or(0).min(u32::MAX as u64) as u32,
        serde_json::Value::String(text) => text.trim().parse().unwrap_or(0),
        _ => 0,
    })
}

fn de_bool<'de, D: Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Bool(flag) => flag,
        serde_json::Value::Number(number) => number.as_f64().unwrap_or(0.0) != 0.0,
        serde_json::Value::String(text) => matches!(text.trim(), "true" | "1"),
        _ => false,
    })
}

fn de_opt_f64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<f64>, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Number(number) => number.as_f64(),
        serde_json::Value::String(text) => text.trim().parse().ok(),
        _ => None,
    })
}

// ---------------- 数据模型（字段名与前端缓存一致） ----------------

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScoreRecord {
    #[serde(default)]
    pub year: String,
    #[serde(default)]
    pub term: String,
    #[serde(default)]
    pub course_id: String,
    #[serde(default)]
    pub course_name: String,
    #[serde(default)]
    pub course_seq: String,
    #[serde(default)]
    pub course_group: String,
    /// 总评原文（可能是数字、五级制等级，或"缓考"等无结论文本）
    #[serde(default, deserialize_with = "de_string")]
    pub score: String,
    #[serde(default, deserialize_with = "de_f64")]
    pub credit: f64,
    #[serde(default, deserialize_with = "de_f64")]
    pub hours: f64,
    #[serde(default)]
    pub property: String,
    #[serde(default)]
    pub remark: String,
    #[serde(default)]
    pub exam_type: String,
    #[serde(default)]
    pub pass_mark: String,
    #[serde(default, deserialize_with = "de_bool")]
    pub passed: bool,
    #[serde(default, deserialize_with = "de_bool")]
    pub pending: bool,
    /// 由 `stats::dedupe_scores` 计算：该课是否存在补考/重修或多学期多次修读
    #[serde(skip)]
    pub is_retake: bool,
    #[serde(skip)]
    pub record_count: usize,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TimetableCell {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub course_id: String,
    #[serde(default, deserialize_with = "de_u32")]
    pub day: u32,
    #[serde(default, deserialize_with = "de_u32")]
    pub section_index: u32,
    #[serde(default)]
    pub section_label: String,
    #[serde(default)]
    pub course_name: String,
    #[serde(default)]
    pub course_seq: String,
    #[serde(default)]
    pub location: String,
    #[serde(default)]
    pub teacher: String,
    #[serde(default)]
    pub weeks: String,
    #[serde(default)]
    pub hours_type: String,
    #[serde(default)]
    pub raw_lines: Vec<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UnarrangedCourse {
    #[serde(default)]
    pub course_id: String,
    #[serde(default)]
    pub course_name: String,
    #[serde(default)]
    pub course_seq: String,
    #[serde(default)]
    pub teacher: String,
    #[serde(default)]
    pub merge_class: String,
    #[serde(default)]
    pub weeks: String,
    #[serde(default)]
    pub day: String,
    #[serde(default)]
    pub location: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Timetable {
    #[serde(default)]
    pub cells: Vec<TimetableCell>,
    #[serde(default)]
    pub unarranged: Vec<UnarrangedCourse>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExamRecord {
    #[serde(default)]
    pub course_id: String,
    #[serde(default)]
    pub course_name: String,
    #[serde(default)]
    pub time: String,
    #[serde(default)]
    pub location: String,
    #[serde(default)]
    pub property: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Notice {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub content: String,
    #[serde(default)]
    pub date: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanCourse {
    #[serde(default)]
    pub code: String,
    #[serde(default)]
    pub name: String,
    #[serde(default, deserialize_with = "de_f64")]
    pub credit: f64,
    #[serde(default, deserialize_with = "de_f64")]
    pub hours: f64,
    #[serde(default)]
    pub property: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanGroup {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub property: String,
    #[serde(default, deserialize_with = "de_f64")]
    pub required_credits: f64,
    #[serde(default, deserialize_with = "de_u32")]
    pub required_courses: u32,
    #[serde(default, deserialize_with = "de_f64")]
    pub earned_credits: f64,
    #[serde(default, deserialize_with = "de_u32")]
    pub passed_courses: u32,
    #[serde(default)]
    pub courses: Vec<PlanCourse>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    #[serde(default)]
    pub groups: Vec<PlanGroup>,
    #[serde(default, deserialize_with = "de_opt_f64")]
    pub total_required_credits: Option<f64>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurrentCourse {
    #[serde(default)]
    pub course_id: String,
    #[serde(default)]
    pub course_seq: String,
    #[serde(default)]
    pub course_name: String,
    #[serde(default)]
    pub teacher: String,
    #[serde(default, deserialize_with = "de_f64")]
    pub credit: f64,
    #[serde(default, deserialize_with = "de_f64")]
    pub hours: f64,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileChange {
    #[serde(default, rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub date: String,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub remark: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    #[serde(default)]
    pub student_number: String,
    #[serde(default)]
    pub internal_id: String,
    #[serde(default)]
    pub real_name: String,
    #[serde(default)]
    pub college: String,
    #[serde(default)]
    pub major: String,
    #[serde(default)]
    pub direction: String,
    #[serde(default)]
    pub grade: String,
    #[serde(default)]
    pub class_name: String,
    #[serde(default)]
    pub student_type: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub id_card: String,
    #[serde(default)]
    pub email: String,
    #[serde(default)]
    pub phone: String,
    #[serde(default)]
    pub address: String,
    #[serde(default)]
    pub postal_code: String,
    #[serde(default)]
    pub photo_url: String,
    #[serde(default)]
    pub changes: Vec<ProfileChange>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncMeta {
    #[serde(default, deserialize_with = "de_u64")]
    pub last_sync_at: u64,
    #[serde(default)]
    pub last_sync_date: String,
    #[serde(default)]
    pub last_error: String,
}

fn de_u64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<u64, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Number(number) => number.as_u64().unwrap_or(0),
        serde_json::Value::String(text) => text.trim().parse().unwrap_or(0),
        _ => 0,
    })
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct YearOption {
    #[serde(default)]
    pub value: String,
    #[serde(default)]
    pub label: String,
}

// ---------------- 路径解析 ----------------

/// 数据目录解析优先级：
/// `--data-dir` > 环境变量 `BETTERHRBUST_DATA_DIR` > `%APPDATA%\edu.hrbust.betterhrbust`
///
/// 后两者与 Tauri 的 `app_data_dir()`（`%APPDATA%\{identifier}`）一致；
/// 显式覆盖用于测试夹具与多数据目录隔离。
pub fn resolve_data_dir(explicit: Option<PathBuf>) -> PathBuf {
    if let Some(dir) = explicit {
        return dir;
    }
    if let Ok(value) = std::env::var("BETTERHRBUST_DATA_DIR") {
        if !value.trim().is_empty() {
            return PathBuf::from(value);
        }
    }
    if let Ok(appdata) = std::env::var("APPDATA") {
        if !appdata.trim().is_empty() {
            return Path::new(&appdata).join("edu.hrbust.betterhrbust");
        }
    }
    PathBuf::from(".").join("betterhrbust-data")
}

/// 当前进程所在目录下的另一个可执行文件（CLI 与 GUI 同目录）
fn sibling_exe(name: &str) -> String {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join(name)))
        .map(|path| path.to_string_lossy().to_string())
        .unwrap_or_else(|| name.to_string())
}

// ---------------- 数据量统计与可用性 ----------------

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DataCounts {
    pub scores: usize,
    pub exams: usize,
    pub timetable_cells: usize,
    pub plan_groups: usize,
    pub notices: usize,
    pub courses: usize,
}

/// 数据可用性判定：决定命令是否执行、退出码与提示文案
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Readiness {
    /// 可以查询；`offline` 表示当前已无登录态，只有本地缓存
    Ready { offline: bool },
    /// 未登录且没有任何本地数据 → 必须回到图形界面登录
    NeedLogin,
    /// 未登录无数据以外的情况：已登录但缓存为空 / 存储文件损坏
    NoData(String),
}

/// `/__app/cli` 返回体（设置页与 `doctor` 共用同一份判断）
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliInfo {
    pub version: String,
    pub cli_path: String,
    pub cli_installed: bool,
    pub app_path: String,
    pub data_dir: String,
    pub storage_path: String,
    pub storage_exists: bool,
    pub logged_in: bool,
    pub session_expired: bool,
    pub has_data: bool,
    pub counts: DataCounts,
    pub last_sync_at: u64,
    pub last_sync_error: String,
    pub account: String,
}

// ---------------- 存储快照 ----------------

pub struct Store {
    pub data_dir: PathBuf,
    pub storage_path: PathBuf,
    pub storage_exists: bool,
    /// 读取失败原因（文件不可读 / JSON 损坏）；有值时视为"存储不可用"
    pub load_error: Option<String>,
    raw: BTreeMap<String, String>,
}

impl Store {
    /// 打开数据目录解析出的存储快照（只读）。失败不 panic，记录原因交由调用方提示。
    pub fn open(explicit_dir: Option<PathBuf>) -> Self {
        let data_dir = resolve_data_dir(explicit_dir);
        let storage_path = data_dir.join("storage.json");
        let storage_exists = storage_path.is_file();
        match crate::storage::read_snapshot(&data_dir) {
            Ok(raw) => Self { data_dir, storage_path, storage_exists, load_error: None, raw },
            Err(err) => Self {
                data_dir,
                storage_path,
                storage_exists,
                load_error: Some(format!(
                    "无法解析本地数据文件（{err}）。请在 BetterHRBUST 图形界面刷新一次以重建缓存"
                )),
                raw: BTreeMap::new(),
            },
        }
    }

    /// 测试用：直接注入键值，不触碰文件系统
    #[cfg(test)]
    pub fn from_pairs(dir: &Path, pairs: &[(&str, &str)]) -> Self {
        let raw = pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect();
        Self {
            data_dir: dir.to_path_buf(),
            storage_path: dir.join("storage.json"),
            storage_exists: true,
            load_error: None,
            raw,
        }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.raw.get(key).map(String::as_str)
    }

    pub fn keys(&self) -> impl Iterator<Item = (&String, &String)> {
        self.raw.iter()
    }

    /// 存储文件大小（doctor 用；不存在为 0）
    pub fn storage_size(&self) -> u64 {
        std::fs::metadata(&self.storage_path).map(|meta| meta.len()).unwrap_or(0)
    }

    fn json_list<T>(&self, key: &str) -> Vec<T>
    where
        T: serde::de::DeserializeOwned,
    {
        self.get(key)
            .and_then(|text| serde_json::from_str::<Vec<T>>(text).ok())
            .unwrap_or_default()
    }

    fn json_object<T>(&self, key: &str) -> T
    where
        T: serde::de::DeserializeOwned + Default,
    {
        self.get(key)
            .and_then(|text| serde_json::from_str::<T>(text).ok())
            .unwrap_or_default()
    }

    pub fn logged_in(&self) -> bool {
        self.get(KEY_SESSION_FLAG) == Some("true")
    }

    pub fn session_expired(&self) -> bool {
        self.get(KEY_SESSION_EXPIRED) == Some("true")
    }

    /// 上次登录时间（毫秒时间戳）；未记录为 0
    pub fn last_login_at(&self) -> u64 {
        self.get(KEY_LAST_LOGIN).and_then(|text| text.trim().parse().ok()).unwrap_or(0)
    }

    pub fn profile(&self) -> Profile {
        self.json_object::<Profile>(KEY_PROFILE)
    }

    pub fn account(&self) -> String {
        let profile = self.profile();
        if !profile.student_number.is_empty() {
            return profile.student_number;
        }
        self.get(KEY_SAVED_NUMBER).unwrap_or("").to_string()
    }

    pub fn scores(&self) -> Vec<ScoreRecord> {
        self.json_list::<ScoreRecord>(KEY_SCORES)
    }

    pub fn score_years(&self) -> Vec<YearOption> {
        self.json_list::<YearOption>(KEY_SCORE_YEARS)
    }

    pub fn exams(&self) -> Vec<ExamRecord> {
        self.json_list::<ExamRecord>(KEY_EXAMS)
    }

    pub fn notices(&self) -> Vec<Notice> {
        self.json_list::<Notice>(KEY_NOTICES)
    }

    pub fn notice_weeks(&self) -> BTreeMap<String, Vec<Notice>> {
        self.json_object::<BTreeMap<String, Vec<Notice>>>(KEY_NOTICE_WEEKS)
    }

    pub fn current_courses(&self) -> Vec<CurrentCourse> {
        self.json_list::<CurrentCourse>(KEY_CURRENT_COURSES)
    }

    pub fn plan(&self) -> Plan {
        self.json_object::<Plan>(KEY_PLAN)
    }

    /// 大节课表（COMBINE）；`base = true` 时取小节课表（BASE）
    pub fn timetable(&self, base: bool) -> Timetable {
        let key = if base { KEY_TIMETABLE_BASE } else { KEY_TIMETABLE_COMBINE };
        let timetable = self.json_object::<Timetable>(key);
        if base || !timetable.cells.is_empty() {
            timetable
        } else {
            // 大节缺失时退回小节：课时与地点对查询仍然有效
            let fallback = self.json_object::<Timetable>(KEY_TIMETABLE_BASE);
            if fallback.cells.is_empty() {
                timetable
            } else {
                fallback
            }
        }
    }

    pub fn sync_meta(&self) -> SyncMeta {
        self.json_object::<SyncMeta>(KEY_SYNC_META)
    }

    /// 是否有本地数据（与前端 hasCachedData 同口径：
    /// 成绩 / 培养方案 / 课表 / 考试任一非空）
    pub fn has_data(&self) -> bool {
        !self.scores().is_empty()
            || !self.plan().groups.is_empty()
            || !self.timetable(false).cells.is_empty()
            || !self.exams().is_empty()
    }

    pub fn counts(&self) -> DataCounts {
        DataCounts {
            scores: self.scores().len(),
            exams: self.exams().len(),
            timetable_cells: self.timetable(false).cells.len(),
            plan_groups: self.plan().groups.len(),
            notices: self.notices().len(),
            courses: self.current_courses().len(),
        }
    }

    /// 是否可查询；决定退出码（见 `cli::EXIT_*`）
    pub fn readiness(&self) -> Readiness {
        if let Some(error) = &self.load_error {
            return Readiness::NoData(error.clone());
        }
        let has_data = self.has_data();
        match (self.logged_in(), has_data) {
            (true, true) => Readiness::Ready { offline: false },
            (true, false) => Readiness::NoData(
                "已登录但本地还没有缓存数据：请打开 BetterHRBUST 图形界面，在顶栏点击「刷新」同步一次".into(),
            ),
            (false, true) => Readiness::Ready { offline: true },
            (false, false) => Readiness::NeedLogin,
        }
    }

    /// 设置页 / doctor 展示的一致状态
    pub fn info(&self) -> CliInfo {
        let cli_path = sibling_exe("BetterHRBUSTCLI.exe");
        let app_path = sibling_exe("BetterHRBUST.exe");
        let sync_meta = self.sync_meta();
        CliInfo {
            version: env!("CARGO_PKG_VERSION").to_string(),
            cli_installed: Path::new(&cli_path).is_file(),
            cli_path,
            app_path,
            data_dir: self.data_dir.to_string_lossy().to_string(),
            storage_path: self.storage_path.to_string_lossy().to_string(),
            storage_exists: self.storage_exists,
            logged_in: self.logged_in(),
            session_expired: self.session_expired(),
            has_data: self.has_data(),
            counts: self.counts(),
            last_sync_at: sync_meta.last_sync_at,
            last_sync_error: sync_meta.last_error,
            account: self.account(),
        }
    }
}

/// 人类可读文件大小
pub fn human_size(bytes: u64) -> String {
    const KB: f64 = 1024.0;
    let value = bytes as f64;
    if value < KB {
        format!("{bytes} B")
    } else if value < KB * KB {
        format!("{:.1} KB", value / KB)
    } else {
        format!("{:.1} MB", value / (KB * KB))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store(pairs: &[(&str, &str)]) -> Store {
        Store::from_pairs(Path::new("C:/test"), pairs)
    }

    #[test]
    fn logged_in_requires_exact_true_flag() {
        assert!(store(&[(KEY_SESSION_FLAG, "true")]).logged_in());
        assert!(!store(&[(KEY_SESSION_FLAG, "1")]).logged_in());
        assert!(!store(&[]).logged_in());
    }

    #[test]
    fn readiness_matches_frontend_has_cached_data_rules() {
        // 未登录 + 无数据 → 必须回图形界面登录
        assert_eq!(store(&[]).readiness(), Readiness::NeedLogin);
        // 未登录 + 有考试缓存 → 可离线查询
        assert_eq!(
            store(&[(KEY_EXAMS, r#"[{"courseName":"高等数学"}]"#)]).readiness(),
            Readiness::Ready { offline: true }
        );
        // 已登录 + 有课表 → 在线
        assert_eq!(
            store(&[(KEY_SESSION_FLAG, "true"), (KEY_TIMETABLE_COMBINE, r#"{"cells":[{"day":1}]}"#)])
                .readiness(),
            Readiness::Ready { offline: false }
        );
        // 已登录但缓存为空 → 提示去图形界面刷新（不是去登录）
        match store(&[(KEY_SESSION_FLAG, "true")]).readiness() {
            Readiness::NoData(message) => assert!(message.contains("刷新")),
            other => panic!("应判定为无数据，实际 {other:?}"),
        }
    }

    #[test]
    fn tolerates_type_drift_in_cached_records() {
        let raw = r#"[{"courseName":"线性代数","credit":"3.5","hours":"56","score":92.5,"passed":"true","pending":0}]"#;
        let parsed = store(&[(KEY_SCORES, raw)]).scores();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].credit, 3.5);
        assert_eq!(parsed[0].hours, 56.0);
        assert_eq!(parsed[0].score, "92.5");
        assert!(parsed[0].passed);
        assert!(!parsed[0].pending);
    }

    #[test]
    fn corrupt_cache_degrades_to_empty_instead_of_panicking() {
        let parsed = store(&[(KEY_SCORES, "{not json")]).scores();
        assert!(parsed.is_empty());
    }

    #[test]
    fn plan_missing_total_credits_is_none() {
        let plan = store(&[(KEY_PLAN, r#"{"groups":[{"name":"学科基础课","requiredCredits":10}]}"#)]).plan();
        assert_eq!(plan.groups.len(), 1);
        assert_eq!(plan.groups[0].required_credits, 10.0);
        assert!(plan.total_required_credits.is_none());
    }

    #[test]
    fn credential_key_is_flagged_sensitive() {
        assert!(is_sensitive_key(KEY_SAVED_CREDENTIAL));
        assert!(!is_sensitive_key(KEY_SCORES));
    }

    #[test]
    fn counts_and_has_data_follow_cache_content() {
        let filled = store(&[
            (KEY_SCORES, r#"[{"courseName":"A"}]"#),
            (KEY_EXAMS, r#"[{"courseName":"B"},{"courseName":"C"}]"#),
        ]);
        assert!(filled.has_data());
        assert_eq!(filled.counts().scores, 1);
        assert_eq!(filled.counts().exams, 2);
        assert!(!store(&[]).has_data());
    }
}
