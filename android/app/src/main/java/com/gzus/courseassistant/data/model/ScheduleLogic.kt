package com.gzus.courseassistant.data.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object ScheduleLogic {
    private val clockPattern = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{1,2})""")
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    data class Occurrence(
        val course: Course,
        val period: Period,
        val start: LocalTime?,
        val end: LocalTime?,
    )

    enum class TimingState {
        UPCOMING,
        CURRENT,
        FINISHED,
        UNKNOWN,
    }

    data class BigSlot(
        val number: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val start: LocalTime?,
        val end: LocalTime?,
    )

    fun parseTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        val match = clockPattern.find(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun normalizedTime(value: String?): String =
        parseTime(value)?.format(clockFormatter) ?: value.orEmpty().trim()

    fun occurrences(
        schedule: ScheduleSnapshot,
        date: LocalDate,
        firstWeekStart: LocalDate? = null,
    ): List<Occurrence> {
        val week = schedule.term.currentWeek(date, firstWeekStart)
        val weekday = date.dayOfWeek.value
        if (weekday !in 1..5) return emptyList()
        val periodMap = schedule.periods.associateBy { it.index }
        return CourseNormalizer.normalize(schedule.courses)
            .asSequence()
            .filter {
                it.weekday in 1..5 &&
                    it.weekday == weekday &&
                    it.isActiveInWeek(week)
            }
            .map { course ->
                val startPeriod = periodMap[course.startPeriod]
                val endPeriod = periodMap[course.endPeriod] ?: startPeriod
                Occurrence(
                    course = course,
                    period = startPeriod ?: Period(
                        index = course.startPeriod,
                        label = course.startPeriod.toString(),
                        start = "",
                        end = "",
                    ),
                    start = parseTime(startPeriod?.start),
                    end = parseTime(endPeriod?.end),
                )
            }
            .sortedWith(
                compareBy<Occurrence> { it.start ?: LocalTime.MAX }
                    .thenBy { it.course.startPeriod }
                    .thenBy { it.course.name },
            )
            .toList()
    }

    fun coursesForWeek(
        schedule: ScheduleSnapshot,
        week: Int,
    ): List<Course> = CourseNormalizer.normalize(schedule.courses)
        .filter { it.weekday in 1..5 && week in it.weeks }
        .sortedWith(
            compareBy<Course> { it.weekday }
                .thenBy { it.startPeriod }
                .thenBy { it.name },
        )

    fun bigSlots(
        periods: List<Period>,
    ): List<BigSlot> {
        val periodMap = periods.associateBy { it.index }
        return (1..16 step 2).mapIndexed { index, start ->
            val end = start + 1
            BigSlot(
                number = index + 1,
                startPeriod = start,
                endPeriod = end,
                start = parseTime(periodMap[start]?.start),
                end = parseTime(periodMap[end]?.end),
            )
        }
    }

    fun currentOccurrences(
        schedule: ScheduleSnapshot,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
        firstWeekStart: LocalDate? = null,
    ): List<Occurrence> = occurrences(schedule, date, firstWeekStart)
        .filter { timingState(it, time) == TimingState.CURRENT }

    fun nextOccurrences(
        schedule: ScheduleSnapshot,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
        firstWeekStart: LocalDate? = null,
    ): List<Occurrence> {
        val future = occurrences(schedule, date, firstWeekStart)
            .filter { it.start?.isAfter(time) == true }
        val firstStart = future.firstOrNull()?.start ?: return emptyList()
        return future.takeWhile { it.start == firstStart }
    }

    fun dueOccurrences(
        schedule: ScheduleSnapshot,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
        minutesBefore: Int,
        firstWeekStart: LocalDate? = null,
    ): List<Occurrence> {
        if (minutesBefore <= 0) return emptyList()
        return occurrences(schedule, date, firstWeekStart).filter { occurrence ->
            val start = occurrence.start ?: return@filter false
            val minutes = Duration.between(time, start).toMinutes()
            minutes in 0..minutesBefore.toLong()
        }
    }

    fun reminderKey(date: LocalDate, term: Term, courseId: String): String =
        "${term.label}|$date|$courseId"

    fun timingState(
        occurrence: Occurrence,
        time: LocalTime = LocalTime.now(),
    ): TimingState {
        val start = occurrence.start ?: return TimingState.UNKNOWN
        val end = occurrence.end ?: start.plusMinutes(45)
        return when {
            !time.isBefore(end) -> TimingState.FINISHED
            !time.isBefore(start) -> TimingState.CURRENT
            else -> TimingState.UPCOMING
        }
    }
}
