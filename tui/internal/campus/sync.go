package campus

import (
	"context"
	"errors"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Glassous/BetterHRBUST/tui/internal/academic"
)

// 全量同步。
//
// 与 `web/src/composables/useAcademicData.js` 的 syncAll 对齐：
//   1. 先取上下文（内部学号 + 学年学期），后面的课表请求依赖它；
//   2. 成绩 / 培养方案 / 考试 / 档案 / 公告 / 课表(大节、小节) 并发拉取；
//   3. 任一环节判定会话失效即整体失败，但**已成功的数据照样写入**；
//   4. 最后写同步元信息，图形界面据此判断"今天已经同步过"，不会重复联网。

// ErrNoSession 表示本地没有可用的教务会话。
var ErrNoSession = errors.New("尚未登录教务系统")

// SyncOptions 是一次同步的参数。
type SyncOptions struct {
	// Account 是期望的学号，用于校验档案一致性（空则不校验）。
	Account string
}

// SyncResult 是一次同步的结果。
type SyncResult struct {
	// Failures 是各环节的非致命错误，用「；」连接后写进同步元信息。
	Failures []string
	// Expired 表示同步过程中发现会话失效。
	Expired bool
	// CompletedAt 是完成时间。
	CompletedAt time.Time
	// Written 是写回缓存的键数。
	Written int
	// Report 是写回明细。
	Report WriteReport
}

// Sync 执行一次全量同步并写回共享缓存。
func (s *Service) Sync(ctx context.Context, options SyncOptions) (SyncResult, error) {
	if !s.session.HasCookies() {
		return SyncResult{}, ErrNoSession
	}

	// 上下文决定后续课表参数，必须顺序执行
	contextCtx, cancel := contextWithTimeout(ctx, RequestTimeout)
	contextInfo, err := s.studentContext(contextCtx)
	cancel()
	if errors.Is(err, ErrSessionExpired) {
		return s.expire(ctx)
	}

	patch := Patch{Set: map[string]string{}}
	var failures []string
	if err != nil {
		failures = append(failures, "上下文："+err.Error())
	} else {
		patch.Set[KeyCurrentCourses] = encodeJSON(contextInfo.Courses)
	}

	studentID := contextInfo.StudentID
	yearID := contextInfo.Year
	termID := contextInfo.Term
	account := strings.TrimSpace(options.Account)
	if account == "" {
		account = s.session.StudentNumber()
	}

	type outcome struct {
		set  map[string]string
		err  error
		gone bool
	}

	tasks := []struct {
		label string
		run   func(context.Context) (map[string]string, error)
	}{
		{label: "成绩", run: func(ctx context.Context) (map[string]string, error) {
			result, err := s.fetchScores(ctx)
			if err != nil {
				return nil, err
			}
			return map[string]string{
				KeyScores:     encodeJSON(result.Scores),
				KeyScoreYears: encodeJSON(result.YearOptions),
			}, nil
		}},
		{label: "培养方案", run: func(ctx context.Context) (map[string]string, error) {
			plan, err := s.fetchPlan(ctx, studentID)
			if err != nil {
				return nil, err
			}
			if len(plan.Groups) == 0 && plan.TotalRequiredCredits == nil {
				return nil, errors.New("学校未返回培养方案学分要求，未覆盖已有缓存")
			}
			return map[string]string{KeyPlan: encodeJSON(plan)}, nil
		}},
		{label: "考试", run: func(ctx context.Context) (map[string]string, error) {
			exams, err := s.fetchExams(ctx)
			if err != nil {
				return nil, err
			}
			return map[string]string{KeyExams: encodeJSON(exams)}, nil
		}},
		{label: "个人档案", run: func(ctx context.Context) (map[string]string, error) {
			profile, err := s.personalInfo(ctx)
			if err != nil {
				return nil, err
			}
			if account != "" && strings.TrimSpace(profile.StudentNumber) != account {
				return nil, errors.New("档案账号与当前登录账号不一致，已拒绝更新")
			}
			profile.InternalID = studentID
			return map[string]string{KeyProfile: encodeJSON(profile)}, nil
		}},
		{label: "公告", run: func(ctx context.Context) (map[string]string, error) {
			info, err := s.fetchCalendar(ctx, -1)
			if err != nil {
				return nil, err
			}
			return map[string]string{KeyNotices: encodeJSON(info.Notices)}, nil
		}},
	}

	if studentID != "" && yearID != "" && termID != "" {
		for _, section := range []struct {
			label string
			kind  string
			key   string
		}{
			{label: "课表(大节)", kind: "COMBINE", key: KeyTimetableCombine},
			{label: "课表(小节)", kind: "BASE", key: KeyTimetableBase},
		} {
			section := section
			tasks = append(tasks, struct {
				label string
				run   func(context.Context) (map[string]string, error)
			}{
				label: section.label,
				run: func(ctx context.Context) (map[string]string, error) {
					timetable, err := s.fetchTimetable(ctx, studentID, yearID, termID, section.kind)
					if err != nil {
						return nil, err
					}
					return map[string]string{section.key: encodeJSON(timetable)}, nil
				},
			})
		}
	} else {
		failures = append(failures, "课表：未获取到当前学年学期，已跳过本次课表同步")
	}

	results := make([]outcome, len(tasks))
	var waitGroup sync.WaitGroup
	for index := range tasks {
		index := index
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			taskCtx, taskCancel := contextWithTimeout(ctx, RequestTimeout*2)
			set, runErr := tasks[index].run(taskCtx)
			taskCancel()
			results[index] = outcome{
				set:  set,
				err:  runErr,
				gone: errors.Is(runErr, ErrSessionExpired),
			}
		}()
	}
	waitGroup.Wait()

	expired := false
	for index, result := range results {
		if result.gone {
			expired = true
			continue
		}
		if result.err != nil {
			failures = append(failures, tasks[index].label+"："+result.err.Error())
			continue
		}
		for key, value := range result.set {
			patch.Set[key] = value
		}
	}

	if expired {
		return s.expire(ctx)
	}

	now := s.now()
	patch.Set[KeySyncMeta] = encodeJSON(map[string]any{
		"lastSyncAt":   now.UnixMilli(),
		"lastSyncDate": now.Format("2006-01-02"),
		"lastError":    strings.Join(failures, "；"),
	})
	// 能同步成功就说明会话有效：顺手把登录标记补齐、清掉失效标记，
	// 图形界面冷启动读到的就是"已登录 + 数据新鲜"。
	patch.Set[KeySessionFlag] = "true"
	patch.Delete = append(patch.Delete, KeySessionExpired)

	report := WriteReport{}
	if s.writer != nil && !patch.Empty() {
		written, writeErr := s.writer(patch)
		if writeErr != nil {
			return SyncResult{Failures: failures, CompletedAt: now}, writeErr
		}
		report = written
	}

	return SyncResult{
		Failures:    failures,
		CompletedAt: now,
		Written:     report.Written,
		Report:      report,
	}, nil
}

// expire 在会话失效时把本地标记为离线：保留数据、清掉登录标记。
func (s *Service) expire(_ context.Context) (SyncResult, error) {
	patch := Patch{
		Set:    map[string]string{KeySessionExpired: "true"},
		Delete: []string{KeySessionFlag},
	}
	report := WriteReport{}
	if s.writer != nil {
		if written, err := s.writer(patch); err == nil {
			report = written
		}
	}
	return SyncResult{Expired: true, CompletedAt: s.now(), Report: report}, ErrSessionExpired
}

// fetchScores 拉取全部成绩与学年选项。
func (s *Service) fetchScores(ctx context.Context) (ScoresResult, error) {
	query := url.Values{}
	query.Set("year", "")
	query.Set("term", "")
	query.Set("prop", "")
	query.Set("groupName", "")
	query.Set("para", "0")
	query.Set("sortColumn", "")

	response, err := s.client.Get(ctx, "manager/score/studentOwnScore.do?"+query.Encode())
	if err != nil {
		return ScoresResult{}, err
	}
	if isLoginPage(response.HTML) {
		return ScoresResult{}, ErrSessionExpired
	}
	return parseScores(response.HTML), nil
}

// fetchTimetable 拉取课表。sectionType 为 COMBINE（大节）或 BASE（小节）。
func (s *Service) fetchTimetable(
	ctx context.Context, studentID, yearID, termID, sectionType string,
) (Timetable, error) {
	query := url.Values{}
	query.Set("id", studentID)
	query.Set("yearid", yearID)
	query.Set("termid", termID)
	query.Set("timetableType", "STUDENT")
	query.Set("sectionType", sectionType)

	response, err := s.client.Get(ctx, "manager/coursearrange/showTimetable.do?"+query.Encode())
	if err != nil {
		return Timetable{}, err
	}
	if isLoginPage(response.HTML) {
		return Timetable{}, ErrSessionExpired
	}
	return parseTimetable(response.HTML), nil
}

// fetchExams 拉取考试安排：优先全量接口，空结果时回退近期考试接口。
func (s *Service) fetchExams(ctx context.Context) ([]ExamEntry, error) {
	response, err := s.client.Get(ctx, "manager/examstu/studentQueryAllExam.do")
	if err == nil && !isLoginPage(response.HTML) {
		if exams := parseExams(response.HTML); len(exams) > 0 {
			return exams, nil
		}
	} else if err == nil && isLoginPage(response.HTML) {
		return nil, ErrSessionExpired
	}

	fallback, fallbackErr := s.client.Get(ctx, "student/exam/index.jsdo")
	if fallbackErr != nil {
		if err != nil {
			return nil, err
		}
		return nil, fallbackErr
	}
	if isLoginPage(fallback.HTML) {
		return nil, ErrSessionExpired
	}
	return parseExams(fallback.HTML), nil
}

// fetchPlan 拉取培养方案。
func (s *Service) fetchPlan(ctx context.Context, studentID string) (Plan, error) {
	entry, err := s.client.Get(ctx, "manager/studyschedule/studentSelfSchedule.jsdo")
	if err != nil {
		return Plan{}, err
	}
	if isLoginPage(entry.HTML) {
		return Plan{}, ErrSessionExpired
	}
	id := studentID
	if match := planStudentIDPattern.FindStringSubmatch(entry.HTML); match != nil {
		id = match[1]
	}
	if id == "" {
		return Plan{}, errors.New("培养方案未返回学生查询参数，请重新同步")
	}
	if decoded, decodeErr := url.QueryUnescape(id); decodeErr == nil {
		id = decoded
	}

	response, err := s.client.Get(ctx,
		"manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId="+url.QueryEscape(id))
	if err != nil {
		return Plan{}, err
	}
	if isLoginPage(response.HTML) {
		return Plan{}, ErrSessionExpired
	}
	return parseCurriculumPlan(response.HTML), nil
}

// fetchCalendar 拉取校历与公告。week <= 0 表示不带周次参数。
func (s *Service) fetchCalendar(ctx context.Context, week int) (CalendarInfo, error) {
	path := "calendarinfo/viewCalendarInfo.do"
	if week > 0 {
		path += "?week=" + strconv.Itoa(week)
	}
	response, err := s.client.Get(ctx, path)
	if err != nil {
		return CalendarInfo{}, err
	}
	if isLoginPage(response.HTML) {
		return CalendarInfo{}, ErrSessionExpired
	}

	today := s.today()
	semester, hasSemester := academic.SemesterOn(today)
	teachingWeek, hasWeek := academic.TeachingWeekOn(today)
	var info *SemesterInfo
	if hasSemester {
		info = &SemesterInfo{Label: semester.Label, TeachingWeeks: semester.TeachingWeeks}
	}
	return parseCalendarInfo(response.HTML, info, teachingWeek, hasWeek), nil
}

// today 返回当前日期（复用 academic 的日期类型）。
func (s *Service) today() academic.Date {
	now := s.now()
	return academic.Date{Year: now.Year(), Month: int(now.Month()), Day: now.Day()}
}

// planStudentIDPattern 从培养方案入口页取出 studentId 查询参数。
var planStudentIDPattern = regexp.MustCompile(`studentId=([^&"'<>\s]+)`)
