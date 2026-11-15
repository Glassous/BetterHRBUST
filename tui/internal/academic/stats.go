package academic

import (
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/Glassous/BetterHRBUST/tui/internal/model"
)

// 学校口径常量。改动任一数值都属于口径变更，必须同步更新
// `shared/parity` 里的黄金向量与 Web / Android / 桌面端测试。
const (
	// DegreeGPAThreshold 学位绩点门槛（平均学分绩点下限）。
	DegreeGPAThreshold = 1.5
	// EarlyGradGPAThreshold 历史兼容常量，不作为资格判定展示。
	EarlyGradGPAThreshold = 4.0
	// RiskDowngradeCredits 累计挂科学分超过该值 → 留降级风险。
	RiskDowngradeCredits = 15.0
	// RiskExpelCredits 累计挂科学分超过该值 → 退学风险。
	RiskExpelCredits = 25.0
	// RecommendRetakeLimit 推免：补考 + 重修课程累计门数上限。
	RecommendRetakeLimit = 2
	// EarnedCreditsRequiredOnly 已获学分是否只统计必修课。
	EarnedCreditsRequiredOnly = false
)

var retakeKeywords = []string{"补考", "重修", "重考", "清考"}

// professionalElectivePattern 与 Web / Android 的
// `/专业(?:方向)?(?:选修|限选)/` 一致。
//
// 注意：Rust 命令行用的是 `contains("专业") && (contains("选修") || contains("限选"))`，
// 对「专业基础限选」这种课组名会误判为专业选修。本端采用与 Web / Android 相同的
// 正则，parity 用例同时钉死两种输入，让 Rust 侧将来跑 parity 时暴露该差异。
var professionalElectivePattern = regexp.MustCompile(`专业(?:方向)?(?:选修|限选)`)

// Round1 保留一位小数。
func Round1(value float64) float64 { return math.Round(value*10) / 10 }

// Round2 保留两位小数。
func Round2(value float64) float64 { return math.Round(value*100) / 100 }

// ScoreValue 是成绩文本折算出的百分制数值与官方绩点。
//
// `Numeric` 只有原文是纯数字时才非空；`Estimated` 对五级制给出折算分，
// 对「缓考」「免修」等无法折算的文本为空——**绝不臆造**。
type ScoreValue struct {
	Numeric    *float64
	GradePoint float64
	Estimated  *float64
}

func (v ScoreValue) estimatedOrZero() float64 {
	if v.Estimated == nil {
		return 0
	}
	return *v.Estimated
}

// isPlainDecimal 等价于 JS 的 `/^-?\d+(\.\d+)?$/`。
func isPlainDecimal(text string) bool {
	if text == "" {
		return false
	}
	index := 0
	if text[0] == '-' {
		index = 1
		if len(text) == 1 {
			return false
		}
	}
	digits := 0
	for index < len(text) && text[index] >= '0' && text[index] <= '9' {
		index++
		digits++
	}
	if digits == 0 {
		return false
	}
	if index < len(text) && text[index] == '.' {
		index++
		fraction := 0
		for index < len(text) && text[index] >= '0' && text[index] <= '9' {
			index++
			fraction++
		}
		if fraction == 0 {
			return false
		}
	}
	return index == len(text)
}

// ParseScoreValue 把成绩原文折算成百分制数值与官方绩点。
//
// 五分制：60 分 = 1 绩点，每增 1 分 +0.1（即 (成绩-50)/10，上限 5.0），60 分以下为 0。
// 五级制：优秀/优 4.5、良好/良 3.5、中等/中 2.5、及格/合格 1.5、不及格 0。
func ParseScoreValue(raw string) ScoreValue {
	text := strings.TrimSpace(raw)
	if text == "" {
		return ScoreValue{}
	}
	if isPlainDecimal(text) {
		number, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return ScoreValue{}
		}
		point := 0.0
		if number >= 60 {
			point = math.Min((number-50)/10, 5.0)
		}
		numeric := number
		estimated := number
		return ScoreValue{Numeric: &numeric, GradePoint: point, Estimated: &estimated}
	}
	key := strings.Join(strings.Fields(text), "")
	point, estimated, ok := levelScore(key)
	if !ok {
		return ScoreValue{}
	}
	value := &estimated
	return ScoreValue{GradePoint: point, Estimated: value}
}

// levelScore 是五级记分制对照表。
func levelScore(key string) (float64, float64, bool) {
	switch key {
	case "优秀", "优":
		return 4.5, 95, true
	case "良好", "良":
		return 3.5, 85, true
	case "中等", "中":
		return 2.5, 75, true
	case "及格", "合格", "及格线":
		return 1.5, 65, true
	case "不及格", "不合格":
		return 0, 0, true
	default:
		return 0, 0, false
	}
}

// GradePoint 只取绩点，供展示使用。
func GradePoint(raw string) float64 { return ParseScoreValue(raw).GradePoint }

// Property 是归一化后的选课属性。
type Property int

const (
	// PropertyOther 表示属性缺失或无法归类。
	PropertyOther Property = iota
	// PropertyRequired 表示必修。
	PropertyRequired
	// PropertyLimited 表示限选。
	PropertyLimited
	// PropertyElective 表示任选 / 公选 / 通识。
	PropertyElective
)

// Label 返回属性中文名。
func (p Property) Label() string {
	switch p {
	case PropertyRequired:
		return "必修"
	case PropertyLimited:
		return "限选"
	case PropertyElective:
		return "任选"
	default:
		return "其他"
	}
}

// ResolveProperty 归一化选课属性。
//
// 顺序不可调整：「专业选修」必须落到限选，因为判定发生在「任选」之前，
// 而「专业选修」不含「任选」；但「通识选修」含「通识」，会先落到任选。
func ResolveProperty(property string) Property {
	text := strings.TrimSpace(property)
	if text == "" {
		return PropertyOther
	}
	switch {
	case strings.Contains(text, "必修"):
		return PropertyRequired
	case strings.Contains(text, "任选") || strings.Contains(text, "公选") || strings.Contains(text, "通识"):
		return PropertyElective
	case strings.Contains(text, "限选") || strings.Contains(text, "选"):
		return PropertyLimited
	default:
		return PropertyOther
	}
}

// PropertyFilter 解析 `--property` 取值；非法值返回 false 供参数校验报错。
func PropertyFilter(text string) (Property, bool) {
	switch strings.TrimSpace(text) {
	case "必修", "required":
		return PropertyRequired, true
	case "限选", "limited":
		return PropertyLimited, true
	case "任选", "elective", "公选", "通识":
		return PropertyElective, true
	case "其他", "other":
		return PropertyOther, true
	default:
		return PropertyOther, false
	}
}

// IsRequired 判断是否必修。
func IsRequired(property string) bool { return ResolveProperty(property) == PropertyRequired }

// IsElective 判断是否任选类。
func IsElective(property string) bool { return ResolveProperty(property) == PropertyElective }

// IsDegreeCourse 判断是否学位课（必修 + 限选）。
func IsDegreeCourse(property string) bool {
	switch ResolveProperty(property) {
	case PropertyRequired, PropertyLimited:
		return true
	default:
		return false
	}
}

// CourseKey 是去重键：优先课程号，缺失时回退课程名。
func CourseKey(record model.ScoreRecord) string {
	if id := strings.TrimSpace(record.CourseID); id != "" {
		return id
	}
	return record.CourseName
}

// isRetakeRecord 判断单条记录是否带补考 / 重修标记。
func isRetakeRecord(record model.ScoreRecord) bool {
	text := record.ExamType + record.Remark
	for _, keyword := range retakeKeywords {
		if strings.Contains(text, keyword) {
			return true
		}
	}
	return false
}

// Dedupe 合并同一门课的多次修读记录。
//
// 规则：优先保留「已通过」的记录，其次保留折算分最高的记录；
// 同组内只要出现过不同学期或补考重修标记，即标记为重修。
// 保留首次出现的顺序，使输出稳定（与 JS 的插入顺序一致）。
func Dedupe(scores []model.ScoreRecord) []model.ScoreRecord {
	order := make([]string, 0, len(scores))
	groups := make(map[string][]model.ScoreRecord, len(scores))
	for _, record := range scores {
		key := CourseKey(record)
		if key == "" {
			continue
		}
		if _, seen := groups[key]; !seen {
			order = append(order, key)
		}
		groups[key] = append(groups[key], record)
	}

	result := make([]model.ScoreRecord, 0, len(order))
	for _, key := range order {
		items := groups[key]
		sorted := make([]model.ScoreRecord, len(items))
		copy(sorted, items)
		sort.SliceStable(sorted, func(left, right int) bool {
			if sorted[left].Passed != sorted[right].Passed {
				return sorted[left].Passed
			}
			leftScore := ParseScoreValue(sorted[left].Score).estimatedOrZero()
			rightScore := ParseScoreValue(sorted[right].Score).estimatedOrZero()
			return leftScore > rightScore
		})
		best := sorted[0]
		attempts := map[string]struct{}{}
		for _, item := range items {
			attempts[item.Year+"\x00"+item.Term] = struct{}{}
		}
		retake := len(attempts) > 1
		if !retake {
			for _, item := range items {
				if isRetakeRecord(item) {
					retake = true
					break
				}
			}
		}
		best.IsRetake = retake
		best.RecordCount = len(items)
		result = append(result, best)
	}
	return result
}

// ElectiveCategory 返回选修课的 A–E 类；非选修课或课组含「专业」时返回 0。
func ElectiveCategory(record model.ScoreRecord) byte {
	if IsRequired(record.Property) || strings.Contains(record.CourseGroup, "专业") {
		return 0
	}
	electiveName := ""
	if IsElective(record.Property) {
		electiveName = record.CourseName
	}
	text := strings.ToUpper(record.CourseGroup + " " + record.Property + " " + electiveName)
	return findCategory(text)
}

// findCategory 等价于 `/([ABCDE])\s*类|[（(]([ABCDE])[）)]/`。
func findCategory(text string) byte {
	runes := []rune(text)
	for index := range runes {
		if runes[index] >= 'A' && runes[index] <= 'E' {
			next := index + 1
			for next < len(runes) && isSpace(runes[next]) {
				next++
			}
			if next < len(runes) && runes[next] == '类' {
				return byte(runes[index])
			}
		}
		if (runes[index] == '(' || runes[index] == '（') && index+2 < len(runes) {
			if runes[index+1] >= 'A' && runes[index+1] <= 'E' &&
				(runes[index+2] == ')' || runes[index+2] == '）') {
				return byte(runes[index+1])
			}
		}
	}
	return 0
}

func isSpace(r rune) bool {
	return r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == '\u3000'
}

// DegreeCourses 返回学位课集合：学业课 + E 类最高一门 + 其余 A–E 类最高一门。
//
// 0 学分课程不参与。第二门可以仍是 E 类——规则只要求「其余 A–E 类最高」。
func DegreeCourses(scores []model.ScoreRecord) []model.ScoreRecord {
	deduped := Dedupe(scores)
	courses := make([]model.ScoreRecord, 0, len(deduped))
	for _, item := range deduped {
		if item.Credit > 0 {
			courses = append(courses, item)
		}
	}

	categories := make([]byte, len(courses))
	for index, course := range courses {
		categories[index] = ElectiveCategory(course)
	}

	better := func(left, right model.ScoreRecord) bool {
		leftScore := ParseScoreValue(left.Score).Estimated
		rightScore := ParseScoreValue(right.Score).Estimated
		switch {
		case leftScore != nil && rightScore != nil:
			if *leftScore != *rightScore {
				return *leftScore > *rightScore
			}
		case leftScore != nil:
			return true
		case rightScore != nil:
			return false
		}
		// 折算分相同时按课程号升序，保证结果确定。
		return CourseKey(left) < CourseKey(right)
	}

	electives := make([]int, 0, len(courses))
	for index := range courses {
		if categories[index] != 0 {
			electives = append(electives, index)
		}
	}
	sort.SliceStable(electives, func(left, right int) bool {
		return better(courses[electives[left]], courses[electives[right]])
	})

	firstE := -1
	for _, index := range electives {
		if categories[index] == 'E' {
			firstE = index
			break
		}
	}
	second := -1
	if firstE >= 0 {
		rest := make([]int, 0, len(electives))
		for _, index := range electives {
			if index != firstE {
				rest = append(rest, index)
			}
		}
		sort.SliceStable(rest, func(left, right int) bool {
			return better(courses[rest[left]], courses[rest[right]])
		})
		if len(rest) > 0 {
			second = rest[0]
		}
	}

	selected := make([]model.ScoreRecord, 0, len(courses))
	for index, course := range courses {
		academic := categories[index] == 0 &&
			(IsDegreeCourse(course.Property) || strings.Contains(course.CourseGroup, "专业"))
		if academic {
			selected = append(selected, course)
		}
	}
	for _, index := range []int{firstE, second} {
		if index >= 0 {
			selected = append(selected, courses[index])
		}
	}
	return selected
}

// weighted 是加权聚合的中间结果。
type weighted struct {
	creditSum   float64
	gpa         float64
	weightedAvg float64
	excellent   int
}

// calcWeighted 计算学分加权绩点、加权平均分与优秀门数。
//
// 只统计有学分且成绩可折算的课程；「缓考」「免修」不参与分母。
func calcWeighted(courses []model.ScoreRecord) weighted {
	var creditSum, scoreWeight, gpaWeight float64
	excellent := 0
	for _, item := range courses {
		if item.Credit <= 0 {
			continue
		}
		value := ParseScoreValue(item.Score)
		if value.Estimated == nil {
			continue
		}
		creditSum += item.Credit
		scoreWeight += *value.Estimated * item.Credit
		gpaWeight += value.GradePoint * item.Credit
		isExcellent := value.GradePoint >= 4.5
		if value.Numeric != nil {
			isExcellent = *value.Numeric >= 90
		}
		if isExcellent {
			excellent++
		}
	}
	out := weighted{creditSum: creditSum, excellent: excellent}
	if creditSum > 0 {
		out.gpa = Round2(gpaWeight / creditSum)
		out.weightedAvg = Round1(scoreWeight / creditSum)
	}
	return out
}

// GPAOf 返回加权平均学分绩点（**不四舍五入**，用于门槛判定）。
func GPAOf(records []model.ScoreRecord) float64 {
	var totalCredits, totalWeight float64
	for _, item := range records {
		if item.Credit <= 0 || ParseScoreValue(item.Score).Estimated == nil {
			continue
		}
		totalCredits += item.Credit
		totalWeight += GradePoint(item.Score) * item.Credit
	}
	if totalCredits <= 0 {
		return 0
	}
	return totalWeight / totalCredits
}

// HasCredits 判断是否有有效学分。
func HasCredits(records []model.ScoreRecord) bool {
	for _, item := range records {
		if item.Credit > 0 {
			return true
		}
	}
	return false
}

// DegreeStats 是学位证资格判定结果。
type DegreeStats struct {
	GPA             float64 `json:"gpa"`
	CourseCount     int     `json:"courseCount"`
	RequiredCredits float64 `json:"requiredCredits"`
	EarnedCredits   float64 `json:"earnedCredits"`
	AllPassed       bool    `json:"allPassed"`
	Threshold       float64 `json:"threshold"`
	Qualified       bool    `json:"qualified"`
}

// RecommendStats 是推免资格自检结果。
type RecommendStats struct {
	GPA               float64 `json:"gpa"`
	CourseCount       int     `json:"courseCount"`
	AllPassed         bool    `json:"allPassed"`
	RetakeCount       int     `json:"retakeCount"`
	RetakeLimit       int     `json:"retakeLimit"`
	RetakeWithinLimit bool    `json:"retakeWithinLimit"`
	Qualified         bool    `json:"qualified"`
}

// RiskStats 是学业风险预警结果。
type RiskStats struct {
	FailedCredits float64 `json:"failedCredits"`
	DowngradeLine float64 `json:"downgradeLine"`
	ExpelLine     float64 `json:"expelLine"`
	Level         string  `json:"level"`
	Label         string  `json:"label"`
	Description   string  `json:"description"`
}

// EarlyGraduationStats 是历史兼容字段，不作为学校政策展示。
type EarlyGraduationStats struct {
	GPA       float64 `json:"gpa"`
	Threshold float64 `json:"threshold"`
	Qualified bool    `json:"qualified"`
}

// AcademicStats 是学业统计总览（必修课口径）。
type AcademicStats struct {
	GPA             float64              `json:"gpa"`
	WeightedAvg     float64              `json:"weightedAvg"`
	CourseCount     int                  `json:"courseCount"`
	RawCourseCount  int                  `json:"rawCourseCount"`
	DedupedCount    int                  `json:"dedupedCount"`
	RetakeCount     int                  `json:"retakeCount"`
	ExcellentRate   int                  `json:"excellentRate"`
	TotalCredits    float64              `json:"totalCredits"`
	EarnedCredits   float64              `json:"earnedCredits"`
	EarnedRatio     float64              `json:"earnedRatio"`
	FailedCount     int                  `json:"failedCount"`
	FailedCredits   float64              `json:"failedCredits"`
	Degree          DegreeStats          `json:"degree"`
	Recommend       RecommendStats       `json:"recommend"`
	Risk            RiskStats            `json:"risk"`
	EarlyGraduation EarlyGraduationStats `json:"earlyGraduation"`
}

// BuildAcademicStats 计算学业统计总览。
//
// 除学位绩点外，全部指标只统计**必修课**，且先做补考 / 重修去重：
//
//   - 已获学分、挂科门数与挂科学分排除 pending（未出分）记录；
//   - 总学分、课程数与优秀率分母包含 pending（它们是「应修」口径）；
//   - 风险警戒线严格大于（16 学分才触发留降级，15 不触发）。
func BuildAcademicStats(scores []model.ScoreRecord) AcademicStats {
	deduped := Dedupe(scores)
	required := make([]model.ScoreRecord, 0, len(deduped))
	for _, item := range deduped {
		if IsRequired(item.Property) && item.Credit > 0 {
			required = append(required, item)
		}
	}

	var totalCredits, earnedCredits, failedCredits float64
	failedCount := 0
	for _, item := range required {
		totalCredits += item.Credit
		if item.Pending {
			continue
		}
		if item.Passed {
			earnedCredits += item.Credit
			continue
		}
		failedCount++
		failedCredits += item.Credit
	}
	totalCredits = Round1(totalCredits)
	earnedCredits = Round1(earnedCredits)
	failedCredits = Round1(failedCredits)

	overall := calcWeighted(required)

	degreeSelected := DegreeCourses(scores)
	degreeWeight := calcWeighted(degreeSelected)
	degreeAllPassed := len(degreeSelected) > 0
	for _, item := range degreeSelected {
		if !item.Passed {
			degreeAllPassed = false
			break
		}
	}
	degreeQualified := HasCredits(degreeSelected) && GPAOf(degreeSelected) >= DegreeGPAThreshold

	retakeCount := 0
	for _, item := range required {
		if item.IsRetake {
			retakeCount++
		}
	}
	recommendAllPassed := len(required) > 0 && failedCount == 0
	retakeOK := retakeCount <= RecommendRetakeLimit
	recommendQualified := len(required) > 0 && recommendAllPassed && retakeOK

	var degreeCredits, degreeEarned float64
	for _, item := range degreeSelected {
		degreeCredits += item.Credit
		if item.Passed && !item.Pending {
			degreeEarned += item.Credit
		}
	}

	dedupedRetakes := 0
	for _, item := range deduped {
		if item.IsRetake && item.Credit > 0 {
			dedupedRetakes++
		}
	}

	excellentRate := 0
	if len(required) > 0 {
		excellentRate = int(math.Round(float64(overall.excellent) / float64(len(required)) * 100))
	}
	earnedRatio := 0.0
	if totalCredits > 0 {
		earnedRatio = math.Min(earnedCredits/totalCredits, 1.0)
	}

	return AcademicStats{
		GPA:            overall.gpa,
		WeightedAvg:    overall.weightedAvg,
		CourseCount:    len(required),
		RawCourseCount: len(scores),
		DedupedCount:   len(deduped),
		RetakeCount:    dedupedRetakes,
		ExcellentRate:  excellentRate,
		TotalCredits:   totalCredits,
		EarnedCredits:  earnedCredits,
		EarnedRatio:    earnedRatio,
		FailedCount:    failedCount,
		FailedCredits:  failedCredits,
		Degree: DegreeStats{
			GPA:             degreeWeight.gpa,
			CourseCount:     len(degreeSelected),
			RequiredCredits: Round1(degreeCredits),
			EarnedCredits:   Round1(degreeEarned),
			AllPassed:       degreeAllPassed,
			Threshold:       DegreeGPAThreshold,
			Qualified:       degreeQualified,
		},
		Recommend: RecommendStats{
			GPA:               overall.gpa,
			CourseCount:       len(required),
			AllPassed:         recommendAllPassed,
			RetakeCount:       retakeCount,
			RetakeLimit:       RecommendRetakeLimit,
			RetakeWithinLimit: retakeOK,
			Qualified:         recommendQualified,
		},
		Risk: buildRisk(failedCredits),
		EarlyGraduation: EarlyGraduationStats{
			GPA:       overall.gpa,
			Threshold: EarlyGradGPAThreshold,
			Qualified: overall.creditSum > 0 && overall.gpa >= EarlyGradGPAThreshold,
		},
	}
}

// buildRisk 依据累计挂科学分给出风险等级。
func buildRisk(failedCredits float64) RiskStats {
	stats := RiskStats{
		FailedCredits: failedCredits,
		DowngradeLine: RiskDowngradeCredits,
		ExpelLine:     RiskExpelCredits,
	}
	switch {
	case failedCredits > RiskExpelCredits:
		stats.Level = "expel"
		stats.Label = "退学风险"
		stats.Description = "累计挂科 " + formatNumber(failedCredits) +
			" 学分，已超过 " + formatNumber(RiskExpelCredits) + " 学分退学警戒线"
	case failedCredits > RiskDowngradeCredits:
		stats.Level = "downgrade"
		stats.Label = "留降级风险"
		stats.Description = "累计挂科 " + formatNumber(failedCredits) +
			" 学分，已超过 " + formatNumber(RiskDowngradeCredits) + " 学分留降级警戒线"
	default:
		stats.Level = "none"
		stats.Label = "正常"
		stats.Description = "累计挂科 " + formatNumber(failedCredits) +
			" 学分，未触及 " + formatNumber(RiskDowngradeCredits) + " 学分留降级警戒线"
	}
	return stats
}

// SemesterSortKey 是学期时间轴排序键。
//
// 教务「学年」列是学期所在公历年份，同年内先春后秋，因此春 0 / 夏 1 / 秋 2 / 其他 3。
func SemesterSortKey(year, term string) int {
	yearNumber := 0
	digits := make([]byte, 0, 4)
	for index := 0; index+4 <= len(year); index++ {
		window := year[index : index+4]
		allDigits := true
		for _, ch := range []byte(window) {
			if ch < '0' || ch > '9' {
				allDigits = false
				break
			}
		}
		if !allDigits {
			continue
		}
		digits = append(digits[:0], window...)
		if parsed, err := strconv.Atoi(string(digits)); err == nil {
			yearNumber = parsed
		}
		break
	}
	rank := 3
	switch strings.ReplaceAll(strings.TrimSpace(term), " ", "") {
	case "春", "春季", "1":
		rank = 0
	case "夏", "夏季":
		rank = 1
	case "秋", "秋季", "2":
		rank = 2
	}
	return yearNumber*10 + rank
}

// CategoryProgress 是一个课组的学分完成度。
type CategoryProgress struct {
	Name               string  `json:"name"`
	Property           string  `json:"property"`
	Required           float64 `json:"required"`
	Earned             float64 `json:"earned"`
	RequiredCourses    *int    `json:"requiredCourses"`
	ElectiveCandidates *int    `json:"electiveCandidates"`
}

// CreditsProgress 是整份培养方案的学分完成度。
type CreditsProgress struct {
	Categories        []CategoryProgress `json:"categories"`
	EarnedTotal       float64            `json:"earnedTotal"`
	RequiredTotal     float64            `json:"requiredTotal"`
	CompletionPercent int                `json:"completionPercent"`
	RequiredOnly      bool               `json:"requiredOnly"`
}

// IsProfessionalElectiveGroup 判断是否「专业选修 / 专业限选」课组。
//
// 这类课组在培养方案里是「X 门候选中选 4 门」，显示的课程门数固定为 4，
// 且不能把候选课程的全部学分累加成要求学分。
func IsProfessionalElectiveGroup(name string) bool {
	return professionalElectivePattern.MatchString(name)
}

// ComputeCreditsProgress 计算培养方案学分完成度。
//
// 与概览页 / 培养方案页共用同一口径：
//   - 只看已通过（passed）的课程，重复修读先合并；
//   - 一门课只归入**第一个**匹配到的课组（全局 matched 集），避免重复计分；
//   - 未匹配的课程归入「方案外课程」，既不算进课组，也不丢弃；
//   - 总要求学分优先用方案总学分，其次「总计」类课组，最后是各课组之和。
func ComputeCreditsProgress(
	scores []model.ScoreRecord,
	groups []model.PlanGroup,
	planTotalCredits *float64,
	requiredOnly bool,
) CreditsProgress {
	deduped := Dedupe(scores)
	eligible := make([]model.ScoreRecord, 0, len(deduped))
	for _, item := range deduped {
		if !item.Passed {
			continue
		}
		if requiredOnly && !IsRequired(item.Property) {
			continue
		}
		eligible = append(eligible, item)
	}

	// 课组去重：同名或同 id 的课组只保留第一个。
	planGroups := make([]model.PlanGroup, 0, len(groups))
	seenGroup := map[string]struct{}{}
	for _, group := range groups {
		key := group.ID
		if key == "" {
			key = group.Name
		}
		if _, ok := seenGroup[key]; ok {
			continue
		}
		seenGroup[key] = struct{}{}
		planGroups = append(planGroups, group)
	}

	matched := map[string]struct{}{}
	categories := make([]CategoryProgress, 0, len(planGroups)+1)

	if len(planGroups) > 0 {
		for _, group := range planGroups {
			earned := 0.0
			for _, record := range eligible {
				key := CourseKey(record)
				if _, done := matched[key]; done {
					continue
				}
				if groupMatches(group, record) {
					earned += record.Credit
					matched[key] = struct{}{}
				}
			}
			professional := IsProfessionalElectiveGroup(group.Name)
			requiredCourses := group.RequiredCourses
			if professional {
				requiredCourses = 4
			}
			category := CategoryProgress{
				Name:            group.Name,
				Property:        group.Property,
				Required:        Round1(group.RequiredCredits),
				Earned:          Round1(earned),
				RequiredCourses: &requiredCourses,
			}
			if professional {
				candidates := len(group.Courses)
				category.ElectiveCandidates = &candidates
			}
			categories = append(categories, category)
		}
	} else {
		for _, record := range eligible {
			name := record.CourseGroup
			if strings.TrimSpace(name) == "" {
				name = "其它"
			}
			found := false
			for index := range categories {
				if categories[index].Name == name {
					categories[index].Earned += record.Credit
					found = true
					break
				}
			}
			if !found {
				categories = append(categories, CategoryProgress{
					Name: name, Property: "必修", Required: 0, Earned: record.Credit,
				})
			}
			matched[CourseKey(record)] = struct{}{}
		}
		for index := range categories {
			categories[index].Earned = Round1(categories[index].Earned)
		}
	}

	leftover := 0.0
	for _, record := range eligible {
		if _, done := matched[CourseKey(record)]; !done {
			leftover += record.Credit
		}
	}
	if leftover > 0 {
		name := "其它课程"
		if len(planGroups) > 0 {
			name = "方案外课程（通识选修 / 实践拓展）"
		}
		categories = append(categories, CategoryProgress{
			Name: name, Property: "任选", Required: 0, Earned: Round1(leftover),
		})
	}

	var earnedTotal float64
	for _, category := range categories {
		earnedTotal += category.Earned
	}
	earnedTotal = Round1(earnedTotal)

	requiredTotal := 0.0
	if planTotalCredits != nil && *planTotalCredits > 0 {
		requiredTotal = *planTotalCredits
	} else if summary, ok := findSummaryGroup(planGroups); ok {
		requiredTotal = summary.RequiredCredits
	} else {
		var sum float64
		for _, category := range categories {
			sum += category.Required
		}
		requiredTotal = Round1(sum)
	}

	completion := 0
	if requiredTotal > 0 {
		completion = int(math.Round(earnedTotal / requiredTotal * 100))
		completion = max(0, min(100, completion))
	}

	return CreditsProgress{
		Categories:        categories,
		EarnedTotal:       earnedTotal,
		RequiredTotal:     requiredTotal,
		CompletionPercent: completion,
		RequiredOnly:      requiredOnly,
	}
}

// groupMatches 判断一条成绩记录是否属于某个课组。
func groupMatches(group model.PlanGroup, record model.ScoreRecord) bool {
	for _, course := range group.Courses {
		if (course.Code != "" && course.Code == record.CourseID) || course.Name == record.CourseName {
			return true
		}
	}
	if record.CourseGroup == "" {
		return false
	}
	if strings.Contains(group.Name, record.CourseGroup) ||
		strings.Contains(record.CourseGroup, group.Name) {
		return true
	}
	return firstChars(group.Name, 3) == firstChars(record.CourseGroup, 3)
}

// findSummaryGroup 找出「总计」类课组。
func findSummaryGroup(groups []model.PlanGroup) (model.PlanGroup, bool) {
	for _, group := range groups {
		switch strings.TrimSpace(group.Name) {
		case "总计", "合计", "全部课程", "毕业要求", "培养方案总计":
			return group, true
		}
	}
	return model.PlanGroup{}, false
}

// firstChars 返回前 count 个字符。
func firstChars(text string, count int) string {
	runes := []rune(text)
	if len(runes) <= count {
		return string(runes)
	}
	return string(runes[:count])
}

// formatNumber 与 Rust 的 `{}` 输出一致：整数不带小数点，小数保留原始位数。
func formatNumber(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}
