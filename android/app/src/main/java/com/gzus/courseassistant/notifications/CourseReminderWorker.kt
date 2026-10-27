package com.gzus.courseassistant.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.data.model.ScheduleLogic
import java.time.LocalDate
import java.time.LocalTime

class CourseReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CourseApplication ?: return Result.failure()
        val prefs = app.container.settings
        val minutes = prefs.reminderMinutes
        if (minutes <= 0) return Result.success()
        val state = app.container.repository.state.value
        val schedule = state.activeSchedule ?: return Result.success()
        val firstWeekStart = state.firstWeekStarts[schedule.term.label]
        if (!NotificationHelper.canPostNotifications(app)) return Result.success()

        val today = LocalDate.now()
        val now = LocalTime.now()
        val due = ScheduleLogic.dueOccurrences(
            schedule = schedule,
            date = today,
            time = now,
            minutesBefore = minutes,
            firstWeekStart = firstWeekStart,
        )
        if (due.isEmpty()) return Result.success()

        val todayToken = "|$today|"
        val sentKeys = prefs.remindedKeys
            .filter { todayToken in it }
            .toMutableSet()
        val pending = due.filter {
            ScheduleLogic.reminderKey(today, schedule.term, it.course.id) !in sentKeys
        }
        if (pending.isEmpty()) return Result.success()

        pending.forEach { occurrence ->
            NotificationHelper.notifyCourse(
                app,
                occurrence.course,
                occurrence.period,
                minutes,
            )
            sentKeys += ScheduleLogic.reminderKey(today, schedule.term, occurrence.course.id)
        }
        prefs.remindedKeys = sentKeys
        return Result.success()
    }
}
