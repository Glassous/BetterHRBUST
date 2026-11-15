package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// desktopAIConfigFile 与桌面端 Rust 侧 ai.rs 的 CONFIG_FILE 保持一致。
// 文件位于教务数据目录（与 storage.json 同目录），由图形界面写入。
const desktopAIConfigFile = "ai-config.json"

// OverlayDesktopAI 用桌面端（图形界面）保存的 AI 配置覆盖 LLM 段。
//
// 目的：三端共享同一份 AI 配置——在设置页填一次接口地址、模型与 API Key，
// 终端界面不必再抄一遍。
//
// 规则：
//   - 桌面配置不存在 / 解析失败 / 未启用 → 保持终端自己的配置不变（返回 false）；
//   - 桌面配置已启用 → 整体替换 LLM 段，包括 enabled 与 consent：
//     用户已经对同一服务商确认过数据出网，终端再问一次只是重复劳动。
//
// 该函数**只读**：终端界面不写桌面的 ai-config.json（那是图形界面拥有的文件，
// 两个进程同时写会互相覆盖，桌面进程的内存镜像还会把终端写入直接盖掉）。
func OverlayDesktopAI(settings Config, dataDir string) (Config, bool) {
	dir := strings.TrimSpace(dataDir)
	if dir == "" {
		return settings, false
	}
	bytes, err := os.ReadFile(filepath.Join(dir, desktopAIConfigFile))
	if err != nil {
		return settings, false
	}
	var desktop LLMSettings
	if err := json.Unmarshal(bytes, &desktop); err != nil {
		return settings, false
	}
	if !desktop.Enabled {
		return settings, false
	}
	// api_key_env 桌面端没有这个概念，保留终端既有的环境变量名
	if strings.TrimSpace(desktop.APIKeyEnv) == "" {
		desktop.APIKeyEnv = settings.LLM.APIKeyEnv
	}
	settings.LLM = desktop
	return settings.Normalized(), true
}
