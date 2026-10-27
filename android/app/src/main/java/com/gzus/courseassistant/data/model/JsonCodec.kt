package com.gzus.courseassistant.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object JsonCodec {
    fun encodeState(state: AppState): String = JSONObject().apply {
        put("schemaVersion", state.schemaVersion)
        put("selectedTerm", state.selectedTerm)
        put("currentTerm", state.currentTerm)
        put("sessionValid", state.sessionValid)
        put("firstWeekStarts", JSONObject().apply {
            state.firstWeekStarts.forEach { (term, date) ->
                put(term, date.toString())
            }
        })
        put("availableTerms", JSONArray().apply {
            state.availableTerms.forEach { put(encodeTerm(it)) }
        })
        put("schedules", JSONArray().apply {
            state.schedules.forEach { put(encodeSchedule(it)) }
        })
        put("grades", JSONArray().apply {
            state.grades.forEach { put(encodeGrade(it)) }
        })
        put("exams", JSONArray().apply {
            state.exams.forEach { put(encodeExam(it)) }
        })
        put("calendarEntries", JSONArray().apply {
            state.calendarEntries.forEach { put(encodeCalendar(it)) }
        })
        put("lastScheduleSync", state.lastScheduleSync)
        put("lastAcademicSync", state.lastAcademicSync)
        put("lastCalendarSync", state.lastCalendarSync)
        put("studentName", state.studentName)
    }.toString()

    fun decodeState(raw: String?): AppState {
        if (raw.isNullOrBlank()) return AppState()
        return runCatching {
            val json = JSONObject(raw)
            AppState(
                schemaVersion = json.optInt("schemaVersion", 1),
                selectedTerm = json.optString("selectedTerm"),
                currentTerm = json.optString("currentTerm"),
                sessionValid = json.optBoolean("sessionValid", false),
                firstWeekStarts = json.optJSONObject("firstWeekStarts").dateMap(),
                availableTerms = json.optJSONArray("availableTerms").objects()
                    .mapNotNull(::decodeTerm),
                schedules = json.optJSONArray("schedules").objects()
                    .mapNotNull(::decodeSchedule),
                grades = json.optJSONArray("grades").objects()
                    .mapNotNull(::decodeGrade),
                exams = json.optJSONArray("exams").objects()
                    .mapNotNull(::decodeExam),
                calendarEntries = json.optJSONArray("calendarEntries").objects()
                    .mapNotNull(::decodeCalendar),
                lastScheduleSync = json.optLong("lastScheduleSync"),
                lastAcademicSync = json.optLong("lastAcademicSync"),
                lastCalendarSync = json.optLong("lastCalendarSync"),
                studentName = json.optString("studentName"),
            )
        }.getOrDefault(AppState())
    }

    private fun encodeTerm(term: Term) = JSONObject()
        .put("year", term.year)
        .put("number", term.number)

    private fun decodeTerm(json: JSONObject): Term? =
        runCatching { Term(json.getInt("year"), json.getInt("number")) }.getOrNull()

    private fun encodeSchedule(snapshot: ScheduleSnapshot) = JSONObject()
        .put("term", encodeTerm(snapshot.term))
        .put("periods", JSONArray().apply {
            snapshot.periods.forEach { period ->
                put(
                    JSONObject()
                        .put("index", period.index)
                        .put("label", period.label)
                        .put("start", period.start)
                        .put("end", period.end),
                )
            }
        })
        .put("courses", JSONArray().apply {
            snapshot.courses.forEach { course ->
                put(
                    JSONObject()
                        .put("id", course.id)
                        .put("name", course.name)
                        .put("teacher", course.teacher)
                        .put("room", course.room)
                        .put("campus", course.campus)
                        .put("weekday", course.weekday)
                        .put("startPeriod", course.startPeriod)
                        .put("endPeriod", course.endPeriod)
                        .put("weeks", JSONArray(course.weeks.sorted()))
                        .put("weekText", course.weekText)
                        .put("credit", course.credit)
                        .put("courseCode", course.courseCode)
                        .put("teachingClassId", course.teachingClassId)
                        .put("teachingClassName", course.teachingClassName),
                )
            }
        })
        .put("fetchedAt", snapshot.fetchedAt)

    private fun decodeSchedule(json: JSONObject): ScheduleSnapshot? = runCatching {
        val term = decodeTerm(json.getJSONObject("term")) ?: return null
        ScheduleSnapshot(
            term = term,
            periods = json.optJSONArray("periods").objects().map { period ->
                Period(
                    index = period.getInt("index"),
                    label = period.optString("label"),
                    start = period.optString("start"),
                    end = period.optString("end"),
                )
            },
            courses = CourseNormalizer.normalize(json.optJSONArray("courses").objects().map { course ->
                val weeks = course.optJSONArray("weeks").intValues().toSet()
                Course(
                    id = course.getString("id"),
                    name = course.getString("name"),
                    teacher = course.optString("teacher"),
                    room = course.optString("room"),
                    campus = course.optString("campus"),
                    weekday = course.getInt("weekday"),
                    startPeriod = course.getInt("startPeriod"),
                    endPeriod = course.getInt("endPeriod"),
                    weeks = weeks,
                    weekText = course.optString("weekText"),
                    credit = course.optString("credit"),
                    courseCode = course.optString("courseCode"),
                    teachingClassId = course.optString("teachingClassId"),
                    teachingClassName = course.optString("teachingClassName"),
                )
            }),
            fetchedAt = json.optLong("fetchedAt"),
        )
    }.getOrNull()

    private fun encodeGrade(grade: Grade) = JSONObject()
        .put("term", encodeTerm(grade.term))
        .put("courseName", grade.courseName)
        .put("score", grade.score)
        .put("credit", grade.credit)
        .put("gpa", grade.gpa)
        .put("courseType", grade.courseType)
        .put("note", grade.note)
        .put("passed", grade.passed)

    private fun decodeGrade(json: JSONObject): Grade? = runCatching {
        Grade(
            term = decodeTerm(json.getJSONObject("term")) ?: return null,
            courseName = json.getString("courseName"),
            score = json.optString("score"),
            credit = json.optString("credit"),
            gpa = json.optString("gpa"),
            courseType = json.optString("courseType"),
            note = json.optString("note"),
            passed = json.optBoolean("passed"),
        )
    }.getOrNull()

    private fun encodeExam(exam: Exam) = JSONObject()
        .put("term", encodeTerm(exam.term))
        .put("courseName", exam.courseName)
        .put("date", exam.date)
        .put("time", exam.time)
        .put("room", exam.room)
        .put("seat", exam.seat)
        .put("type", exam.type)
        .put("note", exam.note)

    private fun decodeExam(json: JSONObject): Exam? = runCatching {
        Exam(
            term = decodeTerm(json.getJSONObject("term")) ?: return null,
            courseName = json.getString("courseName"),
            date = json.optString("date"),
            time = json.optString("time"),
            room = json.optString("room"),
            seat = json.optString("seat"),
            type = json.optString("type"),
            note = json.optString("note"),
        )
    }.getOrNull()

    private fun encodeCalendar(entry: CalendarEntry) = JSONObject()
        .put("title", entry.title)
        .put("imageUrl", entry.imageUrl)
        .put("localFile", entry.localFile)

    private fun decodeCalendar(json: JSONObject) = runCatching {
        CalendarEntry(
            title = json.getString("title"),
            imageUrl = json.getString("imageUrl"),
            localFile = json.optString("localFile"),
        )
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.intValues(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { optInt(it) }
    }

    private fun JSONObject?.dateMap(): Map<String, LocalDate> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { key ->
            runCatching { key to LocalDate.parse(getString(key)) }.getOrNull()
        }.toMap()
    }
}
