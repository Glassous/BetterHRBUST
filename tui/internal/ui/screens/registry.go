package screens

import "github.com/Glassous/BetterHRBUST/tui/internal/ui"

// Registry 返回全部页面的注册表。
//
// 顺序同时决定侧栏顺序与数字键的映射（顺序必须与 ui.KeyMap.NavBindings 一致）：
// 1 概览 · 2 课表 · 3 成绩 · 4 考试 · 5 培养方案
// 6 公告 · 7 学籍 · 8 课程 · 0 诊断 · , 设置
//
// 新增页面只需在这里加一行——外壳的导航、命令面板、侧栏、页脚都会自动带上它。
func Registry() []ui.Entry {
	return []ui.Entry{
		{
			ID: ui.ScreenDashboard, Title: "概览", Key: "1",
			Aliases: []string{"dashboard", "summary", "home", "gaiklan", "概览"},
			Hint:    "今日课程 · 学业指标 · 近期考试",
			New:     NewDashboard,
		},
		{
			ID: ui.ScreenTimetable, Title: "课程表", Key: "2",
			Aliases: []string{"timetable", "schedule", "kebiao", "课表"},
			Hint:    "按周查看 · 大节/小节 · 未安排课程",
			New:     NewTimetable,
		},
		{
			ID: ui.ScreenScores, Title: "成绩与 GPA", Key: "3",
			Aliases: []string{"scores", "score", "gpa", "chengji", "成绩"},
			Hint:    "筛选 · 合并重修 · 学位与推免自检",
			New:     NewScores,
		},
		{
			ID: ui.ScreenExams, Title: "考试日程", Key: "4",
			Aliases: []string{"exams", "exam", "kaoshi", "考试"},
			Hint:    "考场 · 倒计时 · 时间窗口",
			New:     NewExams,
		},
		{
			ID: ui.ScreenPlan, Title: "培养方案", Key: "5",
			Aliases: []string{"plan", "program", "peiyang", "学分"},
			Hint:    "课组学分进度 · 候选课程",
			New:     NewPlan,
		},
		{
			ID: ui.ScreenNotices, Title: "教学公告", Key: "6",
			Aliases: []string{"notices", "notice", "gonggao", "公告"},
			Hint:    "按周查看 · 关键字搜索 · 全文",
			New:     NewNotices,
		},
		{
			ID: ui.ScreenProfile, Title: "学籍档案", Key: "7",
			Aliases: []string{"profile", "xueji", "学籍"},
			Hint:    "学籍信息（证件号等固定打码）· 异动记录",
			New:     NewProfile,
		},
		{
			ID: ui.ScreenCourses, Title: "课程名录", Key: "8",
			Aliases: []string{"courses", "course", "kecheng", "课程"},
			Hint:    "本学期修读课程 · 搜索",
			New:     NewCourses,
		},
		{
			ID: ui.ScreenDoctor, Title: "诊断", Key: "0",
			Aliases: []string{"doctor", "check", "zhenduan", "检查"},
			Hint:    "数据完整性 · 路径 · 缓存键",
			New:     NewDoctor,
		},
		{
			ID: ui.ScreenSettings, Title: "设置", Key: ",",
			Aliases: []string{"settings", "config", "shezhi", "配置"},
			Hint:    "主题 · 界面 · AI 对话配置",
			New:     NewSettings,
		},
	}
}
