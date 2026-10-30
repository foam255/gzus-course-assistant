package com.gzus.courseassistant.data.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

/**
 * 一场即将到来的考试。[start] 为 null 表示日期无法解析。
 *
 * @param daysLeft 距今天的自然日差（今天 = 0）；[start] 为 null 时为 null。
 * @param minutesLeft 距开考的分钟数；考试时间未公布时为 null。
 * @param hasTime 教务是否给出了具体开考时刻。
 */
data class Upcoming(
    val exam: Exam,
    val start: LocalDateTime?,
    val daysLeft: Long?,
    val minutesLeft: Long?,
    val hasTime: Boolean,
) {
    val isToday: Boolean
        get() = daysLeft == 0L
}

/**
 * 考试时间解析与倒计时计算。纯函数，便于单元测试。
 *
 * 教务系统的日期/时间字段格式并不统一（`2026-01-05`、`2026/1/5`、`1月5日`，
 * 时间可能是 `08:00-10:00` 或独立字段），解析失败时返回 null 而不是抛异常，
 * 由调用方降级为「按教务原始顺序展示」。
 */
object ExamSchedule {
    private val DATE_PATTERNS = listOf("uuuu-M-d", "uuuu/M/d", "uuuu.M.d", "uuuuMMdd")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    private val NUMERIC_DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val CHINESE_DATE = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    private val TIME_PATTERN = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")

    fun parseStart(exam: Exam): LocalDateTime? = parseStartDetail(exam)?.time

    fun hasTime(exam: Exam): Boolean = parseStartDetail(exam)?.hasTime ?: false

    /** 全部考试按开考时间升序；无法解析时间的考试保持原顺序排在最后。 */
    fun upcoming(exams: List<Exam>, now: LocalDateTime): List<Upcoming> {
        return exams.map { exam -> buildUpcoming(exam, now) }
            .sortedWith(
                compareBy<Upcoming> { it.start == null }
                    .thenBy { it.start },
            )
    }

    /** 最近一场尚未结束的考试；全部已结束（或无法解析）时返回 null。 */
    fun next(exams: List<Exam>, now: LocalDateTime): Upcoming? =
        upcoming(exams, now).firstOrNull { upcoming ->
            val start = upcoming.start ?: return@firstOrNull false
            // 未公布时刻的考试只在自然日维度判断，当天整天都算「即将到来」。
            if (upcoming.hasTime) {
                !start.isBefore(now)
            } else {
                !start.toLocalDate().isBefore(now.toLocalDate())
            }
        }

    /** 按自然日统计距离最近一场考试的天数；没有可解析的考试时返回 null。 */
    fun daysUntilNext(exams: List<Exam>, now: LocalDateTime): Long? = next(exams, now)?.daysLeft

    private fun buildUpcoming(exam: Exam, now: LocalDateTime): Upcoming {
        val detail = parseStartDetail(exam)
        val start = detail?.time
        if (start == null) {
            return Upcoming(exam, null, null, null, hasTime = false)
        }
        val daysLeft = start.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
        val minutesLeft = if (detail.hasTime) {
            ChronoUnit.MINUTES.between(now, start)
        } else {
            null
        }
        return Upcoming(exam, start, daysLeft, minutesLeft, detail.hasTime)
    }

    data class ParsedStart(
        val time: LocalDateTime,
        val hasTime: Boolean,
    )

    fun parseStartDetail(exam: Exam): ParsedStart? {
        val date = parseDate(exam.date, exam.term) ?: return null
        val time = parseTime(exam.time) ?: parseTime(exam.date)
        return ParsedStart(
            time = LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT),
            hasTime = time != null,
        )
    }

    fun parseDate(value: String, term: Term): LocalDate? {
        val text = value.trim()
        if (text.isEmpty()) return null
        DATE_PATTERNS.forEach { formatter ->
            val parsed = runCatching { LocalDate.parse(text, formatter) }.getOrNull()
            if (parsed != null) return parsed
        }
        NUMERIC_DATE.find(text)?.let { match ->
            DATE_PATTERNS.forEach { formatter ->
                val parsed = runCatching { LocalDate.parse(match.value, formatter) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        CHINESE_DATE.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return@let
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            // 学年语义：8-12 月属于上半学年，1-7 月属于下半学年。
            val year = if (month >= 8) term.year else term.year + 1
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        return null
    }

    fun parseTime(value: String): LocalTime? {
        val match = TIME_PATTERN.find(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun minutesBetween(now: LocalDateTime, target: LocalDateTime): Long =
        Duration.between(now, target).toMinutes()
}
