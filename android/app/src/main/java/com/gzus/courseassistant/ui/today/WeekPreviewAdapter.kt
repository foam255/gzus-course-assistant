package com.gzus.courseassistant.ui.today

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.databinding.ItemWeekPreviewBinding
import java.time.LocalDate

class WeekPreviewAdapter(
    private val onClick: (LocalDate) -> Unit,
) : RecyclerView.Adapter<WeekPreviewAdapter.Holder>() {
    private var items: List<WeekPreviewDay> = emptyList()

    fun submit(items: List<WeekPreviewDay>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemWeekPreviewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemWeekPreviewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WeekPreviewDay) {
            val context = binding.root.context
            val isSelected = item.isSelected
            binding.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_week_day_today else R.drawable.bg_week_day,
            )
            binding.root.alpha = if (item.courseCount > 0 || isSelected) 1f else 0.62f
            binding.weekday.text = WEEKDAY_LABELS[item.date.dayOfWeek.value - 1]
            binding.day.text = item.date.dayOfMonth.toString()
            binding.count.text = if (item.courseCount > 0) {
                context.getString(R.string.week_preview_count, item.courseCount)
            } else {
                context.getString(R.string.week_preview_empty)
            }

            val primary = if (isSelected) R.color.white else R.color.primary
            val ink = if (isSelected) R.color.white else R.color.ink
            val muted = if (isSelected) R.color.white else R.color.muted
            binding.weekday.setTextColor(ContextCompat.getColor(context, muted))
            binding.day.setTextColor(ContextCompat.getColor(context, ink))
            binding.count.setTextColor(ContextCompat.getColor(context, primary))
            binding.courseDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, primary),
            )
            binding.courseDot.visibility =
                if (item.courseCount > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.contentDescription = context.getString(
                R.string.week_preview_day_description,
                binding.weekday.text,
                item.date.dayOfMonth,
                binding.count.text,
            )
            binding.root.setOnClickListener { onClick(item.date) }
        }
    }

    private companion object {
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

data class WeekPreviewDay(
    val date: LocalDate,
    val courseCount: Int,
    val isSelected: Boolean,
)
