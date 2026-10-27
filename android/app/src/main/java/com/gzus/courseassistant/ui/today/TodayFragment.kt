package com.gzus.courseassistant.ui.today

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzus.courseassistant.R
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.databinding.FragmentTodayBinding
import com.gzus.courseassistant.ui.main.MainActivity
import com.gzus.courseassistant.ui.timetable.CourseDetailDialog
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TodayFragment : Fragment() {
    private var binding: FragmentTodayBinding? = null
    private val adapter = TodayCourseAdapter { item ->
        binding?.let { CourseDetailDialog.show(it.root.context, item.occurrence.course) }
    }
    private val weekPreviewAdapter = WeekPreviewAdapter(::selectDate)
    private var syncing = false
    private var selectedDate: LocalDate = LocalDate.now()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentTodayBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        viewBinding.courseList.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.courseList.adapter = adapter
        viewBinding.weekPreviewGrid.layoutManager = GridLayoutManager(requireContext(), 5)
        viewBinding.weekPreviewGrid.adapter = weekPreviewAdapter
        viewBinding.todayDate.text = UiUtils.todayLabel()
        viewBinding.syncButton.setOnClickListener { sync() }
        viewBinding.swipeRefresh.setOnRefreshListener { sync() }

        val repository = (requireActivity().application as CourseApplication).container.repository
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        binding?.let { render(repository().state.value) }
    }

    private fun render(state: AppState) {
        val viewBinding = binding ?: return
        val snapshot = state.activeSchedule
        if (snapshot == null) {
            adapter.submit(emptyList())
            viewBinding.emptyText.visibility = View.VISIBLE
            viewBinding.syncStatus.text = getString(R.string.never_synced)
            viewBinding.todayState.visibility = View.GONE
            viewBinding.todayTitle.setText(R.string.today_title)
            viewBinding.todayDate.text = UiUtils.dateLabel(selectedDate)
            viewBinding.weekPreviewSection.visibility = View.GONE
            weekPreviewAdapter.submit(emptyList())
            selectedDate = LocalDate.now()
            return
        }
        val today = LocalDate.now()
        val now = LocalTime.now()
        val firstWeekStart = state.firstWeekStarts[snapshot.term.label]
        val week = snapshot.term.currentWeek(today, firstWeekStart)
        val monday = snapshot.term.weekStartDate(week, firstWeekStart)
        if (selectedDate !in monday..monday.plusDays(6)) {
            selectedDate = today
        }
        val selected = selectedDate
        val isToday = selected == today
        val isWeekend = selected.dayOfWeek.value > 5
        viewBinding.todayTitle.text = if (isToday) {
            getString(R.string.today_title)
        } else {
            getString(
                R.string.day_course_title,
                WEEKDAY_LABELS[selected.dayOfWeek.value - 1],
            )
        }
        viewBinding.todayDate.text = getString(
            R.string.today_date_with_week,
            UiUtils.dateLabel(selected),
            week,
        )
        val occurrences = if (isWeekend) {
            emptyList()
        } else {
            ScheduleLogic.occurrences(snapshot, selected, firstWeekStart)
        }
        adapter.submit(
            occurrences.map { occurrence ->
                TodayCourseItem(
                    occurrence = occurrence,
                    state = when {
                        selected.isBefore(today) -> ScheduleLogic.TimingState.FINISHED
                        selected.isAfter(today) -> ScheduleLogic.TimingState.UPCOMING
                        else -> ScheduleLogic.timingState(occurrence, now)
                    },
                )
            },
        )
        viewBinding.emptyText.text = when {
            isWeekend -> getString(R.string.weekend_no_course)
            else -> getString(R.string.no_day_courses)
        }
        viewBinding.emptyText.visibility =
            if (occurrences.isEmpty()) View.VISIBLE else View.GONE
        renderTodayState(
            snapshot,
            selected,
            now,
            occurrences,
            occurrences.isNotEmpty(),
            firstWeekStart,
            isToday,
        )
        renderWeekPreview(snapshot, today, selected, firstWeekStart)

        val lastSync = getString(
            R.string.last_sync,
            UiUtils.syncLabel(state.lastScheduleSync, getString(R.string.never_synced)),
        )
        viewBinding.syncStatus.text = if (state.sessionValid) {
            lastSync
        } else {
            getString(R.string.status_with_cache, lastSync, getString(R.string.offline_hint))
        }
        viewBinding.swipeRefresh.isRefreshing = false
    }

    private fun renderTodayState(
        snapshot: com.gzus.courseassistant.data.model.ScheduleSnapshot,
        selected: LocalDate,
        now: LocalTime,
        occurrences: List<ScheduleLogic.Occurrence>,
        hasCourses: Boolean,
        firstWeekStart: LocalDate?,
        isToday: Boolean,
    ) {
        val viewBinding = binding ?: return
        if (!hasCourses) {
            viewBinding.todayState.visibility = View.GONE
            return
        }
        if (!isToday) {
            viewBinding.todayState.text = getString(
                R.string.day_course_count,
                occurrences.size,
            )
            viewBinding.todayState.visibility = View.VISIBLE
            return
        }
        if (occurrences.all { it.start == null }) {
            viewBinding.todayState.setText(R.string.today_time_unavailable)
            viewBinding.todayState.visibility = View.VISIBLE
            return
        }
        val current = ScheduleLogic.currentOccurrences(
            snapshot,
            selected,
            now,
            firstWeekStart,
        )
        val next = ScheduleLogic.nextOccurrences(
            snapshot,
            selected,
            now,
            firstWeekStart,
        )
        val parts = mutableListOf<String>()
        if (current.isNotEmpty()) {
            val names = current.map { it.course.name }.distinct().joinToString("、")
            parts += getString(R.string.today_current_course, names)
        }
        if (next.isNotEmpty()) {
            val names = next.map { it.course.name }.distinct().joinToString("、")
            val time = next.firstNotNullOfOrNull { it.start }
                ?.format(DateTimeFormatter.ofPattern("HH:mm"))
                .orEmpty()
            parts += getString(R.string.today_next_course, names, time)
        }
        viewBinding.todayState.text = if (parts.isEmpty()) {
            getString(R.string.today_courses_finished)
        } else {
            parts.joinToString(" · ")
        }
        viewBinding.todayState.visibility = View.VISIBLE
    }

    private fun renderWeekPreview(
        snapshot: ScheduleSnapshot,
        today: LocalDate,
        selected: LocalDate,
        firstWeekStart: LocalDate?,
    ) {
        val viewBinding = binding ?: return
        val week = snapshot.term.currentWeek(today, firstWeekStart)
        val monday = snapshot.term.weekStartDate(week, firstWeekStart)
        weekPreviewAdapter.submit(
            (0..4).map { offset ->
                val date = monday.plusDays(offset.toLong())
                WeekPreviewDay(
                    date = date,
                    courseCount = ScheduleLogic.occurrences(
                        snapshot,
                        date,
                        firstWeekStart,
                    ).size,
                    isSelected = date == selected,
                )
            },
        )
        viewBinding.weekPreviewSection.visibility = View.VISIBLE
    }

    private fun selectDate(date: LocalDate) {
        if (date.dayOfWeek.value > 5) return
        selectedDate = date
        render(repository().state.value)
    }

    private fun sync() {
        if (syncing) return
        val repository = (requireActivity().application as CourseApplication).container.repository
        if (!repository.hasSession) {
            (activity as? MainActivity)?.showLogin()
            binding?.swipeRefresh?.isRefreshing = false
            return
        }
        syncing = true
        binding?.syncButton?.isEnabled = false
        binding?.syncButton?.animate()?.rotationBy(360f)?.setDuration(500)?.start()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { repository.syncAll() }
                .onFailure { (activity as? MainActivity)?.handleSyncError(it) }
            syncing = false
            binding?.syncButton?.isEnabled = true
            binding?.swipeRefresh?.isRefreshing = false
        }
    }

    private fun repository() =
        (requireActivity().application as CourseApplication).container.repository

    private companion object {
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
