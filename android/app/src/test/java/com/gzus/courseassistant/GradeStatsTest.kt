package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.data.model.GradeStatsCalculator
import com.gzus.courseassistant.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeStatsTest {
    private val term1 = Term(2025, 1)
    private val term2 = Term(2026, 1)

    @Test
    fun emptyGradesProduceEmptyStats() {
        val stats = GradeStatsCalculator.compute(emptyList())

        assertEquals(0, stats.totalCourses)
        assertEquals(0, stats.distribution.total)
        assertNull(stats.cumulativeGpa)
        assertNull(stats.weightedScore)
        assertTrue(stats.terms.isEmpty())
        assertTrue(!stats.hasData)
    }

    @Test
    fun computesCreditWeightedGpaAndScore() {
        val grades = listOf(
            grade(term1, "课程A", score = "90", credit = "3", gpa = "4.0"),
            grade(term1, "课程B", score = "80", credit = "1", gpa = "3.0"),
        )

        val stats = GradeStatsCalculator.compute(grades)

        assertEquals(2, stats.totalCourses)
        assertEquals(4.0, stats.totalCredits, 0.0001)
        assertEquals((4.0 * 3 + 3.0 * 1) / 4.0, stats.cumulativeGpa ?: 0.0, 0.0001)
        assertEquals((90.0 * 3 + 80.0 * 1) / 4.0, stats.weightedScore ?: 0.0, 0.0001)
    }

    @Test
    fun skipsUnpublishedGpaFromWeightingButCountsCourse() {
        val grades = listOf(
            grade(term1, "课程A", score = "85", credit = "2", gpa = "3.5"),
            grade(term1, "课程B", score = "70", credit = "2", gpa = ""),
        )

        val stats = GradeStatsCalculator.compute(grades)

        assertEquals(2, stats.totalCourses)
        assertEquals(4.0, stats.totalCredits, 0.0001)
        assertEquals(3.5, stats.cumulativeGpa ?: 0.0, 0.0001)
        assertEquals(77.5, stats.weightedScore ?: 0.0, 0.0001)
    }

    @Test
    fun countsGradeDistributionBuckets() {
        val grades = listOf(
            grade(term1, "优秀", score = "95"),
            grade(term1, "良好", score = "84"),
            grade(term1, "中等", score = "75"),
            grade(term1, "及格", score = "60"),
            grade(term1, "不及格", score = "52"),
            grade(term1, "等级制", score = "合格"),
        )

        val distribution = GradeStatsCalculator.compute(grades).distribution

        assertEquals(1, distribution.excellent)
        assertEquals(1, distribution.good)
        assertEquals(1, distribution.medium)
        assertEquals(1, distribution.pass)
        assertEquals(1, distribution.fail)
        assertEquals(1, distribution.unknown)
        assertEquals(6, distribution.total)
    }

    @Test
    fun splitsStatsByTermAndSortsNewestFirst() {
        val grades = listOf(
            grade(term1, "上学期课程", score = "70", credit = "2", gpa = "2.0"),
            grade(term2, "本学期课程一", score = "90", credit = "3", gpa = "4.0"),
            grade(term2, "本学期课程二", score = "80", credit = "1", gpa = "3.0"),
        )

        val stats = GradeStatsCalculator.compute(grades)

        assertEquals(2, stats.terms.size)
        assertEquals(term2, stats.terms[0].term)
        assertEquals(term1, stats.terms[1].term)
        assertEquals(2, stats.terms[0].courseCount)
        assertEquals(4.0, stats.terms[0].credits, 0.0001)
        assertEquals((4.0 * 3 + 3.0 * 1) / 4.0, stats.terms[0].gpa ?: 0.0, 0.0001)
        assertEquals(1, stats.terms[1].courseCount)
        assertEquals(2.0, stats.terms[1].gpa ?: 0.0, 0.0001)
    }

    @Test
    fun groupsByCourseTypeAndLabelsBlankType() {
        val grades = listOf(
            grade(term1, "必修一", credit = "4", gpa = "4.0", courseType = "必修"),
            grade(term1, "必修二", credit = "2", gpa = "2.0", courseType = "必修"),
            grade(term1, "选修一", credit = "1", gpa = "3.0", courseType = "选修"),
            grade(term1, "未知类型", credit = "1", gpa = "3.0", courseType = ""),
        )

        val types = GradeStatsCalculator.compute(grades).byType

        assertEquals(3, types.size)
        assertEquals("必修", types[0].courseType)
        assertEquals(2, types[0].courseCount)
        assertEquals(6.0, types[0].credits, 0.0001)
        assertEquals((4.0 * 4 + 2.0 * 2) / 6.0, types[0].gpa ?: 0.0, 0.0001)
        assertEquals("", types[1].courseType)
        assertEquals(1, types[1].courseCount)
    }

    @Test
    fun countsFailedCourses() {
        val grades = listOf(
            grade(term1, "通过", score = "60", passed = true),
            grade(term1, "挂科", score = "40", passed = false),
            grade(term1, "等级制不合格", score = "不合格", passed = false),
        )

        assertEquals(2, GradeStatsCalculator.compute(grades).failedCourses)
    }

    @Test
    fun ignoresInvalidCreditsInWeighting() {
        val grades = listOf(
            grade(term1, "无学分", score = "100", credit = "0", gpa = "5.0"),
            grade(term1, "正常", score = "80", credit = "2", gpa = "3.0"),
        )

        val stats = GradeStatsCalculator.compute(grades)

        assertEquals(2.0, stats.totalCredits, 0.0001)
        assertEquals(3.0, stats.cumulativeGpa ?: 0.0, 0.0001)
        assertEquals(80.0, stats.weightedScore ?: 0.0, 0.0001)
    }

    private fun grade(
        term: Term,
        name: String,
        score: String = "80",
        credit: String = "2",
        gpa: String = "3.0",
        courseType: String = "必修",
        passed: Boolean = true,
    ) = Grade(
        term = term,
        courseName = name,
        score = score,
        credit = credit,
        gpa = gpa,
        courseType = courseType,
        note = "",
        passed = passed,
    )
}
