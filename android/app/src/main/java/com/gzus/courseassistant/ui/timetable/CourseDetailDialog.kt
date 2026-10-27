package com.gzus.courseassistant.ui.timetable

import android.content.Context
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.Course
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object CourseDetailDialog {
    fun show(context: Context, course: Course) {
        val lines = buildList {
            if (course.teacher.isNotBlank()) {
                add(context.getString(R.string.course_detail_teacher, course.teacher))
            }
            if (course.room.isNotBlank()) {
                add(context.getString(R.string.course_detail_room, course.room))
            }
            if (course.campus.isNotBlank() && !course.room.contains(course.campus)) {
                add(context.getString(R.string.course_detail_campus, course.campus))
            }
            add(
                context.getString(
                    R.string.course_detail_periods,
                    course.startPeriod,
                    course.endPeriod,
                ),
            )
            if (course.weekText.isNotBlank()) {
                add(context.getString(R.string.course_detail_weeks, course.weekText))
            }
            if (course.credit.isNotBlank()) {
                add(context.getString(R.string.course_detail_credit, course.credit))
            }
            if (course.courseCode.isNotBlank()) {
                add(context.getString(R.string.course_detail_code, course.courseCode))
            }
            if (course.teachingClassName.isNotBlank()) {
                add(
                    context.getString(
                        R.string.course_detail_class,
                        course.teachingClassName,
                    ),
                )
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(course.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
