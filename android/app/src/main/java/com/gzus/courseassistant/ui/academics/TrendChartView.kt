package com.gzus.courseassistant.ui.academics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.TermStat
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量折线图：按学期展示加权绩点趋势。
 *
 * 不引入第三方图表库，只用 Canvas 绘制网格、折线、数据点与数值标签。
 * 传入的学期按时间正序（旧 → 新）绘制；没有绩点的学期会被跳过。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        alpha = 28
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.line)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private var labels: List<String> = emptyList()
    private var values: List<Double> = emptyList()
    private var emptyMessage: String = context.getString(R.string.analysis_gpa_trend_empty)

    fun setData(terms: List<TermStat>, emptyMessage: String? = null) {
        if (emptyMessage != null) this.emptyMessage = emptyMessage
        val points = terms
            .sortedWith(compareBy<TermStat> { it.term.year }.thenBy { it.term.number })
            .mapNotNull { stat ->
                val gpa = stat.gpa ?: return@mapNotNull null
                shortLabel(stat) to gpa
            }
        if (points.size > MAX_POINTS) {
            // 学期很多时只保留最近的若干学期，避免 x 轴标签重叠。
            val tail = points.takeLast(MAX_POINTS)
            labels = tail.map { it.first }
            values = tail.map { it.second }
        } else {
            labels = points.map { it.first }
            values = points.map { it.second }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeight = (180 * density).toInt() + paddingTop + paddingBottom
        val height = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) {
            canvas.drawText(emptyMessage, width / 2f, height / 2f, emptyPaint)
            return
        }
        // 首尾标签以数据点为中心绘制，需要按标签宽度向内缩进，避免贴边被裁掉。
        val labelHalf = labels.maxOfOrNull { labelPaint.measureText(it) }?.div(2f) ?: 0f
        val inset = max(6f * density, labelHalf + 4f * density)
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + 18f * density
        val bottom = height - paddingBottom - 18f * density
        if (right <= left || bottom <= top) return

        var minValue = values.min()
        var maxValue = values.max()
        if (maxValue - minValue < 0.5) {
            val center = (maxValue + minValue) / 2
            minValue = max(0.0, center - 0.5)
            maxValue = center + 0.5
        }
        val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

        // 三条水平网格线
        repeat(3) { index ->
            val y = top + (bottom - top) * index / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
        val path = Path()
        val areaPath = Path()
        val points = values.mapIndexed { index, value ->
            val x = if (values.size > 1) left + step * index else (left + right) / 2f
            val ratio = ((value - minValue) / range).toFloat()
            val y = bottom - (bottom - top) * ratio
            x to y
        }

        points.forEachIndexed { index, (x, y) ->
            if (index == 0) {
                path.moveTo(x, y)
                areaPath.moveTo(x, bottom)
                areaPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        if (points.size > 1) {
            areaPath.lineTo(points.last().first, bottom)
            areaPath.close()
            canvas.drawPath(areaPath, fillPaint)
        }
        if (points.size > 1) {
            canvas.drawPath(path, linePaint)
        }

        points.forEachIndexed { index, (x, y) ->
            canvas.drawCircle(x, y, 5f * density, dotHaloPaint)
            canvas.drawCircle(x, y, 3f * density, dotPaint)
            val valueText = formatValue(values[index])
            val valueHalf = valuePaint.measureText(valueText) / 2f
            val valueX = x.coerceIn(valueHalf + paddingLeft, width - paddingRight - valueHalf)
            canvas.drawText(valueText, valueX, y - 9f * density, valuePaint)
            val label = labels.getOrElse(index) { "" }
            if (label.isNotBlank()) {
                val labelX = x.coerceIn(
                    labelHalf + paddingLeft,
                    width - paddingRight - labelHalf,
                )
                canvas.drawText(label, labelX, height - paddingBottom - 4f * density, labelPaint)
            }
        }
    }

    private fun shortLabel(stat: TermStat): String {
        val year = stat.term.year % 100
        val next = (stat.term.year + 1) % 100
        return "%02d-%02d-%d".format(year, next, stat.term.number)
    }

    private fun formatValue(value: Double): String = "%.2f".format(value)

    private companion object {
        const val MAX_POINTS = 10
    }
}
