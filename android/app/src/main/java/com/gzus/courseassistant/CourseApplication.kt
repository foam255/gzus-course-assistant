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
import com.gzus.courseassistant.data.update.UpdateChecker
import com.gzus.courseassistant.data.update.UpdateCheckWorker
import com.gzus.courseassistant.data.update.UpdateManager
import okhttp3.OkHttpClient
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

        val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            24,
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UPDATE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            updateRequest,
        )
    }

    private companion object {
        const val SYNC_WORK = "daily-course-sync"
        const val REMINDER_WORK = "course-reminders"
        const val UPDATE_WORK = "daily-update-check"
    }
}

class AppContainer(private val application: Application) {
    private val secureStore = SecureFileStore(application)
    val settings = AppSettings(application)
    private val updateHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
    val updateManager = UpdateManager(
        context = application,
        checker = UpdateChecker(updateHttpClient),
        client = updateHttpClient,
    )

    private companion object {
        const val COOKIE_FILE = "cookies.json"
    }
}
