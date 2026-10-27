package com.gzus.courseassistant.ui.calendar

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.data.model.CalendarEntry
import com.gzus.courseassistant.databinding.ItemCalendarBinding

class CalendarAdapter(
    private val onClick: (CalendarEntry) -> Unit,
) : RecyclerView.Adapter<CalendarAdapter.Holder>() {
    private var items: List<CalendarEntry> = emptyList()

    fun submit(items: List<CalendarEntry>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemCalendarBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemCalendarBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: CalendarEntry) {
            binding.title.text = entry.title
            binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
