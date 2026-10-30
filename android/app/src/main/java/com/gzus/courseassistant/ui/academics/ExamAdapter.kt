package com.gzus.courseassistant.ui.academics

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Upcoming
import com.gzus.courseassistant.databinding.ItemExamBinding

class ExamAdapter(
    private val onClick: (Upcoming) -> Unit = {},
) : RecyclerView.Adapter<ExamAdapter.Holder>() {
    private var items: List<Upcoming> = emptyList()

    fun submit(items: List<Upcoming>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemExamBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onClick,
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemExamBinding,
        private val onClick: (Upcoming) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(upcoming: Upcoming) {
            val exam = upcoming.exam
            val context = binding.root.context
            binding.courseName.text = exam.courseName
            binding.examType.text = exam.type.ifBlank { "考试" }
            binding.examTime.text = listOf(exam.date, exam.time)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            binding.examPlace.text = buildList {
                if (exam.room.isNotBlank()) {
                    add(context.getString(R.string.exam_room_format, exam.room))
                }
                if (exam.seat.isNotBlank()) {
                    add(context.getString(R.string.exam_seat_format, exam.seat))
                }
            }.joinToString(" · ")
            binding.examNote.text = exam.note
            binding.examNote.visibility = if (exam.note.isBlank()) View.GONE else View.VISIBLE

            val daysLeft = upcoming.daysLeft
            val daysLeftText = when {
                daysLeft == null -> ""
                daysLeft <= 0L -> context.getString(R.string.exam_days_left_today)
                else -> context.getString(R.string.exam_days_left, daysLeft)
            }
            binding.examDaysLeft.text = daysLeftText
            binding.examDaysLeft.visibility =
                if (daysLeftText.isBlank()) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onClick(upcoming) }
        }
    }
}
