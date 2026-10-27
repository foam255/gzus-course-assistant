package com.gzus.courseassistant.ui.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.util.UiUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val headerHeight = 58f.dp
    private val rowHeight = 82f.dp
    private val timeWidth = 48f.dp
    private val gridInset = 1f.dp

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        strokeWidth = 1f.dp
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textSize = 10f.sp
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 10f.sp
        textAlign = Paint.Align.CENTER
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textSize = 9f.sp
        textAlign = Paint.Align.CENTER
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 9f.sp
        textAlign = Paint.Align.CENTER
    }
    private val coursePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val courseTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 9f.sp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val courseRoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        alpha = 220
        textSize = 7.5f.sp
        textAlign = Paint.Align.CENTER
    }

    private var schedule: ScheduleSnapshot? = null
    private var selectedWeek: Int = 1
    private var firstWeekStart: LocalDate? = null
    private var weekCourses: List<Course> = emptyList()
    private var bigSlots: List<ScheduleLogic.BigSlot> = emptyList()
    private val hitAreas = mutableListOf<Pair<RectF, Course>>()
    var onCourseClick: ((Course) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(
        snapshot: ScheduleSnapshot?,
        week: Int,
        firstWeekStart: LocalDate? = null,
    ) {
        schedule = snapshot
        selectedWeek = week.coerceAtLeast(1)
        this.firstWeekStart = firstWeekStart
        weekCourses = snapshot?.let {
            ScheduleLogic.coursesForWeek(it, selectedWeek)
        }.orEmpty()
        bigSlots = snapshot?.let {
            ScheduleLogic.bigSlots(it.periods)
        }.orEmpty()
        hitAreas.clear()
        contentDescription = snapshot?.let {
            "${it.term.label} 第 $selectedWeek 周课程表，共 ${weekCourses.size} 门课程"
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec)
            else -> resources.displayMetrics.widthPixels
        }
        val height = headerHeight + rowHeight * bigSlots.size + 6f.dp
        setMeasuredDimension(width, resolveSize(height.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dayWidth = (width - timeWidth) / 5f
        drawHeader(canvas, dayWidth)
        drawGrid(canvas, dayWidth)
        drawCourses(canvas, dayWidth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && isClickable) {
            val match = hitAreas.asReversed().firstOrNull {
                it.first.contains(event.x, event.y)
            }
            if (match != null) {
                performClick()
                onCourseClick?.invoke(match.second)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawHeader(canvas: Canvas, dayWidth: Float) {
        val weekStart = schedule?.term?.weekStartDate(selectedWeek, firstWeekStart)
        val today = LocalDate.now()
        val dayNames = listOf("周一", "周二", "周三", "周四", "周五")
        val monthText = weekStart?.format(MONTH_FORMATTER).orEmpty()
        canvas.drawText(monthText, timeWidth / 2f, 22f.dp, headerPaint)

        for (day in 1..5) {
            val top = 3f.dp
            val bottom = headerHeight - 3f.dp
            val left = timeWidth + (day - 1) * dayWidth + 2f.dp
            val right = timeWidth + day * dayWidth - 2f.dp
            val centerX = (left + right) / 2f
            val date = weekStart?.plusDays((day - 1).toLong())
            val isToday = date == today
            if (isToday) {
                canvas.drawRoundRect(
                    RectF(left, top, right, bottom),
                    7f.dp,
                    7f.dp,
                    todayPaint,
                )
            }
            headerPaint.color = ContextCompat.getColor(
                context,
                if (isToday) R.color.white else R.color.ink,
            )
            datePaint.color = ContextCompat.getColor(
                context,
                if (isToday) R.color.white else R.color.muted,
            )
            canvas.drawText(dayNames[day - 1], centerX, 20f.dp, headerPaint)
            val dateText = when {
                date == null -> ""
                date.monthValue != weekStart?.monthValue ->
                    "${date.dayOfMonth}/${date.monthValue}"
                else -> date.dayOfMonth.toString()
            }
            canvas.drawText(dateText, centerX, 40f.dp, datePaint)
        }
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, linePaint)
    }

    private fun drawGrid(canvas: Canvas, dayWidth: Float) {
        for ((row, slot) in bigSlots.withIndex()) {
            val top = headerHeight + row * rowHeight
            if (row > 0) {
                canvas.drawLine(timeWidth, top, width.toFloat(), top, linePaint)
            }
            val centerX = timeWidth / 2f
            timePaint.color = ContextCompat.getColor(context, R.color.ink)
            slotPaint.color = ContextCompat.getColor(context, R.color.muted)
            canvas.drawText(
                slot.start?.format(TIME_FORMATTER).orEmpty(),
                centerX,
                top + 22f.dp,
                timePaint,
            )
            canvas.drawText(
                "${slot.startPeriod}-${slot.endPeriod}",
                centerX,
                top + 42f.dp,
                slotPaint,
            )
            canvas.drawText(
                slot.end?.format(TIME_FORMATTER).orEmpty(),
                centerX,
                top + 62f.dp,
                timePaint,
            )
        }

        val tableBottom = headerHeight + rowHeight * bigSlots.size
        if (bigSlots.isNotEmpty()) {
            canvas.drawLine(timeWidth, tableBottom, width.toFloat(), tableBottom, linePaint)
        }
        for (day in 0..5) {
            val x = timeWidth + day * dayWidth
            canvas.drawLine(x, 0f, x, tableBottom, linePaint)
        }
    }

    private fun drawCourses(canvas: Canvas, dayWidth: Float) {
        val spans = weekCourses.mapNotNull { course ->
            courseSpan(course)?.let { course to it }
        }.toMap()
        val lanes = assignLanes(spans)
        weekCourses.forEach { course ->
            val span = spans[course] ?: return@forEach
            val lane = lanes[course.id] ?: Lane(0, 1)
            val leftBase = timeWidth + (course.weekday - 1) * dayWidth
            val laneWidth = dayWidth / lane.count
            val left = leftBase + lane.index * laneWidth + 2f.dp
            val right = leftBase + (lane.index + 1) * laneWidth - 2f.dp
            val top = headerHeight + span.start * rowHeight + 2f.dp
            val bottom = headerHeight + (span.end + 1) * rowHeight - 2f.dp
            if (right <= left || bottom <= top) return@forEach

            val rect = RectF(left, top, right, bottom)
            hitAreas += rect to course
            coursePaint.color = UiUtils.courseAccent(course.name)
            canvas.drawRoundRect(rect, 5f.dp, 5f.dp, coursePaint)

            val available = rect.width() - 8f.dp
            val lineHeight = 12.5f.dp
            val compactRoom = compactRoom(course)
            val roomReserve = if (compactRoom.isBlank()) 5f.dp else 18f.dp
            val maxLines = ((rect.height() - roomReserve - 12f.dp) / lineHeight)
                .toInt()
                .coerceIn(1, 6)
            val lines = wrapCourseName(
                text = course.name,
                paint = courseTitlePaint,
                maxWidth = available,
                maxLines = maxLines,
            )
            val centerX = rect.centerX()
            var baseline = top + 15f.dp
            lines.forEach { line ->
                canvas.drawText(line, centerX, baseline, courseTitlePaint)
                baseline += lineHeight
            }
            if (compactRoom.isNotBlank() && rect.height() >= 28f.dp) {
                canvas.drawText(
                    ellipsize(compactRoom, courseRoomPaint, available),
                    centerX,
                    bottom - 7f.dp,
                    courseRoomPaint,
                )
            }
        }
    }

    private fun courseSpan(course: Course): CourseSpan? {
        val start = bigSlots.indexOfFirst {
            course.startPeriod in it.startPeriod..it.endPeriod
        }
        val end = bigSlots.indexOfFirst {
            course.endPeriod in it.startPeriod..it.endPeriod
        }
        if (start < 0 || end < 0) return null
        return CourseSpan(minOf(start, end), maxOf(start, end))
    }

    private fun assignLanes(spans: Map<Course, CourseSpan>): Map<String, Lane> {
        val result = mutableMapOf<String, Lane>()
        spans.entries.groupBy { it.key.weekday }.values.forEach { dayEntries ->
            val sorted = dayEntries.sortedBy { it.value.start }
            val groups = mutableListOf<MutableList<Map.Entry<Course, CourseSpan>>>()
            var group = mutableListOf<Map.Entry<Course, CourseSpan>>()
            var groupEnd = -1
            sorted.forEach { entry ->
                if (group.isNotEmpty() && entry.value.start > groupEnd) {
                    groups += group
                    group = mutableListOf()
                    groupEnd = -1
                }
                group += entry
                groupEnd = maxOf(groupEnd, entry.value.end)
            }
            if (group.isNotEmpty()) groups += group

            groups.forEach { overlapping ->
                val laneEnds = mutableListOf<Int>()
                overlapping.forEach { entry ->
                    var lane = laneEnds.indexOfFirst { it < entry.value.start }
                    if (lane < 0) {
                        lane = laneEnds.size
                        laneEnds += entry.value.end
                    } else {
                        laneEnds[lane] = entry.value.end
                    }
                    result[entry.key.id] = Lane(lane, 0)
                }
                overlapping.forEach {
                    result[it.key.id] = Lane(result.getValue(it.key.id).index, laneEnds.size)
                }
            }
        }
        return result
    }

    private fun wrapCourseName(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxLines: Int,
    ): List<String> {
        if (text.isBlank() || maxWidth <= 0f) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.forEachIndexed { index, character ->
            val candidate = current.toString() + character
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += current.toString()
                current = StringBuilder().append(character)
                if (lines.size == maxLines) {
                    val remaining = current.toString() + text.substring(index + 1)
                    lines[lines.lastIndex] = ellipsize(remaining, paint, maxWidth)
                    return lines
                }
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) {
            lines += current.toString()
        }
        return lines
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        return TextEllipsis.ellipsize(text, paint, width.toInt())
    }

    private fun compactRoom(course: Course): String {
        val withoutCampus = if (
            course.campus.isNotBlank() &&
            course.room.startsWith(course.campus)
        ) {
            course.room.removePrefix(course.campus).trimStart(' ', '·')
        } else {
            course.room
        }
        return withoutCampus.trim().substringAfterLast(' ')
    }

    private val Float.dp: Float
        get() = this * density

    private val Float.sp: Float
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            this,
            resources.displayMetrics,
        )

    private data class CourseSpan(val start: Int, val end: Int)

    private data class Lane(val index: Int, val count: Int)

    private object TextEllipsis {
        fun ellipsize(text: String, paint: Paint, width: Int): String {
            if (width <= 0) return ""
            val ellipsis = "…"
            if (paint.measureText(ellipsis) > width) return ""
            var end = text.length
            while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > width) {
                end--
            }
            return text.substring(0, end) + ellipsis
        }
    }

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M月", Locale.CHINA)
    }
}
