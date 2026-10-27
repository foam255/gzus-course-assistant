package com.gzus.courseassistant

import com.gzus.courseassistant.data.update.UpdateChecker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
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
    fun followsLatestReleaseRedirectAndBuildsAssetUrl() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "/foam255/gzus-course-assistant/releases/tag/v1.3.2",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val root = server.url("/").toString().trimEnd('/')
        val checker = UpdateChecker(
            client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build(),
            releaseLatestUrl = "$root/releases/latest",
            downloadBaseUrl = "$root/releases/download",
        )

        val update = checker.checkLatest("1.3.1")

        assertEquals("1.3.2", update?.versionName)
        assertEquals("v1.3.2", update?.tagName)
        assertTrue(
            update?.downloadUrl.toString()
                .endsWith("/releases/download/v1.3.2/app-release.apk"),
        )
    }

    @Test
    fun reportsNoUpdateWhenCurrentVersionIsLatest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "/foam255/gzus-course-assistant/releases/tag/v1.3.2",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val root = server.url("/").toString().trimEnd('/')
        val checker = UpdateChecker(
            client = OkHttpClient.Builder().followRedirects(true).build(),
            releaseLatestUrl = "$root/releases/latest",
            downloadBaseUrl = "$root/releases/download",
        )

        assertNull(checker.checkLatest("1.3.2"))
    }

    @Test
    fun comparesVersionSegmentsNumerically() {
        assertTrue(UpdateChecker.compareVersions("1.3.10", "1.3.9") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0", "1.99.99") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.3", "1.3.0.0"))
        assertEquals("1.4.0", UpdateChecker.normalizeVersion("v1.4.0"))
    }
}
