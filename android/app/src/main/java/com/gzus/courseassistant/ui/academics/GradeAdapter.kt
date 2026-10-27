package com.gzus.courseassistant.ui.academics

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.databinding.ItemGradeBinding

class GradeAdapter : RecyclerView.Adapter<GradeAdapter.Holder>() {
    private var items: List<Grade> = emptyList()

    fun submit(items: List<Grade>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemGradeBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemGradeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(grade: Grade) {
            binding.courseName.text = grade.courseName
            val details = buildList {
                if (grade.credit.isNotBlank()) add("${grade.credit} 学分")
                if (grade.gpa.isNotBlank()) add("${grade.gpa} 绩点")
                if (grade.courseType.isNotBlank()) add(grade.courseType)
            }
            binding.courseMeta.text = details.joinToString(" · ")
            binding.score.text = grade.score.ifBlank { "—" }
            binding.score.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (grade.score.toDoubleOrNull()?.let { it < 60 } == true) {
                        R.color.danger
                    } else {
                        R.color.ink
                    },
                ),
            )
        }
    }
}
