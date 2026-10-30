package com.gzus.courseassistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.ui.main.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 「正在上课 / 下一节课」桌面小组件。
 *
 * 只用 RemoteViews，不引 Glance / Compose。
 */
class NextCourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onEnabled(context: Context) {
        refresh(context)
    }

    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * 供同步、提醒等后台任务调用；没有摆放小组件时直接返回。
         */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, NextCourseWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val views = buildViews(appContext)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_next_course)
            val plan = plan(context)
            val course = plan.course

            if (course == null) {
                views.setTextViewText(
                    R.id.widgetLabel,
                    context.getString(R.string.widget_label_empty),
                )
                views.setTextViewText(
                    R.id.widgetTitle,
                    context.getString(
                        if (plan.scheduleAvailable) {
                            R.string.widget_no_course_title
                        } else {
                            R.string.widget_empty_title
                        },
                    ),
                )
                views.setTextViewText(
                    R.id.widgetDetail,
                    context.getString(
                        if (plan.scheduleAvailable) {
                            R.string.widget_no_course_hint
                        } else {
                            R.string.widget_empty_hint
                        },
                    ),
                )
                views.setTextColor(
                    R.id.widgetLabel,
                    ContextCompat.getColor(context, R.color.muted),
                )
            } else {
                views.setTextViewText(
                    R.id.widgetLabel,
                    labelText(context, plan),
                )
                views.setTextColor(
                    R.id.widgetLabel,
                    ContextCompat.getColor(
                        context,
                        if (plan.kind == WidgetKind.CURRENT) R.color.primary else R.color.muted,
                    ),
                )
                views.setTextViewText(R.id.widgetTitle, course.name)
                views.setTextViewText(R.id.widgetDetail, detailText(context, plan))
            }

            views.setOnClickPendingIntent(R.id.widgetRoot, openToday(context))
            return views
        }

        private fun plan(context: Context): WidgetPlan {
            val app = context.applicationContext as? CourseApplication
                ?: return WidgetPlan(WidgetKind.EMPTY, scheduleAvailable = false)
            return WidgetCoursePlanner.plan(
                app.container.repository.state.value,
                LocalDateTime.now(),
            )
        }

        private fun labelText(context: Context, plan: WidgetPlan): String = when (plan.kind) {
            WidgetKind.CURRENT -> context.getString(R.string.widget_label_current)
            WidgetKind.NEXT_TODAY -> context.getString(R.string.widget_label_next)
            WidgetKind.NEXT_UPCOMING -> context.getString(
                R.string.widget_label_next_day,
                dayLabel(context, plan.dayOffset),
            )
            WidgetKind.EMPTY -> context.getString(R.string.widget_label_empty)
        }

        private fun dayLabel(context: Context, dayOffset: Int): String {
            if (dayOffset <= 1) return context.getString(R.string.widget_day_tomorrow)
            val date = LocalDate.now().plusDays(dayOffset.toLong())
            val names = context.resources.getStringArray(R.array.widget_weekdays)
            val index = date.dayOfWeek.value - 1
            return names.getOrElse(index) { context.getString(R.string.widget_day_soon, dayOffset) }
        }

        private fun detailText(context: Context, plan: WidgetPlan): String {
            val timeText = timeRange(plan.start, plan.end)
            val course = plan.course
            val room = course?.room.orEmpty().trim()
            val campus = course?.campus.orEmpty().trim()
            val place = when {
                room.isBlank() -> campus
                campus.isBlank() || room.contains(campus) -> room
                else -> "$campus $room"
            }
            val parts = listOfNotNull(timeText, place.takeIf { it.isNotBlank() })
            return if (parts.isEmpty()) {
                context.getString(R.string.widget_detail_unknown)
            } else {
                parts.joinToString(" · ")
            }
        }

        private fun timeRange(start: LocalTime?, end: LocalTime?): String? = when {
            start != null && end != null -> "${start.format(timeFormatter)}-${end.format(timeFormatter)}"
            start != null -> start.format(timeFormatter)
            else -> null
        }

        private fun openToday(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
