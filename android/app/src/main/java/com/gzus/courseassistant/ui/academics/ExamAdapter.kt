package com.gzus.courseassistant.ui.academics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.databinding.ItemExamBinding

class ExamAdapter : RecyclerView.Adapter<ExamAdapter.Holder>() {
    private var items: List<Exam> = emptyList()

    fun submit(items: List<Exam>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemExamBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemExamBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(exam: Exam) {
            binding.courseName.text = exam.courseName
            binding.examType.text = exam.type.ifBlank { "考试" }
            binding.examTime.text = listOf(exam.date, exam.time)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            binding.examPlace.text = buildList {
                if (exam.room.isNotBlank()) {
                    add(binding.root.context.getString(R.string.exam_room_format, exam.room))
                }
                if (exam.seat.isNotBlank()) {
                    add(binding.root.context.getString(R.string.exam_seat_format, exam.seat))
                }
            }.joinToString(" · ")
            binding.examNote.text = exam.note
            binding.examNote.visibility = if (exam.note.isBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }
    }
}
