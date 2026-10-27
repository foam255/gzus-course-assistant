package com.gzus.courseassistant.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class Term(
    val year: Int,
    val number: Int,
) {
    val label: String
        get() = "$year-${year + 1}-$number"

    val xnm: String
        get() = year.toString()

    val xqm: String
        get() = when (number) {
            1 -> "3"
            2 -> "12"
            else -> "16"
        }

    val startDate: LocalDate
        get() {
            val fixed = mapOf(
                "2025-2026-2" to LocalDate.of(2026, 3, 2),
                "2026-2027-1" to LocalDate.of(2026, 8, 31),
                "2026-2027-2" to LocalDate.of(2027, 3, 1),
            )
            fixed[label]?.let { return it }
            val anchor = when (number) {
                1 -> LocalDate.of(year, 9, 1)
                2 -> LocalDate.of(year + 1, 3, 1)
                else -> LocalDate.of(year + 1, 7, 1)
            }
            return anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }

    fun effectiveStartDate(firstWeekStart: LocalDate? = null): LocalDate =
        firstWeekStart ?: startDate

    fun weekStartDate(
        week: Int,
        firstWeekStart: LocalDate? = null,
    ): LocalDate = effectiveStartDate(firstWeekStart).plusWeeks((week - 1).toLong())

    fun currentWeek(
        today: LocalDate = LocalDate.now(),
        firstWeekStart: LocalDate? = null,
    ): Int {
        val days = today.toEpochDay() - effectiveStartDate(firstWeekStart).toEpochDay()
        return (days / 7 + 1).coerceIn(1, 20).toInt()
    }

    companion object {
        fun parse(value: String): Term? {
            val match = Regex("""(\d{4})(?:-\d{4})?-([123])""")
                .matchEntire(value.trim()) ?: return null
            return Term(
                year = match.groupValues[1].toInt(),
                number = match.groupValues[2].toInt(),
            )
        }

        fun guess(today: LocalDate = LocalDate.now()): Term = when (today.monthValue) {
            in 8..12 -> Term(today.year, 1)
            1 -> Term(today.year - 1, 1)
            else -> Term(today.year - 1, 2)
        }
    }
}

data class Period(
    val index: Int,
    val label: String,
    val start: String,
    val end: String,
)

data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val room: String,
    val campus: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Set<Int>,
    val weekText: String,
    val credit: String,
    val courseCode: String,
    val teachingClassId: String,
    val teachingClassName: String,
) {
    fun isActiveInWeek(week: Int): Boolean = week in weeks
}

data class ScheduleSnapshot(
    val term: Term,
    val periods: List<Period>,
    val courses: List<Course>,
    val fetchedAt: Long,
)

data class Grade(
    val term: Term,
    val courseName: String,
    val score: String,
    val credit: String,
    val gpa: String,
    val courseType: String,
    val note: String,
    val passed: Boolean,
)

data class Exam(
    val term: Term,
    val courseName: String,
    val date: String,
    val time: String,
    val room: String,
    val seat: String,
    val type: String,
    val note: String,
)

data class CalendarEntry(
    val title: String,
    val imageUrl: String,
    val localFile: String,
)

data class AppState(
    val schemaVersion: Int = 1,
    val selectedTerm: String = "",
    val currentTerm: String = "",
    val sessionValid: Boolean = false,
    val firstWeekStarts: Map<String, LocalDate> = emptyMap(),
    val availableTerms: List<Term> = emptyList(),
    val schedules: List<ScheduleSnapshot> = emptyList(),
    val grades: List<Grade> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val calendarEntries: List<CalendarEntry> = emptyList(),
    val lastScheduleSync: Long = 0L,
    val lastAcademicSync: Long = 0L,
    val lastCalendarSync: Long = 0L,
    val studentName: String = "",
) {
    val currentSchedule: ScheduleSnapshot?
        get() {
            if (selectedTerm.isNotBlank()) {
                return schedules.firstOrNull { it.term.label == selectedTerm }
            }
            return schedules.maxByOrNull { it.fetchedAt }
        }

    val activeSchedule: ScheduleSnapshot?
        get() {
            if (currentTerm.isNotBlank()) {
                schedules.firstOrNull { it.term.label == currentTerm }?.let { return it }
            }
            return currentSchedule
        }
}

data class CaptchaChallenge(
    val id: String,
    val imageDataUri: String,
    val timeoutSeconds: Int,
)

data class SyncSummary(
    val scheduleUpdated: Boolean,
    val gradesUpdated: Boolean,
    val examsUpdated: Boolean,
    val calendarUpdated: Boolean,
    val message: String,
)
