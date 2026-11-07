package com.knowledgeatlas.app.search

import com.knowledgeatlas.app.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebSearchHit(
    val title: String, val url: String, val snippet: String, val publisher: String,
    val retrievedAt: String?, val publisherType: String, val trustNote: String, val relevance: Double
)

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult
    data object NotConfigured : WebSearchResult
    data class Failure(val message: String) : WebSearchResult
}

/** Provider-neutral HTTPS gateway. Search credentials remain server-side. */
class AtlasSearchGateway {
    suspend fun search(query: String, limit: Int = 20): WebSearchResult = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.SEARCH_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext WebSearchResult.NotConfigured
        if (!isHttpsUrl(baseUrl)) return@withContext WebSearchResult.Failure("يجب أن يستخدم خادم البحث عنوان HTTPS صالحاً.")
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext WebSearchResult.Success(emptyList())
        if (cleanQuery.length > MAX_QUERY_LENGTH) return@withContext WebSearchResult.Failure("السؤال أطول من الحد المسموح.")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$baseUrl/search").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 20_000
                instanceFollowRedirects = false; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "KnowledgeAtlas-Android/1.0")
            }
            val ar = cleanQuery.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
            val en = cleanQuery.count { it in 'A'..'Z' || it in 'a'..'z' }
            val language = if (ar >= en) "ar" else "en"
            val request = JSONObject().put("query", cleanQuery).put("language", language)
                .put("mode", "hybrid").put("limit", limit.coerceIn(1, MAX_RESULTS))
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) return@withContext WebSearchResult.Failure(
                if (status in 300..399) "خادم البحث أعاد تحويلاً غير مسموح."
                else "خدمة البحث غير متاحة حالياً (HTTP $status)."
            )
            val payload = readBounded(connection, MAX_RESPONSE_BYTES)
            val array = JSONObject(payload).optJSONArray("results") ?: return@withContext WebSearchResult.Success(emptyList())
            val hits = buildList {
                for (i in 0 until minOf(array.length(), MAX_RESULTS)) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (!isHttpsUrl(url)) continue
                    val title = item.optString("title").trim().take(240)
                    if (title.isBlank()) continue
                    add(WebSearchHit(title, url, item.optString("snippet").take(1200),
                        item.optString("publisher").take(180),
                        item.optString("retrievedAt").takeIf { it.isNotBlank() },
                        item.optString("publisherType", "غير مصنف"),
                        item.optString("trustNote", ""), item.optDouble("relevance", 0.0).coerceIn(0.0,1.0)))
                }
            }
            WebSearchResult.Success(hits)
        } catch (_: Exception) {
            WebSearchResult.Failure("تعذر الاتصال بخدمة البحث أو قراءة استجابتها.")
        } finally { connection?.disconnect() }
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val parsed = URL(value)
        parsed.protocol.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.userInfo == null
    }.getOrDefault(false)

    private fun readBounded(connection: HttpURLConnection, maxBytes: Int): String {
        require(connection.contentLengthLong <= maxBytes) { "Search response exceeds size limit." }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= maxBytes) { "Search response exceeds size limit." }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
    private companion object {
        const val MAX_QUERY_LENGTH=500; const val MAX_RESULTS=50; const val MAX_RESPONSE_BYTES=1_500_000
    }
}
