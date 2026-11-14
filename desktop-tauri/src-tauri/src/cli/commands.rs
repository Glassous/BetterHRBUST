//! 查询命令实现（文本 + `--json` 双输出）
//!
//! 约定：
//!  - 文本输出面向人：中文、CJK 对齐、必要时附口径说明；
//!  - `--json` 面向脚本与 AI 工具：顶层字段固定（`command` / `ok` / `generatedAt` /
//!    `loggedIn` / `offline` / `account` / `lastSyncAt` / `lastSyncText` / `data`），
//!    缺失数据用空数组或 `null`，不省略键；
//!  - 统计数字全部来自 `cli::stats`（与图形界面同口径），命令层不重复计算口径。

use serde_json::{json, Value};

use super::calendar;
use super::clock::{self, LocalDateTime};
use super::render::{self, Printer, Table};
use super::stats::{self, AcademicStats};
use super::store::{self, ExamRecord, ScoreRecord, Store};
use super::{Command, Options};

/// 统一的 JSON 输出（格式化，便于人眼与工具同时阅读）
pub fn print_json<T: serde::Serialize>(out: &mut Printer, value: &T) {
    match serde_json::to_string_pretty(value) {
        Ok(text) => out.line(&text),
        Err(_) => out.line("{\"ok\":false,\"error\":{\"code\":\"SERIALIZE\",\"message\":\"输出序列化失败\"}}"),
    }
}

/// 成功输出的顶层结构（字段顺序固定，便于人眼扫读；AI 工具按字段名取值）
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct Envelope {
    command: &'static str,
    ok: bool,
    generated_at: String,
    logged_in: bool,
    offline: bool,
    account: String,
    last_login_at: u64,
    last_sync_at: u64,
    last_sync_text: String,
    sync_error: String,
    data: Value,
}

fn num(value: f64) -> String {
    if (value.fract()).abs() < 1e-9 {
        format!("{}", value as i64)
    } else {
        format!("{value}")
    }
}

fn percent(value: f64) -> String {
    format!("{:.1}%", value * 100.0)
}

/// 年级后缀：教务档案里的年级可能已经是「2024级」，避免渲染成「2024级 级」
fn grade_suffix(grade: &str) -> String {
    let text = collapse_ws(grade);
    if text.is_empty() {
        String::new()
    } else if text.ends_with('级') {
        format!("（{text}）")
    } else {
        format!("（{text}级）")
    }
}

/// 组装成功输出：公用元信息（登录态、数据新鲜度）+ 命令数据
fn envelope(command: Command, store: &Store, offline: bool, data: Value) -> Envelope {
    let sync = store.sync_meta();
    Envelope {
        command: command.name(),
        ok: true,
        generated_at: clock::format_minute(clock::now()),
        logged_in: store.logged_in(),
        offline,
        account: store.account(),
        last_login_at: store.last_login_at(),
        last_sync_at: sync.last_sync_at,
        last_sync_text: if sync.last_sync_at > 0 {
            clock::format_minute(clock::from_epoch_ms(sync.last_sync_at))
        } else {
            "尚未同步".to_string()
        },
        sync_error: sync.last_error,
        data,
    }
}

fn header(out: &mut Printer, title: &str) {
    out.line(&format!("BetterHRBUST · {title}"));
}

/// 页脚：数据时间 + 离线提示（命令行不联网，用户需要知道数据是什么时候的）
///
/// 这里刻意只放"数据是什么时候的"这类中性信息：查询结果后面不再夹带同步报错，
/// 报错统一由 `check`（错误检查）按需输出，避免每条命令都被历史故障刷屏。
fn footer(out: &mut Printer, store: &Store, offline: bool) {
    let sync = store.sync_meta();
    out.blank();
    if sync.last_sync_at > 0 {
        out.line(&format!(
            "数据同步时间：{}",
            clock::format_minute(clock::from_epoch_ms(sync.last_sync_at))
        ));
    } else {
        out.line("数据同步时间：尚未同步");
    }
    if offline {
        out.line("提示：当前为离线缓存（登录状态已失效），如需更新请在图形界面手动刷新。");
    }
}

// ---------------- status ----------------

/// 登录状态与数据可用性（永远返回 0，便于诊断脚本调用）
pub fn status(store: &Store, options: &Options, out: &mut Printer) {
    let info = store.info();
    let readiness = store.readiness();
    let offline = matches!(readiness, store::Readiness::Ready { offline: true });

    if options.json {
        let mut data = serde_json::to_value(&info).unwrap_or_else(|_| json!({}));
        let object = data.as_object_mut().expect("CliInfo 是对象");
        object.insert(
            "readiness".to_string(),
            json!(match readiness {
                store::Readiness::Ready { .. } => "ready",
                store::Readiness::NeedLogin => "needLogin",
                store::Readiness::NoData(_) => "noData",
            }),
        );
        object.insert("offline".to_string(), json!(offline));
        print_json(out, &envelope(Command::Status, store, offline, data));
        return;
    }

    header(out, "状态");
    let login_state = if info.logged_in {
        format!("已登录（账号 {}）", if info.account.is_empty() { "未知" } else { &info.account })
    } else if info.session_expired && info.has_data {
        "离线缓存（登录状态已失效，仍可查询）".to_string()
    } else {
        "未登录".to_string()
    };
    let storage_state = if info.storage_exists {
        format!("存在，{}", store::human_size(store.storage_size()))
    } else {
        "不存在".to_string()
    };
    let counts = &info.counts;
    render::kv(
        out,
        &[
            ("版本", info.version.clone()),
            ("数据目录", info.data_dir.clone()),
            ("数据文件", format!("{}（{}）", info.storage_path, storage_state)),
            ("登录状态", login_state),
            (
                "上次登录",
                if store.last_login_at() > 0 {
                    clock::format_minute(clock::from_epoch_ms(store.last_login_at()))
                } else {
                    "未记录".to_string()
                },
            ),
            (
                "本地数据",
                format!(
                    "成绩 {} 条 · 考试 {} 条 · 课表 {} 格 · 课组 {} 个 · 公告 {} 条",
                    counts.scores, counts.exams, counts.timetable_cells, counts.plan_groups, counts.notices
                ),
            ),
            (
                "上次同步",
                if info.last_sync_at > 0 {
                    clock::format_minute(clock::from_epoch_ms(info.last_sync_at))
                } else {
                    "尚未同步".to_string()
                },
            ),
        ],
        10,
    );

    match readiness {
        store::Readiness::Ready { .. } => {
            out.blank();
            out.line("可以查询。执行 `BetterHRBUSTCLI summary` 查看概览。");
        }
        store::Readiness::NeedLogin => {
            out.blank();
            out.line("未检测到登录状态，也没有任何本地教务数据。");
            out.line("登录需要学号、密码与图片验证码，终端无法完成。");
            out.line("请先打开 BetterHRBUST 图形界面登录一次，再回到终端查询。");
        }
        store::Readiness::NoData(message) => {
            out.blank();
            out.line(&message);
        }
    }
}

// ---------------- doctor ----------------

/// 路径与数据文件诊断（不输出键值内容，敏感键只报告存在性）
pub fn doctor(store: &Store, options: &Options, out: &mut Printer) {
    let info = store.info();
    let sync = store.sync_meta();
    let today = clock::now();
    let week = calendar::teaching_week_on(today);
    let semester = calendar::semester_on(today);

    let mut keys: Vec<Value> = Vec::new();
    let mut text_rows: Vec<(String, String)> = Vec::new();
    for (key, value) in store.keys() {
        let sensitive = store::is_sensitive_key(key);
        let bytes = value.len();
        keys.push(json!({
            "key": key,
            "bytes": bytes,
            "sensitive": sensitive,
        }));
        text_rows.push((
            key.clone(),
            if sensitive { "已隐藏（敏感键）".to_string() } else { store::human_size(bytes as u64) },
        ));
    }

    if options.json {
        let data = json!({
            "info": serde_json::to_value(&info).unwrap_or_else(|_| json!({})),
            "keys": keys,
            "calendar": {
                "semester": semester.map(|item| item.label.clone()),
                "teachingWeek": week,
                "teachingWeeks": semester.map(|item| item.teaching_weeks),
                "holiday": week.is_none(),
            },
            "loadError": store.load_error,
        });
        let offline = !store.logged_in();
        print_json(out, &envelope(Command::Doctor, store, offline, data));
        return;
    }

    header(out, "诊断");
    render::kv(
        out,
        &[
            (
                "CLI 可执行文件",
                format!(
                    "{}（{}）",
                    info.cli_path,
                    if info.cli_installed { "存在" } else { "缺失：命令行可能未随安装包安装" }
                ),
            ),
            (
                "GUI 可执行文件",
                format!(
                    "{}（{}）",
                    info.app_path,
                    if std::path::Path::new(&info.app_path).is_file() { "存在" } else { "缺失" }
                ),
            ),
            (
                "数据目录",
                format!(
                    "{}（{}）",
                    info.data_dir,
                    if std::path::Path::new(&info.data_dir).is_dir() { "存在" } else { "缺失" }
                ),
            ),
            (
                "数据文件",
                format!(
                    "{}（{}，{}）",
                    info.storage_path,
                    if info.storage_exists { "存在" } else { "缺失" },
                    store::human_size(store.storage_size())
                ),
            ),
            (
                "解析状态",
                match &store.load_error {
                    Some(error) => format!("失败：{error}"),
                    None => "正常".to_string(),
                },
            ),
            (
                "校历",
                match (semester, week) {
                    (Some(semester), Some(week)) => {
                        format!("{} · 第 {week} 教学周（共 {} 周）", semester.label, semester.teaching_weeks)
                    }
                    (Some(semester), None) => format!("{} · 当前处于假期", semester.label),
                    (None, _) => "当前日期不在已收录校历内".to_string(),
                },
            ),
            (
                "上次同步",
                if sync.last_sync_at > 0 {
                    clock::format_minute(clock::from_epoch_ms(sync.last_sync_at))
                } else {
                    "尚未同步".to_string()
                },
            ),
            (
                "同步失败项",
                if sync.last_error.is_empty() {
                    "无".to_string()
                } else {
                    sync.last_error.clone()
                },
            ),
            ("数据自检", "执行 `BetterHRBUSTCLI check` 查看错误检查明细".to_string()),
        ],
        16,
    );

    out.blank();
    out.line(&format!("缓存键（{} 个）", text_rows.len()));
    for (key, value) in &text_rows {
        out.line(&format!("  {}  {}", render::pad_end(key, 34), value));
    }
    if text_rows.is_empty() {
        out.line("  （空：桌面端尚未写入任何缓存）");
    }
    out.blank();
    out.line("说明：命令行只读本地缓存，不联网；登录与刷新请在图形界面完成。");
}

// ---------------- check（错误检查专用） ----------------

/// 单条检查结果：脚本/AI 只需看 `level`，人只需要看 `title + detail`
#[derive(Debug, Clone, serde::Serialize)]
struct CheckItem {
    id: &'static str,
    level: &'static str,
    title: &'static str,
    detail: String,
    hint: String,
}

impl CheckItem {
    fn ok(id: &'static str, title: &'static str, detail: impl Into<String>) -> Self {
        Self { id, level: "ok", title, detail: detail.into(), hint: String::new() }
    }

    fn warn(id: &'static str, title: &'static str, detail: impl Into<String>, hint: &str) -> Self {
        Self { id, level: "warn", title, detail: detail.into(), hint: hint.to_string() }
    }

    fn fail(id: &'static str, title: &'static str, detail: impl Into<String>, hint: &str) -> Self {
        Self { id, level: "fail", title, detail: detail.into(), hint: hint.to_string() }
    }

    fn label(&self) -> &'static str {
        match self.level {
            "fail" => "失败",
            "warn" => "注意",
            _ => "正常",
        }
    }
}

/// 错误检查：正常查询命令不再夹带报错，排查统一走这里
///
/// 退出码：存在 `fail`（数据不可用）→ 3；仅 `warn` 且带 `--strict` → 1；
/// 其余 0。这样脚本可以把它当门禁使用，而普通查询的退出码语义保持不变。
pub fn check(store: &Store, options: &Options, out: &mut Printer) -> i32 {
    let info = store.info();
    let sync = store.sync_meta();
    let counts = store.counts();
    let today = clock::now();
    let mut items: Vec<CheckItem> = Vec::new();

    // 1. 数据文件
    if !info.storage_exists {
        items.push(CheckItem::fail(
            "storage",
            "数据文件",
            format!("{} 不存在", info.storage_path),
            "请先打开 BetterHRBUST 图形界面并登录一次，登录后会写入本地缓存。",
        ));
    } else {
        items.push(CheckItem::ok(
            "storage",
            "数据文件",
            format!("{}（{}）", info.storage_path, store::human_size(store.storage_size())),
        ));
    }

    // 2. JSON 解析（损坏文件会被 GUI 改名 .corrupt，这里只报告）
    match &store.load_error {
        Some(error) => items.push(CheckItem::fail(
            "parse",
            "数据解析",
            error.clone(),
            "数据文件可能已损坏：请在图形界面重新登录并同步一次以重建缓存。",
        )),
        None => items.push(CheckItem::ok("parse", "数据解析", "键值 JSON 正常")),
    }

    // 3. 本地数据量
    if info.has_data {
        items.push(CheckItem::ok(
            "data",
            "本地数据",
            format!(
                "成绩 {} 条 · 考试 {} 条 · 课表 {} 格 · 课组 {} 个 · 公告 {} 条",
                counts.scores, counts.exams, counts.timetable_cells, counts.plan_groups, counts.notices
            ),
        ));
    } else {
        items.push(CheckItem::warn(
            "data",
            "本地数据",
            "缓存中没有可查询的教务数据",
            "请在图形界面登录（或手动刷新）一次。",
        ));
    }

    // 4. 登录态
    if info.logged_in {
        items.push(CheckItem::ok(
            "session",
            "登录状态",
            format!("已登录（账号 {}）", if info.account.is_empty() { "未知" } else { &info.account }),
        ));
    } else if info.session_expired && info.has_data {
        items.push(CheckItem::warn(
            "session",
            "登录状态",
            "会话已失效，当前为只读离线缓存",
            "需要更新数据时请在图形界面重新登录。",
        ));
    } else {
        items.push(CheckItem::warn(
            "session",
            "登录状态",
            "未登录",
            "登录需要学号、密码与图片验证码，请在图形界面完成。",
        ));
    }

    // 5. 上次同步的失败项（图形界面记录了部分模块同步失败时才有）
    if sync.last_error.is_empty() {
        items.push(CheckItem::ok("sync", "上次同步", "没有失败项"));
    } else {
        items.push(CheckItem::warn(
            "sync",
            "上次同步失败项",
            sync.last_error.clone(),
            "对应模块的数据可能仍是旧缓存；在图形界面手动刷新可重试。",
        ));
    }

    // 6. 数据新鲜度（超过 7 天未同步只提醒，不阻断查询）
    if sync.last_sync_at == 0 {
        items.push(CheckItem::warn("fresh", "数据新鲜度", "尚未记录同步时间", "请在图形界面刷新一次。"));
    } else {
        let synced = clock::from_epoch_ms(sync.last_sync_at);
        let days = today.day_number() - synced.day_number();
        let detail = format!("{}（{} 天前）", clock::format_minute(synced), days.max(0));
        if days > 7 {
            items.push(CheckItem::warn(
                "fresh",
                "数据新鲜度",
                detail,
                "数据可能已过期：在图形界面手动刷新可获取最新成绩与考试。",
            ));
        } else {
            items.push(CheckItem::ok("fresh", "数据新鲜度", detail));
        }
    }

    // 7. 培养方案 / 公告缺失（源站点常返回空，属提示级）
    if counts.plan_groups == 0 {
        items.push(CheckItem::warn(
            "plan",
            "培养方案",
            "本地没有课组数据",
            "学校未返回方案学分要求时无法生成进度，可在图形界面「培养方案」页重试。",
        ));
    } else {
        items.push(CheckItem::ok("plan", "培养方案", format!("{} 个课组", counts.plan_groups)));
    }
    if counts.notices == 0 {
        items.push(CheckItem::warn(
            "notices",
            "教学公告",
            "本地没有公告内容",
            "源页面未返回内容时会为空，可在图形界面手动刷新。",
        ));
    } else {
        items.push(CheckItem::ok("notices", "教学公告", format!("{} 条", counts.notices)));
    }

    // 8. 成绩可解析性：有成绩但折算不出分数，会让 GPA 统计偏低
    let unparsable: Vec<String> = store
        .scores()
        .into_iter()
        .filter(|record| {
            !record.score.trim().is_empty()
                && stats::parse_score_value(&record.score).estimated.is_none()
        })
        .map(|record| format!("{}（{}）", record.course_name, record.score))
        .collect();
    if unparsable.is_empty() {
        items.push(CheckItem::ok("scores", "成绩可读性", "全部成绩均可换算为绩点"));
    } else {
        items.push(CheckItem::warn(
            "scores",
            "成绩可读性",
            format!("{} 条成绩无法换算绩点：{}", unparsable.len(), unparsable.join("、")),
            "这些记录不计入 GPA 与学分统计，属源数据表现（如缓考、免修等）。",
        ));
    }

    let ok_count = items.iter().filter(|item| item.level == "ok").count();
    let warn_count = items.iter().filter(|item| item.level == "warn").count();
    let fail_count = items.iter().filter(|item| item.level == "fail").count();

    if options.json {
        let data = json!({
            "summary": { "ok": ok_count, "warn": warn_count, "fail": fail_count },
            "strict": options.strict,
            "checks": items,
        });
        print_json(out, &envelope(Command::Check, store, !store.logged_in(), data));
    } else {
        header(out, "错误检查");
        for item in &items {
            out.line(&format!(
                "  [{}] {}  {}",
                item.label(),
                render::pad_end(item.title, 14),
                item.detail
            ));
            if !item.hint.is_empty() {
                for line in render::wrap(&item.hint, 72) {
                    out.line(&format!("          {line}"));
                }
            }
        }
        out.blank();
        out.line(&format!(
            "共 {} 项：正常 {ok_count} · 注意 {warn_count} · 失败 {fail_count}",
            items.len()
        ));
        if fail_count == 0 && warn_count == 0 {
            out.line("没有发现需要处理的问题。");
        } else if warn_count > 0 && !options.strict {
            out.line("提示：注意项不会影响查询；需要门禁时加 --strict 让退出码非零。");
        }
    }

    if fail_count > 0 {
        super::EXIT_NO_DATA
    } else if options.strict && warn_count > 0 {
        super::EXIT_ERROR
    } else {
        super::EXIT_OK
    }
}

// ---------------- 数据命令分发 ----------------

pub fn run_data_command(
    command: Command,
    store: &Store,
    options: &Options,
    offline: bool,
    out: &mut Printer,
) -> i32 {
    let data = match command {
        Command::Summary => summary(store, options, out),
        Command::Timetable => timetable(store, options, out),
        Command::Scores => scores(store, options, out),
        Command::Exams => exams(store, options, out),
        Command::Plan => plan(store, options, out),
        Command::Notices => notices(store, options, out),
        Command::Profile => profile(store, options, out),
        Command::Courses => courses(store, options, out),
        // 调用方已经按 `needs_data()` 过滤，其余命令不会走到这里
        _ => return super::EXIT_OK,
    };
    if options.json {
        print_json(out, &envelope(command, store, offline, data));
    }
    super::EXIT_OK
}

// ---------------- summary ----------------

/// 某一天的课程。
///
/// `drop_ended` 只在"今天"时为真：查历史 / 未来日期时，课程都没有"已下课"的概念，
/// 否则 `summary --date 2026-11-13` 会永远返回空列表。
fn day_courses(
    store: &Store,
    date: LocalDateTime,
    drop_ended: bool,
) -> Vec<super::store::TimetableCell> {
    let Some(week) = calendar::teaching_week_on(date) else {
        return Vec::new();
    };
    let day = date.weekday_index();
    let minutes = date.minutes_of_day();
    let mut cells: Vec<super::store::TimetableCell> = store
        .timetable(false)
        .cells
        .into_iter()
        .filter(|cell| {
            // 空课程名的单元格来自教务表格里的合并/占位格，渲染出来只会是空行
            !cell.course_name.trim().is_empty()
                && cell.day == day
                && calendar::is_course_active_in_week(&cell.weeks, week)
                && (!drop_ended || !calendar::has_combine_slot_ended(cell.section_index, minutes))
        })
        .collect();
    cells.sort_by_key(|cell| cell.section_index);
    cells
}

/// `--date` 指定的日期（缺省为今天），并告知调用方它是否就是今天
fn anchor_date(options: &Options) -> (LocalDateTime, bool) {
    let now = clock::now();
    match options.date.as_deref().and_then(clock::parse_iso_date) {
        Some(date) => (date, date.day_number() == now.day_number()),
        None => (now, true),
    }
}

/// 近期考试：未来 + 过去 `days` 天（默认 7，与概览页同口径），按时间升序
fn recent_exams(
    store: &Store,
    today: LocalDateTime,
    days: u32,
) -> Vec<(ExamRecord, Option<calendar::ExamTime>)> {
    let today_number = today.day_number();
    let window = i64::from(days);
    let mut list: Vec<(ExamRecord, Option<calendar::ExamTime>)> = store
        .exams()
        .into_iter()
        .map(|exam| {
            let parsed = calendar::parse_exam_datetime(&exam.time);
            (exam, parsed)
        })
        .filter(|(exam, parsed)| {
            if exam.time.trim().is_empty() {
                return true;
            }
            match parsed {
                Some(time) => time.date.day_number() >= today_number - window,
                None => true,
            }
        })
        .collect();
    list.sort_by_key(|(_, parsed)| parsed.map(|time| time.date.day_number()).unwrap_or(i64::MAX));
    list
}

fn exam_status(parsed: Option<&calendar::ExamTime>, today: LocalDateTime) -> String {
    let Some(time) = parsed else {
        return "时间未知".to_string();
    };
    let days = time.date.day_number() - today.day_number();
    if days < 0 {
        "已结束".to_string()
    } else if days == 0 {
        "今日考试".to_string()
    } else {
        format!("{days} 天后")
    }
}

fn summary(store: &Store, options: &Options, out: &mut Printer) -> Value {
    // `--date` 可以把"今日课程 / 考试倒计时"的基准日挪到指定日期
    let (today, is_today) = anchor_date(options);
    let profile = store.profile();
    let scores = store.scores();
    let stats = stats::build_academic_stats(&scores);
    let courses = day_courses(store, today, is_today);
    let exams = recent_exams(store, today, options.days.unwrap_or(7));
    let week = calendar::teaching_week_on(today);
    let semester = calendar::semester_on(today);

    if !options.json {
        header(out, "概览");
        let account = store.account();
        render::kv(
            out,
            &[
                ("姓名", profile_plain(&profile.real_name)),
                ("学号", if account.is_empty() { "未记录".to_string() } else { account }),
                ("学院", profile_plain(&profile.college)),
                (
                    "专业",
                    format!(
                        "{}{}{}",
                        profile_plain(&profile.major),
                        grade_suffix(&profile.grade),
                        if profile.class_name.trim().is_empty() {
                            String::new()
                        } else {
                            format!(" · {}", collapse_ws(&profile.class_name))
                        },
                    ),
                ),
                (
                    "学期",
                    match (semester, week) {
                        (Some(semester), Some(week)) => {
                            format!("{} · 第 {week} 教学周（共 {} 周）", semester.label, semester.teaching_weeks)
                        }
                        (Some(semester), None) => format!("{} · 当前处于假期", semester.label),
                        (None, _) => "校历未收录当前学期".to_string(),
                    },
                ),
            ],
            6,
        );

        out.blank();
        out.line("学业指标（必修课口径）");
        render::kv(
            out,
            &[
                ("平均学分绩点", num(stats.gpa)),
                ("加权平均分", num(stats.weighted_avg)),
                (
                    "已获学分",
                    format!(
                        "{} / {}（{}）",
                        num(stats.earned_credits),
                        num(stats.total_credits),
                        percent(stats.earned_ratio)
                    ),
                ),
                ("挂科", format!("{} 门 / {} 学分", stats.failed_count, num(stats.failed_credits))),
                ("学位绩点", format!("{}（{}）", num(stats.degree.gpa), if stats.degree.qualified { "达标" } else { "未达标" })),
            ],
            12,
        );

        out.blank();
        out.line(&format!(
            "{}（{} · {}）{} 门",
            if is_today { "今日课程" } else { "指定日期课程" },
            clock::weekday_name(today.weekday_index()),
            clock::format_date(today),
            courses.len()
        ));
        if courses.is_empty() {
            let reason = if week.is_none() {
                "该日期不在校历教学周内"
            } else if is_today {
                "今日无课或课程已结束"
            } else {
                "该日期没有课程"
            };
            out.line(&format!("  （{reason}）"));
        } else {
            for cell in &courses {
                let slot = calendar::combine_slot_time(cell.section_index);
                let mut line = format!(
                    "  {}  {:<13}  {}",
                    render::pad_end(if slot.is_empty() { &cell.section_label } else { slot }, 18),
                    render::truncate(&cell.course_name, 24),
                    cell.location
                );
                if !cell.teacher.is_empty() {
                    line.push_str(&format!("  {}", cell.teacher));
                }
                out.line(line.trim_end());
            }
        }

        out.blank();
        out.line(&format!("近期考试（{} 门）", exams.len()));
        if exams.is_empty() {
            out.line("  （暂无近期考试安排）");
        } else {
            for (exam, parsed) in &exams {
                let time = parsed
                    .and_then(|time| match (time.start, time.end) {
                        (Some((hour, minute)), Some((end_hour, end_minute))) => Some(format!(
                            "{}-{:02} {:02}:{:02}-{:02}:{:02}",
                            clock::format_date(time.date),
                            time.date.month,
                            hour,
                            minute,
                            end_hour,
                            end_minute
                        )),
                        _ => Some(format!("{} {}-{:02}", clock::format_date(time.date), time.date.year, time.date.month)),
                    })
                    .unwrap_or_else(|| exam.time.clone());
                out.line(&format!(
                    "  {}  {}  {}  {}",
                    render::pad_end(&exam_status(parsed.as_ref(), today), 10),
                    render::pad_end(&time, 24),
                    render::pad_end(&render::truncate(&exam.course_name, 22), 24),
                    exam.location
                ).trim_end());
            }
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "profile": profile,
        "date": clock::format_date(today),
        "isToday": is_today,
        "semester": {
            "label": semester.map(|item| item.label.clone()),
            "teachingWeek": week,
            "teachingWeeks": semester.map(|item| item.teaching_weeks),
            "holiday": week.is_none(),
        },
        "stats": stats,
        "todayCourses": courses,
        "upcomingExams": exams.iter().map(|(exam, parsed)| json!({
            "exam": exam,
            "status": exam_status(parsed.as_ref(), today),
        })).collect::<Vec<_>>(),
    })
}

// ---------------- timetable ----------------

fn timetable(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let now = clock::now();
    // 指定日期时以该日期为基准：自动换算教学周与星期，"已下课"过滤只对今天生效
    let (today, is_today) = anchor_date(options);
    let single_day = options.today || options.date.is_some();
    let current_week = calendar::teaching_week_on(today);
    let teaching_weeks = calendar::teaching_weeks(today);
    let week = options
        .week
        .or(current_week)
        .or_else(|| calendar::teaching_week_on(now))
        .unwrap_or(1)
        .min(teaching_weeks.max(1));
    let table = store.timetable(options.base);
    let course_keyword = options.course.as_deref().map(str::to_lowercase);
    let mut cells: Vec<super::store::TimetableCell> = table
        .cells
        .into_iter()
        .filter(|cell| {
            !cell.course_name.trim().is_empty() && calendar::is_course_active_in_week(&cell.weeks, week)
        })
        .filter(|cell| match &course_keyword {
            Some(keyword) => {
                cell.course_name.to_lowercase().contains(keyword)
                    || cell.course_id.to_lowercase().contains(keyword)
            }
            None => true,
        })
        .collect();

    // 单日视图：`--today` 与概览页"今日剩余课程"同口径（含"已下课不再显示"），
    // `--date` 查历史 / 未来日期时不做已下课过滤
    if single_day {
        let day = today.weekday_index();
        let minutes = today.minutes_of_day();
        cells.retain(|cell| {
            cell.day == day
                && (options.base
                    || !is_today
                    || !calendar::has_combine_slot_ended(cell.section_index, minutes))
        });
    }
    if let Some(day) = options.day {
        cells.retain(|cell| cell.day == day);
    }
    cells.sort_by_key(|cell| (cell.day, cell.section_index));

    let days: Vec<u32> = if single_day {
        vec![today.weekday_index()]
    } else if let Some(day) = options.day {
        vec![day]
    } else {
        let mut present: Vec<u32> = cells.iter().map(|cell| cell.day).collect();
        present.sort_unstable();
        present.dedup();
        present
    };

    // 未排课课程按同一关键字裁剪：文本与 JSON 必须给出同一份列表
    let unarranged: Vec<super::store::UnarrangedCourse> = table
        .unarranged
        .iter()
        .filter(|course| match &course_keyword {
            Some(keyword) => course.course_name.to_lowercase().contains(keyword),
            None => true,
        })
        .cloned()
        .collect();

    if !options.json {
        let day_label = format!(
            "{} {}",
            clock::weekday_name(today.weekday_index()),
            clock::format_date(today)
        );
        let label = if options.today {
            format!("今日（{day_label}）")
        } else if options.date.is_some() {
            format!("指定日期（{day_label}）")
        } else if options.day.is_some() {
            format!("第 {week} 教学周 · 指定星期")
        } else {
            format!("第 {week} 教学周")
        };
        header(out, &format!("课程表 · {label}"));
        if let Some(keyword) = &options.course {
            out.line(&format!("  筛选：课程名或课程号包含「{keyword}」"));
        }
        if cells.is_empty() {
            let reason = if single_day && current_week.is_none() {
                "  该日期不在校历教学周内（或处于假期）。"
            } else if options.today {
                "  今日无课或课程已结束。"
            } else if options.date.is_some() {
                "  该日期没有课程。"
            } else {
                "  该范围内没有课程。"
            };
            out.line(reason);
        }
        // 没有课时不打印空的日期标题（避免"今日剩余"下面一片空白）
        for (index, day) in days.iter().enumerate().filter(|_| !cells.is_empty()) {
            let date = calendar::timetable_day_date(
                current_week.unwrap_or(week),
                week,
                *day,
                today,
            );
            if index > 0 {
                out.blank();
            }
            out.line(&format!(
                "{} {}{}",
                clock::weekday_name(*day),
                date,
                if options.today {
                    " · 今日剩余"
                } else if options.date.is_some() {
                    " · 当日课程"
                } else {
                    ""
                }
            ));
            let mut rows: Vec<&super::store::TimetableCell> = cells.iter().filter(|cell| cell.day == *day).collect();
            rows.sort_by_key(|cell| cell.section_index);
            for cell in rows {
                let slot = calendar::combine_slot_time(cell.section_index);
                let mut line = format!(
                    "  {}  {}",
                    render::pad_end(if slot.is_empty() { &cell.section_label } else { slot }, 18),
                    render::pad_end(&render::truncate(&cell.course_name, 26), 28)
                );
                if !cell.location.is_empty() {
                    // 按显示宽度补齐：地点里含中文与全角括号，字节/字符长度都不等于终端列数
                    line.push_str(&format!("@{}", render::pad_end(&cell.location, 16)));
                }
                if !cell.teacher.is_empty() {
                    line.push_str(&format!(" {}", cell.teacher));
                }
                if !cell.weeks.is_empty() {
                    line.push_str(&format!(" ({})", cell.weeks));
                }
                out.line(line.trim_end());
            }
        }
        // 未安排课程只在整周视图里列出：查单日时它们没有具体日期，列出来只会干扰
        if !unarranged.is_empty() && !single_day && options.day.is_none() {
            out.blank();
            out.line(&format!("未安排上课时间的课程（{} 门）", unarranged.len()));
            for course in &unarranged {
                out.line(&format!(
                    "  {}  {}  {}  {}",
                    render::pad_end(&render::truncate(&course.course_name, 24), 26),
                    render::pad_end(&course.teacher, 10),
                    render::pad_end(&course.weeks, 10),
                    course.location
                ).trim_end());
            }
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "week": week,
        "teachingWeeks": teaching_weeks,
        "base": options.base,
        "today": options.today,
        "date": clock::format_date(today),
        "singleDay": single_day,
        "courseFilter": options.course,
        "days": days.iter().map(|day| json!({
            "day": day,
            "weekday": clock::weekday_name(*day),
            "date": calendar::timetable_day_date(current_week.unwrap_or(week), week, *day, today),
            "courses": cells.iter().filter(|cell| cell.day == *day).collect::<Vec<_>>(),
        })).collect::<Vec<_>>(),
        "unarranged": unarranged,
    })
}

// ---------------- scores ----------------

fn score_matches(record: &ScoreRecord, options: &Options) -> bool {
    if let Some(year) = &options.year {
        if !record.year.contains(year.as_str()) {
            return false;
        }
    }
    if let Some(term) = &options.term {
        if !record.term.contains(term.as_str()) {
            return false;
        }
    }
    if let Some(course) = &options.course {
        let keyword = course.to_lowercase();
        if !record.course_name.to_lowercase().contains(&keyword)
            && !record.course_id.to_lowercase().contains(&keyword)
        {
            return false;
        }
    }
    if let Some(property) = &options.property {
        if let Some(kind) = stats::property_filter(property) {
            if stats::resolve_property(&record.property) != kind {
                return false;
            }
        }
    }
    // 只保留"明确不及格且有结论"的记录：pending（缓考 / 未出分）不算挂科
    if options.failed && (record.passed || record.pending) {
        return false;
    }
    true
}

fn scores(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let all = store.scores();
    let stats: AcademicStats = stats::build_academic_stats(&all);
    let filtered: Vec<ScoreRecord> = all.iter().filter(|record| score_matches(record, options)).cloned().collect();
    let deduped = stats::dedupe_scores(&filtered);

    if !options.json {
        header(out, "成绩与 GPA");
        out.line(&format!(
            "  共 {} 条记录 · {} 门课程（重修补考已合并）",
            filtered.len(),
            deduped.len()
        ));
        out.blank();
        out.line("学业统计（全部成绩 · 必修课口径）");
        render::kv(
            out,
            &[
                ("平均学分绩点", num(stats.gpa)),
                ("加权平均分", num(stats.weighted_avg)),
                (
                    "已获学分",
                    format!(
                        "{} / {}（{}）",
                        num(stats.earned_credits),
                        num(stats.total_credits),
                        percent(stats.earned_ratio)
                    ),
                ),
                ("挂科", format!("{} 门 / {} 学分", stats.failed_count, num(stats.failed_credits))),
                (
                    "学位绩点",
                    format!(
                        "{}（门槛 {}，{}）",
                        num(stats.degree.gpa),
                        num(stats.degree.threshold),
                        if stats.degree.qualified { "已达标" } else { "未达标" }
                    ),
                ),
                (
                    "推免自检",
                    format!(
                        "补考重修 {} / {} 门，必修课{}",
                        stats.recommend.retake_count,
                        stats.recommend.retake_limit,
                        if stats.recommend.all_passed { "全部合格" } else { "存在不合格" }
                    ),
                ),
                ("学业风险", stats.risk.description.clone()),
            ],
            12,
        );

        let mut notes: Vec<String> = Vec::new();
        if let Some(year) = &options.year {
            notes.push(format!("学年含「{year}」"));
        }
        if let Some(term) = &options.term {
            notes.push(format!("学期含「{term}」"));
        }
        if let Some(course) = &options.course {
            notes.push(format!("课程含「{course}」"));
        }
        if let Some(property) = &options.property {
            notes.push(format!("属性={property}"));
        }
        if options.failed {
            notes.push("仅未通过".to_string());
        }
        let filter_note = notes.join(" ");

        out.blank();
        if filtered.is_empty() {
            out.line("  没有匹配的成绩记录。");
        } else if options.merged {
            // 去重合并视图：同一门课只看最终结论（重修/补考取通过且折算分最高的一条）
            if !filter_note.is_empty() {
                out.line(&format!("课程清单 · 去重合并（筛选：{filter_note}）"));
            } else {
                out.line("课程清单 · 去重合并（重修补考取最优一条）");
            }
            let mut table = Table::new(&[
                "课程号",
                "课程名称",
                "学分",
                "总评",
                "绩点",
                "属性",
                "重修",
                "记录",
            ])
            .right_align(&[2, 3, 4, 7])
            .max_width(0, 15)
            .max_width(1, 30);
            let mut sorted = deduped.clone();
            sorted.sort_by_key(|record| {
                std::cmp::Reverse(stats::semester_sort_key(&record.year, &record.term))
            });
            for record in sorted {
                let grade = stats::parse_score_value(&record.score);
                table.push(vec![
                    record.course_id.clone(),
                    record.course_name.clone(),
                    num(record.credit),
                    record.score.clone(),
                    num(grade.grade_point),
                    record.property.clone(),
                    if record.is_retake { "是".to_string() } else { "-".to_string() },
                    record.record_count.to_string(),
                ]);
            }
            table.indent(2).render(out);
        } else {
            if !filter_note.is_empty() {
                out.line(&format!("成绩明细（筛选：{filter_note}）"));
            } else {
                out.line("成绩明细");
            }
            let mut table = Table::new(&[
                "学年", "学期", "课程号", "课程名称", "学分", "总评", "绩点", "属性", "备注",
            ])
            .right_align(&[4, 5, 6])
            .max_width(2, 15)
            .max_width(3, 30)
            .max_width(8, 12);
            let mut sorted = filtered.clone();
            sorted.sort_by_key(|record| {
                std::cmp::Reverse(stats::semester_sort_key(&record.year, &record.term))
            });
            for record in sorted {
                let grade = stats::parse_score_value(&record.score);
                table.push(vec![
                    record.year.clone(),
                    record.term.clone(),
                    record.course_id.clone(),
                    record.course_name.clone(),
                    num(record.credit),
                    record.score.clone(),
                    num(grade.grade_point),
                    record.property.clone(),
                    record.remark.clone(),
                ]);
            }
            table.indent(2).render(out);
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "stats": stats,
        "filters": {
            "year": options.year,
            "term": options.term,
            "course": options.course,
            "property": options.property,
            "failedOnly": options.failed,
            "merged": options.merged,
        },
        "years": store.score_years(),
        "scores": filtered,
        "courses": deduped.iter().map(|record| json!({
            "record": record,
            "isRetake": record.is_retake,
            "recordCount": record.record_count,
        })).collect::<Vec<_>>(),
        "dedupedCount": deduped.len(),
    })
}

// ---------------- exams ----------------

fn exams(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let today = clock::now();
    let days = options.days.unwrap_or(7);
    let all = store.exams();
    let mut list: Vec<(ExamRecord, Option<calendar::ExamTime>)> = if options.all {
        all.into_iter().map(|exam| {
            let parsed = calendar::parse_exam_datetime(&exam.time);
            (exam, parsed)
        }).collect()
    } else {
        recent_exams(store, today, days)
    };
    if let Some(course) = &options.course {
        let keyword = course.to_lowercase();
        list.retain(|(exam, _)| {
            exam.course_name.to_lowercase().contains(&keyword)
                || exam.course_id.to_lowercase().contains(&keyword)
        });
    }

    if !options.json {
        header(
            out,
            if options.all {
                "考试日程（全部）"
            } else if options.days.is_some() {
                "考试日程（指定窗口）"
            } else {
                "考试日程（近期）"
            },
        );
        if let Some(course) = &options.course {
            out.line(&format!("  筛选：课程名或课程号包含「{course}」"));
        }
        if list.is_empty() {
            out.line("  暂无考试安排。");
        } else {
            let mut table = Table::new(&["状态", "考试时间", "课程编号", "课程名称", "考场", "性质"])
                .max_width(1, 24)
                .max_width(2, 15)
                .max_width(3, 26)
                .max_width(4, 20);
            for (exam, parsed) in &list {
                table.push(vec![
                    exam_status(parsed.as_ref(), today),
                    exam.time.clone(),
                    exam.course_id.clone(),
                    exam.course_name.clone(),
                    exam.location.clone(),
                    exam.property.clone(),
                ]);
            }
            table.indent(2).render(out);
        }
        if !options.all {
            out.line("");
            if options.days.is_some() {
                out.line(&format!("提示：当前窗口为未来与过去 {days} 天，--all 可查看全部。"));
            } else {
                out.line(&format!(
                    "提示：默认只显示近期安排（未来与过去 {days} 天），使用 --all 查看全部、--days N 调整窗口。"
                ));
            }
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "filter": if options.all { "all" } else { "recent" },
        "windowDays": if options.all { Value::Null } else { json!(days) },
        "courseFilter": options.course,
        "total": store.counts().exams,
        "exams": list.iter().map(|(exam, parsed)| json!({
            "exam": exam,
            "status": exam_status(parsed.as_ref(), today),
        })).collect::<Vec<_>>(),
    })
}

// ---------------- plan ----------------

fn plan(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let all_plan = store.plan();
    let scores = store.scores();
    let progress = stats::compute_credits_progress(
        &scores,
        &all_plan.groups,
        all_plan.total_required_credits,
        stats::EARNED_CREDITS_REQUIRED_ONLY,
    );

    // `--group` 只裁剪展示，不改统计口径（总学分与完成度仍是整份方案）
    let mut matched: Vec<usize> = (0..progress.categories.len()).collect();
    if let Some(keyword) = &options.group {
        matched.retain(|index| progress.categories[*index].name.contains(keyword.as_str()));
    }

    if !options.json {
        header(out, "培养方案与学分进度");
        if all_plan.groups.is_empty() {
            out.line("  尚未同步培养方案，暂无课组数据。");
            out.line("  提示：请在图形界面「培养方案」页或顶栏刷新后重试。");
        } else {
            out.line(&format!(
                "  方案总学分要求 {} · 已获 {}（完成度 {}%）",
                num(progress.required_total),
                num(progress.earned_total),
                progress.completion_percent
            ));
            if let Some(keyword) = &options.group {
                out.line(&format!("  筛选：课组名称包含「{keyword}」（{} 个）", matched.len()));
            }
            out.blank();
            if matched.is_empty() {
                out.line("  没有名称匹配的课组。");
            }
            let mut table = Table::new(&["课组", "要求学分", "已获学分", "进度", "完成度"])
                .right_align(&[1, 2])
                .max_width(0, 26);
            for index in &matched {
                let category = &progress.categories[*index];
                let requirement = if let Some(candidates) = category.elective_candidates {
                    format!(
                        "{}（{} 门候选）",
                        num(category.required),
                        candidates
                    )
                } else if let Some(count) = category.required_courses.filter(|count| *count > 0) {
                    format!("{}（{count} 门）", num(category.required))
                } else {
                    if category.required > 0.0 {
                        num(category.required)
                    } else {
                        "-".to_string()
                    }
                };
                let bar = render::progress_bar(category.earned, category.required, 12);
                let percent_text = if category.required > 0.0 {
                    format!("{:.0}%", (category.earned / category.required * 100.0).min(100.0))
                } else {
                    "-".to_string()
                };
                table.push(vec![
                    category.name.clone(),
                    requirement,
                    num(category.earned),
                    bar,
                    percent_text,
                ]);
            }
            table.indent(2).render(out);
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "progress": progress,
        "planTotalCredits": all_plan.total_required_credits,
        "groups": all_plan.groups.len(),
        "groupFilter": options.group,
        "matchedGroups": matched.len(),
    })
}

// ---------------- notices ----------------

fn notices(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let today = clock::now();
    let week = options.week.or_else(|| calendar::teaching_week_on(today));
    let mut source = "当前公告".to_string();
    let mut list = match week {
        Some(week) => {
            let weeks = store.notice_weeks();
            match weeks.get(&week.to_string()) {
                Some(items) => {
                    source = format!("第 {week} 教学周公告（已缓存）");
                    items.clone()
                }
                None => store.notices(),
            }
        }
        None => store.notices(),
    };
    let total = list.len();
    if let Some(query) = &options.query {
        let keyword = query.to_lowercase();
        list.retain(|notice| {
            notice.title.to_lowercase().contains(&keyword)
                || notice.content.to_lowercase().contains(&keyword)
        });
    }
    if let Some(limit) = options.limit {
        list.truncate(limit);
    }

    if !options.json {
        header(out, &format!("教学公告 · {source}"));
        if let Some(query) = &options.query {
            out.line(&format!("  筛选：标题或正文包含「{query}」（命中 {} / {total} 条）", list.len()));
        } else if options.limit.is_some() && total > list.len() {
            out.line(&format!("  仅显示前 {} / {total} 条", list.len()));
        }
        if list.is_empty() {
            out.line(if options.query.is_some() {
                "  没有匹配的公告。"
            } else {
                "  暂无公告（源页面未返回内容）。"
            });
        } else {
            for (index, notice) in list.iter().enumerate() {
                out.blank();
                out.line(&format!("  [{}] {}", index + 1, notice.title));
                for line in render::wrap(&notice.content, 76) {
                    out.line(&format!("      {line}"));
                }
            }
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "week": week,
        "source": source,
        "total": total,
        "query": options.query,
        "limit": options.limit,
        "notices": list,
    })
}

// ---------------- profile ----------------

/// 折叠空白：教务页面的单元格用 `<br>` 分隔多项信息，取 textContent 会带换行；
/// 图形界面由 HTML 自动折叠成空格，终端必须显式折叠，否则一条信息会被拆成多行
fn collapse_ws(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// 学籍字段展示：折叠空白 → 脱敏 → 截断（超长地址不应撑爆终端）
fn profile_masked(text: &str, head: usize, tail: usize) -> String {
    let clean = collapse_ws(text);
    if clean.is_empty() {
        return String::new();
    }
    render::truncate(&mask_middle(&clean, head, tail), 40)
}

/// 学籍字段展示：折叠空白，空值统一显示"未记录"
fn profile_plain(text: &str) -> String {
    let clean = collapse_ws(text);
    if clean.is_empty() {
        "未记录".to_string()
    } else {
        render::truncate(&clean, 40)
    }
}

/// 中间打码：证件号 / 手机号在终端不应完整落屏（截图、日志、AI 上下文都会外泄）
fn mask_middle(text: &str, keep_head: usize, keep_tail: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    if chars.len() <= keep_head + keep_tail {
        return "*".repeat(chars.len());
    }
    let mut out = String::new();
    out.extend(chars.iter().take(keep_head));
    out.push_str(&"*".repeat(chars.len() - keep_head - keep_tail));
    out.extend(chars.iter().skip(chars.len() - keep_tail));
    out
}

fn profile(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let profile = store.profile();

    if !options.json {
        header(out, "学籍档案");
        let masked = |text: &str, head: usize, tail: usize| {
            let value = profile_masked(text, head, tail);
            if value.is_empty() {
                "未记录".to_string()
            } else {
                value
            }
        };
        render::kv(
            out,
            &[
                ("姓名", profile_plain(&profile.real_name)),
                ("学号", profile_plain(&profile.student_number)),
                ("学院", profile_plain(&profile.college)),
                ("专业", profile_plain(&profile.major)),
                ("专业方向", profile_plain(&profile.direction)),
                ("年级", profile_plain(&profile.grade)),
                ("班级", profile_plain(&profile.class_name)),
                ("学生类别", profile_plain(&profile.student_type)),
                ("学籍状态", profile_plain(&profile.status)),
                ("证件号码", masked(&profile.id_card, 4, 2)),
                ("联系电话", masked(&profile.phone, 3, 2)),
                ("电子邮箱", masked(&profile.email, 2, 0)),
                ("通讯地址", masked(&profile.address, 6, 0)),
            ],
            10,
        );

        if !profile.changes.is_empty() {
            out.blank();
            out.line(&format!("学籍异动记录（{} 条）", profile.changes.len()));
            let mut table =
                Table::new(&["类型", "日期", "原因", "备注"]).max_width(2, 18).max_width(3, 18);
            for change in &profile.changes {
                table.push(vec![
                    change.kind.clone(),
                    change.date.clone(),
                    change.reason.clone(),
                    change.remark.clone(),
                ]);
            }
            table.indent(2).render(out);
        }
        out.blank();
        out.line("隐私说明：证件号码、联系电话、电子邮箱与通讯地址在终端默认打码。");
        footer(out, store, !store.logged_in());
    }

    // JSON 输出同样脱敏：AI 工具不需要、也不应该持有完整证件号
    let mut safe = serde_json::to_value(&profile).unwrap_or_else(|_| json!({}));
    if let Some(object) = safe.as_object_mut() {
        for (key, head, tail) in [
            ("idCard", 4usize, 2usize),
            ("phone", 3, 2),
            ("email", 2, 0),
            ("address", 6, 0),
        ] {
            let raw = object
                .get(key)
                .and_then(|value| value.as_str())
                .unwrap_or("")
                .to_string();
            object.insert(key.to_string(), json!(profile_masked(&raw, head, tail)));
        }
        // 其余文本字段折叠 <br> 带来的换行，保持与图形界面一致的展示形态
        for key in [
            "realName",
            "college",
            "major",
            "direction",
            "grade",
            "className",
            "studentType",
            "status",
            "postalCode",
        ] {
            if let Some(raw) = object.get(key).and_then(|value| value.as_str()) {
                let clean = collapse_ws(raw);
                object.insert(key.to_string(), json!(clean));
            }
        }
    }

    json!({
        "profile": safe,
        "masked": ["idCard", "phone", "email", "address"],
    })
}

// ---------------- courses ----------------

fn courses(store: &Store, options: &Options, out: &mut Printer) -> Value {
    let mut list = store.current_courses();
    let total = list.len();
    // `--course` 与 `--query` 在这里等价：课程名 / 课程号子串匹配
    let keyword = options.course.as_deref().or(options.query.as_deref()).map(str::to_lowercase);
    if let Some(keyword) = &keyword {
        list.retain(|course| {
            course.course_name.to_lowercase().contains(keyword)
                || course.course_id.to_lowercase().contains(keyword)
        });
    }
    if let Some(limit) = options.limit {
        list.truncate(limit);
    }

    if !options.json {
        header(out, "本学期课程名录");
        if list.is_empty() {
            out.line(if keyword.is_some() {
                "  没有匹配的课程。"
            } else {
                "  本地缓存中没有本学期课程名录（上下文接口未返回课程表）。"
            });
        } else {
            if keyword.is_some() {
                out.line(&format!("  命中 {} / {total} 门", list.len()));
            } else {
                out.line(&format!("  共 {} 门", list.len()));
            }
            out.blank();
            let mut table = Table::new(&["课程号", "课序号", "课程名称", "教师", "学分"])
                .right_align(&[4])
                .max_width(2, 30);
            for course in &list {
                table.push(vec![
                    course.course_id.clone(),
                    course.course_seq.clone(),
                    course.course_name.clone(),
                    course.teacher.clone(),
                    num(course.credit),
                ]);
            }
            table.indent(2).render(out);
        }
        footer(out, store, !store.logged_in());
    }

    json!({
        "total": total,
        "limit": options.limit,
        "query": keyword,
        "courses": list,
    })
}
