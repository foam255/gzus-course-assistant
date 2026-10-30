package com.gzus.courseassistant.widget

import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.Period
import com.gzus.courseassistant.data.model.ScheduleLogic
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 桌面小组件要展示的状态。
 */
enum class WidgetKind {
    /** 正在上课 */
    CURRENT,

    /** 今天还有课，尚未开始 */
    NEXT_TODAY,

    /** 今天已无课，往后几天内还有课 */
    NEXT_UPCOMING,

    /** 没有可展示的课程 */
    EMPTY,
}

data class WidgetPlan(
    val kind: WidgetKind,
    val course: Course? = null,
    val period: Period? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val dayOffset: Int = 0,
    val scheduleAvailable: Boolean = true,
)

/** 今日课程列表里的一门课。 */
data class TodayItem(
    val course: Course,
    val period: Period,
    val start: LocalTime?,
    val end: LocalTime?,
    val timing: ScheduleLogic.TimingState,
)

/** 「今日课程」小组件要展示的整日内容。 */
data class TodayPlan(
    val weekday: Int,
    val items: List<TodayItem>,
    val scheduleAvailable: Boolean,
)

/**
 * 小组件内容推算，纯逻辑，可 JVM 单测。
 */
object WidgetCoursePlanner {
    /** 今天没课时往后找几天 */
    const val LOOKAHEAD_DAYS = 7

    fun plan(
        state: AppState,
        now: LocalDateTime = LocalDateTime.now(),
    ): WidgetPlan {
        val schedule = state.activeSchedule
            ?: return WidgetPlan(WidgetKind.EMPTY, scheduleAvailable = false)
        val firstWeekStart = state.firstWeekStarts[schedule.term.label]
        val today = now.toLocalDate()
        val time = now.toLocalTime()

        ScheduleLogic.currentOccurrences(schedule, today, time, firstWeekStart)
            .firstOrNull()
            ?.let { return it.toPlan(WidgetKind.CURRENT, 0) }

        ScheduleLogic.nextOccurrences(schedule, today, time, firstWeekStart)
            .firstOrNull()
            ?.let { return it.toPlan(WidgetKind.NEXT_TODAY, 0) }

        for (offset in 1..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset.toLong())
            val occurrence = ScheduleLogic.occurrences(schedule, date, firstWeekStart)
                .firstOrNull() ?: continue
            return occurrence.toPlan(WidgetKind.NEXT_UPCOMING, offset)
        }

        return WidgetPlan(WidgetKind.EMPTY)
    }

    /**
     * 今日课程整日列表，供大号小组件使用。
     */
    fun today(
        state: AppState,
        now: LocalDateTime = LocalDateTime.now(),
    ): TodayPlan {
        val weekday = now.dayOfWeek.value
        val schedule = state.activeSchedule
            ?: return TodayPlan(weekday, emptyList(), scheduleAvailable = false)
        val firstWeekStart = state.firstWeekStarts[schedule.term.label]
        val time = now.toLocalTime()
        val items = ScheduleLogic.occurrences(schedule, now.toLocalDate(), firstWeekStart)
            .map { occurrence ->
                TodayItem(
                    course = occurrence.course,
                    period = occurrence.period,
                    start = occurrence.start,
                    end = occurrence.end,
                    timing = ScheduleLogic.timingState(occurrence, time),
                )
            }
        return TodayPlan(weekday, items, scheduleAvailable = true)
    }

    private fun ScheduleLogic.Occurrence.toPlan(
        kind: WidgetKind,
        dayOffset: Int,
    ): WidgetPlan = WidgetPlan(
        kind = kind,
        course = course,
        period = period,
        start = start,
        end = end,
        dayOffset = dayOffset,
    )
}
