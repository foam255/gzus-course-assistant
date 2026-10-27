package com.gzus.courseassistant.data.network

import com.gzus.courseassistant.data.model.CalendarEntry
import com.gzus.courseassistant.data.model.CaptchaChallenge
import com.gzus.courseassistant.data.model.Exam
import com.gzus.courseassistant.data.model.Grade
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.data.model.Term
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

open class JwxtException(message: String) : Exception(message)
class LoginRejectedException(message: String) : JwxtException(message)
class CaptchaRejectedException(message: String) : JwxtException(message)
class SessionExpiredException : JwxtException("登录会话已失效，请重新登录")

class JwxtClient(
    cookieJar: CookieJar,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val casBaseUrl: String = DEFAULT_CAS_BASE_URL,
    private val ssoUrl: String = DEFAULT_SSO_URL,
    private val calendarUrl: String = DEFAULT_CALENDAR_URL,
    client: OkHttpClient? = null,
) {
    private var casSessionStarted = false
    private val http = client ?: OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun beginCasSession() {
        val url = casUrl("/login").newBuilder()
            .addQueryParameter("service", ssoUrl)
            .build()
        get(url)
        casSessionStarted = true
    }

    fun fetchCaptcha(): CaptchaChallenge {
        if (!casSessionStarted) beginCasSession()
        val url = casUrl("/kaptcha").newBuilder()
            .addQueryParameter("uid", "")
            .build()
        val payload = getJsonObject(url)
        val id = payload.optString("uid")
        val image = payload.optString("content")
        if (id.isBlank() || !image.startsWith("data:image/")) {
            throw JwxtException("认证系统没有返回有效验证码")
        }
        return CaptchaChallenge(
            id = id,
            imageDataUri = image,
            timeoutSeconds = payload.optInt("timeout", 300),
        )
    }

    fun login(
        username: String,
        password: String,
        captchaId: String,
        captchaCode: String,
    ) {
        if (username.isBlank() || password.isBlank()) {
            throw LoginRejectedException("请输入账号和密码")
        }
        if (captchaId.isBlank() || captchaCode.isBlank()) {
            throw JwxtException("请输入验证码")
        }
        if (!casSessionStarted) beginCasSession()
        if (password.any { it.code > 255 }) {
            throw LoginRejectedException("密码包含当前登录流程不支持的字符")
        }

        val body = FormBody.Builder()
            .add("username", username.trim())
            .add("password", CasCrypto.encrypt(password))
            .add("service", ssoUrl)
            .add("loginType", "")
            .add("id", captchaId)
            .add("code", captchaCode.trim())
            .build()
        val payload = postJsonObject(casUrl("/v1/tickets"), body)
        val data = payload.opt("data")
        if (data is JSONObject && data.optString("code").isNotBlank()) {
            handleCasError(data, payload.optJSONObject("meta"))
        }
        val meta = payload.optJSONObject("meta")
        if (meta != null && !meta.optBoolean("success", true)) {
            throw LoginRejectedException(meta.optString("message", "CAS 登录失败"))
        }

        var ticket = payload.optString("ticket")
        var tgt = payload.optString("tgt")
        if (data is JSONObject) {
            if (ticket.isBlank()) ticket = data.optString("ticket")
            if (tgt.isBlank()) tgt = data.optString("tgt")
        }
        if (ticket.isBlank() && data is String) tgt = data
        if (ticket.isBlank() && tgt.isNotBlank()) {
            ticket = requestServiceTicket(tgt)
        }
        if (ticket.isBlank()) {
            throw LoginRejectedException("CAS 登录成功，但没有返回服务票据")
        }

        val service = ssoUrl.toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket)
            .build()
        ensureAuthenticated(get(service))
    }

    fun fetchSchedule(termLabel: String? = null): ScheduleSnapshot {
        val pageUrl = url("/kbcx/xskbcx_cxXskbcxIndex.html").newBuilder()
            .addQueryParameter("gnmkdm", "N2151")
            .addQueryParameter("layout", "default")
            .build()
        val page = get(pageUrl)
        ensureAuthenticated(page)
        val termPage = JwxtParsers.parseTermPage(page)
        val term = termLabel?.let(Term::parse) ?: termPage.currentTerm

        val form = FormBody.Builder()
            .add("xnm", term.xnm)
            .add("xqm", term.xqm)
            .add("kzlx", "ck")
            .add("xsdm", "")
            .add("kclbdm", "")
            .add("kclxdm", "")
            .build()
        val payload = postJsonObject(url("/kbcx/xskbcx_cxXsgrkb.html"), form)
        val parsed = JwxtParsers.parseSchedule(payload, term)

        val periodForm = FormBody.Builder()
            .add("xnm", term.xnm)
            .add("xqm", term.xqm)
            .add("xqh_id", parsed.campusId)
            .build()
        val periodArray = try {
            postJsonArray(url("/kbcx/xskbcx_cxRjc.html"), periodForm)
        } catch (error: SessionExpiredException) {
            throw error
        } catch (error: JwxtException) {
            JSONArray()
        }

        return ScheduleSnapshot(
            term = term,
            periods = JwxtParsers.parsePeriods(periodArray),
            courses = parsed.courses,
            fetchedAt = System.currentTimeMillis(),
        ).also {
            lastStudentName = parsed.studentName
            lastAvailableTerms = termPage.terms
        }
    }

    fun fetchGrades(term: Term): List<Grade> {
        val form = FormBody.Builder()
            .add("xnm", term.xnm)
            .add("xqm", term.xqm)
            .add("sy_id", "")
            .add("sq_id", "")
            .add("_search", "false")
            .add("nd", System.currentTimeMillis().toString())
            .add("queryModel.showCount", "200")
            .add("queryModel.currentPage", "1")
            .add("queryModel.sortName", "")
            .add("queryModel.sortOrder", "asc")
            .add("time", "1")
            .add("zd_fzdm", "N305005-xs")
            .build()
        val url = url("/cjcx/cjcx_cxXsgrcj.html").newBuilder()
            .addQueryParameter("doType", "query")
            .build()
        val payload = postJsonObject(url, form)
        return JwxtParsers.parseGrades(payload, term)
    }

    fun fetchExams(term: Term): List<Exam> {
        val form = FormBody.Builder()
            .add("xnm", term.xnm)
            .add("xqm", term.xqm)
            .add("ksmcdmb_id", "")
            .add("kch", "")
            .add("kc", "")
            .add("ksrq", "")
            .add("kkbm_id", "")
            .add("_search", "false")
            .add("nd", System.currentTimeMillis().toString())
            .add("queryModel.showCount", "200")
            .add("queryModel.currentPage", "1")
            .add("queryModel.sortName", "")
            .add("queryModel.sortOrder", "asc")
            .add("time", "1")
            .build()
        val url = url("/kwgl/kscx_cxXsksxxIndex.html").newBuilder()
            .addQueryParameter("doType", "query")
            .build()
        val payload = postJsonObject(url, form)
        return JwxtParsers.parseExams(payload, term)
    }

    fun fetchCalendar(): List<CalendarEntry> {
        val payload = getJsonArray(calendarUrl.toHttpUrl())
        return JwxtParsers.parseCalendar(payload)
    }

    fun downloadCalendarImage(entry: CalendarEntry, directory: File): CalendarEntry {
        if (entry.imageUrl.isBlank()) return entry
        directory.mkdirs()
        val safeName = entry.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val target = File(directory, "$safeName-${entry.imageUrl.hashCode()}.img")
        if (!target.exists()) {
            val response = getResponse(Request.Builder().url(entry.imageUrl).build())
            if (!response.isSuccessful) {
                throw JwxtException("校历图片下载失败：HTTP ${response.code}")
            }
            response.use { target.writeBytes(it.body?.bytes() ?: ByteArray(0)) }
        }
        return entry.copy(localFile = target.absolutePath)
    }

    var lastStudentName: String = ""
        private set
    var lastAvailableTerms: List<Term> = emptyList()
        private set

    private fun requestServiceTicket(tgt: String): String {
        val body = FormBody.Builder()
            .add("service", ssoUrl)
            .add("loginToken", "loginToken")
            .build()
        val encoded = java.net.URLEncoder.encode(tgt, Charsets.UTF_8.name())
        val response = postRaw(casUrl("/v1/tickets/$encoded"), body)
        if (!response.isSuccessful) {
            throw JwxtException("CAS 服务票据接口返回 HTTP ${response.code}")
        }
        val text = response.use { it.body?.string().orEmpty() }.trim()
        if (text.isBlank()) throw JwxtException("CAS 服务票据接口返回为空")
        return runCatching {
            val json = JSONObject(text)
            when (val data = json.opt("data")) {
                is String -> data
                is JSONObject -> data.optString("ticket")
                else -> json.optString("ticket")
            }
        }.getOrDefault(text)
    }

    private fun handleCasError(data: JSONObject, meta: JSONObject?) {
        val code = data.optString("code")
        val detail = data.optString("data")
        val message = when (code) {
            "CODEFALSE" -> throw CaptchaRejectedException("验证码错误，请重新输入")
            "FALSE" -> throw LoginRejectedException("账号或密码错误")
            "PASSERROR" -> throw LoginRejectedException(
                if (detail.isBlank()) "密码错误" else "密码错误：$detail",
            )
            "NOUSER" -> throw LoginRejectedException("账号不存在")
            "USERDISABLED" -> throw LoginRejectedException("账号已被停用")
            "USERLOCK" -> throw LoginRejectedException(
                if (detail.isBlank()) "账号已锁定" else "账号已锁定：$detail",
            )
            "TWOVERIFY" -> throw LoginRejectedException("该账号需要二次验证")
            "PEOPLEMOREACCOUNT" -> throw LoginRejectedException("该账号存在多个身份")
            "ISMODIFYPASS" -> throw LoginRejectedException("账号需要先修改密码")
            "ISPHONEOREMAILORANSWER", "ISBINDWX", "NETWORKCOMMITMENT" ->
                throw LoginRejectedException("账号需要额外安全验证")
            else -> meta?.optString("message").orEmpty()
        }
        throw LoginRejectedException(message.ifBlank { "CAS 登录失败：$code" })
    }

    private fun get(url: HttpUrl): String {
        val response = getResponse(Request.Builder().url(url).build())
        if (!response.isSuccessful) {
            throw JwxtException("请求失败：HTTP ${response.code}")
        }
        return response.use { it.body?.string().orEmpty() }
    }

    private fun getJsonObject(url: HttpUrl): JSONObject {
        val body = get(url)
        return runCatching { JSONObject(body) }
            .getOrElse { throw JwxtException("服务器没有返回有效 JSON") }
    }

    private fun getJsonArray(url: HttpUrl): JSONArray {
        val body = get(url)
        return runCatching { JSONArray(body) }
            .getOrElse { throw JwxtException("服务器没有返回有效 JSON") }
    }

    private fun postJsonObject(url: HttpUrl, body: FormBody): JSONObject {
        val response = postRaw(url, body)
        if (response.code in 300..399) throw SessionExpiredException()
        if (!response.isSuccessful) {
            throw JwxtException("请求失败：HTTP ${response.code}")
        }
        val text = response.use { it.body?.string().orEmpty() }
        ensureAuthenticated(text)
        return runCatching { JSONObject(text) }
            .getOrElse { throw JwxtException("服务器没有返回有效 JSON") }
    }

    private fun postJsonArray(url: HttpUrl, body: FormBody): JSONArray {
        val response = postRaw(url, body)
        if (response.code in 300..399) throw SessionExpiredException()
        if (!response.isSuccessful) {
            throw JwxtException("请求失败：HTTP ${response.code}")
        }
        val text = response.use { it.body?.string().orEmpty() }
        ensureAuthenticated(text)
        return runCatching { JSONArray(text) }
            .getOrElse { throw JwxtException("服务器没有返回有效 JSON") }
    }

    private fun postRaw(url: HttpUrl, body: FormBody): Response {
        val request = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(body)
            .build()
        return getResponse(request)
    }

    private fun getResponse(request: Request): Response {
        return try {
            if (request.method == "GET" || request.method == "HEAD") {
                executeFollowingRedirects(request)
            } else {
                http.newCall(request).execute()
            }
        } catch (error: IOException) {
            throw JwxtException("无法连接学校系统：${error.message.orEmpty()}")
        }
    }

    private fun executeFollowingRedirects(initial: Request): Response {
        var request = initial
        repeat(MAX_REDIRECTS + 1) {
            val response = http.newCall(request).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            if (location.isNullOrBlank()) return response

            val target = request.url.resolve(location)
                ?: run {
                    response.close()
                    throw JwxtException("学校系统返回了无效的跳转地址")
                }
            response.close()
            val secureTarget = secureRedirectUrl(target)
            request = request.newBuilder()
                .url(secureTarget)
                .apply {
                    if (request.method == "HEAD") head() else get()
                }
                .build()
        }
        throw JwxtException("学校系统重定向次数过多")
    }

    private fun ensureAuthenticated(html: String) {
        if (
            html.contains("login_slogin") ||
            html.contains("login_getPublicKey") ||
            html.contains("lyuapServer/login") ||
            html.contains("统一身份认证平台")
        ) {
            throw SessionExpiredException()
        }
    }

    private fun url(path: String): HttpUrl = "${baseUrl.trimEnd('/')}/${
        path.trimStart('/')
    }".toHttpUrl()

    private fun casUrl(path: String): HttpUrl = "${casBaseUrl.trimEnd('/')}/${
        path.trimStart('/')
    }".toHttpUrl()

    companion object {
        private const val MAX_REDIRECTS = 10
        const val DEFAULT_BASE_URL = "https://jwxt.gzus.edu.cn/jwglxt"
        const val DEFAULT_CAS_BASE_URL = "https://cas.gzus.edu.cn/lyuapServer"
        const val DEFAULT_SSO_URL = "https://jwxt.gzus.edu.cn/sso/lyiotlogin"
        const val DEFAULT_CALENDAR_URL = "https://seig.ethank.top/api/school-calendar/miniapp"

        internal fun secureRedirectUrl(url: HttpUrl): HttpUrl {
            if (url.scheme != "http" || !isSchoolHost(url.host)) return url
            return url.newBuilder()
                .scheme("https")
                .port(if (url.port == 80) 443 else url.port)
                .build()
        }

        internal fun isSchoolHost(host: String): Boolean =
            host == "gzus.edu.cn" || host.endsWith(".gzus.edu.cn")
    }
}
