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
)

// 缓存写回。
//
// 到这里之前，终端界面一直是 `storage.json` 的纯读者；接入"在终端里登录并同步"
// 之后它必须成为写者，规则如下：
//
//  1. **合并写**：只改自己负责的键，其余键（图形界面写入的、将来新增的）原样保留；
//  2. **原子替换**：先写 `storage.json.tmp` 再改名，与图形界面的写盘方式一致，
//     崩溃不会留下半截文件；
//  3. **不碰损坏文件**：文件存在但解析不了时报错让人去修，而不是覆盖掉现场；
//  4. **同步正在运行的图形界面**：若本地服务在线（127.0.0.1:1950…），
//     把改动同时推给它的 `/__app/storage` 端点，图形界面的内存镜像因此不会
//     在下一次写入时把我们刚写的数据覆盖掉。

// StorageFileName 是共享缓存文件名。
const StorageFileName = "storage.json"

// Patch 是一次合并写：Set 写入、Delete 删除。
type Patch struct {
	Set    map[string]string
	Delete []string
}

// Empty 表示没有任何改动。
func (p Patch) Empty() bool { return len(p.Set) == 0 && len(p.Delete) == 0 }

// WriteReport 是写回结果。
type WriteReport struct {
	// Written 是写入 / 删除的键数。
	Written int
	// Path 是缓存文件路径。
	Path string
	// BridgePort 非 0 表示改动已推送给运行中的图形界面。
	BridgePort int
	// BridgeError 非空表示推送图形界面失败（文件本身已写好）。
	BridgeError string
}

// Writer 是 `storage.json` 的合并写实现。
type Writer struct {
	dir string
	// ports 是图形界面本地服务的候选端口；nil 表示使用生产端口段。
	ports []int
	// bridge 缓存一次探测结果，避免每次同步都扫九个端口。
	bridge    *bridge
	bridgedAt time.Time
}

// NewWriter 构造写入器。
func NewWriter(dir string) *Writer { return &Writer{dir: dir} }

// Apply 把改动合并写回缓存，并在图形界面在线时同步给它。
func (w *Writer) Apply(patch Patch) (WriteReport, error) {
	path := filepath.Join(w.dir, StorageFileName)
	report := WriteReport{Path: path}
	if patch.Empty() {
		return report, nil
	}

	raw, err := readRawMap(path)
	if err != nil {
		return report, err
	}
	for key, value := range patch.Set {
		raw[key] = value
		report.Written++
	}
	for _, key := range patch.Delete {
		if _, exists := raw[key]; exists {
			delete(raw, key)
			report.Written++
		}
	}

	if err := writeRawMap(path, raw); err != nil {
		return report, err
	}

	// 图形界面在线时把改动推过去：它持有内存镜像，否则下一次写入会把
	// 终端刚写的数据覆盖掉。
	if bridged := w.bridgeFor(); bridged != nil {
		report.BridgePort = bridged.port
		if err := bridged.push(patch); err != nil {
			report.BridgeError = err.Error()
		}
	}
	return report, nil
}

// bridgeFor 返回可用的图形界面桥，未运行时返回 nil。
//
// 结果缓存 10 秒：一次同步会调用 Apply 一次，但连续手动刷新时
// 没必要每次都扫一遍端口。
func (w *Writer) bridgeFor() *bridge {
	if w.bridge != nil && time.Since(w.bridgedAt) < 10*time.Second {
		return w.bridge
	}
	w.bridge = detectBridge(w.dir, w.ports)
	w.bridgedAt = time.Now()
	if w.bridge == nil {
		// 明确记下"这次没有"，避免 nil 判定被缓存逻辑绕过
		w.bridgedAt = time.Time{}
	}
	return w.bridge
}

// readRawMap 读取缓存文件的键值表。
//
// 文件不存在视为空表；存在但解析失败返回错误（绝不覆盖现场）。
func readRawMap(path string) (map[string]string, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return map[string]string{}, nil
		}
		return nil, fmt.Errorf("无法读取本地缓存：%w", err)
	}
	text := strings.TrimSpace(strings.TrimPrefix(string(content), "\ufeff"))
	if text == "" {
		return map[string]string{}, nil
	}
	var raw map[string]string
	if err := json.Unmarshal([]byte(text), &raw); err != nil {
		return nil, fmt.Errorf(
			"本地缓存无法解析（%w）：为避免覆盖现场，本次未写回。请先在图形界面刷新一次以重建缓存", err)
	}
	if raw == nil {
		raw = map[string]string{}
	}
	return raw, nil
}

// writeRawMap 原子写回缓存文件。
func writeRawMap(path string, raw map[string]string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("无法创建数据目录：%w", err)
	}
	buffer := &bytes.Buffer{}
	encoder := json.NewEncoder(buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(raw); err != nil {
		return fmt.Errorf("无法编码本地缓存：%w", err)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, buffer.Bytes(), 0o600); err != nil {
		return fmt.Errorf("无法写入本地缓存：%w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("无法替换本地缓存：%w", err)
	}
	return nil
}
