//! BetterHRBUST 命令行模式（`BetterHRBUSTCLI.exe`）
//!
//! 设计约束（与桌面端图形界面的分工）：
//!  - 图形界面是**唯一登录入口**：教务登录需要学号、密码与图片验证码，
//!    终端无法呈现验证码，因此命令行不做任何登录尝试，也不联网；
//!  - 命令行只读桌面端已落盘的缓存（`storage.json`），
//!    未登录但存在缓存时照常可查，页面底部会标注"离线缓存"；
//!  - 未登录且无任何缓存时以专用退出码 2 结束，并提示先打开图形界面登录。
//!
//! 退出码是给脚本 / AI 工具用的稳定契约（不要解析文案）：
//!  - `0` 正常
//!  - `1` 参数错误或内部错误
//!  - `2` 未登录且没有本地数据 → 需要先到图形界面登录
//!  - `3` 存储文件不可用，或已登录但缓存为空 → 需要到图形界面刷新
//!
//! 本模块的逻辑同时被本地服务（`/__app/cli`）复用，
//! 保证设置页展示的状态与命令行看到的状态一致。

pub mod calendar;
mod clock;
mod commands;
mod path_env;
mod render;
mod skill;
mod stats;
mod store;

use std::path::{Path, PathBuf};

pub use store::CliInfo;

/// 本地服务 `/__app/cli` 用：数据目录下的命令行状态
///
/// 与 `doctor` 共用同一套解析逻辑，避免设置页显示"可用"而终端报"无数据"。
pub fn info_for(data_dir: &Path) -> CliInfo {
    store::Store::open(Some(data_dir.to_path_buf())).info()
}

/// 本地服务 `/__app/cli/skill` 用：内嵌的 SKILL 文本
pub fn skill_text() -> &'static str {
    skill::text()
}

/// 正常结束
pub const EXIT_OK: i32 = 0;
/// 参数错误 / 内部错误
pub const EXIT_ERROR: i32 = 1;
/// 未登录且无本地数据：请先打开图形界面登录
pub const EXIT_NEED_LOGIN: i32 = 2;
/// 存储不可用或已登录但无缓存：请到图形界面刷新数据
pub const EXIT_NO_DATA: i32 = 3;

/// 命令行参数（子命令 + 全局/子命令选项）
#[derive(Debug, Clone, Default)]
pub struct Options {
    /// 机器可读输出（供脚本与 AI 工具消费）
    pub json: bool,
    /// 显式指定数据目录（测试夹具、多数据目录隔离）
    pub data_dir: Option<PathBuf>,
    /// 课表：指定教学周
    pub week: Option<u32>,
    /// 课表：指定星期（1~7）
    pub day: Option<u32>,
    /// 课表：只看今日（与概览页"今日剩余课程"同口径）
    pub today: bool,
    /// 课表：小节课表（BASE），默认大节（COMBINE）
    pub base: bool,
    /// 成绩：学年 / 学期筛选
    pub year: Option<String>,
    pub term: Option<String>,
    /// 考试：是否显示全部（默认只显示近期）
    pub all: bool,
    /// SKILL：保存目标路径
    pub save: Option<PathBuf>,
    /// `path` 命令的动作（缺省为查看当前用户 PATH）
    pub path_action: Option<PathAction>,
    /// `path` 命令处理的目标目录（缺省为当前 exe 所在目录）
    pub dir: Option<PathBuf>,
}

/// `path` 命令的动作：安装器在安装 / 卸载阶段调用
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PathAction {
    /// 把目录加入用户 PATH（安装后立即可用）
    Install,
    /// 从用户 PATH 移除目录（卸载时清理）
    Uninstall,
    /// 打印当前用户 PATH（不展开 %VAR%）
    Print,
}

/// 支持的子命令
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    Status,
    Summary,
    Timetable,
    Scores,
    Exams,
    Plan,
    Notices,
    Profile,
    Courses,
    Doctor,
    Skill,
    Path,
    Help,
    Version,
}

impl Command {
    fn parse(text: &str) -> Option<Self> {
        // 中文别名降低记忆成本：`BetterHRBUSTCLI 课表`
        match text {
            "status" | "状态" => Some(Command::Status),
            "summary" | "dashboard" | "overview" | "概览" => Some(Command::Summary),
            "timetable" | "schedule" | "课表" | "课程表" => Some(Command::Timetable),
            "scores" | "score" | "成绩" => Some(Command::Scores),
            "exams" | "exam" | "考试" => Some(Command::Exams),
            "plan" | "program" | "培养方案" => Some(Command::Plan),
            "notices" | "notice" | "公告" => Some(Command::Notices),
            "profile" | "学籍" | "档案" => Some(Command::Profile),
            "courses" | "course" | "课程" => Some(Command::Courses),
            "doctor" | "诊断" => Some(Command::Doctor),
            "skill" | "技能" => Some(Command::Skill),
            "path" | "环境变量" => Some(Command::Path),
            "help" | "--help" | "-h" | "帮助" => Some(Command::Help),
            "version" | "--version" | "-V" | "版本" => Some(Command::Version),
            _ => None,
        }
    }

    /// 需要读取本地缓存数据的命令（受登录态 / 数据量约束）
    fn needs_data(self) -> bool {
        !matches!(
            self,
            Command::Status
                | Command::Doctor
                | Command::Skill
                | Command::Path
                | Command::Help
                | Command::Version
        )
    }

    fn name(self) -> &'static str {
        match self {
            Command::Status => "status",
            Command::Summary => "summary",
            Command::Timetable => "timetable",
            Command::Scores => "scores",
            Command::Exams => "exams",
            Command::Plan => "plan",
            Command::Notices => "notices",
            Command::Profile => "profile",
            Command::Courses => "courses",
            Command::Doctor => "doctor",
            Command::Skill => "skill",
            Command::Path => "path",
            Command::Help => "help",
            Command::Version => "version",
        }
    }
}

/// 参数解析结果
struct Parsed {
    command: Command,
    options: Options,
}

/// 取选项取值：支持 `--week=12` 与 `--week 12`，并拒绝把下一个选项当成值
fn option_value(
    raw: &[String],
    index: &mut usize,
    inline: Option<&str>,
    name: &str,
) -> Result<String, String> {
    if let Some(value) = inline {
        return Ok(value.to_string());
    }
    *index += 1;
    raw.get(*index)
        .filter(|next| !next.starts_with("--"))
        .cloned()
        .ok_or_else(|| format!("选项 {name} 缺少取值"))
}

fn parse(raw: &[String]) -> Result<Parsed, String> {
    let mut options = Options::default();
    let mut command: Option<Command> = None;
    let mut index = 0usize;

    while index < raw.len() {
        let arg = raw[index].as_str();
        // 支持 `--week=12` 与 `--week 12` 两种写法
        let (flag, inline) = match arg.split_once('=') {
            Some((flag, value)) => (flag, Some(value)),
            None => (arg, None),
        };

        match flag {
            "--json" => options.json = true,
            // 历史兼容：输出本来就不带颜色，接受该参数但不做任何事
            "--no-color" => {}
            "--data-dir" => {
                let value = option_value(raw, &mut index, inline, "--data-dir")?;
                options.data_dir = Some(PathBuf::from(value));
            }
            "--week" => {
                let value = option_value(raw, &mut index, inline, "--week")?;
                let week: u32 =
                    value.parse().map_err(|_| format!("--week 需要 1~26 的整数，收到 `{value}`"))?;
                if !(1..=26).contains(&week) {
                    return Err(format!("--week 需要 1~26 的整数，收到 `{week}`"));
                }
                options.week = Some(week);
            }
            "--day" => {
                let value = option_value(raw, &mut index, inline, "--day")?;
                let day: u32 =
                    value.parse().map_err(|_| format!("--day 需要 1~7 的整数，收到 `{value}`"))?;
                if !(1..=7).contains(&day) {
                    return Err(format!("--day 需要 1~7 的整数，收到 `{day}`"));
                }
                options.day = Some(day);
            }
            "--today" => options.today = true,
            "--base" => options.base = true,
            "--year" => options.year = Some(option_value(raw, &mut index, inline, "--year")?),
            "--term" => options.term = Some(option_value(raw, &mut index, inline, "--term")?),
            "--all" => options.all = true,
            // 安装器使用：把安装目录接入 / 移出用户 PATH（见 cli/path_env.rs）
            "--install" => options.path_action = Some(PathAction::Install),
            "--uninstall" => options.path_action = Some(PathAction::Uninstall),
            "--print" => options.path_action = Some(PathAction::Print),
            "--dir" => {
                let value = option_value(raw, &mut index, inline, "--dir")?;
                options.dir = Some(PathBuf::from(value));
            }
            "--save" => {
                let value = option_value(raw, &mut index, inline, "--save")?;
                options.save = Some(PathBuf::from(value));
            }
            _ if flag.starts_with('-') => return Err(format!("未知选项 `{flag}`")),
            _ => {
                if command.is_some() {
                    return Err(format!("多余的参数 `{arg}`（每个命令只接受一个子命令）"));
                }
                command = Some(
                    Command::parse(flag).ok_or_else(|| format!("未知命令 `{flag}`"))?,
                );
            }
        }
        index += 1;
    }

    Ok(Parsed { command: command.unwrap_or(Command::Help), options })
}

/// 进程入口：返回退出码（由 `src/bin/cli.rs` 交给 `std::process::exit`）
pub fn run() -> i32 {
    let raw: Vec<String> = std::env::args().skip(1).collect();
    let parsed = match parse(&raw) {
        Ok(parsed) => parsed,
        Err(error) => {
            let mut stderr = render::Printer::stderr();
            stderr.line(&format!("参数错误：{error}"));
            stderr.line("执行 `BetterHRBUSTCLI help` 查看用法。");
            return EXIT_ERROR;
        }
    };
    execute(parsed.command, parsed.options)
}

fn execute(command: Command, options: Options) -> i32 {
    let mut stdout = render::Printer::stdout();
    let store = store::Store::open(options.data_dir.clone());

    match command {
        Command::Help => {
            print_help(&mut stdout);
            EXIT_OK
        }
        Command::Version => {
            if options.json {
                let payload = serde_json::json!({
                    "command": "version",
                    "ok": true,
                    "version": env!("CARGO_PKG_VERSION"),
                });
                commands::print_json(&mut stdout, &payload);
            } else {
                stdout.line(&format!("BetterHRBUSTCLI {}", env!("CARGO_PKG_VERSION")));
            }
            EXIT_OK
        }
        Command::Skill => skill::run(&mut stdout, &options),
        Command::Path => path_command(&options, &mut stdout),
        Command::Doctor => {
            commands::doctor(&store, &options, &mut stdout);
            EXIT_OK
        }
        Command::Status => {
            commands::status(&store, &options, &mut stdout);
            EXIT_OK
        }
        _ => {
            if command.needs_data() {
                match store.readiness() {
                    store::Readiness::Ready { offline } => {
                        commands::run_data_command(command, &store, &options, offline, &mut stdout)
                    }
                    store::Readiness::NeedLogin => {
                        return report_blocked(
                            command,
                            &options,
                            EXIT_NEED_LOGIN,
                            "NEED_GUI_LOGIN",
                            &[
                                "未检测到登录状态，也没有任何本地教务数据。",
                                "登录需要学号、密码与图片验证码，终端无法完成，请先打开",
                                "BetterHRBUST 图形界面登录一次，再回到终端查询。",
                            ],
                        )
                    }
                    store::Readiness::NoData(message) => {
                        return report_blocked(
                            command,
                            &options,
                            EXIT_NO_DATA,
                            "NO_LOCAL_DATA",
                            &[&message],
                        )
                    }
                }
            } else {
                EXIT_OK
            }
        }
    }
}

/// `path` 命令：维护用户 PATH
///
/// 安装器（NSIS）在安装 / 卸载阶段调用，替代在 NSIS 脚本里直接读写注册表——
/// NSIS 的字符串上限是 1024 字符，读取更长的 PATH 会失败并把 PATH 覆盖坏。
fn path_command(options: &Options, out: &mut render::Printer) -> i32 {
    let action = options.path_action.unwrap_or(PathAction::Print);
    let dir = options.dir.clone().or_else(|| {
        std::env::current_exe().ok().and_then(|exe| exe.parent().map(|parent| parent.to_path_buf()))
    });
    let Some(dir) = dir else {
        out.line("无法确定安装目录：请用 --dir 指定");
        return EXIT_ERROR;
    };

    let result = match action {
        PathAction::Print => path_env::current().map(|value| (value, false)),
        PathAction::Install => path_env::install(&dir).map(|outcome| (outcome.path, outcome.changed)),
        PathAction::Uninstall => {
            path_env::uninstall(&dir).map(|outcome| (outcome.path, outcome.changed))
        }
    };

    let (path, changed) = match result {
        Ok(value) => value,
        Err(error) => {
            if options.json {
                let payload = serde_json::json!({
                    "command": "path",
                    "ok": false,
                    "error": { "code": "PATH_FAILED", "message": error },
                });
                commands::print_json(out, &payload);
            } else {
                out.line(&format!("PATH 操作失败：{error}"));
            }
            return EXIT_ERROR;
        }
    };

    if options.json {
        let payload = serde_json::json!({
            "command": "path",
            "ok": true,
            "data": {
                "action": match action {
                    PathAction::Print => "print",
                    PathAction::Install => "install",
                    PathAction::Uninstall => "uninstall",
                },
                "dir": dir.to_string_lossy(),
                "changed": changed,
                "path": path,
            },
        });
        commands::print_json(out, &payload);
        return EXIT_OK;
    }

    match action {
        PathAction::Print => {
            out.line(&path);
        }
        PathAction::Install => {
            if changed {
                out.line(&format!("已把 {} 加入用户 PATH（新开的终端生效）", dir.display()));
            } else {
                out.line(&format!("{} 已在用户 PATH 中，无需重复添加", dir.display()));
            }
        }
        PathAction::Uninstall => {
            if changed {
                out.line(&format!("已从用户 PATH 移除 {}", dir.display()));
            } else {
                out.line(&format!("用户 PATH 中没有 {}", dir.display()));
            }
        }
    }
    EXIT_OK
}

/// 不可查询时的统一反馈：文本走 stderr，`--json` 走 stdout 的固定错误结构
fn report_blocked(
    command: Command,
    options: &Options,
    exit_code: i32,
    error_code: &str,
    lines: &[&str],
) -> i32 {
    let message = lines.join("");
    if options.json {
        let mut stdout = render::Printer::stdout();
        let payload = serde_json::json!({
            "command": command.name(),
            "ok": false,
            "error": { "code": error_code, "message": message },
        });
        commands::print_json(&mut stdout, &payload);
    } else {
        let mut stderr = render::Printer::stderr();
        stderr.line("无法查询：");
        for line in lines {
            stderr.line(&format!("  {line}"));
        }
        stderr.line("提示：执行 `BetterHRBUSTCLI doctor` 可查看数据文件与路径诊断。");
    }
    exit_code
}

fn print_help(out: &mut render::Printer) {
    out.line(&format!("BetterHRBUSTCLI {} · 桌面端本地只读命令行", env!("CARGO_PKG_VERSION")));
    out.blank();
    out.line("用法：BetterHRBUSTCLI <命令> [选项]");
    out.blank();
    out.line("命令：");
    out.line("  status                    登录状态、本地数据与上次同步时间");
    out.line("  summary                   概览：身份、今日课程、近期考试、学业指标");
    out.line("  timetable                 课程表（默认当前教学周）");
    out.line("  scores                    成绩与 GPA（必修课口径，与图形界面一致）");
    out.line("  exams                     考试日程（默认近期安排）");
    out.line("  plan                      培养方案与学分进度");
    out.line("  notices                   教学公告");
    out.line("  profile                   学籍档案（证件号 / 手机号默认打码）");
    out.line("  courses                   本学期课程名录");
    out.line("  doctor                    数据文件、路径与可用性诊断");
    out.line("  skill [--save <路径>]     输出 / 保存给 AI 工具使用的 SKILL");
    out.line("  path [--install|--uninstall|--print]");
    out.line("                            查看 / 维护用户 PATH（安装器使用）");
    out.line("  help                      显示本帮助");
    out.line("  version                   显示版本");
    out.blank();
    out.line("选项：");
    out.line("  --json                    机器可读输出（脚本 / AI 工具）");
    out.line("  --data-dir <路径>         指定数据目录（默认 %APPDATA%\\edu.hrbust.betterhrbust）");
    out.line("  --week <1-26>             课表：指定教学周");
    out.line("  --day <1-7>               课表：指定星期（1 = 周一）");
    out.line("  --today                   课表：只看今日剩余课程");
    out.line("  --base                    课表：使用小节课表（默认大节）");
    out.line("  --year <学年> --term <学期>  成绩：筛选学年 / 学期");
    out.line("  --all                     考试：显示全部（含已结束）");
    out.line("  --dir <路径>              path：指定要操作的目录（默认当前 exe 所在目录）");
    out.blank();
    out.line("退出码：0 正常 · 1 参数/内部错误 · 2 未登录且无本地数据（请先在图形界面登录）");
    out.line("        3 存储不可用或已登录但无缓存（请到图形界面刷新）");
    out.blank();
    out.line("说明：终端无法输入图片验证码，因此命令行不做登录、不联网，只读本地缓存。");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|item| item.to_string()).collect()
    }

    #[test]
    fn parses_commands_and_aliases() {
        assert_eq!(parse(&args(&["scores"])).unwrap().command, Command::Scores);
        assert_eq!(parse(&args(&["课表"])).unwrap().command, Command::Timetable);
        assert_eq!(parse(&args(&["program"])).unwrap().command, Command::Plan);
        // 无参数默认显示帮助
        assert_eq!(parse(&args(&[])).unwrap().command, Command::Help);
    }

    #[test]
    fn parses_options_in_both_forms() {
        let parsed = parse(&args(&["timetable", "--week=12", "--day", "3", "--json"])).unwrap();
        assert_eq!(parsed.command, Command::Timetable);
        assert_eq!(parsed.options.week, Some(12));
        assert_eq!(parsed.options.day, Some(3));
        assert!(parsed.options.json);
    }

    #[test]
    fn rejects_unknown_command_and_bad_values() {
        assert!(parse(&args(&["nope"])).is_err());
        assert!(parse(&args(&["timetable", "--week", "abc"])).is_err());
        assert!(parse(&args(&["timetable", "--day", "9"])).is_err());
        assert!(parse(&args(&["scores", "extra"])).is_err());
        assert!(parse(&args(&["scores", "--unknown"])).is_err());
    }

    #[test]
    fn data_commands_are_gated_but_diagnostics_are_not() {
        for command in [
            Command::Status,
            Command::Doctor,
            Command::Skill,
            Command::Path,
            Command::Help,
            Command::Version,
        ] {
            assert!(!command.needs_data(), "{} 不应被数据状态拦截", command.name());
        }
        for command in [Command::Summary, Command::Scores, Command::Timetable, Command::Exams] {
            assert!(command.needs_data(), "{} 必须先有本地数据", command.name());
        }
    }
}
