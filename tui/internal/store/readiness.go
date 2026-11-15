package store

import "github.com/Glassous/BetterHRBUST/tui/internal/model"

// 退出码契约（与 `BetterHRBUSTCLI` 完全一致，脚本与 AI 工具据此分支）。
const (
	// ExitOK 表示正常。
	ExitOK = 0
	// ExitError 表示参数错误、内部错误，或 `check --strict` 发现注意项。
	ExitError = 1
	// ExitNeedLogin 表示未登录且没有任何本地数据。
	ExitNeedLogin = 2
	// ExitNoData 表示存储不可用，或已登录但缓存为空。
	ExitNoData = 3
)

// Kind 是就绪状态的三态判别。
type Kind int

const (
	// KindReady 表示可以查询。
	KindReady Kind = iota
	// KindNeedLogin 表示未登录且没有本地数据，必须回图形界面登录。
	KindNeedLogin
	// KindNoData 表示存储损坏或已登录但缓存为空。
	KindNoData
)

// noDataMessage 是「已登录但缓存为空」的提示，与 Rust 命令行逐字一致。
const noDataMessage = "已登录但本地还没有缓存数据：请打开 BetterHRBUST 图形界面，在顶栏点击「刷新」同步一次"

// needLoginMessage 是「未登录且无数据」的提示，与 Rust 命令行逐字一致。
const needLoginMessage = "未检测到登录状态，也没有任何本地教务数据。" +
	"登录需要学号、密码与图片验证码，终端无法完成，请先打开" +
	"BetterHRBUST 图形界面登录一次，再回到终端查询。"

// Readiness 描述当前能否查询，以及不能查询时的原因与建议。
type Readiness struct {
	Kind Kind
	// Offline 为真表示已无登录态，只有本地缓存可供查询。
	Offline bool
	// Message 在 Kind != KindReady 时给出可直接展示的中文说明。
	Message string
}

// Evaluate 依据快照判定就绪状态。
//
// 判定顺序与前端 `hasCachedData`、Rust `Store::readiness` 一致：
// 先看读盘错误，再看登录态与数据量的四种组合。
func Evaluate(snapshot *model.Snapshot) Readiness {
	if snapshot.LoadError != "" {
		return Readiness{Kind: KindNoData, Message: snapshot.LoadError}
	}
	hasData := snapshot.HasData()
	switch {
	case snapshot.LoggedIn() && hasData:
		return Readiness{Kind: KindReady}
	case snapshot.LoggedIn() && !hasData:
		return Readiness{Kind: KindNoData, Message: noDataMessage}
	case !snapshot.LoggedIn() && hasData:
		return Readiness{Kind: KindReady, Offline: true}
	default:
		return Readiness{Kind: KindNeedLogin, Message: needLoginMessage}
	}
}

// OK 表示可以执行数据查询。
func (r Readiness) OK() bool { return r.Kind == KindReady }

// ExitCode 返回该状态对应的进程退出码。
func (r Readiness) ExitCode() int {
	switch r.Kind {
	case KindReady:
		return ExitOK
	case KindNeedLogin:
		return ExitNeedLogin
	default:
		return ExitNoData
	}
}

// ErrorCode 返回 `--json` 失败结构里的错误码，与 Rust 命令行一致。
func (r Readiness) ErrorCode() string {
	switch r.Kind {
	case KindNeedLogin:
		return "NEED_GUI_LOGIN"
	case KindNoData:
		return "NO_LOCAL_DATA"
	default:
		return ""
	}
}

// Code 返回机器可读的状态码，供 `--json` 与 LLM 工具使用。
func (r Readiness) Code() string {
	switch r.Kind {
	case KindReady:
		return "ready"
	case KindNeedLogin:
		return "needLogin"
	default:
		return "noData"
	}
}

// Label 返回给 UI 用的短标签。
func (r Readiness) Label() string {
	switch {
	case r.Kind == KindReady && r.Offline:
		return "离线缓存"
	case r.Kind == KindReady:
		return "已登录"
	case r.Kind == KindNeedLogin:
		return "未登录"
	default:
		return "无缓存数据"
	}
}
