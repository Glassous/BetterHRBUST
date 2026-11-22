package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.model.CurrentCourseItem
import com.glassous.betterhrbust.core.model.ScoreItem
import com.glassous.betterhrbust.core.util.MyCourseCatalog
import com.glassous.betterhrbust.core.util.MyCourseFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyCourseCatalogTest {

    @Test
    fun buildMergesCurrentAndHistoricalCoursesWithoutDuplicates() {
        val current = listOf(
            CurrentCourseItem(
                courseId = "C01",
                courseSeq = "1",
                courseName = "软件项目管理",
                teacher = "崔丽杰",
                credit = 2.0,
                property = "必修",
                examWay = "分散（46）",
                examType = "正常考试",
                timeAndPlace = "4-18周 星期二 第三大节"
            )
        )
        val scores = listOf(
            // 同课号重修中：本学期在修优先展示，不重复出现
            ScoreItem(
                year = "2024", term = "秋", courseId = "C01", courseName = "软件项目管理",
                courseSeq = "1", courseGroup = "专业必修课", score = "50", credit = 2.0,
                hours = 32, property = "必修", remark = "", examType = "正常考试",
                passMark = "不及格", passed = false
            ),
            ScoreItem(
                year = "2023", term = "秋", courseId = "C02", courseName = "程序设计基础",
                courseSeq = "1", courseGroup = "学科基础", score = "55", credit = 4.0,
                hours = 64, property = "必修", remark = "", examType = "正常考试",
                passMark = "不及格", passed = false
            ),
            ScoreItem(
                year = "2024", term = "春", courseId = "C02", courseName = "程序设计基础",
                courseSeq = "1", courseGroup = "学科基础", score = "88", credit = 4.0,
                hours = 64, property = "必修", remark = "", examType = "重修",
                passMark = "及格", passed = true
            ),
            ScoreItem(
                year = "2025", term = "春", courseId = "E01", courseName = "科技英语",
                courseSeq = "1", courseGroup = "专业选修课", score = "优秀", credit = 2.0,
                hours = 32, property = "限选", remark = "", examType = "正常考试",
                passMark = "及格", passed = true
            )
        )

        val merged = MyCourseCatalog.build(current, scores)
        assertEquals(3, merged.size)
        assertTrue(merged[0].isCurrent)
        assertEquals("C01", merged[0].id)
        assertEquals("崔丽杰", merged[0].teacher)
        assertEquals(32, merged[0].hours)

        // 历史成绩中按学期倒序且同一门课仅保留最优记录
        assertEquals("E01", merged[1].id)
        assertFalse(merged[1].isCurrent)
        assertEquals("优秀", merged[1].latestScore)
        assertEquals("C02", merged[2].id)
        assertEquals("88", merged[2].latestScore)
        assertEquals(true, merged[2].passed)
    }

    @Test
    fun filterMatchesCategoryAndSearchKeywords() {
        val current = listOf(
            CurrentCourseItem("C01", "1", "Linux操作系统", "张三", 2.0, "必修", "", "", "")
        )
        val scores = listOf(
            ScoreItem("2025", "春", "E01", "摄影艺术鉴赏", "1", "通识选修", "92", 1.5, 24, "任选", "", "", "及格", true)
        )
        val items = MyCourseCatalog.build(current, scores)

        assertEquals(1, MyCourseCatalog.filter(items, "", MyCourseFilter.CURRENT).size)
        assertEquals(1, MyCourseCatalog.filter(items, "", MyCourseFilter.REQUIRED).size)
        assertEquals(1, MyCourseCatalog.filter(items, "", MyCourseFilter.ELECTIVE).size)
        assertEquals(1, MyCourseCatalog.filter(items, "张三", MyCourseFilter.ALL).size)
        assertEquals(1, MyCourseCatalog.filter(items, "e01", MyCourseFilter.ALL).size)
        assertEquals(0, MyCourseCatalog.filter(items, "摄影", MyCourseFilter.CURRENT).size)
    }
}
