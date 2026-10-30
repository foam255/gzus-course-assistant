package com.gzus.courseassistant.ui.academics

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Exam

object ExamDetailDialog {
    fun show(context: Context, exam: Exam) {
        val lines = buildList {
            if (exam.date.isNotBlank()) {
                add(context.getString(R.string.exam_detail_date, exam.date))
            }
            if (exam.time.isNotBlank()) {
                add(context.getString(R.string.exam_detail_time, exam.time))
            }
            if (exam.type.isNotBlank()) {
                add(context.getString(R.string.exam_detail_type, exam.type))
            }
            if (exam.room.isNotBlank()) {
                add(context.getString(R.string.exam_room_format, exam.room))
            }
            if (exam.seat.isNotBlank()) {
                add(context.getString(R.string.exam_seat_format, exam.seat))
            }
            if (exam.note.isNotBlank()) {
                add(context.getString(R.string.exam_detail_note, exam.note))
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(exam.courseName)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
