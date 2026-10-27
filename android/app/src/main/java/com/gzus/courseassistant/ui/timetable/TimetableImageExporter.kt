package com.gzus.courseassistant.ui.timetable

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gzus.courseassistant.BuildConfig
import com.gzus.courseassistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TimetableImageExporter {
    fun createBitmap(
        context: Context,
        tableView: View,
        title: String,
        subtitle: String,
    ): Bitmap {
        check(tableView.width > 0 && tableView.height > 0) {
            "Timetable is not laid out"
        }
        val scale = maxOf(1f, 1080f / tableView.width)
        val density = context.resources.displayMetrics.density
        val headerHeight = 82f * density
        val bitmap = Bitmap.createBitmap(
            (tableView.width * scale).toInt(),
            ((tableView.height + headerHeight) * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.canvas))
        canvas.scale(scale, scale)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ink)
            textSize = 18f.sp(context)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.muted)
            textSize = 11f.sp(context)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.line)
            strokeWidth = density
        }
        val left = 16f * density
        canvas.drawText(title, left, 30f * density, titlePaint)
        canvas.drawText(subtitle, left, 53f * density, subtitlePaint)
        canvas.drawLine(
            left,
            headerHeight - density,
            (tableView.width - 16f * density),
            headerHeight - density,
            linePaint,
        )
        canvas.save()
        canvas.translate(0f, headerHeight)
        tableView.draw(canvas)
        canvas.restore()
        return bitmap
    }

    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Uri = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "$displayName-${EXPORT_TIME.format(LocalDateTime.now())}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/课表轻助",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Cannot create image")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Cannot open image output")
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    suspend fun saveShareFile(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "$fileName.png")
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
    }

    private fun Float.sp(context: Context): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        context.resources.displayMetrics,
    )

    private val EXPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
}
