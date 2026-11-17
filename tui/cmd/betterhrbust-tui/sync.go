package main

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/campus"
	"github.com/Glassous/BetterHRBUST/tui/internal/config"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/store"
)

// syncTimeout 是命令行同步的整体上限（与交互界面一致）。
const syncTimeout = 120 * time.Second

// runSyncCommand 用已保存的会话来一次全量同步。
//
// 登录本身需要图片验证码，命令行做不了；但只要会话已经建立
// （终端或图形界面登过任意一次），这里就能把数据刷到最新，
// 供脚本与 AI 工具使用。
func runSyncCommand(service *query.Service, dataDir string, settings config.Config) int {
	syncService := buildSync(dataDir, settings)
	ctx, cancel := context.WithTimeout(context.Background(), syncTimeout)
	defer cancel()

	account := ""
	if service != nil && service.Snapshot != nil {
		account = service.Snapshot.Account()
	}
	result, err := syncService.Sync(ctx, campus.SyncOptions{Account: account})

	failures := result.Failures
	if failures == nil {
		failures = []string{}
	}
	payload := map[string]any{
		"command":  "sync",
		"ok":       err == nil && len(failures) == 0,
		"written":  result.Written,
		"failures": failures,
		"expired":  result.Expired,
		"bridged":  result.Report.BridgePort != 0,
	}
	if !result.CompletedAt.IsZero() {
		payload["completedAt"] = result.CompletedAt.Format(time.RFC3339)
	}
	if result.Report.StoragePath != "" {
		payload["storagePath"] = result.Report.StoragePath
	}
	if result.Report.BridgeError != "" {
		payload["bridgeError"] = result.Report.BridgeError
	}

	switch {
	case errors.Is(err, campus.ErrNoSession):
		payload["ok"] = false
		payload["error"] = map[string]string{
			"code":    "NEED_LOGIN",
			"message": "尚未登录教务系统：请启动交互界面后按 9 打开「登录与同步」完成登录",
		}
		emitJSON(payload)
		return store.ExitNeedLogin
	case errors.Is(err, campus.ErrSessionExpired):
		payload["ok"] = false
		payload["error"] = map[string]string{
			"code":    "SESSION_EXPIRED",
			"message": "教务会话已失效：请重新登录后再同步（交互界面按 9）",
		}
		emitJSON(payload)
		return store.ExitNeedLogin
	case err != nil:
		payload["ok"] = false
		payload["error"] = map[string]string{"code": "SYNC_FAILED", "message": err.Error()}
		emitJSON(payload)
		return store.ExitError
	}

	if len(failures) > 0 {
		payload["error"] = map[string]string{
			"code":    "PARTIAL_SYNC",
			"message": "部分数据同步失败：" + strings.Join(failures, "；"),
		}
	}
	emitJSON(payload)
	if result.Written == 0 && len(failures) > 0 {
		return store.ExitNoData
	}
	return store.ExitOK
}
