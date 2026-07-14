package com.amy.assistant

import android.content.Context
import android.content.SharedPreferences

/**
 * AmyKeys - Centralized API key storage for AMY v4.9.4
 *
 * IMPORTANT: Keys below are EMPTY PLACEHOLDERS.
 * Paste your real keys into the defaults OR set them at runtime via setKey().
 * Keys are persisted in a private (non-backed-up) SharedPreferences file.
 *
 * Required keys:
 *  - CEREBRAS_KEY  : Cerebras Cloud inference API (used as online LLM fallback)
 *  - TAVILY_KEY    : Tavily search API (primary SmartSearch provider)
 *  - SERPAPI_KEY   : SerpAPI (fallback SmartSearch provider)
 *  - CLOUDCONVERT_KEY : CloudConvert API (DOCX/Sheet conversions)
 */
object AmyKeys {

    private const val PREFS_NAME = "amy_secure_keys"
    private const val KEY_CEREBRAS = "CEREBRAS_KEY"
    private const val KEY_TAVILY = "TAVILY_KEY"
    private const val KEY_SERPAPI = "SERPAPI_KEY"
    private const val KEY_CLOUDCONVERT = "CLOUDCONVERT_KEY"
    private const val KEY_OPENWEATHER = "OPENWEATHER_KEY"
    private const val KEY_NEWSAPI = "NEWSAPI_KEY"

    // TODO: paste your key here (or leave blank and use setKey() from Settings tab)
    private const val DEFAULT_CEREBRAS = ""
    // TODO: paste your key here
    private const val DEFAULT_TAVILY = ""
    // TODO: paste your key here
    private const val DEFAULT_SERPAPI = ""
    // TODO: paste your key here
    private const val DEFAULT_CLOUDCONVERT = ""
    // TODO: paste your key here (optional, used by AmyWidgets weather)
    private const val DEFAULT_OPENWEATHER = ""
    // TODO: paste your key here (optional, used by AmyWidgets news)
    private const val DEFAULT_NEWSAPI = ""

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            seedDefaultsIfEmpty()
        }
    }

    private fun seedDefaultsIfEmpty() {
        val p = prefs ?: return
        val editor = p.edit()
        if (!p.contains(KEY_CEREBRAS)) editor.putString(KEY_CEREBRAS, DEFAULT_CEREBRAS)
        if (!p.contains(KEY_TAVILY)) editor.putString(KEY_TAVILY, DEFAULT_TAVILY)
        if (!p.contains(KEY_SERPAPI)) editor.putString(KEY_SERPAPI, DEFAULT_SERPAPI)
        if (!p.contains(KEY_CLOUDCONVERT)) editor.putString(KEY_CLOUDCONVERT, DEFAULT_CLOUDCONVERT)
        if (!p.contains(KEY_OPENWEATHER)) editor.putString(KEY_OPENWEATHER, DEFAULT_OPENWEATHER)
        if (!p.contains(KEY_NEWSAPI)) editor.putString(KEY_NEWSAPI, DEFAULT_NEWSAPI)
        editor.apply()
    }

    fun setKey(name: String, value: String) {
        prefs?.edit()?.putString(name, value)?.apply()
    }

    fun getKey(name: String): String {
        return prefs?.getString(name, "") ?: ""
    }

    fun cerebrasKey(): String = getKey(KEY_CEREBRAS)
    fun tavilyKey(): String = getKey(KEY_TAVILY)
    fun serpApiKey(): String = getKey(KEY_SERPAPI)
    fun cloudConvertKey(): String = getKey(KEY_CLOUDCONVERT)
    fun openWeatherKey(): String = getKey(KEY_OPENWEATHER)
    fun newsApiKey(): String = getKey(KEY_NEWSAPI)

    fun hasKey(name: String): Boolean = getKey(name).isNotBlank()

    fun missingKeysReport(): String {
        val missing = mutableListOf<String>()
        if (!hasKey(KEY_CEREBRAS)) missing.add("CEREBRAS_KEY")
        if (!hasKey(KEY_TAVILY)) missing.add("TAVILY_KEY")
        if (!hasKey(KEY_SERPAPI)) missing.add("SERPAPI_KEY")
        if (!hasKey(KEY_CLOUDCONVERT)) missing.add("CLOUDCONVERT_KEY")
        return if (missing.isEmpty()) "All keys configured."
        else "Missing keys: " + missing.joinToString(", ")
    }
}
