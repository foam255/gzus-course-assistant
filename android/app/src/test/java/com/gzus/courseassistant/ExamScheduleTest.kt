package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.data.model.ExamSchedule
import com.gzus.courseassistant.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ExamScheduleTest {
    private val term = Term(2026, 1)

    @Test
    fun parsesCommonNumericDateFormats() {
        val expected = LocalDate.of(2026, 1, 5)
        listOf("2026-01-05", "2026/1/5", "2026.1.5", "20260105", "2026-01-05 08:00-10:00")
            .forEach { value ->
                assertEquals(value, expected, ExamSchedule.parseDate(value, term))
            }
    }

    @Test
    fun chineseDateUsesAcademicYearSemantics() {
        // 8-12 月属于学年上半段，1-7 月属于学年下半段。
        assertEquals(
            LocalDate.of(2026, 9, 10),
            ExamSchedule.parseDate("9月10日", Term(2026, 1)),
        )
        assertEquals(
            LocalDate.of(2027, 1, 5),
            ExamSchedule.parseDate("1月5日", Term(2026, 1)),
        )
    }

    @Test
    fun rejectsUnparseableDates() {
        assertNull(ExamSchedule.parseDate("", term))
        assertNull(ExamSchedule.parseDate("待定", term))
        assertNull(ExamSchedule.parseDate("2026-13-40", term))
    }

    @Test
    fun parsesFirstTimeOfRange() {
        assertEquals(LocalTime.of(8, 0), ExamSchedule.parseTime("08:00-10:00"))
        assertEquals(LocalTime.of(14, 30), ExamSchedule.parseTime("14:30"))
        assertEquals(LocalTime.of(9, 5), ExamSchedule.parseTime("9：05"))
        assertNull(ExamSchedule.parseTime("25:00"))
        assertNull(ExamSchedule.parseTime("待定"))
    }

    @Test
    fun combinesDateAndTimeIntoStart() {
        val exam = exam(date = "2026-01-05", time = "08:00-10:00")
        val start = ExamSchedule.parseStart(exam)
        assertNotNull(start)
        assertEquals(LocalDateTime.of(2026, 1, 5, 8, 0), start)
        assertTrue(ExamSchedule.hasTime(exam))
    }

    @Test
    fun keepsDayGranularityWhenTimeMissing() {
        val exam = exam(date = "2026-01-05", time = "")
        val upcoming = ExamSchedule.upcoming(listOf(exam), LocalDateTime.of(2026, 1, 1, 9, 0)).single()
        assertFalse(upcoming.hasTime)
        assertNull(upcoming.minutesLeft)
        assertEquals(4L, upcoming.daysLeft)
        assertEquals(LocalDateTime.of(2026, 1, 5, 0, 0), upcoming.start)
    }

    @Test
    fun sortsUpcomingByStartAndPutsUnparseableLast() {
        val later = exam(name = "C", date = "2026-01-09", time = "14:00")
        val earlier = exam(name = "A", date = "2026-01-05", time = "08:00")
        val broken = exam(name = "B", date = "待定", time = "")
        val sorted = ExamSchedule.upcoming(
            listOf(later, broken, earlier),
            LocalDateTime.of(2026, 1, 1, 9, 0),
        )
        assertEquals(listOf("A", "C", "B"), sorted.map { it.exam.courseName })
        assertNull(sorted.last().start)
    }

    @Test
    fun nextSkipsExamsThatAlreadyStarted() {
        val started = exam(name = "已考", date = "2026-01-05", time = "08:00")
        val future = exam(name = "待考", date = "2026-01-06", time = "09:00")
        val next = ExamSchedule.next(
            listOf(started, future),
            LocalDateTime.of(2026, 1, 5, 10, 0),
        )
        assertEquals("待考", next?.exam?.courseName)
    }

    @Test
    fun nextKeepsExamOnItsDayWhenTimeUnknown() {
        val exam = exam(name = "当天", date = "2026-01-05", time = "")
        val next = ExamSchedule.next(listOf(exam), LocalDateTime.of(2026, 1, 5, 18, 0))
        assertEquals("当天", next?.exam?.courseName)
        assertEquals(0L, next?.daysLeft)
    }

    @Test
    fun nextReturnsNullWhenNothingRemains() {
        val exam = exam(date = "2026-01-05", time = "08:00")
        assertNull(ExamSchedule.next(listOf(exam), LocalDateTime.of(2026, 1, 9, 8, 0)))
        assertNull(ExamSchedule.daysUntilNext(emptyList(), LocalDateTime.of(2026, 1, 9, 8, 0)))
    }

    private fun exam(
        name: String = "高等数学",
        date: String = "2026-01-05",
        time: String = "08:00-10:00",
    ) = Exam(
        term = term,
        courseName = name,
        date = date,
        time = time,
        room = "U406",
        seat = "12",
        type = "期末考试",
        note = "",
    )
}
