package com.gzus.courseassistant.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.Period
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.ui.main.MainActivity
import java.time.LocalDate
import java.time.LocalTime

object NotificationHelper {
    private const val COURSE_CHANNEL = "course_reminders"
    private const val UPDATE_CHANNEL = "app_updates"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                COURSE_CHANNEL,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyCourse(
        context: Context,
        course: Course,
        period: Period,
        minutesBefore: Int,
    ) {
        if (!canPostNotifications(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            course.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = listOf(period.start, period.end)
            .filter { it.isNotBlank() }
            .joinToString("-")
        val content = buildString {
            if (time.isNotBlank()) append(time)
            if (course.room.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(course.room)
            }
            if (course.teacher.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(course.teacher)
            }
        }
        val notification = NotificationCompat.Builder(context, COURSE_CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle("${minutesBefore} 分钟后 · ${course.name}")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(course.id.hashCode(), notification)
    }

    @SuppressLint("MissingPermission")
    fun notifyUpdate(context: Context, versionName: String) {
        if (!canPostNotifications(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_MORE, true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                context.getString(R.string.update_notification_title, versionName),
            )
            .setContentText(context.getString(R.string.update_notification_message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(
            UPDATE_NOTIFICATION_ID,
            notification,
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun findDueCourses(
        schedule: ScheduleSnapshot,
        nowDate: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now(),
        minutesBefore: Int,
        firstWeekStart: LocalDate? = null,
    ): List<Pair<Course, Period>> = ScheduleLogic.dueOccurrences(
        schedule = schedule,
        date = nowDate,
        time = nowTime,
        minutesBefore = minutesBefore,
        firstWeekStart = firstWeekStart,
    ).map { it.course to it.period }

    fun findDueCourse(
        schedule: ScheduleSnapshot,
        nowDate: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now(),
        minutesBefore: Int,
        firstWeekStart: LocalDate? = null,
    ): Pair<Course, Period>? = findDueCourses(
        schedule = schedule,
        nowDate = nowDate,
        nowTime = nowTime,
        minutesBefore = minutesBefore,
        firstWeekStart = firstWeekStart,
    ).firstOrNull()

    private const val UPDATE_NOTIFICATION_ID = 21001
}
