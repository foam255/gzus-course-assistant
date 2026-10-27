package com.gzus.courseassistant.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.data.network.JwxtException
import com.gzus.courseassistant.data.network.SessionExpiredException

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? CourseApplication ?: return Result.failure()
        val repository = application.container.repository
        if (!repository.hasSession) {
            repository.markSessionUnavailable()
            return Result.success()
        }
        return try {
            repository.syncAll()
            Result.success()
        } catch (_: SessionExpiredException) {
            Result.success()
        } catch (_: JwxtException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
