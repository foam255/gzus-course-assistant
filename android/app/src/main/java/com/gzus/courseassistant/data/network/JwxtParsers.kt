package com.gzus.courseassistant.data.network

import com.gzus.courseassistant.data.model.CalendarEntry
import com.gzus.courseassistant.data.model.Course
import com.gzus.courseassistant.data.model.CourseNormalizer
import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.data.model.Period
import com.gzus.courseassistant.data.model.ScheduleLogic
import com.gzus.courseassistant.data.model.Term
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class TermPage(
    val currentTerm: Term,
    val terms: List<Term>,
)

data class SchedulePayload(
    val studentName: String,
    val campusId: String,
    val courses: List<Course>,
)

object JwxtParsers {
    private val selectPattern = Regex(
        """(?is)<select[^>]*name\s*=\s*["'](xnm|xqm)["'][^>]*>(.*?)</select>""",
    )
    private val optionPattern = Regex(
        """(?is)<option[^>]*value\s*=\s*["']([^"']+)["'][^>]*>(.*?)</option>""",
    )
    private val selectedPattern = Regex("""(?i)\bselected(?:\s*=\s*["'][^"']*["'])?""")

    fun parseTermPage(html: String): TermPage {
        val values = mutableMapOf<String, String>()
        val years = mutableListOf<Int>()
        val semesterCodes = mutableListOf<Int>()

        selectPattern.findAll(html).forEach { match ->
            val name = match.groupValues[1]
            optionPattern.findAll(match.groupValues[2]).forEach { option ->
                val value = option.groupValues[1].trim()
                if (value.isBlank()) return@forEach
                when (name) {
                    "xnm" -> value.toIntOrNull()?.let {
                        years += it
                        if (selectedPattern.containsMatchIn(option.value)) values[name] = value
                    }
                    "xqm" -> value.toIntOrNull()?.let {
                        semesterCodes += it
                        if (selectedPattern.containsMatchIn(option.value)) values[name] = value
                    }
                }
            }
        }

        val year = values["xnm"]?.toIntOrNull()
        val number = when (values["xqm"]) {
            "3" -> 1
            "12" -> 2
            "16" -> 3
            else -> null
        }
        val current = if (year != null && number != null) {
            Term(year, number)
        } else {
            Term.guess()
        }
        val terms = years.distinct().flatMap { y ->
            semesterCodes.distinct().mapNotNull { code ->
                when (code) {
                    3 -> Term(y, 1)
                    12 -> Term(y, 2)
                    16 -> Term(y, 3)
                    else -> null
                }
            }
        }.sortedWith(compareByDescending<Term> { it.year }.thenByDescending { it.number })

        return TermPage(current, terms.ifEmpty { listOf(current) })
    }

    fun parseSchedule(payload: JSONObject, term: Term): SchedulePayload {
        val rows = JSONArray()
        payload.optJSONArray("kbList")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(rows::put)
            }
        }
        payload.optJSONArray("sjkList")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(rows::put)
            }
        }

        val courses = normalizeCourses(rows)
        val campusId = (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { first(it, "xqh_id").isNotBlank() }
            ?.let { first(it, "xqh_id") }
            .orEmpty()
        val studentName = payload.optJSONObject("xsxx")?.let {
            first(it, "XM", "xm", "xsmc")
        }.orEmpty()

        return SchedulePayload(studentName, campusId, courses)
    }

    fun parsePeriods(payload: JSONArray): List<Period> {
        val periods = (0 until payload.length()).mapNotNull { index ->
            payload.optJSONObject(index)?.let { row ->
                val label = first(row, "rsdjcmc", "jcmc")
                    .ifBlank { (index + 1).toString() }
                val periodIndex = Regex("""\d+""").find(label)?.value?.toIntOrNull()
                    ?: (index + 1)
                Period(
                    index = periodIndex,
                    label = label,
                    start = ScheduleLogic.normalizedTime(first(row, "qssj", "kssj")),
                    end = ScheduleLogic.normalizedTime(first(row, "jssj", "sksj")),
                )
            }
        }.sortedBy { it.index }

        if (periods.isEmpty()) {
            return (1..12).map { Period(it, it.toString(), "", "") }
        }
        return periods
    }

    fun parseGrades(payload: JSONObject, term: Term): List<Grade> {
        val items = payload.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { row ->
                val courseName = first(row, "kcmc", "kcxzmc")
                if (courseName.isBlank()) return@let null
                val score = first(row, "bfzcj", "cj")
                val type = first(row, "ksxz", "kclbmc")
                Grade(
                    term = term,
                    courseName = courseName,
                    score = score,
                    credit = first(row, "xf"),
                    gpa = first(row, "jd"),
                    courseType = type,
                    note = first(row, "cjbz"),
                    passed = isPassing(score),
                )
            }
        }
    }

    fun parseExams(payload: JSONObject, term: Term): List<Exam> {
        val items = payload.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { row ->
                val courseName = first(row, "kcmc")
                if (courseName.isBlank()) return@let null
                Exam(
                    term = term,
                    courseName = courseName,
                    date = first(row, "ksrq", "date"),
                    time = first(row, "kssj", "sjsj"),
                    room = first(row, "cdmc", "cdxqmc"),
                    seat = first(row, "zwh"),
                    type = first(row, "ksmc", "ksfs", "khfs"),
                    note = first(row, "ksbz", "bzxx"),
                )
            }
        }.sortedWith(compareBy<Exam> { it.date }.thenBy { it.time })
    }

    fun parseCalendar(payload: JSONArray): List<CalendarEntry> {
        return (0 until payload.length()).mapNotNull { index ->
            payload.optJSONObject(index)?.let { row ->
                val title = first(row, "school_title", "title")
                val path = first(row, "image_path", "image")
                if (title.isBlank() || path.isBlank()) return@let null
                CalendarEntry(
                    title = title,
                    imageUrl = if (path.startsWith("http")) {
                        path
                    } else {
                        "https://seig.ethank.top/${path.trimStart('/')}"
                    },
                    localFile = "",
                )
            }
        }
    }

    private fun normalizeCourses(rows: JSONArray): List<Course> {
        val result = mutableListOf<Course>()
        val seen = mutableSetOf<String>()

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val name = first(row, "kcmc")
            val weekday = parseWeekday(first(row, "xqj", "weekday")) ?: continue
            if (name.isBlank()) continue
            val teacher = first(row, "xm", "jsxm")
            val campus = first(row, "xqmc")
            val room = first(row, "cdmc").let {
                if (campus.isNotBlank() && it.isNotBlank() && !it.contains(campus)) {
                    "$campus $it"
                } else {
                    it
                }
            }
            val weeks = parseWeeks(row)
            val weekText = listOf(
                first(row, "zcd", "zcmc_display"),
                first(row, "zcmc"),
            ).firstOrNull(::looksLikeWeekText).orEmpty()
            val teachingClassId = first(row, "jxb_id")
            val teachingClassName = first(row, "jxbmc")
            val blocks = parsePeriodBlocks(first(row, "jcs", "jc", "jcor"))

            blocks.forEach { (start, end) ->
                val identity = listOf(
                    teachingClassId,
                    name,
                    teacher,
                    room,
                    weekday,
                    start,
                    end,
                    weeks.sorted().joinToString(","),
                ).joinToString("|")
                if (!seen.add(identity)) return@forEach
                result += Course(
                    id = sha1(identity).take(16),
                    name = name,
                    teacher = teacher,
                    room = room,
                    campus = campus,
                    weekday = weekday,
                    startPeriod = start,
                    endPeriod = end,
                    weeks = weeks,
                    weekText = weekText,
                    credit = first(row, "xf"),
                    courseCode = first(row, "kch", "kcbm"),
                    teachingClassId = teachingClassId,
                    teachingClassName = teachingClassName,
                )
            }
        }
        return CourseNormalizer.normalize(result)
    }

    private fun parseWeeks(row: JSONObject): Set<Int> {
        val text = listOf(
            first(row, "zcd", "zcmc_display"),
            first(row, "zcmc"),
        ).firstOrNull(::looksLikeWeekText).orEmpty().replace(" ", "")
        if (text.isBlank()) return (1..20).toSet()
        if (text.matches(Regex("[01]{8,}"))) {
            return text.mapIndexedNotNull { index, c -> (index + 1).takeIf { c == '1' } }.toSet()
        }

        val weeks = mutableSetOf<Int>()
        Regex("""(\d{1,2})(?:-(\d{1,2}))?""").findAll(text).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: start
            if (start in 1..30 && end in 1..30) {
                weeks += minOf(start, end)..maxOf(start, end)
            }
        }
        val filtered = when {
            "单" in text -> weeks.filter { it % 2 == 1 }
            "双" in text -> weeks.filter { it % 2 == 0 }
            else -> weeks
        }
        return filtered.toSet().ifEmpty { (1..20).toSet() }
    }

    private fun looksLikeWeekText(value: String): Boolean {
        val normalized = value.replace(" ", "")
        if (normalized.isBlank()) return false
        if (normalized.matches(Regex("[01]{8,}"))) return true
        if ("单" in normalized || "双" in normalized || "周" in normalized) return true
        val lowered = normalized.lowercase()
        return "odd" in lowered || "even" in lowered
    }

    private fun parsePeriodBlocks(text: String): List<Pair<Int, Int>> {
        val result = Regex("""(\d{1,2})(?:-(\d{1,2}))?""").findAll(text)
            .mapNotNull { match ->
                val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val end = match.groupValues[2].toIntOrNull() ?: start
                if (start in 1..20 && end in 1..20) {
                    minOf(start, end) to maxOf(start, end)
                } else {
                    null
                }
            }
            .toList()
        return result.ifEmpty { listOf(1 to 1) }
    }

    private fun parseWeekday(value: String): Int? {
        Regex("""[1-7]""").find(value)?.let { return it.value.toInt() }
        return when (value) {
            "周一", "星期一" -> 1
            "周二", "星期二" -> 2
            "周三", "星期三" -> 3
            "周四", "星期四" -> 4
            "周五", "星期五" -> 5
            "周六", "星期六" -> 6
            "周日", "周天", "星期日", "星期天" -> 7
            else -> null
        }
    }

    private fun isPassing(score: String): Boolean {
        if (score.contains("不合格") || score.contains("不及格") || score.contains("未通过")) {
            return false
        }
        if (score.contains("合格") || score.contains("通过") || score.contains("优秀")) {
            return true
        }
        return score.toDoubleOrNull()?.let { it >= 60 } ?: false
    }

    private fun first(json: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (json.has(key) && !json.isNull(key)) {
                val value = json.optString(key).trim()
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return ""
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
