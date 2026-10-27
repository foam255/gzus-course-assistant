package com.gzus.courseassistant.ui.calendar

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.CalendarEntry
import com.gzus.courseassistant.databinding.ActivityCalendarBinding
import com.gzus.courseassistant.databinding.DialogImageBinding
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CalendarActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalendarBinding
    private lateinit var repository: com.gzus.courseassistant.data.sync.AppRepository
    private val adapter = CalendarAdapter(::showCalendarImage)
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiUtils.applySystemBarInsets(binding.root)
        repository = (application as CourseApplication).container.repository
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.calendarList.layoutManager = LinearLayoutManager(this)
        binding.calendarList.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state.collect(::render)
            }
        }
        syncCalendar()
    }

    private fun render(state: AppState) {
        adapter.submit(state.calendarEntries)
        binding.emptyText.visibility = if (state.calendarEntries.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.progressBar.visibility = if (syncing) View.VISIBLE else View.GONE
    }

    private fun syncCalendar() {
        if (syncing) return
        syncing = true
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { repository.syncCalendar() }
            result.onFailure {
                if (repository.state.value.calendarEntries.isEmpty()) {
                    binding.emptyText.text = it.message ?: getString(R.string.net_error)
                }
            }
            if (
                result.getOrNull()?.calendarUpdated == false &&
                repository.state.value.calendarEntries.isEmpty()
            ) {
                binding.emptyText.text = getString(R.string.net_error)
            }
            syncing = false
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun showCalendarImage(entry: CalendarEntry) {
        val dialogBinding = DialogImageBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(entry.title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val path = entry.localFile.ifBlank {
                repository.syncCalendar(force = true)
                repository.state.value.calendarEntries
                    .firstOrNull { it.imageUrl == entry.imageUrl }
                    ?.localFile
                    .orEmpty()
            }
            val bitmap = withContext(Dispatchers.IO) {
                if (path.isBlank()) null else {
                    UiUtils.decodeSampledBitmap(File(path), 1800, 2400)
                }
            }
            bindImage(dialogBinding.image, dialogBinding.progress, bitmap)
        }
    }

    private fun bindImage(imageView: ImageView, progress: ProgressBar, bitmap: Bitmap?) {
        progress.visibility = View.GONE
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        } else {
            imageView.visibility = View.GONE
            Toast.makeText(this, R.string.calendar_image_error, Toast.LENGTH_SHORT).show()
        }
    }
}
