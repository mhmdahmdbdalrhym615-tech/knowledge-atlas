package com.knowledgeatlas.app.search

import com.knowledgeatlas.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val publisher: String,
    val verifiedAt: String?,
    val relevance: Double
)

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult
    data object NotConfigured : WebSearchResult
    data class Failure(val message: String) : WebSearchResult
}

/**
 * Provider-neutral contract for the Atlas semantic web-search backend.
 *
 * The server is responsible for query understanding, Arabic/English expansion,
 * hybrid lexical + vector retrieval, reranking, source checks, and provenance.
 * No search-provider API key is shipped in the Android client.
 *
 * POST /search
 * Request:  { "query": "...", "language": "ar", "mode": "hybrid", "limit": 20 }
 * Response: { "results": [{ "title", "url", "snippet", "publisher",
 *                           "verifiedAt", "relevance" }] }
 */
class AtlasSearchGateway {

    suspend fun search(query: String, limit: Int = 20): WebSearchResult =
        withContext(Dispatchers.IO) {
            val baseUrl = BuildConfig.SEARCH_API_BASE_URL.trimEnd('/')
            if (baseUrl.isBlank()) return@withContext WebSearchResult.NotConfigured
            if (!baseUrl.startsWith("https://")) {
                return@withContext WebSearchResult.Failure("يجب أن يستخدم خادم البحث اتصال HTTPS.")
            }
            if (query.isBlank()) return@withContext WebSearchResult.Success(emptyList())

            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$baseUrl/search").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                val request = JSONObject()
                    .put("query", query.trim())
                    .put("language", "ar")
                    .put("mode", "hybrid")
                    .put("limit", limit.coerceIn(1, 50))

                connection.outputStream.use { output ->
                    output.write(request.toString().toByteArray(Charsets.UTF_8))
                }

                if (connection.responseCode !in 200..299) {
                    return@withContext WebSearchResult.Failure("خدمة البحث أعادت رمزاً غير ناجح.")
                }

                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONObject(payload).optJSONArray("results")
                    ?: return@withContext WebSearchResult.Success(emptyList())

                val hits = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val url = item.optString("url")
                        if (!url.startsWith("https://")) continue
                        add(
                            WebSearchHit(
                                title = item.optString("title"),
                                url = url,
                                snippet = item.optString("snippet"),
                                publisher = item.optString("publisher"),
                                verifiedAt = item.optString("verifiedAt").takeIf { it.isNotBlank() },
                                relevance = item.optDouble("relevance", 0.0).coerceIn(0.0, 1.0)
                            )
                        )
                    }
                }
                WebSearchResult.Success(hits)
            } catch (error: Exception) {
                WebSearchResult.Failure(error.message ?: "تعذر الاتصال بخدمة البحث.")
            } finally {
                connection?.disconnect()
            }
        }
}
