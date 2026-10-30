package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.notifications.ExamReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ExamReminderPlannerTest {
    private val term = Term(2026, 1)
    private val now = LocalDateTime.of(2026, 1, 5, 10, 0)
    private val leads = setOf(24, 3)

    @Test
    fun usesSmallestMatchingLead() {
        // 明天 09:00 的考试距现在 23 小时：只进入 24 小时档。
        val tomorrow = exam(date = "2026-01-06", time = "09:00")
        val plan = ExamReminderPlanner.plan(listOf(tomorrow), now, leads, emptySet())
        assertEquals(1, plan.size)
        assertEquals(24, plan.single().leadHours)
        assertFalse(plan.single().onExamDay)

        // 当天 12:00 的考试：只进入 3 小时档，优先使用更小档位。
        val inTwoHours = exam(time = "12:00")
        val near = ExamReminderPlanner.plan(listOf(inTwoHours), now, leads, emptySet())
        assertEquals(3, near.single().leadHours)
        assertTrue(near.single().onExamDay)
    }

    @Test
    fun skipsExamsBeyondLeadWindow() {
        val farAway = exam(date = "2026-03-01", time = "09:00")
        assertTrue(ExamReminderPlanner.plan(listOf(farAway), now, leads, emptySet()).isEmpty())
    }

    @Test
    fun skipsExamsThatAlreadyStarted() {
        val started = exam(time = "09:00")
        assertTrue(ExamReminderPlanner.plan(listOf(started), now, leads, emptySet()).isEmpty())
    }

    @Test
    fun skipsAlreadyRemindedKeys() {
        val exam = exam(time = "12:00")
        val key = ExamReminderPlanner.reminderKey(exam, 3)
        assertTrue(ExamReminderPlanner.plan(listOf(exam), now, leads, setOf(key)).isEmpty())
    }

    @Test
    fun activeKeysExposeKeysThatStillMatter() {
        val exam = exam(time = "12:00")
        val keys = ExamReminderPlanner.activeKeys(listOf(exam), now, leads)
        assertEquals(setOf(ExamReminderPlanner.reminderKey(exam, 3)), keys)

        val stale = ExamReminderPlanner.activeKeys(emptyList(), now, leads)
        assertTrue(stale.isEmpty())
    }

    @Test
    fun dayOnlyExamRemindsOnExamDayAndDayBefore() {
        val todayExam = exam(time = "")
        val plan = ExamReminderPlanner.plan(listOf(todayExam), now, leads, emptySet())
        assertEquals(ExamReminderPlanner.DAY_ONLY_LEAD, plan.single().leadHours)
        assertTrue(plan.single().onExamDay)

        val tomorrow = exam(date = "2026-01-06", time = "")
        val tomorrowPlan = ExamReminderPlanner.plan(listOf(tomorrow), now, leads, emptySet())
        assertEquals(ExamReminderPlanner.DAY_ONLY_LEAD, tomorrowPlan.single().leadHours)
        assertFalse(tomorrowPlan.single().onExamDay)
    }

    @Test
    fun emptyLeadsDisablePlanning() {
        val exam = exam(time = "12:00")
        assertTrue(ExamReminderPlanner.plan(listOf(exam), now, emptySet(), emptySet()).isEmpty())
        assertTrue(ExamReminderPlanner.activeKeys(listOf(exam), now, emptySet()).isEmpty())
    }

    private fun exam(
        date: String = "2026-01-05",
        time: String = "08:00-10:00",
    ) = Exam(
        term = term,
        courseName = "数据结构",
        date = date,
        time = time,
        room = "U406",
        seat = "12",
        type = "期末考试",
        note = "",
    )
}
