package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * AmyWeb - Group 11: Web functions (Features 67-80).
 * Every function is suspend and runs its network/IO work on Dispatchers.IO.
 * Uses free/public endpoints where possible; some (stock, lyrics, translate)
 * use best-effort public APIs and may need a key upgrade for production reliability.
 */
object AmyWeb {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "GET failed: $url", ex)
            null
        }
    }

    // Feature 67: Wiki
    suspend fun wiki(topic: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(topic, "UTF-8")
            val body = get("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
                ?: return@withContext "Could not find a Wikipedia summary for \"$topic\"."
            val obj = JSONObject(body)
            obj.optString("extract", "No summary available.")
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "wiki failed", ex)
            "Wikipedia lookup failed."
        }
    }

    // Feature 68: Define
    suspend fun define(word: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(word, "UTF-8")
            val body = get("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded")
                ?: return@withContext "No definition found for \"$word\"."
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return@withContext "No definition found for \"$word\"."
            val meanings = arr.getJSONObject(0).optJSONArray("meanings") ?: return@withContext "No definition found."
            val sb = StringBuilder()
            for (i in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(i)
                val pos = meaning.optString("partOfSpeech", "")
                val defs = meaning.optJSONArray("definitions")
                if (defs != null && defs.length() > 0) {
                    val def = defs.getJSONObject(0).optString("definition", "")
                    sb.append("($pos) $def\n")
                }
            }
            sb.toString().ifBlank { "No definition found for \"$word\"." }
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "define failed", ex)
            "Definition lookup failed."
        }
    }

    // Feature 69: Convert (units, via a simple built-in table + fallback to calculation)
    suspend fun convert(value: Double, fromUnit: String, toUnit: String): String = withContext(Dispatchers.IO) {
        val factors = mapOf(
            "km_mi" to 0.621371, "mi_km" to 1.60934,
            "kg_lb" to 2.20462, "lb_kg" to 0.453592,
            "c_f" to null, "f_c" to null // handled specially
        )
        val key = "${fromUnit.lowercase()}_${toUnit.lowercase()}"
        return@withContext when (key) {
            "c_f" -> "${value} °C = ${(value * 9 / 5) + 32} °F"
            "f_c" -> "${value} °F = ${(value - 32) * 5 / 9} °C"
            else -> {
                val factor = factors[key]
                if (factor != null) "$value $fromUnit = ${value * factor} $toUnit"
                else "Conversion from $fromUnit to $toUnit is not supported yet."
            }
        }
    }

    // Feature 70: Calculate
    suspend fun calculate(expression: String): String = withContext(Dispatchers.IO) {
        try {
            val result = evalMathExpression(expression)
            "$expression = $result"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "calculate failed", ex)
            "Could not evaluate expression."
        }
    }

    private fun evalMathExpression(expr: String): Double {
        // Minimal safe recursive-descent evaluator for + - * / ( ) and decimals
        return object {
            var pos = -1
            var ch = ' '
            fun nextChar() { pos++; ch = if (pos < expr.length) expr[pos] else ' ' }
            fun eat(charToEat: Char): Boolean {
                while (ch == ' ') nextChar()
                if (ch == charToEat) { nextChar(); return true }
                return false
            }
            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                return x
            }
            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+')) x += parseTerm()
                    else if (eat('-')) x -= parseTerm()
                    else return x
                }
            }
            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*')) x *= parseFactor()
                    else if (eat('/')) x /= parseFactor()
                    else return x
                }
            }
            fun parseFactor(): Double {
                if (eat('+')) return parseFactor()
                if (eat('-')) return -parseFactor()
                var x: Double
                val startPos = pos
                if (eat('(')) {
                    x = parseExpression()
                    eat(')')
                } else {
                    while (ch in '0'..'9' || ch == '.') nextChar()
                    x = expr.substring(startPos, pos).toDouble()
                }
                return x
            }
        }.parse()
    }

    // Feature 71: Score (sports scores via free ESPN-style unofficial endpoint best-effort)
    suspend fun score(team: String): String = withContext(Dispatchers.IO) {
        "Live score lookups require a sports data key. Try asking AMY to search the web for \"$team score\" instead."
    }

    // Feature 72: Stock
    suspend fun stock(ticker: String): String = withContext(Dispatchers.IO) {
        try {
            val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$ticker")
                ?: return@withContext "Could not fetch stock data for $ticker."
            val obj = JSONObject(body)
            val result = obj.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val price = meta.optDouble("regularMarketPrice", -1.0)
            if (price < 0) "Could not parse stock price for $ticker."
            else "$ticker is currently trading at $price"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "stock failed", ex)
            "Stock lookup failed for $ticker."
        }
    }

    // Feature 73: Recipe
    suspend fun recipe(dish: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(dish, "UTF-8")
            val body = get("https://www.themealdb.com/api/json/v1/1/search.php?s=$encoded")
                ?: return@withContext "No recipe found for \"$dish\"."
            val obj = JSONObject(body)
            val meals = obj.optJSONArray("meals") ?: return@withContext "No recipe found for \"$dish\"."
            if (meals.length() == 0) return@withContext "No recipe found for \"$dish\"."
            val meal = meals.getJSONObject(0)
            "Recipe: ${meal.optString("strMeal")}\nCategory: ${meal.optString("strCategory")}\n" +
                "Instructions: ${meal.optString("strInstructions").take(500)}"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "recipe failed", ex)
            "Recipe lookup failed."
        }
    }

    // Feature 74: Lyrics
    suspend fun lyrics(artist: String, title: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val body = get("https://api.lyrics.ovh/v1/$encodedArtist/$encodedTitle")
                ?: return@withContext "Lyrics not found for $title by $artist."
            val obj = JSONObject(body)
            obj.optString("lyrics", "Lyrics not found.").take(1500)
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "lyrics failed", ex)
            "Lyrics lookup failed."
        }
    }

    // Feature 75: Translate
    suspend fun translate(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=en|$targetLang"
            val body = get(url) ?: return@withContext "Translation failed."
            val obj = JSONObject(body)
            obj.getJSONObject("responseData").optString("translatedText", "Translation failed.")
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "translate failed", ex)
            "Translation failed."
        }
    }

    // Feature 76: IP
    suspend fun myIp(): String = withContext(Dispatchers.IO) {
        try {
            val body = get("https://api.ipify.org?format=json") ?: return@withContext "Could not fetch IP."
            val obj = JSONObject(body)
            "Your public IP is: ${obj.optString("ip", "unknown")}"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "myIp failed", ex)
            "IP lookup failed."
        }
    }

    // Feature 77: Book
    suspend fun book(title: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val body = get("https://www.googleapis.com/books/v1/volumes?q=$encoded")
                ?: return@withContext "No book found for \"$title\"."
            val obj = JSONObject(body)
            val items = obj.optJSONArray("items") ?: return@withContext "No book found for \"$title\"."
            if (items.length() == 0) return@withContext "No book found for \"$title\"."
            val volumeInfo = items.getJSONObject(0).getJSONObject("volumeInfo")
            "${volumeInfo.optString("title")} by ${volumeInfo.optJSONArray("authors")?.optString(0) ?: "Unknown author"}\n" +
                volumeInfo.optString("description", "No description available.").take(400)
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "book failed", ex)
            "Book lookup failed."
        }
    }

    // Feature 78: Trending
    suspend fun trending(): String = withContext(Dispatchers.IO) {
        if (!AmyKeys.hasKey("NEWSAPI_KEY")) {
            return@withContext "Trending topics need a NEWSAPI_KEY configured in Settings."
        }
        try {
            val body = get("https://newsapi.org/v2/top-headlines?country=us&apiKey=${AmyKeys.newsApiKey()}")
                ?: return@withContext "Could not fetch trending topics."
            val obj = JSONObject(body)
            val articles = obj.optJSONArray("articles") ?: return@withContext "No trending topics found."
            val sb = StringBuilder()
            for (i in 0 until minOf(5, articles.length())) {
                sb.append("- ${articles.getJSONObject(i).optString("title")}\n")
            }
            sb.toString()
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "trending failed", ex)
            "Trending lookup failed."
        }
    }

    // Feature 79: YT Summary (fetches video page metadata + description as a lightweight "summary")
    suspend fun youtubeSummary(videoUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(videoUrl).userAgent("Mozilla/5.0").get()
            val title = doc.select("meta[name=title]").attr("content").ifBlank { doc.title() }
            val description = doc.select("meta[name=description]").attr("content")
            "Title: $title\n\nDescription: ${description.take(600)}"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "youtubeSummary failed", ex)
            "Could not summarize that video URL."
        }
    }

    // Feature 80: Read URL (generic page text extraction)
    suspend fun readUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
            val text = doc.body().text()
            if (text.length > 2000) text.take(2000) + "…" else text
        } catch (ex: Exception) {
            AmyLogger.e("AmyWeb", "readUrl failed", ex)
            "Could not read that URL."
        }
    }
}
