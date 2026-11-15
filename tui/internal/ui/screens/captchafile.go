package screens

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// 验证码兜底通道。
//
// 终端里画验证码依赖两件事：真彩色转义能正常解释、字体里有半块字符 ▀。
// 少数环境（老旧 conhost、把 NO_COLOR 打开的极简终端、不认识 Unicode 的
// 点阵字体）做不到，这时把原始图片交给系统看图器打开，用户仍然能拿到
// 验证码并完成登录——"终端无法登录"不该存在硬门槛。

// saveCaptcha 把原始验证码写成临时文件，返回路径。
//
// 固定文件名 + 覆盖写：避免每换一次验证码就在 temp 里堆一个文件；
// 扩展名跟随真实格式，系统看图器才不会认错。
func (s *Sync) saveCaptcha() (string, error) {
	if len(s.captchaRaw) == 0 {
		return "", errors.New("还没有取到验证码，按 c 重新获取")
	}
	ext := ".png"
	switch strings.ToLower(s.captchaFormat) {
	case "jpeg", "jpg":
		ext = ".jpg"
	case "gif":
		ext = ".gif"
	}
	path := filepath.Join(os.TempDir(), "betterhrbust-captcha"+ext)
	if err := os.WriteFile(path, s.captchaRaw, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// openCaptcha 保存验证码并用系统默认程序打开，返回文件路径。
func (s *Sync) openCaptcha() (string, error) {
	path, err := s.saveCaptcha()
	if err != nil {
		return "", err
	}
	return path, openPath(path)
}

// openPath 用系统默认程序打开文件：只 Start 不等待，
// 看图器是 GUI 进程，等它会卡住整个界面。
func openPath(path string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		// FileProtocolHandler 走 ShellExecute，等价于在资源管理器里双击
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", path)
	case "darwin":
		cmd = exec.Command("open", path)
	default:
		cmd = exec.Command("xdg-open", path)
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() { _ = cmd.Wait() }()
	return nil
}
