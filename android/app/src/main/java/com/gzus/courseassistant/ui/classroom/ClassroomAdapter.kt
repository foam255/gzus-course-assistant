package com.gzus.courseassistant.ui.classroom

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Classroom
import com.gzus.courseassistant.databinding.ItemClassroomBinding

class ClassroomAdapter : RecyclerView.Adapter<ClassroomAdapter.Holder>() {
    private val items = mutableListOf<Classroom>()

    fun submit(list: List<Classroom>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemClassroomBinding.inflate(
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

    class Holder(private val binding: ItemClassroomBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Classroom) {
            val context = binding.root.context
            binding.classroomCode.text = item.code
            binding.classroomSeats.text = item.seats.takeIf { it.isNotBlank() }?.let {
                context.getString(R.string.classroom_seats, it)
            }.orEmpty()
            binding.classroomName.text = item.name.ifBlank { item.code }
            binding.classroomName.visibility = if (item.name.isBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            binding.classroomMeta.text = listOf(item.campus, item.building, item.category)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    }
}
