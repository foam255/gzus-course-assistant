package com.gzus.courseassistant

import com.gzus.courseassistant.data.network.StoredCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredCookieJarTest {
    @Test
    fun restoresEncryptedStorePayloadAndMatchesRequests() {
        val cookie = Cookie.Builder()
            .name("JSESSIONID")
            .value("session-value")
            .domain("jwxt.gzus.edu.cn")
            .path("/jwglxt")
            .expiresAt(System.currentTimeMillis() + 60_000)
            .secure()
            .httpOnly()
            .build()

        val restored = StoredCookieJar.fromJson(StoredCookieJar.toJson(listOf(cookie)))
        val jar = StoredCookieJar(restored) {}
        val matches = jar.loadForRequest(
            "https://jwxt.gzus.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html".toHttpUrl(),
        )

        assertEquals(1, matches.size)
        assertEquals("JSESSIONID", matches.single().name)
        assertEquals("session-value", matches.single().value)
        assertTrue(matches.single().secure)
        assertTrue(matches.single().httpOnly)
    }

    @Test
    fun removesAnExpiredRestoredCookie() {
        var persisted: List<Cookie>? = null
        val expired = Cookie.Builder()
            .name("JSESSIONID")
            .value("expired")
            .domain("jwxt.gzus.edu.cn")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 1)
            .build()
        val jar = StoredCookieJar(listOf(expired)) { persisted = it }

        assertTrue(jar.isEmpty())
        assertTrue(persisted.isNullOrEmpty())
        assertFalse(
            jar.loadForRequest("https://jwxt.gzus.edu.cn/jwglxt/".toHttpUrl()).isNotEmpty(),
        )
    }
}
