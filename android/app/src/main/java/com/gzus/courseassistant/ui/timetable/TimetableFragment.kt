package com.gzus.courseassistant.ui.timetable

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.databinding.FragmentTimetableBinding
import com.gzus.courseassistant.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimetableFragment : Fragment() {
    private var binding: FragmentTimetableBinding? = null
    private var week = -1
    private var selectedTerm = ""
    private var terms: List<Term> = emptyList()
    private var syncing = false
    private var exporting = false
    private var suppressTermChange = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentTimetableBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        viewBinding.previousWeekButton.setOnClickListener { changeWeek(-1) }
        viewBinding.nextWeekButton.setOnClickListener { changeWeek(1) }
        viewBinding.currentWeekButton.setOnClickListener { moveToCurrentWeek() }
        viewBinding.syncButton.setOnClickListener { sync() }
        viewBinding.exportButton.setOnClickListener { exportTimetable() }
        viewBinding.timetableView.onCourseClick = { course ->
            CourseDetailDialog.show(requireContext(), course)
        }
        viewBinding.termSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressTermChange || position !in terms.indices) return
                val term = terms[position]
                if (term.label == selectedTerm) return
                selectedTerm = term.label
                week = term.currentWeek(
                    firstWeekStart = repository().state.value.firstWeekStarts[term.label],
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    repository().selectTerm(term.label)
                    render(repository().state.value)
                    scrollTimetableToTop()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository().state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(state: AppState) {
        val viewBinding = binding ?: return
        val allTerms = if (state.availableTerms.isNotEmpty()) {
            state.availableTerms
        } else {
            state.currentSchedule?.let { listOf(it.term) }.orEmpty()
        }
        updateTerms(allTerms)

        val snapshot = state.currentSchedule
        if (snapshot == null) {
            viewBinding.timetableView.setData(null, 0)
            viewBinding.timetableView.visibility = View.GONE
            viewBinding.exportButton.isEnabled = false
            viewBinding.weekLegend.visibility = View.GONE
            viewBinding.emptyText.visibility = View.VISIBLE
            viewBinding.weekText.text = getString(R.string.week_format, 1)
            return
        }
        selectedTerm = snapshot.term.label
        selectTermSilently(snapshot.term)
        val firstWeekStart = state.firstWeekStarts[snapshot.term.label]
        if (week <= 0) {
            week = snapshot.term.currentWeek(firstWeekStart = firstWeekStart)
        }
        week = week.coerceIn(1, 20)
        viewBinding.weekText.text = getString(R.string.week_format, week)
        viewBinding.previousWeekButton.isEnabled = week > 1
        viewBinding.nextWeekButton.isEnabled = week < 20
        val range = weekRange(snapshot.term, week, firstWeekStart)
        viewBinding.weekRangeText.text = if (state.sessionValid) {
            range
        } else {
            getString(R.string.status_with_cache, range, getString(R.string.offline_hint))
        }
        val activeCourses = ScheduleLogic.coursesForWeek(snapshot, week)
        val hasCourses = activeCourses.isNotEmpty()
        viewBinding.weekLegend.text = getString(R.string.timetable_active_only_legend, week)
        viewBinding.weekLegend.visibility = if (hasCourses) View.VISIBLE else View.GONE
        viewBinding.timetableView.visibility = if (hasCourses) View.VISIBLE else View.GONE
        viewBinding.exportButton.isEnabled = hasCourses && !exporting
        viewBinding.emptyText.visibility = if (hasCourses) View.GONE else View.VISIBLE
        if (!hasCourses) {
            viewBinding.emptyText.text = getString(R.string.no_courses_in_week, week)
        }
        viewBinding.timetableView.setData(snapshot, week, firstWeekStart)
    }

    private fun updateTerms(newTerms: List<Term>) {
        if (newTerms == terms) return
        terms = newTerms
        val viewBinding = binding ?: return
        val labels = terms.map {
            "${it.year}-${it.year + 1} 第${it.number}学期"
        }
        suppressTermChange = true
        viewBinding.termSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        suppressTermChange = false
    }

    private fun selectTermSilently(term: Term) {
        val viewBinding = binding ?: return
        val index = terms.indexOfFirst { it.label == term.label }
        if (index < 0 || viewBinding.termSpinner.selectedItemPosition == index) return
        suppressTermChange = true
        viewBinding.termSpinner.setSelection(index)
        suppressTermChange = false
    }

    private fun changeWeek(delta: Int) {
        week = (week + delta).coerceIn(1, 20)
        render(repository().state.value)
        scrollTimetableToTop()
    }

    private fun moveToCurrentWeek() {
        val state = repository().state.value
        val term = state.currentSchedule?.term ?: return
        week = term.currentWeek(
            firstWeekStart = state.firstWeekStarts[term.label],
        )
        render(state)
        scrollTimetableToTop()
    }

    private fun sync() {
        if (syncing) return
        val repo = repository()
        if (!repo.hasSession) {
            (activity as? MainActivity)?.showLogin()
            return
        }
        syncing = true
        binding?.syncButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val term = repo.state.value.selectedTerm.ifBlank { null }
            runCatching { repo.syncAll(termLabel = term) }
                .onFailure { (activity as? MainActivity)?.handleSyncError(it) }
            syncing = false
            binding?.syncButton?.isEnabled = true
        }
    }

    private fun exportTimetable() {
        if (exporting) return
        val state = repository().state.value
        val snapshot = state.currentSchedule ?: return
        val firstWeekStart = state.firstWeekStarts[snapshot.term.label]
        val currentWeek = week.coerceIn(1, 20)
        val activeCourses = ScheduleLogic.coursesForWeek(snapshot, currentWeek)
        if (activeCourses.isEmpty()) {
            (activity as? MainActivity)?.showMessage(
                getString(R.string.no_courses_in_week, currentWeek),
            )
            return
        }
        val tableView = binding?.timetableView ?: return
        if (tableView.width <= 0 || tableView.height <= 0) {
            tableView.post { exportTimetable() }
            return
        }
        val termTitle = "${snapshot.term.year}-${snapshot.term.year + 1} " +
            "第${snapshot.term.number}学期"
        val title = getString(R.string.export_timetable_title, termTitle, currentWeek)
        val subtitle = getString(
            R.string.export_timetable_subtitle,
            weekRange(snapshot.term, currentWeek, firstWeekStart),
        )
        val displayName = "课表轻助-${snapshot.term.label}-第${currentWeek}周"
        exporting = true
        binding?.exportButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmap = TimetableImageExporter.createBitmap(
                    context = requireContext(),
                    tableView = tableView,
                    title = title,
                    subtitle = subtitle,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    TimetableImageExporter.saveToGallery(
                        context = requireContext(),
                        bitmap = bitmap,
                        displayName = displayName,
                    )
                    (activity as? MainActivity)?.showMessage(
                        getString(R.string.export_timetable_saved),
                    )
                } else {
                    val uri = TimetableImageExporter.saveShareFile(
                        context = requireContext(),
                        bitmap = bitmap,
                        fileName = "timetable-${snapshot.term.label}-week$currentWeek",
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(
                        Intent.createChooser(
                            share,
                            getString(R.string.export_timetable_share),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                (activity as? MainActivity)?.showMessage(
                    getString(R.string.export_timetable_failed),
                )
            } finally {
                exporting = false
                binding?.exportButton?.isEnabled =
                    ScheduleLogic.coursesForWeek(
                        snapshot,
                        currentWeek,
                    ).isNotEmpty()
            }
        }
    }

    private fun weekRange(
        term: Term,
        week: Int,
        firstWeekStart: java.time.LocalDate?,
    ): String {
        val monday = term.weekStartDate(week, firstWeekStart)
        val sunday = monday.plusDays(6)
        val formatter = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
        return "${monday.format(formatter)} - ${sunday.format(formatter)}"
    }

    private fun scrollTimetableToTop() {
        val scrollView = binding?.timetableScroll ?: return
        scrollView.post { scrollView.scrollTo(0, 0) }
    }

    private fun repository() =
        (requireActivity().application as CourseApplication).container.repository
}
