package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.Period
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.widget.WidgetCoursePlanner
import com.gzus.courseassistant.widget.WidgetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WidgetCoursePlannerTest {
    private val term = Term(2026, 1)
    private val monday = LocalDate.of(2026, 10, 26)

    @Test
    fun reportsCurrentCourseWhileInClass() {
        val plan = WidgetCoursePlanner.plan(
            state(schedule(course("c1", "数据结构", weekday = 1, start = 1, end = 1))),
            LocalDateTime.of(monday, LocalTime.of(9, 20)),
        )

        assertEquals(WidgetKind.CURRENT, plan.kind)
        assertEquals("数据结构", plan.course?.name)
        assertEquals(LocalTime.of(9, 0), plan.start)
        assertEquals(LocalTime.of(9, 40), plan.end)
        assertEquals(0, plan.dayOffset)
        assertTrue(plan.scheduleAvailable)
    }

    @Test
    fun reportsNextCourseTodayBetweenClasses() {
        val plan = WidgetCoursePlanner.plan(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1),
                    course("c2", "计算机网络", weekday = 1, start = 2, end = 2),
                ),
            ),
            LocalDateTime.of(monday, LocalTime.of(9, 45)),
        )

        assertEquals(WidgetKind.NEXT_TODAY, plan.kind)
        assertEquals("计算机网络", plan.course?.name)
        assertEquals(0, plan.dayOffset)
    }

    @Test
    fun looksAheadToTomorrowWhenTodayFinished() {
        val plan = WidgetCoursePlanner.plan(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1),
                    course("c2", "高等数学", weekday = 2, start = 1, end = 2),
                ),
            ),
            LocalDateTime.of(monday, LocalTime.of(20, 0)),
        )

        assertEquals(WidgetKind.NEXT_UPCOMING, plan.kind)
        assertEquals("高等数学", plan.course?.name)
        assertEquals(1, plan.dayOffset)
    }

    @Test
    fun skipsWeekendWhenLookingAhead() {
        val friday = LocalDate.of(2026, 10, 30)
        val plan = WidgetCoursePlanner.plan(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1, weeks = setOf(9, 10)),
                ),
            ),
            LocalDateTime.of(friday, LocalTime.of(20, 0)),
        )

        assertEquals(WidgetKind.NEXT_UPCOMING, plan.kind)
        assertEquals("数据结构", plan.course?.name)
        assertEquals(3, plan.dayOffset)
    }

    @Test
    fun ignoresCoursesOutsideCurrentWeek() {
        val plan = WidgetCoursePlanner.plan(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1, weeks = setOf(8)),
                ),
            ),
            LocalDateTime.of(monday, LocalTime.of(9, 20)),
        )

        assertEquals(WidgetKind.EMPTY, plan.kind)
        assertNull(plan.course)
        assertTrue(plan.scheduleAvailable)
    }

    @Test
    fun honoursManualFirstWeekStart() {
        val plan = WidgetCoursePlanner.plan(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1, weeks = setOf(1)),
                ),
                firstWeekStart = monday,
            ),
            LocalDateTime.of(monday, LocalTime.of(9, 20)),
        )

        assertEquals(WidgetKind.CURRENT, plan.kind)
        assertEquals("数据结构", plan.course?.name)
    }

    @Test
    fun reportsEmptyWhenScheduleMissing() {
        val plan = WidgetCoursePlanner.plan(AppState(), LocalDateTime.of(monday, LocalTime.of(9, 20)))

        assertEquals(WidgetKind.EMPTY, plan.kind)
        assertNull(plan.course)
        assertFalse(plan.scheduleAvailable)
    }

    @Test
    fun listsWholeDayWithTiming() {
        val plan = WidgetCoursePlanner.today(
            state(
                schedule(
                    course("c1", "数据结构", weekday = 1, start = 1, end = 1),
                    course("c2", "计算机网络", weekday = 1, start = 2, end = 2),
                ),
            ),
            LocalDateTime.of(monday, LocalTime.of(9, 20)),
        )

        assertEquals(2, plan.items.size)
        assertTrue(plan.scheduleAvailable)
        assertEquals("数据结构", plan.items[0].course.name)
        assertEquals(ScheduleLogic.TimingState.CURRENT, plan.items[0].timing)
        assertEquals("计算机网络", plan.items[1].course.name)
        assertEquals(ScheduleLogic.TimingState.UPCOMING, plan.items[1].timing)
    }

    @Test
    fun todayIsEmptyOnWeekend() {
        val saturday = LocalDate.of(2026, 10, 31)
        val plan = WidgetCoursePlanner.today(
            state(schedule(course("c1", "数据结构", weekday = 1, start = 1, end = 1))),
            LocalDateTime.of(saturday, LocalTime.of(10, 0)),
        )

        assertTrue(plan.scheduleAvailable)
        assertTrue(plan.items.isEmpty())
        assertEquals(6, plan.weekday)
    }

    @Test
    fun todayReportsMissingSchedule() {
        val plan = WidgetCoursePlanner.today(AppState(), LocalDateTime.of(monday, LocalTime.of(9, 20)))

        assertFalse(plan.scheduleAvailable)
        assertTrue(plan.items.isEmpty())
    }

    private fun state(
        snapshot: ScheduleSnapshot,
        firstWeekStart: LocalDate? = null,
    ) = AppState(
        selectedTerm = snapshot.term.label,
        currentTerm = snapshot.term.label,
        schedules = listOf(snapshot),
        firstWeekStarts = firstWeekStart?.let { mapOf(snapshot.term.label to it) } ?: emptyMap(),
    )

    private fun schedule(vararg courses: Course) = ScheduleSnapshot(
        term = term,
        periods = listOf(
            Period(1, "1", "09:00", "09:40"),
            Period(2, "2", "10:00", "10:40"),
            Period(3, "3", "11:00", "11:40"),
        ),
        courses = courses.toList(),
        fetchedAt = 0L,
    )

    private fun course(
        id: String,
        name: String,
        weekday: Int,
        start: Int,
        end: Int,
        weeks: Set<Int> = setOf(9),
    ) = Course(
        id = id,
        name = name,
        teacher = "",
        room = "A101",
        campus = "",
        weekday = weekday,
        startPeriod = start,
        endPeriod = end,
        weeks = weeks,
        weekText = "",
        credit = "",
        courseCode = "",
        teachingClassId = "",
        teachingClassName = "",
    )
}
