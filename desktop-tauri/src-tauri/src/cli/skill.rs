//! SKILL 分发：把面向 AI 工具的技能文件交给用户
//!
//! 同一份 `desktop-tauri/skill/SKILL.md` 有两个去处（编译期内嵌，无需联网）：
//!  - `BetterHRBUSTCLI skill` 打印 / `--save` 落盘；
//!  - 本地服务 `/__app/cli/skill` 供设置页预览与一键复制。
//!
//! 因此 SKILL 的措辞必须与命令行实际行为一致——它是"给 AI 看的使用说明"，
//! 写错会直接导致 AI 误用命令（例如在退出码 2 时仍尝试登录）。

use std::fs;
use std::path::{Path, PathBuf};

use super::render::Printer;
use super::{Options, EXIT_ERROR, EXIT_OK};

const SKILL_MD: &str = include_str!("../../../skill/SKILL.md");
/// 技能目录名（与 frontmatter 的 `name` 一致，便于直接放进 skills 目录）
const SKILL_DIR_NAME: &str = "betterhrbust";
const SKILL_FILE_NAME: &str = "SKILL.md";

/// 内嵌的 SKILL 文本（本地服务端点复用）
pub fn text() -> &'static str {
    SKILL_MD
}

pub fn run(out: &mut Printer, options: &Options) -> i32 {
    let Some(target) = options.save.clone() else {
        if options.json {
            let payload = serde_json::json!({
                "command": "skill",
                "ok": true,
                "content": SKILL_MD,
            });
            super::commands::print_json(out, &payload);
        } else {
            out.write(SKILL_MD);
        }
        return EXIT_OK;
    };

    let path = resolve_target(&target);
    match write_skill(&path) {
        Ok(()) => {
            if options.json {
                let payload = serde_json::json!({
                    "command": "skill",
                    "ok": true,
                    "path": path.to_string_lossy(),
                });
                super::commands::print_json(out, &payload);
            } else {
                out.line(&format!("SKILL 已保存：{}", path.display()));
                out.line("放到 AI 工具的技能目录（如 .codebuddy/skills 或 .claude/skills）即可被识别。");
            }
            EXIT_OK
        }
        Err(message) => {
            if options.json {
                let payload = serde_json::json!({
                    "command": "skill",
                    "ok": false,
                    "error": { "code": "WRITE_FAILED", "message": message },
                });
                super::commands::print_json(out, &payload);
            } else {
                out.line(&format!("保存失败：{message}"));
            }
            EXIT_ERROR
        }
    }
}

/// `--save <路径>` 的语义：
///  - 以 `.md` 结尾 → 视为目标文件
///  - 其余（含已存在的目录）→ 视为技能根目录，写入 `<路径>/betterhrbust/SKILL.md`
fn resolve_target(target: &Path) -> PathBuf {
    let looks_like_file = target
        .extension()
        .map(|ext| ext.eq_ignore_ascii_case("md"))
        .unwrap_or(false);
    if looks_like_file {
        target.to_path_buf()
    } else {
        target.join(SKILL_DIR_NAME).join(SKILL_FILE_NAME)
    }
}

fn write_skill(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|err| format!("无法创建目录 {}：{err}", parent.display()))?;
    }
    // 只覆盖本工具写出的 SKILL（内容一致或含我们的 frontmatter 名称），
    // 避免用户把 `--save` 指向已有文档时被静默覆盖
    if let Ok(existing) = fs::read_to_string(path) {
        let ours = existing == SKILL_MD || existing.contains("name: betterhrbust");
        if !ours {
            return Err(format!(
                "{} 已存在且不是 BetterHRBUST 的 SKILL，请换一个路径或先手动备份",
                path.display()
            ));
        }
    }
    fs::write(path, SKILL_MD).map_err(|err| format!("无法写入 {}：{err}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skill_text_has_frontmatter_for_ai_tools() {
        // 仓库文件可能是 CRLF（Windows 编辑）或带 BOM，判定只看内容
        let content = text().trim_start_matches('\u{feff}');
        assert_eq!(
            content.lines().next(),
            Some("---"),
            "SKILL 必须以 YAML frontmatter 开头"
        );
        assert!(text().contains("name: betterhrbust"));
        assert!(text().contains("description:"), "缺少 description 会无法被 AI 工具识别");
        // 命令说明必须与真实 CLI 契约一致
        for command in ["status", "summary", "timetable", "scores", "exams", "plan", "doctor"] {
            assert!(text().contains(command), "SKILL 未覆盖命令 {command}");
        }
        assert!(text().contains("--json"));
        assert!(text().contains("退出码"));
    }

    #[test]
    fn save_target_resolves_directory_and_file() {
        assert_eq!(
            resolve_target(Path::new(r"D:\skills")),
            PathBuf::from(r"D:\skills\betterhrbust\SKILL.md")
        );
        assert_eq!(
            resolve_target(Path::new("skill.md")),
            PathBuf::from("skill.md")
        );
        assert_eq!(resolve_target(Path::new("SKILL.MD")), PathBuf::from("SKILL.MD"));
    }

    #[test]
    fn refuses_to_overwrite_foreign_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("own.md");
        fs::write(&path, "别人的文档").unwrap();
        assert!(write_skill(&path).is_err(), "不得覆盖非本工具写出的文件");

        let skill_path = dir.path().join("betterhrbust").join("SKILL.md");
        assert!(write_skill(&skill_path).is_ok());
        // 二次写入（版本更新）允许覆盖
        assert!(write_skill(&skill_path).is_ok());
        assert_eq!(fs::read_to_string(&skill_path).unwrap(), SKILL_MD);
    }
}
