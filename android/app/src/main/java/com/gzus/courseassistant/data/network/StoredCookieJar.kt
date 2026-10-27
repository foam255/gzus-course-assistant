package com.gzus.courseassistant.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

class StoredCookieJar(
    initialCookies: List<Cookie> = emptyList(),
    private val onChanged: (List<Cookie>) -> Unit,
) : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()
    private val lock = Any()

    init {
        initialCookies.forEach(::store)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach(::store)
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            purgeExpiredLocked()
            return cookies.values.filter { it.matches(url) }
        }
    }

    fun snapshot(): List<Cookie> = synchronized(lock) {
        purgeExpiredLocked()
        cookies.values.toList()
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        purgeExpiredLocked()
        cookies.isEmpty()
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            onChanged(emptyList())
        }
    }

    private fun store(cookie: Cookie) {
        cookies[key(cookie)] = cookie
    }

    private fun persistLocked() {
        onChanged(cookies.values.filter { it.expiresAt >= System.currentTimeMillis() })
    }

    private fun purgeExpiredLocked() {
        val now = System.currentTimeMillis()
        val expired = cookies.filterValues { it.expiresAt < now }.keys.toList()
        if (expired.isNotEmpty()) {
            expired.forEach(cookies::remove)
            persistLocked()
        }
    }

    private fun key(cookie: Cookie): String =
        "${cookie.name}|${cookie.domain}|${cookie.path}"

    companion object {
        fun toJson(cookies: List<Cookie>): String = JSONArray().apply {
            cookies.forEach { cookie ->
                put(
                    JSONObject()
                        .put("name", cookie.name)
                        .put("value", cookie.value)
                        .put("expiresAt", cookie.expiresAt)
                        .put("domain", cookie.domain)
                        .put("path", cookie.path)
                        .put("secure", cookie.secure)
                        .put("httpOnly", cookie.httpOnly)
                        .put("hostOnly", cookie.hostOnly),
                )
            }
        }.toString()

        fun fromJson(raw: String?): List<Cookie> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val json = array.optJSONObject(index) ?: return@mapNotNull null
                    val domain = json.optString("domain")
                    if (domain.isBlank()) return@mapNotNull null
                    runCatching {
                        val builder = Cookie.Builder()
                            .name(json.getString("name"))
                            .value(json.optString("value"))
                            .path(json.optString("path", "/"))
                            .expiresAt(json.optLong("expiresAt", Long.MAX_VALUE))
                        if (json.optBoolean("hostOnly")) {
                            builder.hostOnlyDomain(domain)
                        } else {
                            builder.domain(domain)
                        }
                        if (json.optBoolean("secure")) builder.secure()
                        if (json.optBoolean("httpOnly")) builder.httpOnly()
                        builder.build()
                    }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }
    }
}
