package com.gzus.courseassistant.notifications

import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.data.model.ExamSchedule
import java.time.LocalDateTime

/** 一条待推送的考试提醒。 */
data class ExamReminder(
    val exam: Exam,
    val key: String,
    val leadHours: Int,
    val onExamDay: Boolean,
    val minutesLeft: Long?,
)

/**
 * 考试提醒的纯逻辑层（不依赖 Android，可在 JVM 单元测试中验证）。
 *
 * 规则：
 * - 已公布开考时刻的考试，依次匹配提前档位（默认 24h / 3h），命中最小满足档位才算到期；
 *   同一场考试每个档位只提醒一次。
 * - 未公布时刻的考试只能按天提醒：考前 1 天一次，考试当天一次。
 * - 不生成重复提醒，同时给出「仍然有效」的键集合用于清理过期记录。
 */
object ExamReminderPlanner {
    /** 未公布时刻的考试使用的伪档位。 */
    const val DAY_ONLY_LEAD = -1

    fun reminderKey(exam: Exam, leadHours: Int): String =
        "${exam.courseName}|${exam.date}|${exam.time}|$leadHours"

    /** 当前所有仍然有效的提醒键（用于清理已过期/已结束考试的记录）。 */
    fun activeKeys(
        exams: List<Exam>,
        now: LocalDateTime,
        leadHours: Set<Int>,
    ): Set<String> {
        val leads = normalizedLeads(leadHours)
        if (leads.isEmpty()) return emptySet()
        val keys = mutableSetOf<String>()
        ExamSchedule.upcoming(exams, now).forEach { upcoming ->
            val matched = match(upcoming, leads) ?: return@forEach
            keys += matched.key
        }
        return keys
    }

    /** 尚未提醒过、且已到提醒时间的考试提醒列表。 */
    fun plan(
        exams: List<Exam>,
        now: LocalDateTime,
        leadHours: Set<Int>,
        reminded: Set<String>,
    ): List<ExamReminder> {
        val leads = normalizedLeads(leadHours)
        if (leads.isEmpty()) return emptyList()
        val result = mutableListOf<ExamReminder>()
        ExamSchedule.upcoming(exams, now).forEach { upcoming ->
            val matched = match(upcoming, leads) ?: return@forEach
            if (matched.key in reminded) return@forEach
            result += ExamReminder(
                exam = upcoming.exam,
                key = matched.key,
                leadHours = matched.lead,
                onExamDay = upcoming.daysLeft == 0L,
                minutesLeft = upcoming.minutesLeft,
            )
        }
        return result
    }

    private data class Match(val key: String, val lead: Int)

    private fun match(
        upcoming: com.gzus.courseassistant.data.model.Upcoming,
        leads: List<Int>,
    ): Match? {
        if (upcoming.hasTime) {
            val minutesLeft = upcoming.minutesLeft ?: return null
            if (minutesLeft <= 0) return null
            val lead = leads.firstOrNull { minutesLeft <= it * 60L } ?: return null
            return Match(reminderKey(upcoming.exam, lead), lead)
        }
        val daysLeft = upcoming.daysLeft ?: return null
        if (daysLeft !in 0..1) return null
        return Match(reminderKey(upcoming.exam, DAY_ONLY_LEAD), DAY_ONLY_LEAD)
    }

    private fun normalizedLeads(leadHours: Set<Int>): List<Int> =
        leadHours.filter { it > 0 }.sorted()
}
