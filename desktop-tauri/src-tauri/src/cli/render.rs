//! 终端输出：Windows 控制台安全写入、CJK 显示宽度与表格渲染
//!
//! 为什么不直接用 `println!`：
//!  - cmd.exe 默认代码页是 936（GBK），UTF-8 字节会被显示成乱码；
//!  - `SetConsoleOutputCP(65001)` 虽然能解决乱码，但会污染用户当前终端，
//!    影响后续命令的输出——查询命令不该承担这种副作用。
//!
//! 因此这里按目标分流：stdout / stderr 是控制台时走 `WriteConsoleW`（UTF-16，
//! 绕开代码页），是管道 / 重定向时写 UTF-8 字节（管道给脚本或 AI 工具都正确）。
//! 表格按显示宽度（中文算 2 列）补空格，保证 CJK 与 ASCII 混排对齐。

use std::io::{self, Write};
#[cfg(test)]
use std::sync::{Arc, Mutex};

/// 终端输出句柄：控制台走宽字符 API，管道走字节流
pub struct Printer {
    backend: Backend,
}

enum Backend {
    #[cfg(windows)]
    Console(isize),
    Pipe(Box<dyn Write + Send>),
}

impl Printer {
    pub fn stdout() -> Self {
        Self::open(false)
    }

    pub fn stderr() -> Self {
        Self::open(true)
    }

    /// 测试用：把输出写进内存缓冲，便于断言渲染结果
    #[cfg(test)]
    pub fn capture() -> (Self, Arc<Mutex<Vec<u8>>>) {
        let buffer = Arc::new(Mutex::new(Vec::new()));
        let writer = SharedBuffer(buffer.clone());
        (Self { backend: Backend::Pipe(Box::new(writer)) }, buffer)
    }

    fn open(stderr: bool) -> Self {
        #[cfg(windows)]
        {
            let handle = unsafe {
                win::GetStdHandle(if stderr {
                    win::STD_ERROR_HANDLE
                } else {
                    win::STD_OUTPUT_HANDLE
                })
            };
            let mut mode = 0u32;
            // GetConsoleMode 失败说明句柄不是控制台（管道 / 文件重定向 / 无控制台）
            if handle != 0 && unsafe { win::GetConsoleMode(handle, &mut mode) } != 0 {
                return Self { backend: Backend::Console(handle) };
            }
        }
        let pipe: Box<dyn Write + Send> = if stderr {
            Box::new(io::stderr())
        } else {
            Box::new(io::stdout())
        };
        Self { backend: Backend::Pipe(pipe) }
    }

    /// 写入原生文本（换行符会自动适配控制台：LF → CRLF）
    pub fn write(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        match &mut self.backend {
            #[cfg(windows)]
            Backend::Console(handle) => {
                let normalized = text.replace("\r\n", "\n").replace('\n', "\r\n");
                let units: Vec<u16> = normalized.encode_utf16().collect();
                let mut offset = 0usize;
                while offset < units.len() {
                    let chunk = &units[offset..];
                    // WriteConsoleW 单次上限保守取 8192 个 UTF-16 码元
                    let len = chunk.len().min(8192) as u32;
                    let mut written = 0u32;
                    let ok = unsafe {
                        win::WriteConsoleW(
                            *handle,
                            chunk.as_ptr(),
                            len,
                            &mut written,
                            std::ptr::null_mut(),
                        )
                    };
                    if ok == 0 || written == 0 {
                        break;
                    }
                    offset += written as usize;
                }
            }
            Backend::Pipe(writer) => {
                // 管道被下游提前关闭（如 `| head`）时静默结束，不 panic
                let _ = writer.write_all(text.as_bytes());
                let _ = writer.flush();
            }
        }
    }

    /// 输出一行
    pub fn line(&mut self, text: &str) {
        self.write(text);
        self.write("\n");
    }

    /// 输出空行
    pub fn blank(&mut self) {
        self.write("\n");
    }
}

#[cfg(test)]
struct SharedBuffer(Arc<Mutex<Vec<u8>>>);

#[cfg(test)]
impl Write for SharedBuffer {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.lock().expect("缓冲锁中毒").extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(windows)]
mod win {
    use std::ffi::c_void;

    pub const STD_OUTPUT_HANDLE: u32 = -11i32 as u32;
    pub const STD_ERROR_HANDLE: u32 = -12i32 as u32;

    #[link(name = "kernel32")]
    extern "system" {
        pub fn GetStdHandle(std_handle: u32) -> isize;
        pub fn GetConsoleMode(console_handle: isize, mode: *mut u32) -> i32;
        pub fn WriteConsoleW(
            console_handle: isize,
            buffer: *const u16,
            chars_to_write: u32,
            chars_written: *mut u32,
            reserved: *mut c_void,
        ) -> i32;
    }
}

// ---------------- CJK 显示宽度 ----------------

/// 单个字符占用的终端列数（东亚宽字符 2 列，组合符号 0 列）
pub fn char_width(c: char) -> usize {
    let cp = c as u32;
    // 零宽 / 组合符号：不占列
    if cp == 0
        || cp == 0x200B
        || cp == 0x200C
        || cp == 0x200D
        || cp == 0xFEFF
        || (0x0300..=0x036F).contains(&cp)
        || (0xFE00..=0xFE0F).contains(&cp)
    {
        return 0;
    }
    // 东亚宽字符（CJK、假名、谚文、全角标点、部分 emoji）
    let wide = (0x1100..=0x115F).contains(&cp)
        || (0x2E80..=0x303E).contains(&cp)
        || (0x3041..=0x33FF).contains(&cp)
        || (0x3400..=0x4DBF).contains(&cp)
        || (0x4E00..=0x9FFF).contains(&cp)
        || (0xA000..=0xA4CF).contains(&cp)
        || (0xA960..=0xA97F).contains(&cp)
        || (0xAC00..=0xD7A3).contains(&cp)
        || (0xF900..=0xFAFF).contains(&cp)
        || (0xFE10..=0xFE19).contains(&cp)
        || (0xFE30..=0xFE6F).contains(&cp)
        || (0xFF00..=0xFF60).contains(&cp)
        || (0xFFE0..=0xFFE6).contains(&cp)
        || (0x1F300..=0x1F64F).contains(&cp)
        || (0x1F900..=0x1F9FF).contains(&cp)
        || (0x20000..=0x2FFFD).contains(&cp)
        || (0x30000..=0x3FFFD).contains(&cp);
    if wide {
        2
    } else {
        1
    }
}

/// 字符串在终端中的显示宽度
pub fn display_width(text: &str) -> usize {
    text.chars().map(char_width).sum()
}

/// 按显示宽度截断，超出部分以 `..` 收尾（不用省略号，避免"歧义宽度"字符错位）
pub fn truncate(text: &str, max: usize) -> String {
    if display_width(text) <= max {
        return text.to_string();
    }
    if max <= 2 {
        return ".".repeat(max);
    }
    let mut out = String::new();
    let mut width = 0usize;
    for c in text.chars() {
        let w = char_width(c);
        if width + w > max - 2 {
            break;
        }
        out.push(c);
        width += w;
    }
    out.push_str("..");
    out
}

/// 右补空格到指定显示宽度
pub fn pad_end(text: &str, width: usize) -> String {
    let mut out = text.to_string();
    let current = display_width(text);
    if current < width {
        out.push_str(&" ".repeat(width - current));
    }
    out
}

/// 按显示宽度折行（中英混排按字符边界断开，不切开半个汉字）
pub fn wrap(text: &str, width: usize) -> Vec<String> {
    let width = width.max(8);
    let mut lines = Vec::new();
    let mut current = String::new();
    let mut current_width = 0usize;
    for c in text.chars() {
        if c == '\n' {
            lines.push(std::mem::take(&mut current));
            current_width = 0;
            continue;
        }
        let w = char_width(c);
        if current_width + w > width {
            lines.push(std::mem::take(&mut current));
            current_width = 0;
        }
        current.push(c);
        current_width += w;
    }
    if !current.is_empty() {
        lines.push(current);
    }
    if lines.is_empty() {
        lines.push(String::new());
    }
    lines
}

// ---------------- 表格 ----------------

/// 极简空格对齐表格：列宽取各列最大显示宽度，表头下用 ASCII `-` 分隔
/// （`─` 属东亚歧义宽度字符，在 CJK 终端里可能占 2 列，会破坏对齐）
pub struct Table {
    headers: Vec<String>,
    rows: Vec<Vec<String>>,
    right_align: Vec<usize>,
    /// 每列最大显示宽度：课程名、备注这类长文本会撑爆终端宽度
    max_widths: Vec<Option<usize>>,
    indent: usize,
}

impl Table {
    pub fn new(headers: &[&str]) -> Self {
        Self {
            headers: headers.iter().map(|h| h.to_string()).collect(),
            rows: Vec::new(),
            right_align: Vec::new(),
            max_widths: vec![None; headers.len()],
            indent: 0,
        }
    }

    /// 指定右对齐的列序号（分数、学分等数值列）
    pub fn right_align(mut self, cols: &[usize]) -> Self {
        self.right_align = cols.to_vec();
        self
    }

    /// 限制某列最大显示宽度，超出部分用 `..` 截断
    pub fn max_width(mut self, col: usize, width: usize) -> Self {
        if let Some(slot) = self.max_widths.get_mut(col) {
            *slot = Some(width);
        }
        self
    }

    pub fn indent(mut self, spaces: usize) -> Self {
        self.indent = spaces;
        self
    }

    pub fn push(&mut self, row: Vec<String>) {
        self.rows.push(row);
    }

    fn cell(&self, row: &[String], index: usize) -> String {
        let raw = row.get(index).map(String::as_str).unwrap_or("");
        match self.max_widths.get(index).copied().flatten() {
            Some(limit) => truncate(raw, limit),
            None => raw.to_string(),
        }
    }

    pub fn render(&self, out: &mut Printer) {
        let cols = self.headers.len();
        let mut widths: Vec<usize> = self.headers.iter().map(|h| display_width(h)).collect();
        let rows: Vec<Vec<String>> = self
            .rows
            .iter()
            .map(|row| (0..cols).map(|index| self.cell(row, index)).collect())
            .collect();
        for row in &rows {
            for (index, cell) in row.iter().enumerate() {
                widths[index] = widths[index].max(display_width(cell));
            }
        }
        let indent = " ".repeat(self.indent);

        let header = self
            .headers
            .iter()
            .enumerate()
            .map(|(index, cell)| pad_end(&truncate(cell, widths[index]), widths[index]))
            .collect::<Vec<_>>()
            .join("  ");
        out.line(format!("{indent}{}", header.trim_end()).as_str());
        let separator = widths
            .iter()
            .map(|width| "-".repeat(*width))
            .collect::<Vec<_>>()
            .join("  ");
        out.line(format!("{indent}{separator}").as_str());

        for row in &rows {
            let line = (0..cols)
                .map(|index| {
                    let cell = row[index].as_str();
                    if self.right_align.contains(&index) {
                        let width = widths[index];
                        let fill = width.saturating_sub(display_width(cell));
                        format!("{}{}", " ".repeat(fill), cell)
                    } else {
                        pad_end(cell, widths[index])
                    }
                })
                .collect::<Vec<_>>()
                .join("  ");
            out.line(format!("{indent}{}", line.trim_end()).as_str());
        }
    }
}

/// 键值块：标签左对齐到固定宽度，值保持原样
pub fn kv(out: &mut Printer, pairs: &[(&str, String)], label_width: usize) {
    for (label, value) in pairs {
        out.line(&format!("  {}  {}", pad_end(label, label_width), value));
    }
}

/// ASCII 进度条（`#` 已完成、`-` 未完成），避免歧义宽度字符
pub fn progress_bar(earned: f64, required: f64, width: usize) -> String {
    if required <= 0.0 {
        return "-".repeat(width);
    }
    let ratio = (earned / required).clamp(0.0, 1.0);
    let filled = (ratio * width as f64).round() as usize;
    format!("{}{}", "#".repeat(filled.min(width)), "-".repeat(width - filled.min(width)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn captured(printer: Printer, buffer: Arc<Mutex<Vec<u8>>>) -> String {
        drop(printer);
        let bytes = buffer.lock().unwrap().clone();
        String::from_utf8(bytes).expect("输出应为 UTF-8")
    }

    #[test]
    fn display_width_counts_cjk_as_two_columns() {
        assert_eq!(display_width("abc"), 3);
        assert_eq!(display_width("及格"), 4);
        assert_eq!(display_width("成绩 90"), 7);
        assert_eq!(char_width('\u{301}'), 0, "组合符号不占列");
    }

    #[test]
    fn truncate_never_exceeds_target_width() {
        assert_eq!(truncate("高等数学", 10), "高等数学");
        let cut = truncate("高等数学A(1)", 7);
        assert!(display_width(&cut) <= 7, "{cut} 超宽");
        assert!(cut.ends_with(".."));
        assert_eq!(truncate("abcdef", 2), "..");
    }

    #[test]
    fn wrap_keeps_wide_chars_intact() {
        let lines = wrap("这是一段用于测试折行的中文内容", 10);
        assert!(lines.iter().all(|line| display_width(line) <= 10));
        assert_eq!(lines.concat(), "这是一段用于测试折行的中文内容");
    }

    /// 表格各级列宽按"显示宽度"对齐：中英混排时每行显示宽度必须相等
    #[test]
    fn table_pads_by_display_width() {
        let mut table = Table::new(&["课程", "学分"]).right_align(&[1]);
        table.push(vec!["高等数学".into(), "5".into()]);
        table.push(vec!["Math".into(), "1.5".into()]);

        let (mut out, buffer) = Printer::capture();
        table.render(&mut out);
        let text = captured(out, buffer);
        let widths: Vec<usize> = text.lines().map(display_width).collect();
        assert_eq!(widths[0], widths[1], "表头与分隔线宽度一致");
        assert_eq!(widths[1], widths[2], "分隔线与数据行宽度一致");
        assert_eq!(widths[2], widths[3], "数据行之间宽度一致");
    }

    #[test]
    fn progress_bar_is_ascii_and_clamped() {
        assert_eq!(progress_bar(5.0, 10.0, 10), "#####-----");
        assert_eq!(progress_bar(20.0, 10.0, 4), "####", "超额完成不溢出");
        assert_eq!(progress_bar(1.0, 0.0, 3), "---", "无要求时不下结论");
    }
}
