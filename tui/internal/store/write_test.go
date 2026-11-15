package store

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
)

func TestWriterMergesAndPreservesUnknownKeys(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, StorageFileName)
	if err := os.WriteFile(path, []byte(`{"keep":"1","overwrite":"old"}`), 0o600); err != nil {
		t.Fatal(err)
	}

	writer := NewWriter(dir)
	report, err := writer.Apply(Patch{
		Set:    map[string]string{"overwrite": "new", "added": "x"},
		Delete: []string{"missing"},
	})
	if err != nil {
		t.Fatalf("写回失败：%v", err)
	}
	if report.Written != 2 {
		t.Fatalf("写入计数错误：%d", report.Written)
	}
	if report.BridgePort != 0 {
		t.Fatalf("没有图形界面时不应有桥接：%d", report.BridgePort)
	}

	raw := readTestMap(t, path)
	if raw["keep"] != "1" {
		t.Fatalf("未涉及的键必须保留：%#v", raw)
	}
	if raw["overwrite"] != "new" || raw["added"] != "x" {
		t.Fatalf("写入结果错误：%#v", raw)
	}
	if _, exists := os.Stat(path + ".tmp"); !os.IsNotExist(exists) {
		t.Fatal("临时文件不应残留")
	}
}

func TestWriterDeletesKeys(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, StorageFileName)
	if err := os.WriteFile(path, []byte(`{"a":"1","b":"2"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	writer := NewWriter(dir)
	if _, err := writer.Apply(Patch{Delete: []string{"a"}}); err != nil {
		t.Fatal(err)
	}
	raw := readTestMap(t, path)
	if _, exists := raw["a"]; exists {
		t.Fatalf("键应被删除：%#v", raw)
	}
	if raw["b"] != "2" {
		t.Fatalf("其余键必须保留：%#v", raw)
	}
}

func TestWriterRefusesToOverwriteCorruptFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, StorageFileName)
	broken := []byte("{ not json")
	if err := os.WriteFile(path, broken, 0o600); err != nil {
		t.Fatal(err)
	}

	writer := NewWriter(dir)
	if _, err := writer.Apply(Patch{Set: map[string]string{"a": "1"}}); err == nil {
		t.Fatal("损坏文件必须报错，而不是覆盖现场")
	}
	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(current) != string(broken) {
		t.Fatal("损坏文件必须原样保留")
	}
}

func TestWriterBridgesToRunningGUI(t *testing.T) {
	dir := t.TempDir()

	var mu sync.Mutex
	written := map[string]string{}
	deleted := []string{}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__app/cli":
			_ = json.NewEncoder(w).Encode(map[string]string{"dataDir": dir})
		case "/__app/storage":
			mu.Lock()
			defer mu.Unlock()
			switch r.Method {
			case http.MethodPut:
				var payload struct {
					Key   string `json:"key"`
					Value string `json:"value"`
				}
				if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
					t.Errorf("桥接请求体不是 JSON：%v", err)
				}
				written[payload.Key] = payload.Value
			case http.MethodDelete:
				deleted = append(deleted, r.URL.Query().Get("key"))
			}
			_, _ = w.Write([]byte("ok"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	writer := NewWriter(dir)
	writer.ports = []int{testPort(t, server.URL)}

	report, err := writer.Apply(Patch{
		Set:    map[string]string{"better_hrbust_cache_scores": "[]"},
		Delete: []string{"better_hrbust_session_expired"},
	})
	if err != nil {
		t.Fatalf("写回失败：%v", err)
	}
	if report.BridgePort == 0 {
		t.Fatal("应识别到运行中的图形界面")
	}

	mu.Lock()
	defer mu.Unlock()
	if written["better_hrbust_cache_scores"] != "[]" {
		t.Fatalf("改动未推送给图形界面：%#v", written)
	}
	if len(deleted) != 1 || deleted[0] != "better_hrbust_session_expired" {
		t.Fatalf("删除未推送给图形界面：%#v", deleted)
	}
}

func TestDetectBridgeIgnoresOtherDataDir(t *testing.T) {
	dir := t.TempDir()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"dataDir": filepath.Join(dir, "other")})
	}))
	defer server.Close()

	if instance := detectBridge(dir, []int{testPort(t, server.URL)}); instance != nil {
		t.Fatal("数据目录不一致时不应桥接，否则会串数据")
	}
}

// readTestMap 读回缓存文件并解码。
func readTestMap(t *testing.T, path string) map[string]string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取缓存失败：%v", err)
	}
	var raw map[string]string
	if err := json.Unmarshal(content, &raw); err != nil {
		t.Fatalf("缓存不是合法 JSON：%v", err)
	}
	return raw
}

// testPort 从测试服务器地址里取端口。
func testPort(t *testing.T, rawURL string) int {
	t.Helper()
	parsed, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("解析测试地址失败：%v", err)
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil {
		t.Fatalf("测试地址缺少端口：%v", err)
	}
	return port
}
