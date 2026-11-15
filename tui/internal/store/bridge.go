package store

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// 图形界面桥。
//
// 图形界面把缓存镜像放在内存里（启动时从 `storage.json` 水合），
// 之后每次页面写入都会把整张表重新落盘。如果终端在它运行期间直接改文件，
// 图形界面的下一次写入就会把终端的改动覆盖掉。
//
// 解决办法不是加锁，而是**把改动推给它的本地服务**：`/__app/storage`
// 会同时更新内存镜像与磁盘文件，于是两端始终一致，用户完全无感。

const (
	// bridgePortBase 是图形界面本地服务的起始端口（与 server.rs 一致）。
	bridgePortBase = 1950
	// bridgePortTries 是顺延次数（与 server.rs 一致）。
	bridgePortTries = 10
	// bridgeProbeTimeout 是探测单个端口的超时。
	// 回环端口被防火墙丢弃时连接不会立刻失败，超时必须足够短：
	// 图形界面没运行时这十次探测不能拖慢一次同步。
	bridgeProbeTimeout = 250 * time.Millisecond
	// bridgeRequestTimeout 是单次推送的超时。
	bridgeRequestTimeout = 3 * time.Second
)

// bridge 是一次探测到的图形界面本地服务。
type bridge struct {
	port   int
	client *http.Client
}

// cliInfo 是 `/__app/cli` 响应里我们用得到的那部分。
type cliInfo struct {
	DataDir string `json:"dataDir"`
}

// defaultBridgePorts 返回图形界面的候选端口（与 server.rs 的顺延策略一致）。
func defaultBridgePorts() []int {
	ports := make([]int, 0, bridgePortTries)
	for offset := 0; offset < bridgePortTries; offset++ {
		ports = append(ports, bridgePortBase+offset)
	}
	return ports
}

// detectBridge 在候选端口上寻找指向同一数据目录的图形界面服务。
//
// ports 为 nil 时使用生产端口段；测试可以传入临时端口。
func detectBridge(dir string, ports []int) *bridge {
	if len(ports) == 0 {
		ports = defaultBridgePorts()
	}
	target := normalizeDir(dir)
	for _, port := range ports {
		candidate := &bridge{
			port:   port,
			client: &http.Client{Timeout: bridgeProbeTimeout},
		}
		info, err := candidate.probe()
		if err != nil {
			continue
		}
		if normalizeDir(info.DataDir) != target {
			// 端口上跑的是另一份图形界面（便携模式 / 多数据目录），不要串数据
			continue
		}
		candidate.client = &http.Client{Timeout: bridgeRequestTimeout}
		return candidate
	}
	return nil
}

// probe 读取图形界面的命令行状态。
func (b *bridge) probe() (cliInfo, error) {
	response, err := b.client.Get(b.endpoint("/__app/cli"))
	if err != nil {
		return cliInfo{}, err
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		return cliInfo{}, fmt.Errorf("cli 端点返回 %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return cliInfo{}, err
	}
	var info cliInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return cliInfo{}, err
	}
	if info.DataDir == "" {
		return cliInfo{}, fmt.Errorf("cli 状态缺少数据目录")
	}
	return info, nil
}

// push 把一次改动推送给图形界面。
func (b *bridge) push(patch Patch) error {
	for key, value := range patch.Set {
		payload, err := json.Marshal(map[string]string{"key": key, "value": value})
		if err != nil {
			return err
		}
		if err := b.send(http.MethodPut, "/__app/storage", payload); err != nil {
			return err
		}
	}
	for _, key := range patch.Delete {
		if err := b.send(http.MethodDelete, "/__app/storage?key="+url.QueryEscape(key), nil); err != nil {
			return err
		}
	}
	return nil
}

// send 发一次桥接请求。
func (b *bridge) send(method, path string, body []byte) error {
	request, err := http.NewRequest(method, b.endpoint(path), bytes.NewReader(body))
	if err != nil {
		return err
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := b.client.Do(request)
	if err != nil {
		return err
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode/100 != 2 {
		return fmt.Errorf("图形界面拒绝了改动（HTTP %d）", response.StatusCode)
	}
	return nil
}

// endpoint 拼出本地服务地址。
func (b *bridge) endpoint(path string) string {
	return fmt.Sprintf("http://127.0.0.1:%d%s", b.port, path)
}

// normalizeDir 把路径归一化后用于比较。
//
// Windows 上盘符与路径分隔符大小写不敏感，Linux 上则相反，
// 因此只在该平台折叠大小写。
func normalizeDir(dir string) string {
	if dir == "" {
		return ""
	}
	cleaned := filepath.Clean(dir)
	cleaned = strings.TrimSuffix(cleaned, string(filepath.Separator))
	if runtime.GOOS == "windows" {
		return strings.ToLower(cleaned)
	}
	return cleaned
}
