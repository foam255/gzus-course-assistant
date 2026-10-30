package com.gzus.courseassistant.ui.classroom

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Classroom
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.data.network.SessionExpiredException
import com.gzus.courseassistant.data.sync.AppRepository
import com.gzus.courseassistant.databinding.ActivityEmptyClassroomBinding
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 空闲教室：按「周次 + 星期 + 节次」查询教务当前空闲的场地，
 * 支持按楼栋筛选与教室号/名称模糊搜索（均在本地过滤，不再请求教务）。
 */
class EmptyClassroomActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmptyClassroomBinding
    private lateinit var repository: AppRepository
    private val adapter = ClassroomAdapter()

    private var slots: List<ScheduleLogic.BigSlot> = emptyList()
    private var weekday = 1
    private var slotIndex = 0
    private var loading = false

    private var allClassrooms: List<Classroom> = emptyList()
    private var buildings: List<String> = emptyList()
    private var selectedBuilding = 0
    private var keyword = ""
    private var contextLabel = ""
    private var suppressFilter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEmptyClassroomBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiUtils.applySystemBarInsets(binding.root)
        repository = (application as CourseApplication).container.repository
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.classroomList.layoutManager = LinearLayoutManager(this)
        binding.classroomList.adapter = adapter
        setupFilters()
        setupSearch()
        query()
    }

    private fun setupFilters() {
        weekday = LocalDate.now().dayOfWeek.value.takeIf { it in 1..5 } ?: 1
        binding.weekdaySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            WEEKDAY_LABELS,
        )
        binding.weekdaySpinner.setSelection(weekday - 1)
        binding.weekdaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val next = position + 1
                if (next == weekday) return
                weekday = next
                query()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        slots = buildSlots()
        slotIndex = defaultSlotIndex()
        binding.slotSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            slots.map(::slotLabel),
        )
        binding.slotSpinner.setSelection(slotIndex)
        binding.slotSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (position == slotIndex) return
                slotIndex = position
                query()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.buildingSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (suppressFilter) return
                    selectedBuilding = position
                    applyFilter()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                applyFilter()
            }
        })
    }

    private fun buildSlots(): List<ScheduleLogic.BigSlot> {
        val periods = repository.state.value.activeSchedule?.periods.orEmpty()
        if (periods.isNotEmpty()) return ScheduleLogic.bigSlots(periods)
        return (1..8).map { index ->
            val start = index * 2 - 1
            ScheduleLogic.BigSlot(index, start, start + 1, null, null)
        }
    }

    private fun defaultSlotIndex(): Int {
        val now = LocalTime.now()
        val current = slots.indexOfFirst { slot ->
            val start = slot.start ?: return@indexOfFirst false
            val end = slot.end ?: return@indexOfFirst false
            !now.isBefore(start) && now.isBefore(end)
        }
        if (current >= 0) return current
        val upcoming = slots.indexOfFirst { slot -> slot.start?.isAfter(now) == true }
        return if (upcoming >= 0) upcoming else 0
    }

    private fun slotLabel(slot: ScheduleLogic.BigSlot): String =
        getString(R.string.classroom_slot, slot.startPeriod, slot.endPeriod)

    private fun query() {
        if (loading) return
        val state = repository.state.value
        val term = state.activeSchedule?.term ?: Term.parse(state.currentTerm) ?: Term.guess()
        val week = term.currentWeek(
            LocalDate.now(),
            state.firstWeekStarts[term.label],
        )
        val slot = slots.getOrNull(slotIndex) ?: return
        contextLabel = getString(
            R.string.classroom_context,
            week,
            WEEKDAY_LABELS[weekday - 1],
            slotLabel(slot),
        )
        loading = true
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        binding.statusText.text = getString(R.string.classroom_loading)
        lifecycleScope.launch {
            runCatching {
                repository.emptyClassrooms(
                    term = term,
                    week = week,
                    weekdays = listOf(weekday),
                    periods = listOf(slot.startPeriod, slot.endPeriod),
                )
            }.onSuccess { classrooms ->
                allClassrooms = classrooms
                rebuildBuildingSpinner()
                applyFilter()
            }.onFailure { error ->
                allClassrooms = emptyList()
                adapter.submit(emptyList())
                binding.statusText.text = ""
                binding.emptyText.text = when (error) {
                    is SessionExpiredException -> getString(R.string.classroom_need_login)
                    else -> error.message ?: getString(R.string.net_error)
                }
                binding.emptyText.visibility = View.VISIBLE
            }
            loading = false
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun rebuildBuildingSpinner() {
        buildings = allClassrooms
            .map { it.building }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val options = mutableListOf(getString(R.string.classroom_building_all))
        options += buildings
        suppressFilter = true
        binding.buildingSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options,
        )
        selectedBuilding = if (selectedBuilding in options.indices) selectedBuilding else 0
        binding.buildingSpinner.setSelection(selectedBuilding)
        suppressFilter = false
    }

    private fun applyFilter() {
        if (allClassrooms.isEmpty()) {
            adapter.submit(emptyList())
            binding.statusText.text = contextLabel.takeIf { it.isNotBlank() }?.let {
                getString(R.string.classroom_summary, it, 0)
            }.orEmpty()
            binding.emptyText.setText(R.string.classroom_empty)
            binding.emptyText.visibility = View.VISIBLE
            return
        }
        val building = buildings.getOrNull(selectedBuilding - 1)
        val key = keyword.trim()
        val filtered = allClassrooms.filter { room ->
            (building == null || room.building == building) &&
                (
                    key.isBlank() ||
                        room.code.contains(key, ignoreCase = true) ||
                        room.name.contains(key, ignoreCase = true) ||
                        room.building.contains(key, ignoreCase = true) ||
                        room.category.contains(key, ignoreCase = true)
                    )
        }
        adapter.submit(filtered)
        val filteredOut = filtered.size != allClassrooms.size
        binding.statusText.text = if (filteredOut) {
            getString(
                R.string.classroom_summary_filtered,
                contextLabel,
                filtered.size,
                allClassrooms.size,
            )
        } else {
            getString(R.string.classroom_summary, contextLabel, allClassrooms.size)
        }
        binding.emptyText.setText(
            if (filteredOut) R.string.classroom_empty_filtered else R.string.classroom_empty,
        )
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private companion object {
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五")
    }
}
