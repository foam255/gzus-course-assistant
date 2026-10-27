package com.gzus.courseassistant.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.Term
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object UiUtils {
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val accents = listOf(
        "#0F766E",
        "#D95D39",
        "#3F5F9F",
        "#A56D06",
        "#8C4B9D",
        "#537B3D",
        "#A33B55",
    )

    fun todayLabel(): String = dateLabel(LocalDate.now())

    fun dateLabel(date: LocalDate): String = date.format(dateFormatter)

    fun syncLabel(timestamp: Long, never: String): String {
        if (timestamp <= 0) return never
        return timeFormatter.format(Date(timestamp))
    }

    fun applySystemBarInsets(
        root: View,
        includeIme: Boolean = false,
    ) {
        val originalLeft = root.paddingLeft
        val originalTop = root.paddingTop
        val originalRight = root.paddingRight
        val originalBottom = root.paddingBottom
        val imeType = if (includeIme) WindowInsetsCompat.Type.ime() else 0
        val insetTypes = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or imeType
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(insetTypes)
            view.setPadding(
                originalLeft + bars.left,
                originalTop + bars.top,
                originalRight + bars.right,
                originalBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun termLabel(term: Term): String =
        "${term.year}-${term.year + 1} 学年 · 第 ${term.number} 学期"

    fun courseAccent(name: String): Int {
        val index = (name.fold(0) { acc, c -> acc * 31 + c.code } and Int.MAX_VALUE) % accents.size
        return Color.parseColor(accents[index])
    }

    fun courseBackground(context: Context, course: Course): Int {
        val accent = courseAccent(course.name)
        val night = isNightMode(context)
        return if (night) {
            Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent))
        } else {
            Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
    }

    fun isNightMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun themeColor(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    fun decodeSampledBitmap(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 ||
            bounds.outHeight / sample > maxHeight * 2
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
