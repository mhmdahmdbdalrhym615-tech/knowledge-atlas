package com.knowledgeatlas.app.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.knowledgeatlas.app.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AtlasUpdate(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String, val releaseNotes: String)

sealed interface UpdateResult {
    data object Disabled : UpdateResult
    data object UpToDate : UpdateResult
    data object PermissionRequired : UpdateResult
    data class ReadyToInstall(val file: File, val versionName: String) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/** Direct-distribution updater. Android retains final control of package installation. */
class DirectUpdateManager(private val activity: ComponentActivity) {
    suspend fun checkAndDownload(): UpdateResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return@withContext UpdateResult.Disabled
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        if (!isHttpsUrl(manifestUrl)) return@withContext UpdateResult.Failed("رابط خدمة التحديث غير مضبوط أو لا يستخدم HTTPS.")
        try {
            val update = readManifest(manifestUrl)
            if (update.versionCode <= BuildConfig.VERSION_CODE) return@withContext UpdateResult.UpToDate
            if (!isHttpsUrl(update.apkUrl) ||
                !update.sha256.matches(Regex("[a-fA-F0-9]{64}")) ||
                !update.versionName.matches(Regex("[0-9A-Za-z.+_-]{1,40}"))
            ) return@withContext UpdateResult.Failed("بيانات التحديث غير صالحة.")

            // Do not download a large APK until Android permits this source to request installation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                return@withContext UpdateResult.PermissionRequired
            }
            val apk = downloadApk(update)
            if (!sha256(apk).equals(update.sha256, ignoreCase = true)) {
                apk.delete()
                return@withContext UpdateResult.Failed("فشل التحقق من سلامة ملف التحديث.")
            }
            UpdateResult.ReadyToInstall(apk, update.versionName)
        } catch (error: Exception) {
            UpdateResult.Failed(error.message ?: "تعذر فحص التحديث.")
        }
    }

    fun requestUserInstall(result: UpdateResult.ReadyToInstall) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", result.file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun readManifest(url: String): AtlasUpdate {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod="GET"; connectTimeout=12_000; readTimeout=15_000
            instanceFollowRedirects=false; setRequestProperty("Accept","application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "خادم التحديث لم يستجب بنجاح." }
            val output=ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
                while(true) {
                    val count=input.read(buffer); if(count<0) break
                    require(output.size()+count<=32*1024) { "بيان التحديث أكبر من الحد المسموح." }
                    output.write(buffer,0,count)
                }
            }
            val json=JSONObject(output.toString(Charsets.UTF_8.name()))
            val code=json.getInt("versionCode")
            require(code>0) { "رقم إصدار التحديث غير صالح." }
            return AtlasUpdate(code,json.getString("versionName"),json.getString("apkUrl"),
                json.getString("sha256"),json.optString("releaseNotes","").take(1000))
        } finally { connection.disconnect() }
    }

    private fun downloadApk(update: AtlasUpdate): File {
        val dir=File(activity.cacheDir,"updates").apply { mkdirs() }
        val target=File(dir,"atlas-${update.versionCode}.apk")
        val partial=File(dir,"atlas-${update.versionCode}.apk.part").apply { delete() }
        val connection=(URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod="GET"; connectTimeout=12_000; readTimeout=60_000; instanceFollowRedirects=false
        }
        try {
            require(connection.responseCode in 200..299) { "تعذر تنزيل حزمة التحديث." }
            val declared=connection.contentLengthLong
            require(declared<=MAX_APK_BYTES) { "حجم حزمة التحديث أكبر من المسموح." }
            var total=0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        val count=input.read(buffer); if(count<0) break
                        total+=count; require(total<=MAX_APK_BYTES) { "حجم حزمة التحديث أكبر من المسموح." }
                        output.write(buffer,0,count)
                    }
                    output.fd.sync()
                }
            }
            require(total>0 && (declared<0 || total==declared)) { "ملف التحديث فارغ أو غير مكتمل." }
            target.delete()
            require(partial.renameTo(target)) { "تعذر تجهيز ملف التحديث." }
            return target
        } catch(error:Exception) {
            partial.delete(); throw error
        } finally { connection.disconnect() }
    }

    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
            while(true) { val count=input.read(buffer); if(count<=0) break; digest.update(buffer,0,count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isHttpsUrl(value:String):Boolean=runCatching {
        val url=URL(value)
        url.protocol.equals("https",true) && url.host.isNotBlank() && url.userInfo==null
    }.getOrDefault(false)

    private companion object { const val MAX_APK_BYTES=300L*1024*1024 }
}
