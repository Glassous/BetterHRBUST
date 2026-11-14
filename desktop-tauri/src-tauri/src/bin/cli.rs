//! BetterHRBUSTCLI：命令行（控制台子系统）入口
//!
//! 为什么单独做成一个二进制，而不是给 GUI 主程序加子命令：
//! `BetterHRBUST.exe` 是 Windows GUI 子系统程序，在 cmd / PowerShell 中启动后
//! 不会被等待，也没有可用的控制台句柄，输出会与提示符交错、中文还可能乱码。
//! console 子系统的独立二进制才有标准终端语义（阻塞等待、管道、重定向、`$LASTEXITCODE`）。
//!
//! 全部实现位于库的 `cli` 模块：本地服务 `/__app/cli` 也复用同一套
//! 路径解析与登录态判定，保证设置页展示与终端结果一致。

fn main() {
    std::process::exit(betterhrbust_desktop_lib::cli::run());
}
