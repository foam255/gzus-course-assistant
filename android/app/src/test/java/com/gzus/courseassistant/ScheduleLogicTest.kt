package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.JsonCodec
import com.gzus.courseassistant.data.model.Period
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleLogicTest {
    private val term = Term(2026, 1)
    private val testDate = LocalDate.of(2026, 10, 26)

    @Test
    fun calculatesCurrentWeekAtTermEdges() {
        assertEquals(1, term.currentWeek(term.startDate))
        assertEquals(9, term.currentWeek(testDate))
        assertEquals(20, term.currentWeek(LocalDate.of(2027, 5, 1)))
    }

    @Test
    fun supportsManualFirstWeekDate() {
        val override = LocalDate.of(2026, 9, 7)

        assertEquals(8, term.currentWeek(testDate, override))
        assertEquals(
            override,
            term.weekStartDate(1, override),
        )
        assertEquals(
            override.plusWeeks(8),
            term.weekStartDate(9, override),
        )
    }

    @Test
    fun guessesAcademicTermFromSystemDate() {
        assertEquals(Term(2026, 1), Term.guess(LocalDate.of(2026, 8, 15)))
        assertEquals(Term(2025, 1), Term.guess(LocalDate.of(2026, 1, 10)))
        assertEquals(Term(2025, 2), Term.guess(LocalDate.of(2026, 3, 1)))
        assertEquals(Term(2025, 2), Term.guess(LocalDate.of(2026, 7, 15)))
    }

    @Test
    fun normalizesTimesReturnedByJwxt() {
        assertEquals(LocalTime.of(8, 0), ScheduleLogic.parseTime("8:00:"))
        assertEquals(LocalTime.of(9, 40), ScheduleLogic.parseTime("09:40"))
        assertEquals(LocalTime.of(10, 20), ScheduleLogic.parseTime("10:20-11:00"))
        assertEquals("08:00", ScheduleLogic.normalizedTime("8:00:"))
        assertNull(ScheduleLogic.parseTime(""))
    }

    @Test
    fun findsCurrentAndNextCourses() {
        val schedule = schedule()

        val current = ScheduleLogic.currentOccurrences(
            schedule,
            testDate,
            LocalTime.of(9, 10),
        )
        val next = ScheduleLogic.nextOccurrences(
            schedule,
            testDate,
            LocalTime.of(9, 10),
        )

        assertEquals(listOf("数据结构"), current.map { it.course.name })
        assertEquals(listOf("计算机网络"), next.map { it.course.name })
        assertTrue(
            ScheduleLogic.currentOccurrences(
                schedule,
                testDate,
                LocalTime.of(12, 0),
            ).isEmpty(),
        )
        assertFalse(
            ScheduleLogic.nextOccurrences(
                schedule,
                testDate,
                LocalTime.of(12, 0),
            ).isNotEmpty(),
        )
    }

    @Test
    fun usesManualFirstWeekWhenReadingCourses() {
        val schedule = schedule()
        val manualStart = LocalDate.of(2026, 9, 7)

        assertEquals(2, ScheduleLogic.occurrences(schedule, testDate).size)
        assertTrue(
            ScheduleLogic.occurrences(
                schedule,
                testDate,
                manualStart,
            ).isEmpty(),
        )
    }

    @Test
    fun keepsBrowsingTermSeparateFromActiveTerm() {
        val oldTerm = Term(2025, 2)
        val active = schedule()
        val historical = active.copy(term = oldTerm, fetchedAt = 1L)
        val state = AppState(
            selectedTerm = oldTerm.label,
            currentTerm = active.term.label,
            schedules = listOf(historical, active),
        )

        assertEquals(oldTerm, state.currentSchedule?.term)
        assertEquals(active.term, state.activeSchedule?.term)
    }

    @Test
    fun filtersCoursesByActualWeek() {
        val schedule = schedule().copy(
            courses = listOf(
                course(
                    id = "week-9",
                    name = "第9周课程",
                    startPeriod = 1,
                    endPeriod = 2,
                    weeks = setOf(9),
                ),
                course(
                    id = "week-10",
                    name = "第10周课程",
                    startPeriod = 3,
                    endPeriod = 4,
                    weeks = setOf(10),
                ),
            ),
        )

        assertEquals(
            listOf("第9周课程"),
            ScheduleLogic.coursesForWeek(schedule, 9).map { it.name },
        )
        assertEquals(
            listOf("第10周课程"),
            ScheduleLogic.coursesForWeek(schedule, 10).map { it.name },
        )
    }

    @Test
    fun ignoresWeekendCourses() {
        val saturday = LocalDate.of(2026, 10, 31)
        val schedule = schedule().copy(
            courses = listOf(
                course(
                    id = "weekend",
                    name = "周末课程",
                    startPeriod = 1,
                    endPeriod = 2,
                    weeks = setOf(9),
                    weekday = 6,
                ),
            ),
        )

        assertTrue(ScheduleLogic.occurrences(schedule, saturday).isEmpty())
        assertTrue(ScheduleLogic.coursesForWeek(schedule, 9).isEmpty())
    }

    @Test
    fun findsDueCourseAndBuildsStableReminderKey() {
        val schedule = schedule()
        val due = ScheduleLogic.dueOccurrences(
            schedule,
            testDate,
            LocalTime.of(8, 45),
            minutesBefore = 15,
        )

        assertEquals(1, due.size)
        assertEquals("数据结构", due.single().course.name)
        assertEquals(
            "2026-2027-1|2026-10-26|course-1",
            ScheduleLogic.reminderKey(testDate, term, due.single().course.id),
        )
    }

    @Test
    fun spansBothPeriodsAndMarksFinishedCourse() {
        val spanSchedule = schedule().let { snapshot ->
            snapshot.copy(
                periods = listOf(
                    Period(1, "1", "09:00", "09:40"),
                    Period(2, "2", "09:41", "10:20"),
                ),
                courses = listOf(
                    course(
                        id = "span",
                        name = "大节课程",
                        startPeriod = 1,
                        endPeriod = 2,
                    ),
                ),
            )
        }
        val occurrence = ScheduleLogic.occurrences(spanSchedule, testDate).single()

        assertEquals(LocalTime.of(9, 0), occurrence.start)
        assertEquals(LocalTime.of(10, 20), occurrence.end)
        assertEquals(
            ScheduleLogic.TimingState.CURRENT,
            ScheduleLogic.timingState(occurrence, LocalTime.of(9, 50)),
        )
        assertEquals(
            ScheduleLogic.TimingState.FINISHED,
            ScheduleLogic.timingState(occurrence, LocalTime.of(10, 21)),
        )
        assertEquals(
            ScheduleLogic.TimingState.UPCOMING,
            ScheduleLogic.timingState(occurrence, LocalTime.of(8, 59)),
        )
    }

    @Test
    fun buildsBigPeriodRowsFromConsecutiveSmallPeriods() {
        val periods = listOf(
            Period(1, "1", "09:00", "09:40"),
            Period(2, "2", "09:41", "10:20"),
            Period(3, "3", "10:40", "11:20"),
            Period(4, "4", "11:21", "12:00"),
            Period(5, "5", "12:30", "13:10"),
            Period(6, "6", "13:11", "13:50"),
            Period(7, "7", "14:00", "14:40"),
            Period(8, "8", "14:41", "15:20"),
            Period(9, "9", "15:30", "16:10"),
            Period(10, "10", "16:11", "16:50"),
            Period(11, "11", "17:00", "17:40"),
            Period(12, "12", "17:41", "18:20"),
            Period(13, "13", "19:00", "19:40"),
            Period(14, "14", "19:41", "20:20"),
            Period(15, "15", "20:30", "21:10"),
            Period(16, "16", "21:11", "21:50"),
        )
        val courses = listOf(
            course("early", "上午课程", 1, 2, setOf(9)),
            course("late", "晚间课程", 13, 14, setOf(9)),
        )

        val slots = ScheduleLogic.bigSlots(periods)

        assertEquals(8, slots.size)
        assertEquals(1, slots.first().startPeriod)
        assertEquals(2, slots.first().endPeriod)
        assertEquals(LocalTime.of(9, 0), slots.first().start)
        assertEquals(LocalTime.of(10, 20), slots.first().end)
        assertEquals(13, slots[6].startPeriod)
        assertEquals(14, slots[6].endPeriod)
        assertEquals(15, slots.last().startPeriod)
        assertEquals(16, slots.last().endPeriod)
        assertEquals(LocalTime.of(20, 30), slots.last().start)
        assertEquals(LocalTime.of(21, 50), slots.last().end)
    }

    @Test
    fun persistsFirstWeekOverrides() {
        val state = AppState(
            firstWeekStarts = mapOf(
                "2026-2027-1" to LocalDate.of(2026, 9, 7),
            ),
        )

        val decoded = JsonCodec.decodeState(JsonCodec.encodeState(state))

        assertEquals(
            LocalDate.of(2026, 9, 7),
            decoded.firstWeekStarts["2026-2027-1"],
        )
    }

    private fun schedule(): ScheduleSnapshot = ScheduleSnapshot(
        term = term,
        periods = listOf(
            Period(1, "1", "09:00", "09:40"),
            Period(2, "2", "10:00", "10:40"),
        ),
        courses = listOf(
            course(
                id = "course-1",
                name = "数据结构",
                startPeriod = 1,
                endPeriod = 1,
            ),
            course(
                id = "course-2",
                name = "计算机网络",
                startPeriod = 2,
                endPeriod = 2,
            ),
        ),
        fetchedAt = 0L,
    )

    private fun course(
        id: String,
        name: String,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int> = setOf(9),
        weekday: Int = testDate.dayOfWeek.value,
    ) = Course(
        id = id,
        name = name,
        teacher = "",
        room = "A101",
        campus = "",
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        weekText = "9周",
        credit = "",
        courseCode = "",
        teachingClassId = "",
        teachingClassName = "",
    )
}
