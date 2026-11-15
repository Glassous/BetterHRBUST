package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// MaxStorageBytes 是允许解析的 `storage.json` 上限。
//
// 真实缓存（四年成绩 + 五个学期课表 + 全部公告）约 1–2 MB；
// 32 MB 足以容纳任何合理数据，同时挡住"文件被替换成别的东西"这种事故。
const MaxStorageBytes = 32 << 20

// readRetryDelay 是共享冲突后的重试间隔。
// 图形界面写文件是 tmp + rename，理论上不会读到半截文件；
// 但 Windows 上杀毒软件短暂锁住文件很常见，重试一次能省掉一整类误报。
const readRetryDelay = 50 * time.Millisecond

// ReadSnapshot 读取数据目录下的 `storage.json`，返回只读快照。
//
// 契约（与 Rust 的 `storage::read_snapshot` 一致，且更宽容）：
//   - 文件不存在 → 空快照，不视为错误（未登录不等于文件坏了）；
//   - 文件存在但读不出（权限 / 共享冲突）→ 记 LoadError，重试一次；
//   - 内容为空或只有 BOM → 空快照；
//   - 超过 MaxStorageBytes → 记 LoadError；
//   - JSON 损坏 → 记 LoadError，**绝不重命名、删除或重建文件**。
func ReadSnapshot(dir string) *model.Snapshot {
	path := filepath.Join(dir, "storage.json")
	info, statErr := os.Stat(path)
	exists := statErr == nil && !info.IsDir()
	var size int64
	if exists {
		size = info.Size()
	}
	if exists && size > MaxStorageBytes {
		return model.New(dir, path, true, fmt.Sprintf(
			"本地数据文件过大（%s，超过 %s 上限）：请确认该文件确实是 BetterHRBUST 的缓存，或到图形界面刷新一次",
			humanSize(size), humanSize(MaxStorageBytes),
		), size, nil)
	}

	bytesRead, err := readWithRetry(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			// 未登录：目录和文件都还没建立。这是正常状态。
			return model.New(dir, path, false, "", 0, nil)
		}
		return model.New(dir, path, exists, fmt.Sprintf(
			"无法读取本地数据文件（%v）。请确认文件权限，或在 BetterHRBUST 图形界面刷新一次",
			err,
		), size, nil)
	}

	text := string(bytesRead)
	text = strings.TrimPrefix(text, "\ufeff")
	text = strings.TrimSpace(text)
	if text == "" {
		return model.New(dir, path, true, "", size, nil)
	}

	raw := map[string]string{}
	if err := json.Unmarshal([]byte(text), &raw); err != nil {
		return model.New(dir, path, true, fmt.Sprintf(
			"无法解析本地数据文件（%v）。请在 BetterHRBUST 图形界面刷新一次以重建缓存", err,
		), size, nil)
	}
	return model.New(dir, path, true, "", size, raw)
}

// readWithRetry 读文件，遇到共享冲突之类的临时错误重试一次。
func readWithRetry(path string) ([]byte, error) {
	content, err := os.ReadFile(path)
	if err == nil || errors.Is(err, fs.ErrNotExist) {
		return content, err
	}
	time.Sleep(readRetryDelay)
	return os.ReadFile(path)
}

// SidecarFiles 返回可能存在的残留文件（诊断用，只报告不处理）。
//
// `storage.json.tmp` 是原子写的中间文件，`storage.json.corrupt` 是图形界面
// 解析失败后留下的备份。终端界面**不得**清理它们：那是图形界面的职责，
// 而且 `.corrupt` 往往是用户找回数据的唯一线索。
func SidecarFiles(dir string) []string {
	var found []string
	for _, name := range []string{"storage.json.tmp", "storage.json.corrupt"} {
		path := filepath.Join(dir, name)
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			found = append(found, fmt.Sprintf("%s（%s）", name, humanSize(info.Size())))
		}
	}
	return found
}

// EncodeSnapshot 把键值表重新编码成 `storage.json` 的字节形式，仅用于测试与诊断导出。
func EncodeSnapshot(raw map[string]string) ([]byte, error) {
	buffer := &bytes.Buffer{}
	encoder := json.NewEncoder(buffer)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(raw); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

// humanSize 把字节数转成人类可读形式。
func humanSize(size int64) string {
	const unit = 1024.0
	value := float64(size)
	switch {
	case value < unit:
		return fmt.Sprintf("%d B", size)
	case value < unit*unit:
		return fmt.Sprintf("%.1f KB", value/unit)
	default:
		return fmt.Sprintf("%.1f MB", value/(unit*unit))
	}
}
