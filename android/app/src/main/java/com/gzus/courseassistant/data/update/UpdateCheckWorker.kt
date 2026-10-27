package com.gzus.courseassistant.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.notifications.NotificationHelper

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CourseApplication ?: return Result.failure()
        val settings = app.container.settings
        return try {
            val update = app.container.updateManager.checkLatest()
            if (
                update != null &&
                update.versionName != settings.lastNotifiedUpdateVersion &&
                NotificationHelper.canPostNotifications(app)
            ) {
                NotificationHelper.notifyUpdate(app, update.versionName)
                settings.lastNotifiedUpdateVersion = update.versionName
            }
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}
