package com.glassous.betterhrbust.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Credentials(
    val username: String = "",
    val password: String = ""
)

@Serializable
sealed interface AuthState {
    @Serializable
    data object Unauthenticated : AuthState

    @Serializable
    data class Authenticated(
        val username: String,
        val studentId: String
    ) : AuthState

    @Serializable
    data class Error(
        val message: String
    ) : AuthState
}

@Serializable
data class StudentContext(
    val studentId: String,
    val year: String,
    val term: String,
    val courses: List<CurrentCourseItem> = emptyList()
)

@Serializable
data class CurrentCourseItem(
    val courseId: String,
    val courseSeq: String,
    val courseName: String,
    val teacher: String,
    val credit: Double,
    val property: String,
    val examWay: String,
    val examType: String,
    val timeAndPlace: String
)

@Serializable
data class PersonalInfo(
    val studentNumber: String = "",
    val realName: String = "",
    val college: String = "",
    val major: String = "",
    val direction: String = "",
    val studentType: String = "",
    val grade: String = "",
    val className: String = "",
    val idCard: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val postalCode: String = "",
    val status: String = "在籍",
    val photoUrl: String = "",
    val changes: List<StatusChange> = emptyList()
)

@Serializable
data class StatusChange(
    val type: String,
    val date: String,
    val reason: String,
    val remark: String
)

@Serializable
data class TimetableResult(
    val cells: List<TimetableCell> = emptyList(),
    val unarranged: List<UnarrangedCourse> = emptyList()
)

@Serializable
data class TimetableCell(
    val id: String,
    val courseId: String,
    val day: Int,           // 1..7 (周一至周日)
    val sectionIndex: Int,  // 1..6 大节
    val sectionLabel: String,
    val courseName: String,
    val courseSeq: String,
    val location: String,
    val teacher: String,
    val weeks: String,
    val hoursType: String,
    val rawLines: List<String> = emptyList()
)

@Serializable
data class UnarrangedCourse(
    val courseId: String,
    val courseName: String,
    val courseSeq: String,
    val teacher: String,
    val mergeClass: String,
    val weeks: String,
    val day: String,
    val location: String
)

@Serializable
data class ScoreResult(
    val scores: List<ScoreItem> = emptyList(),
    val yearOptions: List<YearOption> = emptyList()
)

@Serializable
data class ScoreItem(
    val year: String,
    val term: String,
    val courseId: String,
    val courseName: String,
    val courseSeq: String,
    val courseGroup: String,
    val score: String,
    val credit: Double,
    val hours: Int,
    val property: String,
    val remark: String,
    val examType: String,
    val passMark: String,
    val passed: Boolean,
    /** 未出分 / 缓考 / 缺考等无明确结论：不计入已获学分，也不计入挂科 */
    val pending: Boolean = false
)

@Serializable
data class YearOption(
    val value: String,
    val label: String
)

@Serializable
data class ScoreStats(
    val gpa: Double,
    val weightedAvg: Double,
    val totalCredits: Double,
    val earnedCredits: Double,
    val failedCount: Int,
    val excellentRate: Double,
    // ---- 以下为五分制改造新增字段，均带默认值以保证向后兼容 ----
    /** 累计挂科学分（去重后仍未通过的课程学分之和） */
    val failedCredits: Double = 0.0,
    /** 参与统计的必修课门数（去重后） */
    val courseCount: Int = 0,
    /** 原始成绩记录条数 */
    val rawCourseCount: Int = 0,
    /** 去重后课程总门数（含选修，仅用于展示合并效果） */
    val dedupedCount: Int = 0,
    val retakeCount: Int = 0,
    val degree: DegreeStats = DegreeStats(),
    val recommend: RecommendStats = RecommendStats(),
    val risk: RiskStats = RiskStats(),
    val earlyGraduation: EarlyGradStats = EarlyGradStats()
)

/** 特色算法 ① 学位证：学位课（必修 + 限选）平均学分绩点与达标判定 */
@Serializable
data class DegreeStats(
    val gpa: Double = 0.0,
    val courseCount: Int = 0,
    val requiredCredits: Double = 0.0,
    val earnedCredits: Double = 0.0,
    val allPassed: Boolean = false,
    val threshold: Double = 1.5,
    val qualified: Boolean = false
)

/** 特色算法 ② 推免 / 保研资格自检（必修课口径） */
@Serializable
data class RecommendStats(
    val gpa: Double = 0.0,
    val courseCount: Int = 0,
    val allPassed: Boolean = false,
    val retakeCount: Int = 0,
    val retakeLimit: Int = 2,
    val qualified: Boolean = false
) {
    val retakeWithinLimit: Boolean get() = retakeCount <= retakeLimit
}

/** 特色算法 ③ 学业风险预警（累计挂科学分） */
@Serializable
data class RiskStats(
    val failedCredits: Double = 0.0,
    val downgradeLine: Double = 15.0,
    val expelLine: Double = 25.0,
    /** none / downgrade / expel */
    val level: String = "none",
    val label: String = "正常",
    val description: String = ""
)

/** 特色算法 ④ 提前毕业判定 */
@Serializable
data class EarlyGradStats(
    val gpa: Double = 0.0,
    val threshold: Double = 4.0,
    val qualified: Boolean = false
)

/** 去重后的课程记录（附带合并标记），用于成绩列表展示 */
@Serializable
data class DedupedScore(
    val item: ScoreItem,
    val recordCount: Int = 1,
    val isRetake: Boolean = false,
    val gradePoint: Double = 0.0
)

/** 培养方案课组学分完成度（概览页与培养方案页共用口径） */
@Serializable
data class CreditCategory(
    val name: String,
    val property: String = "",
    val required: Double = 0.0,
    val earned: Double = 0.0
)

@Serializable
data class CreditsProgress(
    val categories: List<CreditCategory> = emptyList(),
    val earnedTotal: Double = 0.0,
    val requiredTotal: Double = 160.0,
    val completionPercent: Int = 0,
    /** 是否只统计必修课（选修课不计入已获得学分） */
    val requiredOnly: Boolean = true
)

@Serializable
data class ExamItem(
    val courseId: String,
    val courseName: String,
    val time: String,
    val location: String,
    val property: String,
    val countdownDays: Int? = null,
    val isUpcoming: Boolean = true
)

@Serializable
data class CurriculumPlanResult(
    val groups: List<CurriculumGroup> = emptyList(),
    val totalRequiredCredits: Double? = null
)

@Serializable
data class CurriculumGroup(
    val id: String,
    val name: String,
    val property: String,
    val requiredCredits: Double,
    val requiredCourses: Int,
    val earnedCredits: Double = 0.0,
    val passedCourses: Int = 0,
    val courses: List<PlanCourseDetail> = emptyList()
)

@Serializable
data class PlanCourseDetail(
    val code: String,
    val name: String,
    val credit: Double,
    val hours: Int,
    val property: String
)

@Serializable
data class ClassroomQueryOptions(
    val areas: List<NamedOption> = emptyList(),
    val buildings: List<NamedOption> = emptyList(),
    val rooms: List<NamedOption> = emptyList(),
    // 第几周（1-26）与星期几（1=周一），来自查询页的 whichweek / week 下拉
    val weeks: List<NamedOption> = emptyList(),
    val days: List<NamedOption> = emptyList()
)

@Serializable
data class NamedOption(
    val id: String,
    val name: String
)

/**
 * 单条占用：day 1-7（周一=1），start 1-12 小节，span 连堂节数。
 * type: course（课程）/ exam（课程考试）/ other（其它）/ occupied（未知）
 */
@Serializable
data class ClassroomOccupancy(
    val day: Int,
    val start: Int,
    val span: Int = 1,
    val type: String = "occupied",
    val reason: String = ""
)

@Serializable
data class ClassroomRoom(
    val id: String = "",
    val name: String = "",
    val seats: Int = 0,
    val roomType: String = "",
    val deskType: String = "",
    val occupancy: List<ClassroomOccupancy> = emptyList()
)

/** 按周查询结果：一栋教学楼 × 一个周次 × 一个星期 × 12 小节 */
@Serializable
data class ClassroomDaySchedule(
    val semester: String = "",
    val buildingName: String = "",
    val week: Int = 0,
    val day: Int = 0,
    val date: String = "",
    val rooms: List<ClassroomRoom> = emptyList()
)

@Serializable
data class FreeClassroomSlot(
    val roomName: String,
    val buildingName: String,
    val day: Int,
    val startSection: Int,
    val endSection: Int
)

@Serializable
data class CourseSearchItem(
    val courseId: String,
    val courseName: String,
    val credit: Double,
    val hours: Int,
    val department: String,
    val examWay: String = "",
    val teacher: String = "",
    val isCurrent: Boolean = false
)

@Serializable
data class CalendarInfo(
    val teachingWeeks: Int? = null,
    val currentWeek: Int = 1,
    val semesterName: String = "",
    val notices: List<NoticeItem> = emptyList()
)

@Serializable
data class NoticeItem(
    val id: String = "",
    val title: String,
    val content: String,
    val date: String
)
