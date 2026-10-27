package com.gzus.courseassistant.ui.academics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.databinding.FragmentAcademicsBinding
import com.gzus.courseassistant.ui.main.MainActivity
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch

class AcademicsFragment : Fragment() {
    private var binding: FragmentAcademicsBinding? = null
    private val gradeAdapter = GradeAdapter()
    private val examAdapter = ExamAdapter()
    private var showingGrades = true
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
            showingGrades = checkedId == R.id.gradesTab
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
        if (showingGrades) {
            viewBinding.academicList.adapter = gradeAdapter
            gradeAdapter.submit(grades)
            viewBinding.emptyText.setText(R.string.no_grades)
            viewBinding.emptyText.visibility = if (grades.isEmpty()) View.VISIBLE else View.GONE
            renderGradeSummary(grades)
        } else {
            viewBinding.academicList.adapter = examAdapter
            examAdapter.submit(exams)
            viewBinding.emptyText.setText(R.string.no_exams)
            viewBinding.emptyText.visibility = if (exams.isEmpty()) View.VISIBLE else View.GONE
            viewBinding.summaryLeft.text = "考试 ${exams.size} 场"
            viewBinding.summaryRight.text = exams.firstOrNull()?.let {
                listOf(it.date, it.time).filter(String::isNotBlank).joinToString(" ")
            }.orEmpty()
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
}
