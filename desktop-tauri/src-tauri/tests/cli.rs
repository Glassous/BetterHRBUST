//! 命令行集成测试：直接运行 `BetterHRBUSTCLI.exe` 真实进程
//!
//! 覆盖三件容易回归、且无法靠单元测试证明的事：
//!  1. 退出码契约（0 / 1 / 2 / 3）——脚本与 AI 工具据此分支，不能只靠文案；
//!  2. `--json` 输出必须是可解析的 JSON，且字段名稳定；
//!  3. 隐私边界——凭证键与证件号不得出现在任何输出里。
//!
//! 数据目录用临时目录 + `--data-dir` 注入，不触碰真实用户数据。

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

use serde_json::{json, Value};

fn cli() -> Command {
    Command::new(env!("CARGO_BIN_EXE_BetterHRBUSTCLI"))
}

fn run(data_dir: &Path, args: &[&str]) -> Output {
    cli()
        .arg("--data-dir")
        .arg(data_dir)
        .args(args)
        .output()
        .expect("命令行进程应能启动")
}

fn stdout(output: &Output) -> String {
    String::from_utf8_lossy(&output.stdout).to_string()
}

fn stderr(output: &Output) -> String {
    String::from_utf8_lossy(&output.stderr).to_string()
}

fn json_of(output: &Output) -> Value {
    serde_json::from_str(&stdout(output)).unwrap_or_else(|err| {
        panic!(
            "--json 输出必须可解析：{err}\nstdout={}\nstderr={}",
            stdout(output),
            stderr(output)
        )
    })
}

/// 完整夹具：已登录 + 成绩 / 考试 / 课表 / 培养方案 / 课程名录 / 档案
fn fixture_dir() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    let scores = json!([
        {
            "year": "2025", "term": "秋", "courseId": "REQ1", "courseName": "高等数学",
            "courseGroup": "学科基础课程", "score": "90", "credit": 3, "property": "必修",
            "examType": "正常考试", "remark": "", "passed": true, "pending": false
        },
        {
            "year": "2025", "term": "秋", "courseId": "REQ2", "courseName": "线性代数",
            "courseGroup": "学科基础课程", "score": "50", "credit": 2, "property": "必修",
            "examType": "正常考试", "remark": "", "passed": false, "pending": false
        },
        {
            "year": "2025", "term": "春", "courseId": "E1", "courseName": "通识写作",
            "courseGroup": "E类", "score": "95", "credit": 2, "property": "任选",
            "examType": "正常考试", "remark": "", "passed": true, "pending": false
        }
    ]);
    let timetable = json!({
        "cells": [
            {
                "id": "5-100", "courseId": "REQ1", "day": 5, "sectionIndex": 2,
                "sectionLabel": "第3大节", "courseName": "软件体系结构与设计模式",
                "location": "西-新D502", "teacher": "张春祥", "weeks": "1-18周",
                "hoursType": "讲课学时", "rawLines": []
            },
            {
                "id": "1-101", "courseId": "REQ2", "day": 1, "sectionIndex": 1,
                "sectionLabel": "第1大节", "courseName": "短学期实践",
                "location": "西-区田径场", "teacher": "", "weeks": "4-5周",
                "hoursType": "实践学时", "rawLines": []
            }
        ],
        "unarranged": [
            {
                "courseId": "U1", "courseName": "课程设计", "courseSeq": "3",
                "teacher": "崔香", "mergeClass": "", "weeks": "1-3周",
                "day": "", "location": ""
            }
        ]
    });
    let plan = json!({
        "groups": [
            {
                "id": "1", "name": "学科基础课程", "property": "必修",
                "requiredCredits": 26.5, "requiredCourses": 8,
                "earnedCredits": 0, "passedCourses": 0,
                "courses": [{ "code": "REQ1", "name": "高等数学", "credit": 3, "hours": 48, "property": "必修" }]
            }
        ],
        "totalRequiredCredits": 158.5
    });
    let profile = json!({
        "studentNumber": "2304060322", "internalId": "4638", "realName": "张三",
        "college": "计算机科学与技术学院", "major": "软件工程", "grade": "2024级",
        "className": "软件24-3", "studentType": "本科", "status": "在籍（注册）",
        "idCard": "110101199001011234", "phone": "13800001111",
        "email": "zhangsan@example.com", "address": "黑龙江省哈尔滨市南岗区学府路52号",
        "photoUrl": "", "changes": []
    });

    let map = json!({
        "better_hrbust_has_session": "true",
        "better_hrbust_cached_profile": profile.to_string(),
        "better_hrbust_last_login_at": "1794648219643",
        "better_hrbust_saved_credential": "b2JmdXNjYXRlZA==",
        "better_hrbust_cache_sync_meta": json!({
            "lastSyncAt": 1794648221617u64, "lastSyncDate": "2026-11-14", "lastError": ""
        }).to_string(),
        "better_hrbust_cache_scores": scores.to_string(),
        "better_hrbust_cache_exams": json!([
            {
                "courseId": "REQ1", "courseName": "高等数学",
                "time": "2099-01-05 14:00-16:00", "location": "西-新A308", "property": "正常考试"
            },
            {
                "courseId": "REQ2", "courseName": "线性代数",
                "time": "2020-01-05 14:00-16:00", "location": "西-新A309", "property": "正常考试"
            }
        ]).to_string(),
        "better_hrbust_cache_timetable": timetable.to_string(),
        "better_hrbust_cache_timetable_base": timetable.to_string(),
        "better_hrbust_cache_program_plan": plan.to_string(),
        "better_hrbust_cache_current_courses": json!([
            { "courseId": "REQ1", "courseSeq": "2", "courseName": "高等数学", "teacher": "张老师", "credit": 3 }
        ]).to_string(),
        "better_hrbust_cache_notices": json!([
            { "title": "关于开展期中教学检查的通知", "content": "各学院：现将期中教学检查安排通知如下。", "date": "" }
        ]).to_string(),
    });
    fs::write(dir.path().join("storage.json"), map.to_string()).unwrap();
    dir
}

fn empty_dir() -> tempfile::TempDir {
    tempfile::tempdir().unwrap()
}

fn dir_with(dir: &tempfile::TempDir, content: &str) -> PathBuf {
    fs::write(dir.path().join("storage.json"), content).unwrap();
    dir.path().to_path_buf()
}

#[test]
fn status_reports_state_and_never_fails() {
    let fixture = fixture_dir();
    let output = run(fixture.path(), &["status", "--json"]);
    assert_eq!(output.status.code(), Some(0), "status 永远返回 0");
    let value = json_of(&output);
    assert_eq!(value["command"], "status");
    assert_eq!(value["ok"], true);
    assert_eq!(value["loggedIn"], true);
    assert_eq!(value["data"]["readiness"], "ready");
    assert_eq!(value["data"]["counts"]["scores"], 3);

    // 无数据环境也只是报告状态，不以失败退出
    let empty = empty_dir();
    let output = run(empty.path(), &["status", "--json"]);
    assert_eq!(output.status.code(), Some(0));
    assert_eq!(json_of(&output)["data"]["readiness"], "needLogin");
}

#[test]
fn blocked_states_use_dedicated_exit_codes() {
    // 未登录 + 无数据 → 2，并提示去图形界面登录
    let empty = empty_dir();
    let output = run(empty.path(), &["scores"]);
    assert_eq!(output.status.code(), Some(2));
    let message = stderr(&output);
    assert!(message.contains("图形界面登录"), "提示应引导用户去图形界面：{message}");

    // 已登录但无缓存 → 3，提示刷新
    let login_only = tempfile::tempdir().unwrap();
    let dir = dir_with(&login_only, r#"{"better_hrbust_has_session":"true"}"#);
    let output = run(&dir, &["scores"]);
    assert_eq!(output.status.code(), Some(3));
    assert!(stderr(&output).contains("刷新"));

    // 存储损坏 → 3，且不改名用户的文件
    let corrupt = tempfile::tempdir().unwrap();
    let dir = dir_with(&corrupt, "{ not json");
    let output = run(&dir, &["scores"]);
    assert_eq!(output.status.code(), Some(3));
    assert!(dir.join("storage.json").exists(), "只读命令不得改动损坏文件");
    assert!(!dir.join("storage.json.corrupt").exists());
}

#[test]
fn bad_arguments_exit_with_error_code() {
    let fixture = fixture_dir();
    for args in [
        vec!["no-such-command"],
        vec!["timetable", "--week", "abc"],
        vec!["timetable", "--day", "9"],
        vec!["scores", "--what"],
    ] {
        let output = run(fixture.path(), &args);
        assert_eq!(output.status.code(), Some(1), "{args:?} 应返回 1");
    }
}

#[test]
fn summary_matches_graphics_statistics() {
    let fixture = fixture_dir();
    let value = json_of(&run(fixture.path(), &["summary", "--json"]));
    let stats = &value["data"]["stats"];
    // 必修课口径：(90*3 + 50*2) / 5 = 74 分；GPA (4*3 + 0*2) / 5 = 2.4
    assert!((stats["gpa"].as_f64().unwrap() - 2.4).abs() < 1e-9, "GPA 口径与图形界面不一致");
    assert!((stats["weightedAvg"].as_f64().unwrap() - 74.0).abs() < 1e-9);
    assert_eq!(stats["failedCount"], 1);
    assert_eq!(stats["earnedCredits"], 3.0);
    // 学位课：学业课（必修）+ E 类最高一门 → 学分 7，绩点 (4*3 + 0*2 + 4.5*2)/7 = 3.0
    assert_eq!(stats["degree"]["requiredCredits"], 7.0);
    assert!((stats["degree"]["gpa"].as_f64().unwrap() - 3.0).abs() < 1e-9);
    // 近期考试只保留未来场次
    assert_eq!(value["data"]["upcomingExams"].as_array().unwrap().len(), 1);
}

#[test]
fn scores_supports_filters_and_reports_dedupe() {
    let fixture = fixture_dir();
    let all = json_of(&run(fixture.path(), &["scores", "--json"]));
    assert_eq!(all["data"]["scores"].as_array().unwrap().len(), 3);

    let filtered = json_of(&run(fixture.path(), &["scores", "--json", "--term", "春"]));
    assert_eq!(filtered["data"]["scores"].as_array().unwrap().len(), 1);
    // 统计始终基于全部成绩，不受列表筛选影响
    assert_eq!(filtered["data"]["stats"]["rawCourseCount"], 3);
}

#[test]
fn timetable_filters_by_week_and_today() {
    let fixture = fixture_dir();
    let week11 = json_of(&run(fixture.path(), &["timetable", "--json", "--week", "11"]));
    let days = week11["data"]["days"].as_array().unwrap();
    assert_eq!(days.len(), 1, "第 11 周只剩 1-18 周那门课");
    assert_eq!(days[0]["day"], 5);

    let week5 = json_of(&run(fixture.path(), &["timetable", "--json", "--week", "5"]));
    assert_eq!(week5["data"]["days"].as_array().unwrap().len(), 2, "第 5 周两门课都在");

    let today = json_of(&run(fixture.path(), &["timetable", "--json", "--today"]));
    assert!(today["data"]["today"].as_bool().unwrap());
}

#[test]
fn exams_default_to_recent_and_all_switches_scope() {
    let fixture = fixture_dir();
    let recent = json_of(&run(fixture.path(), &["exams", "--json"]));
    assert_eq!(recent["data"]["exams"].as_array().unwrap().len(), 1);
    let all = json_of(&run(fixture.path(), &["exams", "--json", "--all"]));
    let statuses: Vec<&str> = all["data"]["exams"]
        .as_array()
        .unwrap()
        .iter()
        .map(|exam| exam["status"].as_str().unwrap())
        .collect();
    assert_eq!(statuses.len(), 2);
    assert!(statuses.iter().any(|status| status.ends_with("天后")), "未来考试应显示倒计时");
    assert!(statuses.iter().any(|status| *status == "已结束"), "历史考试应标记已结束");
}

#[test]
fn plan_reports_credit_progress() {
    let fixture = fixture_dir();
    let value = json_of(&run(fixture.path(), &["plan", "--json"]));
    let progress = &value["data"]["progress"];
    assert_eq!(progress["requiredTotal"], 158.5, "方案总学分优先于课组求和");
    assert_eq!(progress["categories"][0]["name"], "学科基础课程");
    assert_eq!(progress["categories"][0]["earned"], 3.0);
}

#[test]
fn profile_and_doctor_never_leak_private_data() {
    let fixture = fixture_dir();

    let output = run(fixture.path(), &["profile", "--json"]);
    let value = json_of(&output);
    let id_card = value["data"]["profile"]["idCard"].as_str().unwrap();
    let phone = value["data"]["profile"]["phone"].as_str().unwrap();
    assert!(id_card.contains('*') && !id_card.contains("110101199001011234"), "证件号必须打码");
    assert!(phone.contains('*') && !phone.contains("13800001111"), "手机号必须打码");

    let doctor = json_of(&run(fixture.path(), &["doctor", "--json"]));
    let keys = doctor["data"]["keys"].as_array().unwrap();
    let credential = keys
        .iter()
        .find(|item| item["key"] == "better_hrbust_saved_credential")
        .expect("诊断应报告凭证键存在");
    assert_eq!(credential["sensitive"], true);
    assert!(
        !stdout(&run(fixture.path(), &["doctor", "--json"])).contains("b2JmdXNjYXRlZA"),
        "诊断输出不得包含凭证内容"
    );
}

#[test]
fn offline_cache_still_queryable_with_hint() {
    // 会话标记被清除但缓存仍在：这是"退出登录后仍可查"的场景
    let dir = tempfile::tempdir().unwrap();
    let map = json!({
        "better_hrbust_cache_scores": json!([
            { "courseId": "REQ1", "courseName": "高等数学", "score": "90", "credit": 3,
              "property": "必修", "year": "2025", "term": "秋", "passed": true, "pending": false }
        ]).to_string(),
        "better_hrbust_session_expired": "true"
    });
    fs::write(dir.path().join("storage.json"), map.to_string()).unwrap();

    let output = run(dir.path(), &["scores"]);
    assert_eq!(output.status.code(), Some(0));
    assert!(stdout(&output).contains("离线缓存"), "应提示当前是离线缓存");

    let value = json_of(&run(dir.path(), &["scores", "--json"]));
    assert_eq!(value["offline"], true);
}

#[test]
fn skill_can_be_printed_and_saved() {
    let fixture = fixture_dir();
    let printed = run(fixture.path(), &["skill"]);
    assert_eq!(printed.status.code(), Some(0));
    assert!(stdout(&printed).contains("name: betterhrbust"));

    let skills_root = tempfile::tempdir().unwrap();
    let output = run(fixture.path(), &["skill", "--save", skills_root.path().to_str().unwrap()]);
    assert_eq!(output.status.code(), Some(0), "保存 SKILL 应成功：{}", stderr(&output));
    let path = skills_root.path().join("betterhrbust").join("SKILL.md");
    let content = fs::read_to_string(&path).expect("SKILL 文件应已落盘");
    assert!(content.contains("name: betterhrbust"));
}

#[test]
fn json_output_is_stable_for_every_command() {
    let fixture = fixture_dir();
    for command in [
        "status", "summary", "timetable", "scores", "exams", "plan", "notices", "profile",
        "courses", "doctor",
    ] {
        let output = run(fixture.path(), &[command, "--json"]);
        assert_eq!(output.status.code(), Some(0), "{command} 应成功");
        let value = json_of(&output);
        assert_eq!(value["command"], command);
        assert_eq!(value["ok"], true);
        assert!(value.get("data").is_some(), "{command} 缺少 data 字段");
        assert!(value.get("lastSyncText").is_some(), "{command} 缺少 lastSyncText");
    }
}
