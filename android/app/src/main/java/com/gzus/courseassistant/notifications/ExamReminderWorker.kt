package com.gzus.courseassistant.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import java.time.LocalDateTime

/**
 * 每小时检查一次是否需要推送考试提醒。
 *
 * WorkManager 的周期任务在 Doze 下可能被延后，因此提醒文案不写精确到分钟。
 */
class ExamReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CourseApplication ?: return Result.success()
        val container = app.container
        val settings = container.settings
        if (!settings.examReminderEnabled) return Result.success()
        if (!NotificationHelper.canPostNotifications(app)) return Result.success()
        val exams = container.repository.state.value.exams
        if (exams.isEmpty()) return Result.success()

        val leads = settings.examReminderLeadHours
        val now = LocalDateTime.now()
        val active = ExamReminderPlanner.activeKeys(exams, now, leads)
        val pending = ExamReminderPlanner.plan(exams, now, leads, settings.examRemindedKeys)
        if (pending.isEmpty() && active == settings.examRemindedKeys) return Result.success()

        pending.forEach { reminder ->
            NotificationHelper.notifyExam(
                context = app,
                exam = reminder.exam,
                leadLabel = leadLabel(app, reminder),
                onExamDay = reminder.onExamDay,
            )
        }
        // 只保留仍然有效的键：考试结束后记录自动清空，不会无限增长。
        settings.examRemindedKeys = active
        return Result.success()
    }

    private fun leadLabel(context: Context, reminder: ExamReminder): String {
        val minutesLeft = reminder.minutesLeft
        if (minutesLeft == null) {
            return context.getString(R.string.exam_notification_lead_day)
        }
        val hours = (minutesLeft / 60L).coerceAtLeast(1L)
        return if (hours >= 24L) {
            context.getString(R.string.exam_notification_lead_days_format, hours / 24L)
        } else {
            context.getString(R.string.exam_notification_lead_hours_format, hours)
        }
    }
}
