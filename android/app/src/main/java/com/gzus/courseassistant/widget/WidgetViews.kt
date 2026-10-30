package com.gzus.courseassistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.ui.main.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 三个小组件共用的渲染逻辑：大号「今日课程」、中号「下一节课」、迷你「下一节课」。
 */
internal object WidgetViews {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private const val MAX_ROWS = 5
    private val todayRowIds = intArrayOf(
        R.id.widgetRow1,
        R.id.widgetRow2,
        R.id.widgetRow3,
        R.id.widgetRow4,
        R.id.widgetRow5,
    )

    /** 同步、提醒、回到前台时调用；没有摆放的小组件会被跳过。 */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        update(
            appContext,
            TodayCoursesWidgetProvider::class.java,
            IntArray(0),
            buildToday(appContext),
        )
        update(
            appContext,
            NextCourseWidgetProvider::class.java,
            IntArray(0),
            buildNext(appContext),
        )
        update(
            appContext,
            MiniCourseWidgetProvider::class.java,
            IntArray(0),
            buildMini(appContext),
        )
    }

    fun update(
        context: Context,
        provider: Class<out AppWidgetProvider>,
        ids: IntArray,
        views: RemoteViews,
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val target = if (ids.isEmpty()) {
            manager.getAppWidgetIds(ComponentName(context, provider))
        } else {
            ids
        }
        target.forEach { id -> manager.updateAppWidget(id, views) }
    }

    fun buildToday(
        context: Context,
        plan: TodayPlan = todayPlan(context),
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_courses)
        views.setTextViewText(
            R.id.widgetLabel,
            when {
                !plan.scheduleAvailable -> context.getString(R.string.widget_label_empty)
                plan.items.isEmpty() ->
                    context.getString(R.string.widget_today_label, weekdayLabel(context, plan.weekday))
                else -> context.getString(
                    R.string.widget_today_label_count,
                    weekdayLabel(context, plan.weekday),
                    plan.items.size,
                )
            },
        )

        val rows = plan.items.take(MAX_ROWS)
        if (rows.isEmpty()) {
            views.setTextViewText(
                R.id.widgetRow1,
                context.getString(
                    if (plan.scheduleAvailable) {
                        R.string.widget_no_course_title
                    } else {
                        R.string.widget_empty_title
                    },
                ),
            )
            views.setTextColor(
                R.id.widgetRow1,
                ContextCompat.getColor(context, R.color.muted),
            )
            views.setViewVisibility(R.id.widgetRow1, View.VISIBLE)
            todayRowIds.drop(1).forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widgetMore, View.GONE)
        } else {
            rows.forEachIndexed { index, item ->
                val rowId = todayRowIds[index]
                views.setTextViewText(rowId, todayRowText(context, item))
                views.setTextColor(
                    rowId,
                    ContextCompat.getColor(
                        context,
                        when (item.timing) {
                            ScheduleLogic.TimingState.CURRENT -> R.color.primary
                            ScheduleLogic.TimingState.FINISHED -> R.color.muted
                            else -> R.color.ink
                        },
                    ),
                )
                views.setViewVisibility(rowId, View.VISIBLE)
            }
            for (index in rows.size until MAX_ROWS) {
                views.setViewVisibility(todayRowIds[index], View.GONE)
            }
            val hidden = plan.items.size - rows.size
            if (hidden > 0) {
                views.setTextViewText(
                    R.id.widgetMore,
                    context.getString(R.string.widget_more_courses, hidden),
                )
                views.setViewVisibility(R.id.widgetMore, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetMore, View.GONE)
            }
        }

        views.setOnClickPendingIntent(R.id.widgetRoot, openToday(context))
        return views
    }

    fun buildNext(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next_course)
        val plan = plan(context)
        val course = plan.course
        if (course == null) {
            views.setTextViewText(R.id.widgetLabel, context.getString(R.string.widget_label_empty))
            views.setTextColor(
                R.id.widgetLabel,
                ContextCompat.getColor(context, R.color.muted),
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
        } else {
            views.setTextViewText(R.id.widgetLabel, labelText(context, plan))
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

    fun buildMini(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_mini_course)
        val plan = plan(context)
        val course = plan.course
        val text = when {
            course == null -> context.getString(R.string.widget_mini_empty)
            plan.kind == WidgetKind.CURRENT ->
                context.getString(R.string.widget_mini_current, course.name)
            plan.kind == WidgetKind.NEXT_TODAY -> context.getString(
                R.string.widget_mini_time,
                timeText(plan.start),
                course.name,
            ).trim()
            else -> context.getString(
                R.string.widget_mini_day,
                dayLabel(context, plan.dayOffset),
                timeText(plan.start),
                course.name,
            ).trim()
        }
        views.setTextViewText(R.id.widgetMiniText, text)
        views.setTextColor(
            R.id.widgetMiniText,
            ContextCompat.getColor(
                context,
                if (plan.kind == WidgetKind.CURRENT) R.color.primary else R.color.ink,
            ),
        )
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

    private fun todayPlan(context: Context): TodayPlan {
        val app = context.applicationContext as? CourseApplication
            ?: return TodayPlan(1, emptyList(), scheduleAvailable = false)
        return WidgetCoursePlanner.today(
            app.container.repository.state.value,
            LocalDateTime.now(),
        )
    }

    private fun todayRowText(context: Context, item: TodayItem): String {
        val time = timeText(item.start)
        val room = compactRoom(item.course)
        val name = if (item.timing == ScheduleLogic.TimingState.CURRENT) {
            "▸ ${item.course.name}"
        } else {
            item.course.name
        }
        return if (room.isBlank()) {
            if (time.isBlank()) name else context.getString(R.string.widget_row, time, name)
        } else {
            context.getString(R.string.widget_row_with_room, time, name, room)
        }.trim()
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

    private fun weekdayLabel(context: Context, weekday: Int): String {
        val names = context.resources.getStringArray(R.array.widget_weekdays)
        return names.getOrElse(weekday - 1) { "" }
    }

    private fun dayLabel(context: Context, dayOffset: Int): String {
        if (dayOffset <= 1) return context.getString(R.string.widget_day_tomorrow)
        val date = LocalDate.now().plusDays(dayOffset.toLong())
        return weekdayLabel(context, date.dayOfWeek.value)
            .ifBlank { context.getString(R.string.widget_day_soon, dayOffset) }
    }

    private fun detailText(context: Context, plan: WidgetPlan): String {
        val time = timeText(plan.start)
        val room = compactRoom(plan.course)
        val parts = listOfNotNull(time.takeIf { it.isNotBlank() }, room.takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) {
            context.getString(R.string.widget_detail_unknown)
        } else {
            parts.joinToString(" · ")
        }
    }

    /** 教室号去掉校区前缀，窄卡片才放得下。 */
    private fun compactRoom(course: com.gzus.courseassistant.data.model.Course?): String {
        if (course == null) return ""
        val room = course.room.removePrefix(course.campus).trimStart(' ', '·').trim()
        return room.ifBlank { course.campus.trim() }
    }

    private fun timeText(time: LocalTime?): String =
        time?.format(timeFormatter).orEmpty()

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
