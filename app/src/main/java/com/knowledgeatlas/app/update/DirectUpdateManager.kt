package com.knowledgeatlas.app.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.knowledgeatlas.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AtlasUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String
)

sealed interface UpdateResult {
    data object Disabled : UpdateResult
    data object UpToDate : UpdateResult
    data object PermissionRequired : UpdateResult
    data class ReadyToInstall(val file: File, val versionName: String) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Direct-distribution updater.
 *
 * The update manifest and APK must be hosted on HTTPS. Android's package installer
 * remains responsible for user approval and signing-certificate compatibility.
 * The Play flavor disables this path at build time.
 */
class DirectUpdateManager(private val activity: ComponentActivity) {

    suspend fun checkAndDownload(): UpdateResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return@withContext UpdateResult.Disabled
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        if (!manifestUrl.startsWith("https://")) {
            return@withContext UpdateResult.Failed("رابط خدمة التحديث غير مضبوط أو لا يستخدم HTTPS.")
        }

        try {
            val update = readManifest(manifestUrl)
            if (update.versionCode <= BuildConfig.VERSION_CODE) {
                return@withContext UpdateResult.UpToDate
            }
            if (!update.apkUrl.startsWith("https://") || !update.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                return@withContext UpdateResult.Failed("بيانات التحديث غير صالحة.")
            }

            val apk = downloadApk(update)
            if (sha256(apk).equals(update.sha256, ignoreCase = true).not()) {
                apk.delete()
                return@withContext UpdateResult.Failed("فشل التحقق من سلامة ملف التحديث.")
            }

            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !activity.packageManager.canRequestPackageInstalls()
                ) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(settingsIntent)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                return@withContext UpdateResult.PermissionRequired
            }

            UpdateResult.ReadyToInstall(apk, update.versionName)
        } catch (error: Exception) {
            UpdateResult.Failed(error.message ?: "تعذر فحص التحديث.")
        }
    }

    fun requestUserInstall(result: UpdateResult.ReadyToInstall) {
        val contentUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            result.file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(installIntent)
    }

    private fun readManifest(manifestUrl: String): AtlasUpdate {
        val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "خادم التحديث لم يستجب بنجاح." }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return AtlasUpdate(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256"),
                releaseNotes = json.optString("releaseNotes", "")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApk(update: AtlasUpdate): File {
        val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "atlas-${update.versionCode}.apk")
        val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        try {
            require(connection.responseCode in 200..299) { "تعذر تنزيل حزمة التحديث." }
            val length = connection.contentLengthLong
            require(length in 1..(300L * 1024 * 1024)) { "حجم حزمة التحديث غير مقبول." }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            require(target.length() == length) { "ملف التحديث غير مكتمل." }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
