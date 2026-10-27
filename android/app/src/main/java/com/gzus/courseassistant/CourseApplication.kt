package com.gzus.courseassistant

import android.app.Application
import com.gzus.courseassistant.data.local.AppSettings
import com.gzus.courseassistant.data.local.SecureFileStore
import com.gzus.courseassistant.data.network.JwxtClient
import com.gzus.courseassistant.data.network.StoredCookieJar
import com.gzus.courseassistant.data.sync.AppRepository
import com.gzus.courseassistant.notifications.CourseReminderWorker
import com.gzus.courseassistant.notifications.NotificationHelper
import com.gzus.courseassistant.data.sync.SyncWorker
import java.util.concurrent.TimeUnit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager

class CourseApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        scheduleWorkers()
    }

    private fun scheduleWorkers() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest,
        )

        val reminderRequest = PeriodicWorkRequestBuilder<CourseReminderWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            REMINDER_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest,
        )
    }

    private companion object {
        const val SYNC_WORK = "daily-course-sync"
        const val REMINDER_WORK = "course-reminders"
    }
}

class AppContainer(private val application: Application) {
    private val secureStore = SecureFileStore(application)
    val settings = AppSettings(application)

    val cookieJar = StoredCookieJar(
        initialCookies = StoredCookieJar.fromJson(secureStore.read(COOKIE_FILE)),
        onChanged = { cookies ->
            secureStore.write(COOKIE_FILE, StoredCookieJar.toJson(cookies))
        },
    )

    val client = JwxtClient(cookieJar)
    val repository = AppRepository(
        application = application,
        secureStore = secureStore,
        cookieJar = cookieJar,
        client = client,
        settings = settings,
    )

    private companion object {
        const val COOKIE_FILE = "cookies.json"
    }
}
