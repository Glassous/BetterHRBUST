package com.glassous.betterhrbust.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable
data object AuthRoute

@Serializable
data object MainRoute

@Serializable
data object DashboardRoute

@Serializable
data object TimetableRoute

@Serializable
data object ScoresRoute

@Serializable
data object ExamsRoute

@Serializable
data object ProgramRoute

@Serializable
data object ClassroomsRoute

@Serializable
data object CoursesRoute

@Serializable
data object ProfileRoute

@Serializable
data object SettingsRoute

@Serializable
data object EvaluationRoute

@Serializable
data object ResourcesRoute

@Serializable
data object FeedbackRoute

/** 本科教学进程（二级页面，数据来自随包内置的教务处公开快照） */
@Serializable
data object TeachingProcessRoute

enum class TopLevelDestination(
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
) {
    DASHBOARD(
        route = DashboardRoute,
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        label = "概览"
    ),
    TIMETABLE(
        route = TimetableRoute,
        selectedIcon = Icons.Filled.CalendarMonth,
        unselectedIcon = Icons.Outlined.CalendarMonth,
        label = "课表"
    ),
    SCORES(
        route = ScoresRoute,
        selectedIcon = Icons.Filled.Assessment,
        unselectedIcon = Icons.Outlined.Assessment,
        label = "成绩"
    ),
    EXAMS(
        route = ExamsRoute,
        selectedIcon = Icons.Filled.DateRange,
        unselectedIcon = Icons.Outlined.DateRange,
        label = "考试"
    ),
    SETTINGS(
        route = SettingsRoute,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        label = "更多"
    )
}
