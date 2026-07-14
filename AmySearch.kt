package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AmySearch - Group 2: SmartSearch (Features 9-16)
 * Primary provider: Tavily. Fallback: SerpAPI.
 * All network calls run on Dispatchers.IO via suspend functions.
 */
object AmySearch {

    data class SearchResult(val title: String, val url: String, val snippet: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Feature 9: SmartSearch entry point.
     * Tries Tavily first; on failure or empty key, falls back to SerpAPI.
     */
    suspend fun smartSearch(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (AmyKeys.hasKey("TAVILY_KEY")) {
            val tavilyResults = searchTavily(query)
            if (tavilyResults.isNotEmpty()) {
                AmyLogger.i("AmySearch", "Tavily returned ${tavilyResults.size} results")
                return@withContext tavilyResults
            }
            AmyLogger.w("AmySearch", "Tavily returned nothing, falling back to SerpAPI")
        } else {
            AmyLogger.w("AmySearch", "No Tavily key configured, using SerpAPI")
        }
        searchSerpApi(query)
    }

    private suspend fun searchTavily(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("api_key", AmyKeys.tavilyKey())
                put("query", query)
                put("max_results", 6)
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AmyLogger.w("AmySearch", "Tavily HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                val results = obj.optJSONArray("results") ?: JSONArray()
                parseGenericResults(results, "title", "url", "content")
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmySearch", "Tavily search failed", ex)
            emptyList()
        }
    }

    private suspend fun searchSerpApi(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            if (!AmyKeys.hasKey("SERPAPI_KEY")) {
                AmyLogger.e("AmySearch", "No SerpAPI key configured either — cannot search")
                return@withContext emptyList()
            }
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://serpapi.com/search.json?q=$encoded&api_key=${AmyKeys.serpApiKey()}"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AmyLogger.w("AmySearch", "SerpAPI HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                val organic = obj.optJSONArray("organic_results") ?: JSONArray()
                parseGenericResults(organic, "title", "link", "snippet")
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmySearch", "SerpAPI search failed", ex)
            emptyList()
        }
    }

    private fun parseGenericResults(
        arr: JSONArray,
        titleKey: String,
        urlKey: String,
        snippetKey: String
    ): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                SearchResult(
                    title = obj.optString(titleKey, "Untitled"),
                    url = obj.optString(urlKey, ""),
                    snippet = obj.optString(snippetKey, "")
                )
            )
        }
        return list
    }

    /** Feature 10-16 helpers: narrower search variants built on top of smartSearch. */
    suspend fun searchNews(query: String): List<SearchResult> = smartSearch("$query latest news")
    suspend fun searchImages(query: String): List<SearchResult> = smartSearch("$query images")
    suspend fun searchAcademic(query: String): List<SearchResult> = smartSearch("$query research paper")
    suspend fun searchLocal(query: String, location: String): List<SearchResult> =
        smartSearch("$query near $location")
    suspend fun searchShopping(query: String): List<SearchResult> = smartSearch("$query buy price")
    suspend fun searchVideos(query: String): List<SearchResult> = smartSearch("$query video")
    suspend fun searchQuick(query: String): SearchResult? = smartSearch(query).firstOrNull()
}
