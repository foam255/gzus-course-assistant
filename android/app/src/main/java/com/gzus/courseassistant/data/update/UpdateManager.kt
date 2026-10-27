package com.gzus.courseassistant.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gzus.courseassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class UpdateManager(
    private val context: Context,
    private val checker: UpdateChecker,
    private val client: OkHttpClient,
) {
    suspend fun checkLatest(): UpdateInfo? =
        checker.checkLatest(BuildConfig.VERSION_NAME)

    suspend fun download(
        update: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val temp = File(directory, "update.apk.tmp")
        val target = File(directory, "update-${update.versionName}.apk")
        val request = Request.Builder()
            .url(update.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "CourseAssistant-Android")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw UpdateException("APK 下载失败", error)
        }
        response.use {
            if (!it.isSuccessful) {
                throw UpdateException("APK 下载返回 HTTP ${it.code}")
            }
            val body = it.body ?: throw UpdateException("APK 响应为空")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) {
                throw UpdateException("APK 大小异常")
            }
            onProgress(0)
            var copied = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_APK_BYTES) {
                            throw UpdateException("APK 大小异常")
                        }
                        output.write(buffer, 0, read)
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (copied == 0L) throw UpdateException("下载的 APK 为空")
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (error: Exception) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            onProgress(100)
            target
        }
    }

    @Suppress("DEPRECATION")
    fun verifyApk(file: File, expectedVersionName: String): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return false
        val current = packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) return false
        if (archive.versionName != expectedVersionName) return false
        if (archive.versionCodeCompat() <= current.versionCodeCompat()) return false
        val archiveSignatures = signatureDigests(archive)
        val currentSignatures = signatureDigests(current)
        return archiveSignatures.isNotEmpty() && archiveSignatures == currentSignatures
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }

    private companion object {
        const val MAX_APK_BYTES = 100L * 1024L * 1024L
    }
}
