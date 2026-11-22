package com.glassous.betterhrbust.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TimetableEntity::class,
        ScoreEntity::class,
        ExamEntity::class,
        ProfileEntity::class,
        NoticeEntity::class,
        CurriculumPlanEntity::class,
        CurrentCoursesEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun scoreDao(): ScoreDao
    abstract fun examDao(): ExamDao
    abstract fun profileDao(): ProfileDao
    abstract fun noticeDao(): NoticeDao
    abstract fun curriculumDao(): CurriculumDao
    abstract fun currentCoursesDao(): CurrentCoursesDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `current_courses_cache` (`studentId` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`studentId`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "better_hrbust.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
