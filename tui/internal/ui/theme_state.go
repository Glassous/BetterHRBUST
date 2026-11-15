package ui

import (
	"sync"

	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// ThemeState 是外壳与页面共享的当前主题。
//
// 用持有者而不是把主题值塞进 Deps：主题会随 ctrl+t、终端背景色探测、
// 配置保存而变，值拷贝会让页面停留在旧配色上。加锁是因为抽屉的流式
// 渲染可能在后台 goroutine 里读取。
type ThemeState struct {
	mu    sync.RWMutex
	theme theme.Theme
}

// NewThemeState 构造主题持有者。
func NewThemeState(initial theme.Theme) *ThemeState {
	return &ThemeState{theme: initial}
}

// Get 返回当前主题。
func (t *ThemeState) Get() theme.Theme {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.theme
}

// Set 替换当前主题。
func (t *ThemeState) Set(next theme.Theme) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.theme = next
}
