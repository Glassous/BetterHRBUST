package com.glassous.betterhrbust.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timetable_cache")
data class TimetableEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "score_cache")
data class ScoreEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "exam_cache")
data class ExamEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "profile_cache")
data class ProfileEntity(
    @PrimaryKey val studentNumber: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notice_cache")
data class NoticeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val date: String,
    val isRead: Boolean = false
)

@Entity(tableName = "curriculum_cache")
data class CurriculumPlanEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "current_courses_cache")
data class CurrentCoursesEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "timetable_overrides")
data class TimetableOverrideEntity(
    @PrimaryKey val studentId: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)
