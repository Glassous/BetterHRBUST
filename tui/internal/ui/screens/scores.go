package screens

import (
	"fmt"
	"sort"
	"strings"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
	"github.com/Glassous/BetterHRBUST/tui/internal/model"
	"github.com/Glassous/BetterHRBUST/tui/internal/query"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/components"
	"github.com/Glassous/BetterHRBUST/tui/internal/ui/theme"
)

// scoresPane 是成绩页的面板。
type scoresPane int

const (
	paneScoreList scoresPane = iota
	paneScoreDetail
	scoresPaneCount
)

// propertyCycle 是 `p` 键循环的属性筛选顺序。
var propertyCycle = []string{"全部", "必修", "限选", "任选", "其他"}

// Scores 是成绩页：指标带 + 列表 / 详情双栏。
type Scores struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.ScoresData
	win   components.Window
	focus components.Focus

	merged   bool
	failed   bool
	property string
	search   searchBox

	keys struct {
		Merged   key.Binding
		Failed   key.Binding
		Property key.Binding
		Search   key.Binding
		Next     key.Binding
		Prev     key.Binding
		Open     key.Binding
		Up       key.Binding
		Down     key.Binding
	}
}

// NewScores 构造成绩页。
func NewScores(deps ui.Deps) ui.Screen {
	screen := &Scores{deps: deps, merged: true, property: "全部"}
	screen.ScreenID = ui.ScreenScores
	screen.ScreenTitle = "成绩与 GPA"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(scoresPaneCount))
	screen.keys.Merged = key.NewBinding(key.WithKeys("m"), key.WithHelp("m", "合并/原始"))
	screen.keys.Failed = key.NewBinding(key.WithKeys("f"), key.WithHelp("f", "只看挂科"))
	screen.keys.Property = key.NewBinding(key.WithKeys("p"), key.WithHelp("p", "属性"))
	screen.keys.Search = key.NewBinding(key.WithKeys("/"), key.WithHelp("/", "搜索"))
	screen.keys.Next = key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "切换面板"))
	screen.keys.Prev = key.NewBinding(key.WithKeys("shift+tab"), key.WithHelp("shift+tab", "上一面板"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦详情"))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"))
	return screen
}

// Init 加载成绩。
func (s *Scores) Init() tea.Cmd { return s.load() }

// load 按当前筛选条件查询。
func (s *Scores) load() tea.Cmd {
	options := query.Options{Failed: s.failed, Merged: s.merged}
	if s.property != "" && s.property != "全部" {
		if property, ok := academic.PropertyFilter(s.property); ok {
			options.Property = &property
		}
	}
	if text := strings.TrimSpace(s.search.text); text != "" {
		options.Course = text
	}
	return loadCmd(s.deps, ui.ScreenScores, func(service *query.Service) any {
		return service.Scores(options)
	})
}

// Update 处理消息。
func (s *Scores) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenScores {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		s.data, _ = typed.Data.(query.ScoresData)
		total := s.rowCount()
		s.state.Empty = total == 0
		s.state.EmptyText = s.emptyText()
		s.win.Clamp(total)
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// emptyText 给出空态原因。
func (s *Scores) emptyText() string {
	switch {
	case s.search.text != "":
		return "没有匹配「" + s.search.text + "」的课程"
	case s.failed:
		return "没有挂科记录"
	case s.property != "全部":
		return "没有该属性的课程"
	default:
		return "本地缓存里没有成绩：请在图形界面刷新一次"
	}
}

// handleKey 处理页面按键。
func (s *Scores) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	if s.search.Active() {
		switch msg.String() {
		case "esc":
			s.search.Clear()
			return s, s.load()
		case "enter":
			s.search.Close()
			return s, s.load()
		case "backspace":
			s.search.Backspace()
			return s, nil
		}
		if text := msg.String(); len([]rune(text)) == 1 {
			s.search.Push([]rune(text)[0])
		}
		return s, nil
	}

	switch {
	case key.Matches(msg, s.keys.Merged):
		s.merged = !s.merged
		s.win.Home(s.rowCount())
		return s, nil
	case key.Matches(msg, s.keys.Failed):
		s.failed = !s.failed
		return s, s.load()
	case key.Matches(msg, s.keys.Property):
		s.property = nextInCycle(propertyCycle, s.property)
		return s, s.load()
	case key.Matches(msg, s.keys.Search):
		s.search.Open("搜索课程")
		return s, nil
	case key.Matches(msg, s.keys.Open):
		s.focus.Set(int(paneScoreDetail))
		return s, nil
	}

	// 焦点在列表面板时方向键移动光标。
	if s.focus.Is(int(paneScoreList)) {
		switch {
		case key.Matches(msg, s.keys.Up):
			s.win.Move(-1, s.rowCount())
		case key.Matches(msg, s.keys.Down):
			s.win.Move(1, s.rowCount())
		}
	}
	return s, nil
}

// WantsInput 在搜索时接管键盘。
func (s *Scores) WantsInput() bool { return s.search.Active() }

// CycleFocus 实现 ui.Focusable。
func (s *Scores) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(scoresPaneCount)) % int(scoresPaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Scores) FocusCount() int { return int(scoresPaneCount) }

// Keys 返回页面按键。
func (s *Scores) Keys() []key.Binding {
	return []key.Binding{
		s.keys.Merged, s.keys.Failed, s.keys.Property,
		s.keys.Search, s.keys.Open,
	}
}

// rowCount 返回当前视图的行数。
func (s *Scores) rowCount() int {
	if s.merged {
		return len(s.data.Courses)
	}
	return len(s.data.Scores)
}

// View 渲染成绩页。
func (s *Scores) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取成绩…"); handled {
		return content
	}

	// 指标带：两行指标块 + 一行趋势。9 行正好装下，少一行趋势会被裁掉。
	stripHeight := 9
	if s.Height < 30 {
		stripHeight = 7
	}
	if s.Height-stripHeight < 6 {
		stripHeight = 5
	}
	strip := s.renderStrip(th, s.Width, stripHeight)

	bodyHeight := s.Height - stripHeight - 1
	if bodyHeight < 4 {
		bodyHeight = 4
	}

	if s.Width < 64 {
		// 窄屏不分栏：列表在上，详情在下。
		detailHeight := minInt(8, bodyHeight/2)
		listHeight := bodyHeight - detailHeight
		return strings.Join([]string{
			strip,
			s.renderList(th, s.Width, listHeight),
			s.renderDetail(th, s.Width, detailHeight),
		}, "\n")
	}

	widths := components.ColumnWidths(s.Width-1, []int{5, 3})
	right := s.renderRight(th, widths[1], bodyHeight)
	body := components.SplitHGap(bodyHeight, 1,
		components.Part{Width: widths[0], Content: s.renderList(th, widths[0], bodyHeight)},
		components.Part{Width: widths[1], Content: right},
	)
	return strings.Join([]string{strip, body}, "\n")
}

// renderStrip 渲染顶部指标带。
func (s *Scores) renderStrip(th theme.Theme, width, height int) string {
	stats := s.data.Stats
	inner := maxInt(24, width-4)
	columns := components.ColumnWidths(inner, []int{1, 1, 1, 1})

	cellHeight := 2
	if height >= 7 {
		cellHeight = 3
	}
	cell := func(label, value, detail, level string) string {
		return components.RenderMiniStat(th, components.MiniStat{
			Label: label, Value: value, Detail: detail, Level: level,
		}, columns[0], cellHeight)
	}

	row1 := components.SplitH(cellHeight,
		components.Part{Width: columns[0], Content: cell("平均学分绩点", academic.FormatGPA(stats.GPA), "必修课口径", "")},
		components.Part{Width: columns[1], Content: cell("加权平均分", academic.FormatGPA(stats.WeightedAvg), "学分加权", "")},
		components.Part{Width: columns[2], Content: cell("已获学分",
			academic.FormatCredits(stats.EarnedCredits)+"/"+academic.FormatCredits(stats.TotalCredits),
			fmt.Sprintf("%d%%", int(stats.EarnedRatio*100+0.5)), "")},
		components.Part{Width: columns[3], Content: cell("挂科",
			fmt.Sprintf("%d 门", stats.FailedCount), academic.FormatCredits(stats.FailedCredits)+" 学分",
			warnIf(stats.FailedCount > 0))},
	)
	row2 := components.SplitH(cellHeight,
		components.Part{Width: columns[0], Content: cell("学位绩点",
			academic.FormatGPA(stats.Degree.GPA), qualify(stats.Degree.Qualified), okIf(stats.Degree.Qualified))},
		components.Part{Width: columns[1], Content: cell("推免自检",
			qualify(stats.Recommend.Qualified),
			fmt.Sprintf("重修 %d/%d", stats.Recommend.RetakeCount, stats.Recommend.RetakeLimit),
			okIf(stats.Recommend.Qualified))},
		components.Part{Width: columns[2], Content: cell("风险预警", stats.Risk.Label, "必修挂科学分", riskLevel(stats.Risk.Level))},
		components.Part{Width: columns[3], Content: cell("课程数",
			fmt.Sprintf("%d 门", stats.CourseCount), fmt.Sprintf("原始 %d 条", stats.RawCourseCount), "")},
	)

	body := row1 + "\n" + row2
	if height >= 7 {
		series := s.semesterSeries()
		if line := s.renderTrend(th, series, inner); line != "" {
			body += "\n" + line
		}
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Scores,
		Title:    "学业指标",
		Subtitle: fmt.Sprintf("%d 门必修 · 优秀率 %d%%", stats.CourseCount, stats.ExcellentRate),
		Body:     body,
		Level:    riskLevel(stats.Risk.Level),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// semesterPoint 是一个学期的绩点。
type semesterPoint struct {
	Label string
	GPA   float64
	// Order 是学期排序键，用于稳定升序。
	Order int
}

// semesterSeries 按学期聚合必修课绩点，返回时间升序序列。
func (s *Scores) semesterSeries() []semesterPoint {
	type bucket struct {
		key    int
		label  string
		values []model.ScoreRecord
	}
	byKey := map[int]*bucket{}
	for _, entry := range s.data.Courses {
		record := entry.Record
		if !academic.IsRequired(record.Property) || record.Credit <= 0 {
			continue
		}
		key := academic.SemesterSortKey(record.Year, record.Term)
		item, ok := byKey[key]
		if !ok {
			item = &bucket{key: key, label: strings.TrimSpace(record.Year + " " + record.Term)}
			byKey[key] = item
		}
		item.values = append(item.values, record)
	}

	points := make([]semesterPoint, 0, len(byKey))
	for _, item := range byKey {
		points = append(points, semesterPoint{Label: item.label, GPA: academic.GPAOf(item.values), Order: item.key})
	}
	sort.Slice(points, func(left, right int) bool {
		return points[left].Order < points[right].Order
	})
	return points
}

// renderTrend 渲染学期绩点趋势行。
func (s *Scores) renderTrend(th theme.Theme, points []semesterPoint, width int) string {
	if len(points) < 2 {
		return ""
	}
	values := make([]float64, 0, len(points))
	for _, point := range points {
		values = append(values, point.GPA)
	}
	sparkWidth := minInt(len(values), maxInt(8, width/2))
	spark := components.Sparkline(th, values, sparkWidth)
	delta, level := components.Trend(values)
	latest := points[len(points)-1]

	label := fmt.Sprintf("学期绩点 %s", spark)
	right := fmt.Sprintf("%s %.2f", latest.Label, latest.GPA)
	if delta != "" {
		right += "  " + th.LevelColorStyle(level).Render(delta)
	}
	gap := width - components.Width(label) - components.Width(right) - 1
	if gap < 1 {
		gap = 1
	}
	return th.SubtleStyle().Render(label) + strings.Repeat(" ", gap) + th.MutedStyle().Render(right)
}

// renderList 渲染列表卡。
func (s *Scores) renderList(th theme.Theme, width, height int) string {
	innerWidth := maxInt(16, width-4)
	innerHeight := maxInt(1, height-2)

	if s.rowCount() == 0 {
		return components.Card{
			Icon: th.Glyphs.Icons.Scores, Title: "课程列表", Subtitle: s.filterSummary(),
			Body: th.SubtleStyle().Render(s.emptyText()), Width: width, Height: height,
		}.Render(th)
	}

	var columns []components.Column
	var rows [][]string
	if s.merged {
		columns = []components.Column{
			{Title: "学期", Max: 10},
			{Title: "课程", Flex: true},
			{Title: "学分", Align: components.Right, Min: 4},
			{Title: "成绩", Align: components.Right, Min: 4},
			{Title: "绩点", Align: components.Right, Min: 4},
		}
		for _, entry := range s.data.Courses {
			record := entry.Record
			grade := academic.FormatScore(record.Score)
			if entry.IsRetake {
				grade += "·补"
			}
			rows = append(rows, []string{
				strings.TrimSpace(record.Year + " " + record.Term),
				record.CourseName,
				academic.FormatCredits(record.Credit),
				grade,
				academic.FormatGPA(academic.GradePoint(record.Score)),
			})
		}
	} else {
		columns = []components.Column{
			{Title: "学期", Max: 10},
			{Title: "课程", Flex: true},
			{Title: "成绩", Align: components.Right, Min: 4},
			{Title: "学分", Align: components.Right, Min: 4},
			{Title: "类型", Max: 8},
		}
		for _, record := range s.data.Scores {
			rows = append(rows, []string{
				strings.TrimSpace(record.Year + " " + record.Term),
				record.CourseName,
				academic.FormatScore(record.Score),
				academic.FormatCredits(record.Credit),
				record.ExamType,
			})
		}
	}

	s.win.Size = maxInt(1, innerHeight-3)
	s.win.Clamp(len(rows))
	start, end := s.win.Range(len(rows))
	cursor := -1
	if s.focus.Is(int(paneScoreList)) {
		cursor = s.win.Cursor - start
	}
	table := components.NewTable(columns, rows[start:end], cursor, th, innerWidth)
	body := table.Render()
	if end-start < len(rows) {
		body += "\n" + th.SubtleStyle().Render(fmt.Sprintf("  %d-%d / %d", start+1, end, len(rows)))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Scores,
		Title:    "课程列表",
		Subtitle: s.filterSummary(),
		Body:     body,
		Footer:   s.search.Render(th, innerWidth, "搜索课程"),
		Focused:  s.focus.Is(int(paneScoreList)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// filterSummary 返回当前筛选摘要。
func (s *Scores) filterSummary() string {
	parts := []string{}
	if !s.merged {
		parts = append(parts, "原始记录")
	}
	if s.failed {
		parts = append(parts, "只看挂科")
	}
	if s.property != "全部" {
		parts = append(parts, s.property)
	}
	if s.search.text != "" {
		parts = append(parts, "“"+s.search.text+"”")
	}
	if len(parts) == 0 {
		parts = append(parts, "全部")
	}
	return strings.Join(parts, " · ")
}

// renderRight 渲染右侧：课程详情 + 分数分布。
//
// 详情卡按内容高度，剩下的给分布图——一张撑满整列的详情卡会显得空，
// 而"我的成绩都落在哪个分数段"是用户看完单科后自然会想看的下一层信息。
func (s *Scores) renderRight(th theme.Theme, width, height int) string {
	detailHeight := minInt(height, 12)
	if detailHeight < 8 {
		detailHeight = minInt(height, 8)
	}
	rest := height - detailHeight - 1
	if rest < 6 {
		return s.renderDetail(th, width, height)
	}

	// 分布卡按内容高度，剩下的交给"待关注"清单：低分与挂科是学生
	// 看完单科后最需要立刻知道的事，比让分布图撑高更有价值。
	distributionHeight := minInt(rest, 10)
	watchHeight := rest - distributionHeight - 1
	parts := []components.Part{
		{Width: detailHeight, Content: s.renderDetail(th, width, detailHeight)},
		{Width: distributionHeight, Content: components.Card{
			Icon:     th.Glyphs.Icons.Trend,
			Title:    "分数分布",
			Subtitle: "去重后课程",
			Body:     s.renderDistribution(th, width-4),
			Width:    width,
			Height:   distributionHeight,
		}.Render(th)},
	}
	if watchHeight >= 5 {
		parts = append(parts, components.Part{Width: watchHeight, Content: components.Card{
			Icon:     th.Glyphs.Icons.Warning,
			Title:    "待关注",
			Subtitle: s.watchSubtitle(),
			Body:     s.renderWatchList(th, width-4),
			Level:    s.watchLevel(),
			Width:    width,
			Height:   watchHeight,
		}.Render(th)})
	}
	return components.StackV(width, parts)
}

// watchList 返回需要关注的课程：先挂科，再按分数从低到高。
func (s *Scores) watchList() []academicRecord {
	records := make([]academicRecord, 0, len(s.data.Courses))
	for _, entry := range s.data.Courses {
		record := entry.Record
		if record.Credit <= 0 || record.Pending {
			continue
		}
		records = append(records, academicRecord{
			CourseName: record.CourseName, Score: record.Score,
			Credit: record.Credit, Property: record.Property, Year: record.Year, Term: record.Term,
		})
	}
	sort.SliceStable(records, func(left, right int) bool {
		leftFailed := academic.ParseScoreValue(records[left].Score).GradePoint <= 0 &&
			academic.ParseScoreValue(records[left].Score).Estimated != nil
		rightFailed := academic.ParseScoreValue(records[right].Score).GradePoint <= 0 &&
			academic.ParseScoreValue(records[right].Score).Estimated != nil
		if leftFailed != rightFailed {
			return leftFailed
		}
		return academic.GradePoint(records[left].Score) < academic.GradePoint(records[right].Score)
	})
	return records
}

// watchLevel 返回待关注卡的标题色。
func (s *Scores) watchLevel() string {
	for _, record := range s.watchList() {
		value := academic.ParseScoreValue(record.Score)
		if value.Estimated != nil && value.GradePoint <= 0 {
			return "warn"
		}
	}
	return ""
}

// watchSubtitle 返回待关注卡副标题。
func (s *Scores) watchSubtitle() string {
	failed := 0
	for _, record := range s.watchList() {
		value := academic.ParseScoreValue(record.Score)
		if value.Estimated != nil && value.GradePoint <= 0 {
			failed++
		}
	}
	if failed > 0 {
		return fmt.Sprintf("%d 门未通过", failed)
	}
	return "最低分课程"
}

// renderWatchList 渲染待关注清单。
func (s *Scores) renderWatchList(th theme.Theme, width int) string {
	records := s.watchList()
	if len(records) == 0 {
		return th.SubtleStyle().Render("没有需要关注的课程")
	}
	lines := make([]string, 0, len(records))
	for _, record := range records {
		value := academic.ParseScoreValue(record.Score)
		level := "ok"
		if value.Estimated != nil && value.GradePoint <= 0 {
			level = "fail"
		} else if value.GradePoint < 2.5 {
			level = "warn"
		}
		meta := record.Score
		if meta == "" {
			meta = "未出分"
		}
		lines = append(lines, th.LevelColorStyle(level).Render(components.PadRight(meta, 6))+
			" "+th.Body().Render(components.Truncate(record.CourseName, maxInt(6, width-16)))+
			th.SubtleStyle().Render("  "+academic.FormatCredits(record.Credit)+" 学分"))
	}
	return strings.Join(lines, "\n")
}

// renderDistribution 渲染分数段条形图。
func (s *Scores) renderDistribution(th theme.Theme, width int) string {
	bands := []struct {
		label string
		level string
		min   float64
		max   float64
	}{
		{"90-100", "ok", 90, 1000},
		{"80-89", "ok", 80, 90},
		{"70-79", "info", 70, 80},
		{"60-69", "warn", 60, 70},
		{"<60", "fail", -1000, 60},
	}
	counts := make([]int, len(bands))
	total := 0
	for _, entry := range s.data.Courses {
		record := entry.Record
		if record.Credit <= 0 {
			continue
		}
		value := academic.ParseScoreValue(record.Score)
		if value.Estimated == nil {
			continue
		}
		total++
		for index, band := range bands {
			if *value.Estimated >= band.min && *value.Estimated < band.max {
				counts[index]++
				break
			}
		}
	}
	if total == 0 {
		return th.SubtleStyle().Render("没有可统计的成绩")
	}

	items := make([]components.Distribution, 0, len(bands))
	for index, band := range bands {
		items = append(items, components.Distribution{
			Label: band.label, Count: counts[index], Level: band.level,
		})
	}
	chart := components.BarChart(th, items, width)
	return chart + "\n\n" + th.SubtleStyle().Render(
		fmt.Sprintf("共 %d 门有成绩的课程", total))
}

// renderDetail 渲染详情卡。
func (s *Scores) renderDetail(th theme.Theme, width, height int) string {
	record, ok := s.selectedRecord()
	if !ok {
		return components.Card{
			Icon: th.Glyphs.Icons.Notices, Title: "课程详情",
			Body:    th.SubtleStyle().Render("在左侧选择一门课程"),
			Focused: s.focus.Is(int(paneScoreDetail)), Width: width, Height: height,
		}.Render(th)
	}

	inner := maxInt(12, width-4)
	value := academic.ParseScoreValue(record.Score)
	point := value.GradePoint

	line := func(label, text string) string {
		return th.SubtleStyle().Render(components.PadRight(label, 6)) +
			th.Body().Render(components.Truncate(text, maxInt(4, inner-7)))
	}

	lines := []string{
		th.Heading().Render(components.Truncate(record.CourseName, inner)),
		th.SubtleStyle().Render(components.Truncate(strings.TrimSpace(record.Year+" "+record.Term), inner)),
		"",
		line("成绩", academic.FormatScore(record.Score)),
		"  " + components.Meter(th, point/5.0, maxInt(6, inner-2)),
		line("绩点", academic.FormatGPA(point)),
		line("学分", academic.FormatCredits(record.Credit)),
		line("属性", orDefault(record.Property, "未记录")),
	}
	if record.CourseGroup != "" {
		lines = append(lines, line("课组", record.CourseGroup))
	}
	if record.ExamType != "" {
		lines = append(lines, line("考试", record.ExamType))
	}
	if record.Remark != "" {
		lines = append(lines, line("备注", record.Remark))
	}
	if record.IsRetake {
		lines = append(lines, line("重修", fmt.Sprintf("共 %d 次修读", record.RecordCount)))
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Courses,
		Title:    "课程详情",
		Subtitle: orDefault(record.Property, ""),
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(paneScoreDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// selectedRecord 返回当前选中的记录。
func (s *Scores) selectedRecord() (academicRecord, bool) {
	if s.merged {
		if s.win.Cursor < 0 || s.win.Cursor >= len(s.data.Courses) {
			return academicRecord{}, false
		}
		entry := s.data.Courses[s.win.Cursor]
		return academicRecord{
			Year: entry.Record.Year, Term: entry.Record.Term,
			CourseID: entry.Record.CourseID, CourseName: entry.Record.CourseName,
			CourseGroup: entry.Record.CourseGroup, Score: entry.Record.Score,
			Credit: entry.Record.Credit, Property: entry.Record.Property,
			ExamType: entry.Record.ExamType, Remark: entry.Record.Remark,
			IsRetake: entry.IsRetake, RecordCount: entry.RecordCount,
		}, true
	}
	if s.win.Cursor < 0 || s.win.Cursor >= len(s.data.Scores) {
		return academicRecord{}, false
	}
	item := s.data.Scores[s.win.Cursor]
	return academicRecord{
		Year: item.Year, Term: item.Term, CourseID: item.CourseID, CourseName: item.CourseName,
		CourseGroup: item.CourseGroup, Score: item.Score, Credit: item.Credit,
		Property: item.Property, ExamType: item.ExamType, Remark: item.Remark,
	}, true
}

// academicRecord 是详情面板用的扁平结构。
type academicRecord struct {
	Year        string
	Term        string
	CourseID    string
	CourseName  string
	CourseGroup string
	Score       string
	Credit      float64
	Property    string
	ExamType    string
	Remark      string
	IsRetake    bool
	RecordCount int
}

// nextInCycle 返回循环列表里的下一个值。
func nextInCycle(values []string, current string) string {
	for index, value := range values {
		if value == current {
			return values[(index+1)%len(values)]
		}
	}
	return values[0]
}
