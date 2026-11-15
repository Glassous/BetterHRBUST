package screens

import (
	"fmt"
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

// timetablePane 是课表页的面板。
type timetablePane int

const (
	paneGrid timetablePane = iota
	paneDetail
	timetablePaneCount
)

// Timetable 是课程表页：左侧周网格 + 右侧详情。
//
// 方向键在网格里移动光标，tab 在"网格 / 详情"之间换焦点——
// 与前端表格里 tab 跳出单元格、方向键在表内移动是同一套模型。
type Timetable struct {
	ui.Base
	deps  ui.Deps
	state components.State
	data  query.TimetableData
	focus components.Focus

	week       int
	base       bool
	showUn     bool
	cursorDay  int
	cursorSlot int

	keys struct {
		PrevWeek key.Binding
		NextWeek key.Binding
		ThisWeek key.Binding
		Base     key.Binding
		Unarr    key.Binding
		Open     key.Binding
		Left     key.Binding
		Right    key.Binding
		Up       key.Binding
		Down     key.Binding
		Next     key.Binding
		Prev     key.Binding
	}
}

// NewTimetable 构造课表页。
func NewTimetable(deps ui.Deps) ui.Screen {
	screen := &Timetable{deps: deps, cursorDay: 1, cursorSlot: 1, showUn: true}
	screen.ScreenID = ui.ScreenTimetable
	screen.ScreenTitle = "课程表"
	screen.state = components.State{Loading: true}
	screen.focus = components.NewFocus(int(timetablePaneCount))
	screen.keys.PrevWeek = key.NewBinding(key.WithKeys("h", "["), key.WithHelp("h/[", "上周"))
	screen.keys.NextWeek = key.NewBinding(key.WithKeys("l", "]"), key.WithHelp("l/]", "下周"))
	screen.keys.ThisWeek = key.NewBinding(key.WithKeys("t"), key.WithHelp("t", "本周"))
	screen.keys.Base = key.NewBinding(key.WithKeys("b"), key.WithHelp("b", "大/小节"))
	screen.keys.Unarr = key.NewBinding(key.WithKeys("u"), key.WithHelp("u", "未安排"))
	screen.keys.Open = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "聚焦详情"))
	screen.keys.Left = key.NewBinding(key.WithKeys("left"))
	screen.keys.Right = key.NewBinding(key.WithKeys("right"))
	screen.keys.Up = key.NewBinding(key.WithKeys("up", "k"))
	screen.keys.Down = key.NewBinding(key.WithKeys("down", "j"))
	screen.keys.Next = key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "切换面板"))
	screen.keys.Prev = key.NewBinding(key.WithKeys("shift+tab"), key.WithHelp("shift+tab", "上一面板"))
	return screen
}

// Init 加载课表。
func (s *Timetable) Init() tea.Cmd { return s.load() }

// load 按当前周次与视图查询。
func (s *Timetable) load() tea.Cmd {
	options := query.Options{Base: s.base}
	if s.week > 0 {
		week := s.week
		options.Week = &week
	}
	return loadCmd(s.deps, ui.ScreenTimetable, func(service *query.Service) any {
		return service.Timetable(options)
	})
}

// Update 处理消息。
func (s *Timetable) Update(msg tea.Msg) (ui.Screen, tea.Cmd) {
	switch typed := msg.(type) {
	case ui.DataMsg:
		if typed.Screen != ui.ScreenTimetable {
			return s, nil
		}
		s.state.Loading = false
		if typed.Err != nil {
			s.state.Err = typed.Err
			return s, nil
		}
		s.state.Err = nil
		hadData := len(s.data.Days) > 0
		s.data, _ = typed.Data.(query.TimetableData)
		s.week = s.data.Week
		// 首次加载或当前格变成空时，自动落到第一门课上。
		if !hadData || s.cellAt(s.cursorDay, s.cursorSlot) == nil {
			s.focusFirstCourse()
		}
		s.clampCursor()
		return s, nil
	case tea.KeyPressMsg:
		return s.handleKey(typed)
	}
	return s, nil
}

// handleKey 处理页面按键。
func (s *Timetable) handleKey(msg tea.KeyPressMsg) (ui.Screen, tea.Cmd) {
	switch {
	case key.Matches(msg, s.keys.PrevWeek):
		return s.changeWeek(-1)
	case key.Matches(msg, s.keys.NextWeek):
		return s.changeWeek(1)
	case key.Matches(msg, s.keys.ThisWeek):
		if s.data.CurrentWeek > 0 && s.week != s.data.CurrentWeek {
			s.week = s.data.CurrentWeek
			return s, s.load()
		}
		return s, nil
	case key.Matches(msg, s.keys.Base):
		s.base = !s.base
		s.cursorSlot = 1
		return s, s.load()
	case key.Matches(msg, s.keys.Unarr):
		s.showUn = !s.showUn
		return s, nil
	case key.Matches(msg, s.keys.Open):
		s.focus.Set(int(paneDetail))
		return s, nil
	}

	// 方向键只在网格面板聚焦时移动光标；焦点在详情面板时不抢键。
	if s.focus.Is(int(paneGrid)) {
		switch {
		case key.Matches(msg, s.keys.Left):
			s.cursorDay--
			s.clampCursor()
		case key.Matches(msg, s.keys.Right):
			s.cursorDay++
			s.clampCursor()
		case key.Matches(msg, s.keys.Up):
			s.cursorSlot--
			s.clampCursor()
		case key.Matches(msg, s.keys.Down):
			s.cursorSlot++
			s.clampCursor()
		}
	}
	return s, nil
}

// changeWeek 切换教学周并重新加载。
func (s *Timetable) changeWeek(delta int) (ui.Screen, tea.Cmd) {
	total := s.data.TeachingWeeks
	if total <= 0 {
		total = 26
	}
	next := s.week + delta
	if next < 1 {
		next = 1
	}
	if next > total {
		next = total
	}
	if next == s.week {
		return s, nil
	}
	s.week = next
	return s, s.load()
}

// clampCursor 约束光标位置。
func (s *Timetable) clampCursor() {
	if s.cursorDay < 1 {
		s.cursorDay = 1
	}
	if s.cursorDay > 7 {
		s.cursorDay = 7
	}
	slots := s.slotCount()
	if s.cursorSlot < 1 {
		s.cursorSlot = 1
	}
	if s.cursorSlot > slots {
		s.cursorSlot = slots
	}
}

// focusFirstCourse 把光标移到本周第一门有课的格子。
//
// 打开课表时默认停在"周一第 1 节"通常是个空格子，右侧详情跟着一片空白，
// 看起来像页面坏了。直接落在第一门课上，一进来就有内容。
func (s *Timetable) focusFirstCourse() {
	for _, block := range s.data.Days {
		if len(block.Courses) == 0 {
			continue
		}
		s.cursorDay = block.Day
		s.cursorSlot = block.Courses[0].SectionIndex
		return
	}
}

// slotCount 返回当前视图的节次数。
func (s *Timetable) slotCount() int {
	if s.base {
		return 12
	}
	return 6
}

// CycleFocus 实现 ui.Focusable。
func (s *Timetable) CycleFocus(delta int) bool {
	s.focus.Index = (s.focus.Index + delta + int(timetablePaneCount)) % int(timetablePaneCount)
	return true
}

// FocusCount 实现 ui.Focusable。
func (s *Timetable) FocusCount() int { return int(timetablePaneCount) }

// Keys 返回页面按键。
func (s *Timetable) Keys() []key.Binding {
	return []key.Binding{
		s.keys.PrevWeek, s.keys.NextWeek, s.keys.ThisWeek,
		s.keys.Base, s.keys.Unarr, s.keys.Open,
	}
}

// View 渲染课表。
func (s *Timetable) View() string {
	th := s.deps.Themes.Get()
	if content, handled := s.state.Render(th, s.Width, s.Height, "正在读取课表…"); handled {
		return content
	}
	if s.Width < 56 || s.Height < 12 {
		return components.Card{
			Icon: th.Glyphs.Icons.Timetable, Title: "课程表",
			Body:  s.renderGridBody(th, maxInt(20, s.Width-4), maxInt(4, s.Height-2)),
			Width: s.Width, Height: s.Height,
		}.Render(th)
	}

	widths := components.ColumnWidths(s.Width-1, []int{7, 3})

	// 统计卡按内容高度放底部，网格吃掉剩余高度：网格是这一页的主角，
	// 多出来的行用"节间留白"摊开，比在底部留一大块空白自然得多。
	statsHeight := minInt(s.Height, 3+len(s.data.Days))
	if statsHeight < 5 {
		statsHeight = minInt(s.Height, 5)
	}
	gridHeight := s.Height
	stackStats := s.Height-statsHeight-1 >= 10
	if stackStats {
		gridHeight = s.Height - statsHeight - 1
	}

	gridCard := components.Card{
		Icon:     th.Glyphs.Icons.Timetable,
		Title:    labelWeek(s.week, s.data.CurrentWeek),
		Subtitle: s.gridSubtitle(),
		Body:     s.renderGridBody(th, widths[0]-4, gridHeight-2),
		Focused:  s.focus.Is(int(paneGrid)),
		Width:    widths[0],
		Height:   gridHeight,
	}.Render(th)

	left := gridCard
	if stackStats {
		stats := components.Card{
			Icon: th.Glyphs.Icons.Spark, Title: "本周统计",
			Body:  s.renderWeekStats(th, widths[0]-4),
			Width: widths[0], Height: statsHeight,
		}.Render(th)
		left = components.StackV(widths[0], []components.Part{
			{Width: gridHeight, Content: gridCard},
			{Width: statsHeight, Content: stats},
		})
	}

	right := s.renderSide(th, widths[1])
	return components.SplitHGap(s.Height, 1,
		components.Part{Width: widths[0], Content: left},
		components.Part{Width: widths[1], Content: right},
	)
}

// linesPerSlot 返回每节占用的行数。
func (s *Timetable) linesPerSlot(width int) int {
	if width < 60 {
		return 1
	}
	return 2
}

// renderWeekStats 渲染本周统计：每天门数与总门数。
func (s *Timetable) renderWeekStats(th theme.Theme, width int) string {
	counts := make([]components.Distribution, 0, 7)
	total := 0
	for _, block := range s.data.Days {
		count := len(block.Courses)
		total += count
		counts = append(counts, components.Distribution{
			Label: block.Weekday, Count: count, Level: "",
		})
	}
	header := th.MutedStyle().Render(fmt.Sprintf("共 %d 门课 · 每天分布", total))
	chart := components.BarChart(th, counts, width)
	if chart == "" {
		return header
	}
	return header + "\n" + chart
}

// gridSubtitle 返回网格卡副标题。
func (s *Timetable) gridSubtitle() string {
	mode := "大节"
	if s.base {
		mode = "小节"
	}
	if s.data.Holiday {
		return mode + " · 假期"
	}
	return fmt.Sprintf("%s · 共 %d 周", mode, s.data.TeachingWeeks)
}

// labelWeek 返回周次标签。
func labelWeek(week, current int) string {
	switch {
	case week <= 0:
		return "第 1 周"
	case week == current:
		return fmt.Sprintf("第 %d 周 · 本周", week)
	default:
		return fmt.Sprintf("第 %d 周", week)
	}
}

// renderSide 渲染右侧列：课程详情 + 当日课程 + 未安排时间。
//
// 右侧从"一张大卡"改成"三张按内容高度的卡"：网格里课程名会被截断，
// 这里的当日列表给全名，正好补上网格的短板，也把竖向空间用满。
func (s *Timetable) renderSide(th theme.Theme, width int) string {
	detailHeight := minInt(s.Height, 11)
	if detailHeight < 8 {
		detailHeight = minInt(s.Height, 8)
	}
	remaining := s.Height - detailHeight - 1

	parts := []components.Part{
		{Width: detailHeight, Content: s.renderDetailCard(th, width, detailHeight)},
	}

	unarrangedHeight := 0
	if s.showUn && len(s.data.Unarranged) > 0 {
		want := len(s.data.Unarranged) + 2
		if want > 6 {
			want = 6
		}
		if remaining-want-1 >= 5 {
			unarrangedHeight = want
		}
	}

	dayHeight := remaining - unarrangedHeight
	if unarrangedHeight > 0 {
		dayHeight--
	}
	if dayHeight >= 4 {
		parts = append(parts, components.Part{Width: dayHeight, Content: s.renderDayCard(th, width, dayHeight)})
	}
	if unarrangedHeight > 0 {
		parts = append(parts, components.Part{
			Width: unarrangedHeight,
			Content: components.Card{
				Icon: th.Glyphs.Icons.Courses, Title: "未安排时间",
				Subtitle: fmt.Sprintf("%d 门", len(s.data.Unarranged)),
				Body:     s.renderUnarrangedBody(th, width-4),
				Width:    width, Height: unarrangedHeight,
			}.Render(th),
		})
	}
	return components.StackV(width, parts)
}

// renderDayCard 渲染选中那天的完整时间轴。
//
// 列出全部节次（没课的显示"空闲"），而不是只列有课的几门：
// 时间轴的用途正是让人一眼看出"哪几节空着"，这比看网格空格更直观。
func (s *Timetable) renderDayCard(th theme.Theme, width, height int) string {
	var block query.DayBlock
	if s.cursorDay-1 < len(s.data.Days) {
		block = s.data.Days[s.cursorDay-1]
	}

	bySection := map[int]model.TimetableCell{}
	for _, cell := range block.Courses {
		bySection[cell.SectionIndex] = cell
	}

	slots := s.slotCount()
	// 高度够就每节两行（课程 + 地点），不够就压成一行。
	linesPerSlot := 2
	if slots*3 > height-2 {
		linesPerSlot = 1
	}
	lines := make([]string, 0, slots*linesPerSlot)
	for slot := 1; slot <= slots; slot++ {
		start := academic.CombineSlotStartTime(slot)
		if start == "" {
			start = fmt.Sprintf("%d", slot)
		}
		cell, ok := bySection[slot]
		label := th.SubtleStyle().Render(components.PadRight(start, 6))
		if !ok {
			lines = append(lines, label+" "+th.SubtleStyle().Render("空闲"))
			for extra := 1; extra < linesPerSlot; extra++ {
				lines = append(lines, "")
			}
			continue
		}
		style := th.Body()
		if slot == s.cursorSlot {
			style = th.SelectedRow()
		}
		lines = append(lines, label+" "+style.Render(
			components.Truncate(cell.CourseName, maxInt(4, width-13))))
		if linesPerSlot > 1 {
			lines = append(lines, "       "+th.SubtleStyle().Render(
				components.Truncate(orDefault(cell.Location, cell.HoursType), maxInt(4, width-11))))
		}
	}

	return components.Card{
		Icon:     th.Glyphs.Icons.Calendar,
		Title:    query.WeekdayNames[s.cursorDay] + " 时间轴",
		Subtitle: fmt.Sprintf("%d 门课", len(block.Courses)),
		Body:     strings.Join(lines, "\n"),
		Focused:  s.focus.Is(int(paneDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderDetailCard 渲染当前格详情。
func (s *Timetable) renderDetailCard(th theme.Theme, width, height int) string {
	cell := s.cellAt(s.cursorDay, s.cursorSlot)
	title := "课程详情"
	subtitle := query.WeekdayNames[s.cursorDay]
	body := ""
	if cell == nil {
		body = th.SubtleStyle().Render(fmt.Sprintf(
			"%s 第 %d 节没有课程", query.WeekdayNames[s.cursorDay], s.cursorSlot))
	} else {
		subtitle = cell.CourseName
		inner := maxInt(8, width-4)
		line := func(label, value string) string {
			return th.SubtleStyle().Render(components.PadRight(label, 6)) +
				th.Body().Render(components.Truncate(value, maxInt(4, inner-7)))
		}
		lines := []string{
			th.Heading().Render(components.Truncate(cell.CourseName, inner)),
			"",
			line("时间", query.WeekdayNames[s.cursorDay]+" "+slotLabel(cell, s.base)),
			line("地点", orDefault(cell.Location, "未记录")),
			line("教师", orDefault(cell.Teacher, "未记录")),
			line("周次", orDefault(cell.Weeks, "全学期")),
			line("学时", orDefault(cell.HoursType, "未记录")),
		}
		if cell.CourseSeq != "" {
			lines = append(lines, line("课序", cell.CourseSeq))
		}
		body = strings.Join(lines, "\n")
	}
	return components.Card{
		Icon:     th.Glyphs.Icons.Clock,
		Title:    title,
		Subtitle: subtitle,
		Body:     body,
		Focused:  s.focus.Is(int(paneDetail)),
		Width:    width,
		Height:   height,
	}.Render(th)
}

// renderUnarrangedBody 渲染未安排课程清单。
func (s *Timetable) renderUnarrangedBody(th theme.Theme, width int) string {
	lines := make([]string, 0, len(s.data.Unarranged))
	for _, course := range s.data.Unarranged {
		lines = append(lines, components.RenderListRow(th, components.ListRow{
			Text:   course.CourseName,
			Detail: orDefault(course.Teacher, course.Location),
			Meta:   orDefault(course.Weeks, "—"),
		}, width))
	}
	return strings.Join(lines, "\n")
}

// renderGridBody 渲染周网格主体（不含卡片边框）。
func (s *Timetable) renderGridBody(th theme.Theme, width, height int) string {
	slots := s.slotCount()
	linesPerSlot := s.linesPerSlot(width)

	gutterWidth := 9
	if width < 70 {
		gutterWidth = 7
	}
	dayWidth := (width - 1 - 7 - gutterWidth) / 7
	if dayWidth < 4 {
		dayWidth = 4
	}

	gutter := make([]string, 0, slots*linesPerSlot+1)
	gutter = append(gutter, th.SubtleStyle().Render(components.PadRight("节次", gutterWidth)))
	for slot := 1; slot <= slots; slot++ {
		label := academic.CombineSlotStartTime(slot)
		if label == "" {
			label = fmt.Sprintf("%d", slot)
		}
		gutter = append(gutter, th.MutedStyle().Render(components.PadRight(label, gutterWidth)))
		for extra := 1; extra < linesPerSlot; extra++ {
			gutter = append(gutter, "")
		}
	}

	columns := make([][]string, 0, 8)
	widths := make([]int, 0, 8)
	columns = append(columns, gutter)
	widths = append(widths, gutterWidth)
	for day := 1; day <= 7; day++ {
		columns = append(columns, s.renderDayColumn(th, day, slots, linesPerSlot, dayWidth))
		widths = append(widths, dayWidth)
	}

	// 节间留白：把多余行数摊到各节之间，既填满高度又让网格透气。
	spacing := 0
	if perSlot := linesPerSlot + 1; perSlot > 0 {
		available := height - 1 - slots*linesPerSlot
		if available > 0 {
			spacing = available / slots
			if spacing > 2 {
				spacing = 2
			}
		}
	}
	if spacing > 0 {
		columns = insertSpacing(columns, slots, linesPerSlot, spacing)
		gutter = insertSpacing([][]string{gutter}, slots, linesPerSlot, spacing)[0]
	}

	rows := len(gutter)
	out := make([]string, 0, rows)
	for row := 0; row < rows && row < height; row++ {
		cells := make([]string, 0, len(columns))
		for index, column := range columns {
			text := ""
			if row < len(column) {
				text = column[row]
			}
			cells = append(cells, components.PadRight(
				components.Truncate(text, widths[index]), widths[index]))
		}
		out = append(out, strings.Join(cells, " "))
	}
	return strings.Join(out, "\n")
}

// insertSpacing 在每节之后插入若干空行。
//
// `columns` 的第一列是节次栏，各列的节边界一致，因此可以按同样规则插入。
func insertSpacing(columns [][]string, slots, linesPerSlot, spacing int) [][]string {
	out := make([][]string, len(columns))
	for index, column := range columns {
		if len(column) == 0 {
			out[index] = column
			continue
		}
		built := make([]string, 0, len(column)+slots*spacing)
		built = append(built, column[0]) // 表头
		cursor := 1
		for slot := 0; slot < slots; slot++ {
			for line := 0; line < linesPerSlot && cursor < len(column); line++ {
				built = append(built, column[cursor])
				cursor++
			}
			for blank := 0; blank < spacing; blank++ {
				built = append(built, "")
			}
		}
		for ; cursor < len(column); cursor++ {
			built = append(built, column[cursor])
		}
		out[index] = built
	}
	return out
}

// renderDayColumn 渲染某一天的所有节次。
func (s *Timetable) renderDayColumn(th theme.Theme, day, slots, linesPerSlot, width int) []string {
	header := query.WeekdayNames[day]
	if day-1 < len(s.data.Days) && s.data.Days[day-1].Date != "" {
		date := s.data.Days[day-1].Date
		// 列宽够就写「周一 11/09」，不够就用「一 11/09」，
		// 再不够只留日号——总之不能把日期截成 "11.."。
		switch {
		case width >= 10:
			header = header + " " + date
		case width >= 7:
			header = strings.TrimSuffix(header, "周") + " " + date
		default:
			header = date
		}
	}
	out := make([]string, 0, slots*linesPerSlot+1)
	if day == s.cursorDay && s.focus.Is(int(paneGrid)) {
		out = append(out, th.Title().Render(components.PadRight(components.Truncate(header, width), width)))
	} else if day == s.cursorDay {
		out = append(out, th.Body().Render(components.PadRight(components.Truncate(header, width), width)))
	} else {
		out = append(out, th.MutedStyle().Render(components.PadRight(components.Truncate(header, width), width)))
	}

	for slot := 1; slot <= slots; slot++ {
		focused := day == s.cursorDay && slot == s.cursorSlot && s.focus.Is(int(paneGrid))
		cell := s.cellAt(day, slot)
		if cell == nil {
			out = append(out, components.PadRight("", width))
			for extra := 1; extra < linesPerSlot; extra++ {
				out = append(out, "")
			}
			continue
		}
		name := components.Truncate(cell.CourseName, maxInt(1, width-1))
		style := th.Body()
		if focused {
			style = th.SelectedRow()
			name = th.Glyphs.Selected + " " + components.Truncate(cell.CourseName, maxInt(1, width-2))
		}
		out = append(out, style.Render(components.PadRight(name, width)))
		if linesPerSlot > 1 {
			place := cell.Location
			if place == "" {
				place = cell.HoursType
			}
			out = append(out, th.SubtleStyle().Render(
				components.PadRight(components.Truncate(place, width), width)))
		}
	}
	return out
}

// cellAt 查找某天某节的课程。
func (s *Timetable) cellAt(day, slot int) *model.TimetableCell {
	if day-1 >= len(s.data.Days) {
		return nil
	}
	block := s.data.Days[day-1]
	for index := range block.Courses {
		if block.Courses[index].SectionIndex == slot {
			return &block.Courses[index]
		}
	}
	return nil
}

// slotLabel 返回节次描述。
func slotLabel(cell *model.TimetableCell, base bool) string {
	if base {
		return fmt.Sprintf("第 %d 节", cell.SectionIndex)
	}
	if text := academic.CombineSlotTime(cell.SectionIndex); text != "" {
		return fmt.Sprintf("第 %d 大节 %s", cell.SectionIndex, text)
	}
	return orDefault(cell.SectionLabel, fmt.Sprintf("第 %d 节", cell.SectionIndex))
}
