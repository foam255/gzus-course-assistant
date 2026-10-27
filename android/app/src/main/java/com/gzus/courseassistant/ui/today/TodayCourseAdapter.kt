package com.gzus.courseassistant.ui.today

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.databinding.ItemTodayCourseBinding
import com.gzus.courseassistant.util.UiUtils
import java.time.format.DateTimeFormatter

class TodayCourseAdapter(
    private val onClick: (TodayCourseItem) -> Unit,
) : RecyclerView.Adapter<TodayCourseAdapter.Holder>() {
    private var items: List<TodayCourseItem> = emptyList()

    fun submit(items: List<TodayCourseItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTodayCourseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemTodayCourseBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TodayCourseItem) {
            val occurrence = item.occurrence
            val course = occurrence.course
            val finished = item.state == ScheduleLogic.TimingState.FINISHED
            val context = binding.root.context
            binding.root.setBackgroundResource(
                if (finished) R.drawable.bg_course_today_finished else R.drawable.bg_course_today,
            )
            binding.root.alpha = if (finished) 0.82f else 1f
            binding.colorBar.backgroundTintList = ColorStateList.valueOf(
                if (finished) {
                    ContextCompat.getColor(context, R.color.line)
                } else {
                    UiUtils.courseAccent(course.name)
                },
            )
            binding.courseName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (finished) R.color.muted else R.color.ink,
                ),
            )
            binding.courseName.text = course.name
            binding.courseMeta.text = listOf(course.room, course.teacher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val periodText = if (course.startPeriod == course.endPeriod) {
                context.getString(R.string.today_course_single_period, course.startPeriod)
            } else {
                context.getString(
                    R.string.today_course_period_range,
                    course.startPeriod,
                    course.endPeriod,
                )
            }
            binding.courseWeek.text = listOf(periodText, course.weekText)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            binding.courseTime.text = when {
                occurrence.start != null && occurrence.end != null ->
                    "${occurrence.start.format(formatter)}\n${occurrence.end.format(formatter)}"
                occurrence.start != null -> occurrence.start.format(formatter)
                else -> context.getString(
                    R.string.today_course_periods_short,
                    course.startPeriod,
                    course.endPeriod,
                )
            }
            binding.courseTime.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (finished) R.color.muted else R.color.ink,
                ),
            )
            when (item.state) {
                ScheduleLogic.TimingState.CURRENT -> {
                    binding.courseStatus.visibility = View.VISIBLE
                    binding.courseStatus.setText(R.string.course_status_current)
                    binding.courseStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.primary),
                    )
                    binding.courseStatus.setBackgroundResource(
                        R.drawable.bg_course_status_current,
                    )
                }
                ScheduleLogic.TimingState.FINISHED -> {
                    binding.courseStatus.visibility = View.VISIBLE
                    binding.courseStatus.setText(R.string.course_status_finished)
                    binding.courseStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.muted),
                    )
                    binding.courseStatus.setBackgroundResource(
                        R.drawable.bg_course_status_muted,
                    )
                }
                ScheduleLogic.TimingState.UPCOMING -> {
                    binding.courseStatus.visibility = View.VISIBLE
                    binding.courseStatus.setText(R.string.course_status_upcoming)
                    binding.courseStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.muted),
                    )
                    binding.courseStatus.setBackgroundResource(
                        R.drawable.bg_course_status_muted,
                    )
                }
                ScheduleLogic.TimingState.UNKNOWN -> {
                    binding.courseStatus.visibility = View.GONE
                }
            }
            binding.root.contentDescription = listOf(
                course.name,
                binding.courseWeek.text,
                binding.courseStatus.text.takeIf {
                    binding.courseStatus.visibility == View.VISIBLE
                },
            ).filterNotNull().joinToString("，")
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

data class TodayCourseItem(
    val occurrence: ScheduleLogic.Occurrence,
    val state: ScheduleLogic.TimingState,
)
