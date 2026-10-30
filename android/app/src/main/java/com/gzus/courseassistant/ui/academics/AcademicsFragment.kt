package com.gzus.courseassistant.ui.academics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.ExamSchedule
import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.data.model.GradeDistribution
import com.gzus.courseassistant.data.model.GradeStatsCalculator
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.data.model.TermStat
import com.gzus.courseassistant.data.model.TypeStat
import com.gzus.courseassistant.data.model.Upcoming
import com.gzus.courseassistant.databinding.FragmentAcademicsBinding
import com.gzus.courseassistant.databinding.ViewStatBarRowBinding
import com.gzus.courseassistant.databinding.ViewStatTextRowBinding
import com.gzus.courseassistant.ui.main.MainActivity
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class AcademicsFragment : Fragment() {
    private var binding: FragmentAcademicsBinding? = null
    private val gradeAdapter = GradeAdapter()
    private val examAdapter = ExamAdapter { upcoming ->
        ExamDetailDialog.show(requireContext(), upcoming.exam)
    }
    private var mode = MODE_GRADES
    private var terms: List<Term> = emptyList()
    private var selectedTerm: Term? = null
    private var syncing = false
    private var suppressTermChange = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentAcademicsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        viewBinding.academicList.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.academicList.adapter = gradeAdapter
        viewBinding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.gradesTab -> MODE_GRADES
                R.id.analysisTab -> MODE_ANALYSIS
                else -> MODE_EXAMS
            }
            render(repository().state.value)
        }
        viewBinding.termSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressTermChange || position !in terms.indices) return
                selectedTerm = terms[position]
                render(repository().state.value)
                viewLifecycleOwner.lifecycleScope.launch {
                    repository().selectTerm(terms[position].label)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        viewBinding.syncButton.setOnClickListener { sync() }

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
        val available = state.availableTerms.ifEmpty {
            state.currentSchedule?.let { listOf(it.term) }.orEmpty()
        }
        if (available != terms) {
            terms = available
            suppressTermChange = true
            viewBinding.termSpinner.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                terms.map { "${it.year}-${it.year + 1} 第${it.number}学期" },
            )
            suppressTermChange = false
        }
        val stateTerm = Term.parse(state.selectedTerm)
        if (selectedTerm == null || stateTerm != null && selectedTerm != stateTerm) {
            selectedTerm = stateTerm ?: state.currentSchedule?.term ?: terms.firstOrNull()
        }
        val term = selectedTerm
        if (term != null) {
            val index = terms.indexOfFirst { it.label == term.label }
            if (index >= 0 && viewBinding.termSpinner.selectedItemPosition != index) {
                suppressTermChange = true
                viewBinding.termSpinner.setSelection(index)
                suppressTermChange = false
            }
        }

        val grades = state.grades.filter { it.term == term }
        val exams = state.exams.filter { it.term == term }
        val isAnalysis = mode == MODE_ANALYSIS
        viewBinding.termSpinner.visibility = if (isAnalysis) View.GONE else View.VISIBLE
        viewBinding.summaryRow.visibility = if (isAnalysis) View.GONE else View.VISIBLE
        viewBinding.analysisScroll.visibility = if (isAnalysis) View.VISIBLE else View.GONE
        viewBinding.academicList.visibility = if (isAnalysis) View.GONE else View.VISIBLE

        when (mode) {
            MODE_GRADES -> {
                viewBinding.emptyText.setText(R.string.no_grades)
                viewBinding.emptyText.visibility = if (grades.isEmpty()) View.VISIBLE else View.GONE
                viewBinding.academicList.adapter = gradeAdapter
                gradeAdapter.submit(grades)
                renderGradeSummary(grades)
            }

            MODE_ANALYSIS -> {
                viewBinding.emptyText.visibility = View.GONE
                renderAnalysis(state.grades)
            }

            else -> {
                viewBinding.emptyText.setText(R.string.no_exams)
                viewBinding.emptyText.visibility = if (exams.isEmpty()) View.VISIBLE else View.GONE
                viewBinding.academicList.adapter = examAdapter
                val upcoming = ExamSchedule.upcoming(exams, LocalDateTime.now())
                examAdapter.submit(upcoming)
                renderExamSummary(upcoming)
            }
        }
        val syncLabel = getString(
            R.string.last_sync,
            UiUtils.syncLabel(state.lastAcademicSync, getString(R.string.never_synced)),
        )
        viewBinding.statusText.text = if (state.sessionValid) {
            syncLabel
        } else {
            getString(R.string.status_with_cache, syncLabel, getString(R.string.offline_hint))
        }
        viewBinding.progressBar.visibility = if (syncing) View.VISIBLE else View.GONE
    }

    private fun renderGradeSummary(grades: List<Grade>) {
        val binding = binding ?: return
        var credits = 0.0
        var weightedPoints = 0.0
        var gpaCredits = 0.0
        grades.forEach { grade ->
            val credit = grade.credit.toDoubleOrNull() ?: 0.0
            credits += credit
            val gpa = grade.gpa.toDoubleOrNull()
            if (gpa != null && credit > 0) {
                weightedPoints += gpa * credit
                gpaCredits += credit
            }
        }
        binding.summaryLeft.text = if (grades.isEmpty()) {
            "成绩 0 门"
        } else {
            "成绩 ${grades.size} 门 · ${"%.1f".format(credits)} 学分"
        }
        binding.summaryRight.text = if (gpaCredits > 0) {
            "加权绩点 ${"%.2f".format(weightedPoints / gpaCredits)}"
        } else {
            getString(R.string.gpa_not_available)
        }
    }

    private fun renderExamSummary(upcoming: List<Upcoming>) {
        val binding = binding ?: return
        binding.summaryLeft.text = "考试 ${upcoming.size} 场"
        val next = upcoming.firstOrNull()
        binding.summaryRight.text = next?.let { countdownLabel(it) }.orEmpty()
    }

    private fun countdownLabel(upcoming: Upcoming): String {
        val exam = upcoming.exam
        val daysLeft = upcoming.daysLeft ?: return listOf(exam.date, exam.time)
            .filter(String::isNotBlank)
            .joinToString(" ")
        return when {
            daysLeft > 0 -> getString(R.string.exam_days_left, daysLeft)
            upcoming.hasTime -> {
                val minutes = upcoming.minutesLeft ?: 0L
                val hours = minutes / 60L
                if (hours <= 0L) {
                    getString(R.string.exam_countdown_soon)
                } else {
                    getString(R.string.exam_hours_left, hours)
                }
            }
            else -> getString(R.string.exam_days_left_today)
        }
    }

    private fun renderAnalysis(grades: List<Grade>) {
        val binding = binding ?: return
        val stats = GradeStatsCalculator.compute(grades)
        val hasData = stats.hasData
        binding.analysisContent.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.analysisEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) {
            binding.trendChart.setData(emptyList())
            return
        }
        binding.analysisGpaValue.text = stats.cumulativeGpa
            ?.let { "%.2f".format(it) }
            ?: getString(R.string.analysis_gpa_unavailable)
        binding.analysisScoreValue.text = stats.weightedScore
            ?.let { "%.1f".format(it) }
            ?: getString(R.string.analysis_score_unavailable)
        binding.analysisCreditsValue.text = "%.1f".format(stats.totalCredits)
        binding.analysisCountValue.text = stats.totalCourses.toString()
        binding.analysisFailedValue.text = stats.failedCourses.toString()
        binding.analysisFailedValue.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (stats.failedCourses > 0) R.color.danger else R.color.ink,
            ),
        )
        binding.trendChart.setData(stats.terms)
        renderDistribution(binding.distributionContainer, stats.distribution)
        renderTypeStats(binding.typeContainer, stats.byType)
        renderTermStats(binding.termContainer, stats.terms)
    }

    private fun renderDistribution(container: LinearLayout, distribution: GradeDistribution) {
        container.removeAllViews()
        val total = distribution.total
        val rows = listOf(
            R.string.analysis_distribution_excellent to distribution.excellent,
            R.string.analysis_distribution_good to distribution.good,
            R.string.analysis_distribution_medium to distribution.medium,
            R.string.analysis_distribution_pass to distribution.pass,
            R.string.analysis_distribution_fail to distribution.fail,
            R.string.analysis_distribution_unknown to distribution.unknown,
        )
        rows.forEach { (labelRes, count) ->
            val row = ViewStatBarRowBinding.inflate(layoutInflater, container, false)
            row.rowLabel.setText(labelRes)
            row.rowValue.text = getString(R.string.analysis_count_format, count)
            row.rowBar.progress = if (total > 0) count * 100 / total else 0
            container.addView(row.root)
        }
    }

    private fun renderTypeStats(container: LinearLayout, stats: List<TypeStat>) {
        container.removeAllViews()
        stats.forEach { stat ->
            val row = ViewStatTextRowBinding.inflate(layoutInflater, container, false)
            row.rowLabel.text = stat.courseType.ifBlank { getString(R.string.analysis_type_unknown) }
            row.rowValue.text = summaryText(stat.courseCount, stat.credits, stat.gpa)
            container.addView(row.root)
        }
    }

    private fun renderTermStats(container: LinearLayout, stats: List<TermStat>) {
        container.removeAllViews()
        stats.forEach { stat ->
            val row = ViewStatTextRowBinding.inflate(layoutInflater, container, false)
            row.rowLabel.text = getString(
                R.string.analysis_term_label,
                stat.term.year,
                stat.term.year + 1,
                stat.term.number,
            )
            row.rowValue.text = summaryText(stat.courseCount, stat.credits, stat.gpa)
            container.addView(row.root)
        }
    }

    private fun summaryText(courseCount: Int, credits: Double, gpa: Double?): String {
        val parts = mutableListOf(getString(R.string.analysis_count_format, courseCount))
        if (credits > 0) {
            parts += getString(R.string.analysis_credits_format, "%.1f".format(credits))
        }
        if (gpa != null) {
            parts += getString(R.string.analysis_gpa_format, "%.2f".format(gpa))
        }
        return parts.joinToString(" · ")
    }

    private fun sync() {
        if (syncing) return
        val term = selectedTerm ?: return
        val repository = repository()
        if (!repository.hasSession) {
            (activity as? MainActivity)?.showLogin()
            return
        }
        syncing = true
        binding?.syncButton?.isEnabled = false
        binding?.progressBar?.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { repository.syncAcademics(term) }
                .onFailure { (activity as? MainActivity)?.handleSyncError(it) }
            syncing = false
            binding?.syncButton?.isEnabled = true
            binding?.progressBar?.visibility = View.GONE
        }
    }

    private fun repository() =
        (requireActivity().application as CourseApplication).container.repository

    private companion object {
        const val MODE_GRADES = 0
        const val MODE_ANALYSIS = 1
        const val MODE_EXAMS = 2
    }
}
