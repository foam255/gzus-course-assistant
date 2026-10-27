package com.gzus.courseassistant.data.model

import java.security.MessageDigest

object CourseNormalizer {
    fun normalize(courses: List<Course>): List<Course> {
        if (courses.isEmpty()) return emptyList()
        return courses
            .groupBy(::visibleSessionKey)
            .values
            .flatMap(::mergeGroup)
            .map { it.copy(weekText = formatWeeks(it.weeks)) }
            .sortedWith(
                compareBy<Course> { it.weekday }
                    .thenBy { it.startPeriod }
                    .thenBy { it.name },
            )
    }

    private fun mergeGroup(courses: List<Course>): List<Course> {
        val sorted = courses.sortedBy { it.startPeriod }
        val merged = mutableListOf<Course>()
        var current = sorted.first()

        sorted.drop(1).forEach { next ->
            if (canMerge(current, next)) {
                current = merge(current, next)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun canMerge(current: Course, next: Course): Boolean {
        if (next.startPeriod <= current.endPeriod) return true
        val bothAreSinglePeriods = current.startPeriod == current.endPeriod &&
            next.startPeriod == next.endPeriod
        return bothAreSinglePeriods && next.startPeriod == current.endPeriod + 1
    }

    private fun merge(current: Course, next: Course): Course {
        val start = minOf(current.startPeriod, next.startPeriod)
        val end = maxOf(current.endPeriod, next.endPeriod)
        val identity = listOf(
            visibleSessionKey(current),
            start,
            end,
        ).joinToString("|")
        return current.copy(
            id = sha1(identity).take(16),
            startPeriod = start,
            endPeriod = end,
            weeks = current.weeks + next.weeks,
            weekText = listOf(current.weekText, next.weekText)
                .filter(String::isNotBlank)
                .maxByOrNull(String::length)
                .orEmpty(),
            credit = current.credit.ifBlank { next.credit },
            courseCode = current.courseCode.ifBlank { next.courseCode },
            teachingClassId = joinDistinct(
                current.teachingClassId,
                next.teachingClassId,
            ),
            teachingClassName = joinDistinct(
                current.teachingClassName,
                next.teachingClassName,
            ),
        )
    }

    private fun visibleSessionKey(course: Course): String = listOf(
        course.name.trim(),
        course.teacher.trim(),
        course.room.trim(),
        course.campus.trim(),
        course.weekday.toString(),
        course.weeks.sorted().joinToString(","),
    ).joinToString("|")

    fun formatWeeks(weeks: Set<Int>): String {
        val sorted = weeks.filter { it in 1..30 }.distinct().sorted()
        if (sorted.isEmpty()) return ""
        if (sorted.size == 1) return "${sorted.first()}周"

        val step = sorted[1] - sorted[0]
        if (
            step == 2 &&
            sorted.zipWithNext().all { (first, second) -> second - first == 2 }
        ) {
            val parity = if (sorted.first() % 2 == 1) "单周" else "双周"
            return "${sorted.first()}-${sorted.last()}$parity"
        }

        val result = mutableListOf<String>()
        var rangeStart = sorted.first()
        var previous = rangeStart
        sorted.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
            } else {
                result += formatRange(rangeStart, previous)
                rangeStart = week
                previous = week
            }
        }
        result += formatRange(rangeStart, previous)
        return result.joinToString("、")
    }

    private fun formatRange(start: Int, end: Int): String =
        if (start == end) "${start}周" else "$start-${end}周"

    private fun joinDistinct(first: String, second: String): String =
        listOf(first, second)
            .flatMap { it.split(" / ", " · ", ", ") }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" · ")

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
