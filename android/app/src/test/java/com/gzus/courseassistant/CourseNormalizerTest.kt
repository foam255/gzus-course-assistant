package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.CourseNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseNormalizerTest {
    @Test
    fun mergesDuplicateTeachingClassRecordsIntoSingleDisplayBlock() {
        val first = course(
            id = "a",
            teachingClassId = "class-a",
            teachingClassName = "1班",
        )
        val duplicate = course(
            id = "b",
            teachingClassId = "class-b",
            teachingClassName = "1班A",
        )

        val merged = CourseNormalizer.normalize(listOf(first, duplicate)).single()

        assertEquals(1, merged.startPeriod)
        assertEquals(2, merged.endPeriod)
        assertTrue(merged.teachingClassId.contains("class-a"))
        assertTrue(merged.teachingClassId.contains("class-b"))
        assertTrue(merged.teachingClassName.contains("1班A"))
    }

    @Test
    fun mergesTwoAdjacentSinglePeriodBlocks() {
        val first = course(id = "first", startPeriod = 1, endPeriod = 1)
        val second = course(id = "second", startPeriod = 2, endPeriod = 2)

        val merged = CourseNormalizer.normalize(listOf(first, second)).single()

        assertEquals(1, merged.startPeriod)
        assertEquals(2, merged.endPeriod)
    }

    @Test
    fun keepsOddAndEvenWeekCoursesSeparate() {
        val odd = course(id = "odd", weeks = setOf(1, 3, 5))
        val even = course(id = "even", weeks = setOf(2, 4, 6))

        val result = CourseNormalizer.normalize(listOf(odd, even))

        assertEquals(2, result.size)
        assertEquals(setOf(1, 3, 5), result.first().weeks)
        assertEquals(setOf(2, 4, 6), result.last().weeks)
    }

    @Test
    fun doesNotMergeSeparateTwoPeriodSessions() {
        val first = course(id = "first", startPeriod = 1, endPeriod = 2)
        val second = course(id = "second", startPeriod = 3, endPeriod = 4)

        assertEquals(2, CourseNormalizer.normalize(listOf(first, second)).size)
    }

    @Test
    fun formatsWeekRangesAndParity() {
        assertEquals("1-20周", CourseNormalizer.formatWeeks((1..20).toSet()))
        assertEquals("1-17单周", CourseNormalizer.formatWeeks((1..17 step 2).toSet()))
        assertEquals("2-18双周", CourseNormalizer.formatWeeks((2..18 step 2).toSet()))
        assertEquals(
            "1-2周、4-18周",
            CourseNormalizer.formatWeeks((1..2).toSet() + (4..18).toSet()),
        )
    }

    private fun course(
        id: String,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
        teachingClassId: String = "class",
        teachingClassName: String = "教学班",
    ) = Course(
        id = id,
        name = "数据分析与建模",
        teacher = "刘老师",
        room = "广州校区 U406",
        campus = "广州校区",
        weekday = 1,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        weekText = "1-16周",
        credit = "3",
        courseCode = "CS100",
        teachingClassId = teachingClassId,
        teachingClassName = teachingClassName,
    )
}
