// Package version 提供终端界面的版本号。
//
// 版本号在链接期通过 -ldflags 注入（见 Makefile 与 build-tui 工作流）：
//
//	go build -ldflags "-X github.com/Glassous/BetterHRBUST/tui/internal/version.Version=1.5.0"
//
// 未注入时回退为 "dev"，`--version` 与设置页据此区分正式构建与本地构建。
package version

// Version 是语义化版本号；正式构建由构建脚本覆盖。
var Version = "dev"

// String 返回可直接展示的版本串。
func String() string {
	return Version
}
