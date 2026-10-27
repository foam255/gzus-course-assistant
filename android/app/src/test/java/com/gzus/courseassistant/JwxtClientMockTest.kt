package com.gzus.courseassistant

import com.gzus.courseassistant.data.network.JwxtClient
import com.gzus.courseassistant.data.network.CaptchaRejectedException
import com.gzus.courseassistant.data.network.StoredCookieJar
import com.gzus.courseassistant.data.model.Term
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class JwxtClientMockTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun signInAndReadSchedule() {
        val page = """
            <select name="xnm">
              <option value="2026" selected="selected">2026-2027</option>
            </select>
            <select name="xqm">
              <option value="3" selected="selected">1</option>
            </select>
        """.trimIndent()

        server.enqueue(MockResponse().setBody("<html>CAS</html>"))
        server.enqueue(
            MockResponse().setBody("""{"meta":{"success":true},"ticket":"ST-test"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html")
                .setHeader("Set-Cookie", "JSESSIONID=test-session; Path=/"),
        )
        server.enqueue(MockResponse().setBody(page))
        server.enqueue(MockResponse().setBody(page))
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "xsxx":{"XM":"测试同学"},
                  "kbList":[
                    {"kcmc":"数据结构","xqj":"1","jcs":"1-2","cdmc":"A101","xm":"张老师","zcmc":"1-16周","xf":"4","kch":"CS01","jxb_id":"class-a","xqh_id":"1"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody("""[{"jcmc":"1","qssj":"09:00","jssj":"09:40"}]"""),
        )

        val root = server.url("/").toString().trimEnd('/')
        val client = JwxtClient(
            cookieJar = StoredCookieJar(emptyList()) {},
            baseUrl = "$root/jwglxt",
            casBaseUrl = "$root/lyuapServer",
            ssoUrl = "$root/sso/lyiotlogin",
            calendarUrl = "$root/calendar",
        )
        client.beginCasSession()
        client.login("2026001", "secret", "captcha", "7")
        val schedule = client.fetchSchedule()

        assertEquals(Term(2026, 1), schedule.term)
        assertEquals(1, schedule.courses.size)
        assertEquals("测试同学", client.lastStudentName)
        assertEquals(1, schedule.periods.size)

        repeat(5) { server.takeRequest() }
        val scheduleRequest = server.takeRequest()
        assertEquals("/jwglxt/kbcx/xskbcx_cxXsgrkb.html", scheduleRequest.path)
        assertTrue(scheduleRequest.body.readUtf8().contains("kzlx=ck"))
        val periodRequest = server.takeRequest()
        assertTrue(periodRequest.body.readUtf8().contains("xqh_id=1"))
    }

    @Test
    fun rejectsInvalidCaptchaResponse() {
        server.enqueue(MockResponse().setBody("<html>CAS</html>"))
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"code":"CODEFALSE","data":""},"meta":{"success":false}}""",
            ),
        )
        val root = server.url("/").toString().trimEnd('/')
        val client = JwxtClient(
            cookieJar = StoredCookieJar(emptyList()) {},
            baseUrl = "$root/jwglxt",
            casBaseUrl = "$root/lyuapServer",
            ssoUrl = "$root/sso/lyiotlogin",
            calendarUrl = "$root/calendar",
        )

        assertThrows(CaptchaRejectedException::class.java) {
            client.login("2026001", "secret", "captcha", "wrong")
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun readsGradesExamsAndCalendar() {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"kcmc":"高等数学","bfzcj":"88","xf":"4","jd":"3.6"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"kcmc":"高等数学","ksrq":"2027-01-05","kssj":"09:00-11:00","cdmc":"A101","zwh":"8"}]}""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/calendar-final"),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"school_title":"2026-2027","image_path":"/group/calendar.png"}]""",
            ),
        )
        val root = server.url("/").toString().trimEnd('/')
        val client = JwxtClient(
            cookieJar = StoredCookieJar(emptyList()) {},
            baseUrl = "$root/jwglxt",
            casBaseUrl = "$root/lyuapServer",
            ssoUrl = "$root/sso/lyiotlogin",
            calendarUrl = "$root/calendar",
        )

        val grades = client.fetchGrades(Term(2026, 1))
        val exams = client.fetchExams(Term(2026, 1))
        val calendar = client.fetchCalendar()

        assertEquals("88", grades.single().score)
        assertEquals("8", exams.single().seat)
        assertTrue(calendar.single().imageUrl.endsWith("/group/calendar.png"))

        val gradeRequest = server.takeRequest()
        assertTrue(gradeRequest.path.orEmpty().startsWith("/jwglxt/cjcx/cjcx_cxXsgrcj.html"))
        assertTrue(gradeRequest.body.readUtf8().contains("xnm=2026"))
        val examRequest = server.takeRequest()
        assertTrue(
            examRequest.path.orEmpty()
                .startsWith("/jwglxt/kwgl/kscx_cxXsksxxIndex.html"),
        )
        assertTrue(examRequest.body.readUtf8().contains("xqm=3"))
        val calendarRedirect = server.takeRequest()
        assertEquals("/calendar", calendarRedirect.path)
        assertEquals("/calendar-final", server.takeRequest().path)
    }

    @Test
    fun upgradesSchoolHttpRedirectToHttps() {
        val upgraded = JwxtClient.secureRedirectUrl(
            "http://jwxt.gzus.edu.cn/jwglxt/xtgl/index_initMenu.html".toHttpUrl(),
        )
        assertEquals("https", upgraded.scheme)
        assertEquals(443, upgraded.port)

        val external = JwxtClient.secureRedirectUrl(
            "http://example.com/path".toHttpUrl(),
        )
        assertEquals("http", external.scheme)
    }

    private fun Term(year: Int, number: Int) =
        com.gzus.courseassistant.data.model.Term(year, number)
}
