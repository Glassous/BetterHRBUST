package ui

import (
	"os"
	"time"

	tea "charm.land/bubbletea/v2"
)

// CacheWatchInterval 是检查本地缓存是否被外部改写的间隔。
//
// 图形界面刷新完会原子替换 storage.json；终端界面不该在用户翻页时
// 突然整体换数据，因此这里只**提示**，由用户按 ctrl+r 决定何时重读。
//
// 是变量而不是常量：测试里调成纳秒级，避免每个用例都真的等 5 秒。
var CacheWatchInterval = 5 * time.Second

// watchCache 返回一次"检查缓存修改时间"的命令。
func watchCache(path string) tea.Cmd {
	return func() tea.Msg {
		info, err := os.Stat(path)
		if err != nil {
			return nil
		}
		return CacheChangedMsg{ModTime: info.ModTime()}
	}
}

// scheduleCacheWatch 在若干秒后再检查一次。
func scheduleCacheWatch() tea.Cmd {
	return tea.Tick(CacheWatchInterval, func(time.Time) tea.Msg { return CacheTickMsg{} })
}
