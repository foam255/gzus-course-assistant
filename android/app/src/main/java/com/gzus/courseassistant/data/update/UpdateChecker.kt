package com.gzus.courseassistant.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val downloadUrl: HttpUrl,
)

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val releaseLatestUrl: String = DEFAULT_RELEASE_LATEST_URL,
    private val downloadBaseUrl: String = DEFAULT_DOWNLOAD_BASE_URL,
) {
    suspend fun checkLatest(currentVersion: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(releaseLatestUrl)
                .get()
                .header("Accept", "text/html")
                .header("User-Agent", "CourseAssistant-Android")
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (error: IOException) {
                throw UpdateException("无法连接 GitHub Releases", error)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw UpdateException("GitHub Releases 返回 HTTP ${it.code}")
                }
                val tagName = tagFromUrl(it.request.url)
                    ?: throw UpdateException("没有找到可用的 GitHub Release")
                val latestVersion = normalizeVersion(tagName)
                    ?: throw UpdateException("Release 标签不是有效版本号")
                if (compareVersions(latestVersion, currentVersion) <= 0) {
                    return@withContext null
                }
                val downloadUrl = downloadBaseUrl.toHttpUrl().newBuilder()
                    .addPathSegment(tagName)
                    .addPathSegment(APK_ASSET_NAME)
                    .build()
                UpdateInfo(
                    versionName = latestVersion,
                    tagName = tagName,
                    downloadUrl = downloadUrl,
                )
            }
        }

    companion object {
        const val DEFAULT_RELEASE_LATEST_URL =
            "https://github.com/foam255/gzus-course-assistant/releases/latest"
        const val DEFAULT_DOWNLOAD_BASE_URL =
            "https://github.com/foam255/gzus-course-assistant/releases/download"
        const val APK_ASSET_NAME = "app-release.apk"

        internal fun tagFromUrl(url: HttpUrl): String? {
            val segments = url.pathSegments
            val tagIndex = segments.indexOf("tag")
            if (tagIndex < 0) return null
            return segments.getOrNull(tagIndex + 1)?.takeIf(String::isNotBlank)
        }

        internal fun normalizeVersion(tag: String): String? {
            val match = Regex("""^[vV]?(\d+(?:\.\d+){1,3})$""")
                .matchEntire(tag.trim())
                ?: return null
            return match.groupValues[1]
        }

        internal fun compareVersions(first: String, second: String): Int {
            val firstParts = first.split('.').mapNotNull(String::toIntOrNull)
            val secondParts = second.split('.').mapNotNull(String::toIntOrNull)
            repeat(maxOf(firstParts.size, secondParts.size, 4)) { index ->
                val left = firstParts.getOrElse(index) { 0 }
                val right = secondParts.getOrElse(index) { 0 }
                if (left != right) return left.compareTo(right)
            }
            return 0
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
