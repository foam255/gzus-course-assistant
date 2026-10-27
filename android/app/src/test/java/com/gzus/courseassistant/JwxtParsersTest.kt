package com.gzus.courseassistant

import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.data.network.JwxtParsers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JwxtParsersTest {
    @Test
    fun parsesSelectedTermAndTermList() {
        val page = """
            <select name="xnm">
              <option value="2025">2025-2026</option>
              <option value="2026" selected="selected">2026-2027</option>
            </select>
            <select name="xqm">
              <option value="3" selected="selected">1</option>
              <option value="12">2</option>
            </select>
        """.trimIndent()
        val result = JwxtParsers.parseTermPage(page)
        assertEquals(Term(2026, 1), result.currentTerm)
        assertTrue(Term(2026, 2) in result.terms)
    }

    @Test
    fun mergesCoLocatedTeachingClassRecordsAndParsesPeriods() {
        val payload = JSONObject(
            """
            {
              "xsxx": {"XM": "测试同学"},
              "kbList": [
                {"kcmc":"数据结构","xqj":"1","jcs":"1-2","cdmc":"A101","xm":"张老师","zcmc":"1-16周","xf":"4","kch":"CS01","jxb_id":"class-a","jxbmc":"1班","xqh_id":"1"},
                {"kcmc":"数据结构","xqj":"1","jcs":"1-2","cdmc":"A101","xm":"张老师","zcmc":"1-16周","xf":"4","kch":"CS01","jxb_id":"class-b","jxbmc":"2班","xqh_id":"1"}
              ]
            }
            """.trimIndent(),
        )
        val schedule = JwxtParsers.parseSchedule(payload, Term(2026, 1))
        assertEquals("测试同学", schedule.studentName)
        assertEquals("1", schedule.campusId)
        assertEquals(1, schedule.courses.size)
        assertTrue(schedule.courses.single().teachingClassId.contains("class-a"))
        assertTrue(schedule.courses.single().teachingClassId.contains("class-b"))
        assertEquals(setOf(1, 2), schedule.courses.single().weeks.filter { it <= 2 }.toSet())

        val periods = JwxtParsers.parsePeriods(
            JSONArray(
                """
                [
                  {"jcmc":"1","qssj":"09:00","jssj":"09:40"},
                  {"jcmc":"2","qssj":"09:41","jssj":"10:20"},
                  {"jcmc":"17","qssj":"8:00:","jssj":"8:40:"}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(3, periods.size)
        assertEquals("09:00", periods.first().start)
        assertEquals("08:00", periods.last().start)
        assertEquals("08:40", periods.last().end)
    }

    @Test
    fun filtersOddWeekCourses() {
        val payload = JSONObject(
            """{"kbList":[{"kcmc":"英语","xqj":"3","jcs":"3-4","zcmc":"1-8周(单)","jxb_id":"x"}]}""",
        )
        val course = JwxtParsers.parseSchedule(payload, Term(2026, 1)).courses.single()
        assertEquals(setOf(1, 3, 5, 7), course.weeks)
    }

    @Test
    fun readsRealWeekFieldInsteadOfTeachingTitle() {
        val payload = JSONObject(
            """
            {
              "kbList":[{
                "kcmc":"大数据算法解析",
                "xqj":"1",
                "jcs":"1-2",
                "zcmc":"助教（高校）",
                "zcd":"1-17周(单)",
                "jxb_id":"real-week-field"
              }]
            }
            """.trimIndent(),
        )

        val course = JwxtParsers.parseSchedule(payload, Term(2026, 1)).courses.single()

        assertEquals((1..17 step 2).toSet(), course.weeks)
        assertEquals("1-17单周", course.weekText)
    }

    @Test
    fun parsesGradesExamsAndCalendar() {
        val grades = JwxtParsers.parseGrades(
            JSONObject(
                """{"items":[{"kcmc":"高等数学","bfzcj":"82","xf":"4","jd":"3.2","ksxz":"必修","cjbz":""}]}""",
            ),
            Term(2025, 2),
        )
        assertEquals(1, grades.size)
        assertEquals("3.2", grades.single().gpa)
        assertTrue(grades.single().passed)

        val exams = JwxtParsers.parseExams(
            JSONObject(
                """{"items":[{"kcmc":"高等数学","ksrq":"2027-01-03","kssj":"09:00-11:00","cdmc":"A101","zwh":"12","ksmc":"期末考试"}]}""",
            ),
            Term(2026, 1),
        )
        assertEquals("A101", exams.single().room)
        assertEquals("12", exams.single().seat)

        val calendar = JwxtParsers.parseCalendar(
            JSONArray("""[{"school_title":"2026-2027","image_path":"/group1/calendar.png"}]"""),
        )
        assertTrue(calendar.single().imageUrl.startsWith("https://seig.ethank.top/"))
    }

    @Test
    fun marksFailingGrade() {
        val grade = JwxtParsers.parseGrades(
            JSONObject("""{"items":[{"kcmc":"课程","bfzcj":"55","xf":"2"}]}"""),
            Term(2025, 2),
        ).single()
        assertFalse(grade.passed)
    }
}
