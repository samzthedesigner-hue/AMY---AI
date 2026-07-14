package com.amy.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AmyWidgets - Group 8: Weather, News, Dashboard, SystemStatus (Features 48-51)
 */
object AmyWidgets {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // Feature 48: Weather
    suspend fun weather(cityName: String): String = withContext(Dispatchers.IO) {
        if (!AmyKeys.hasKey("OPENWEATHER_KEY")) {
            return@withContext "Weather needs an OPENWEATHER_KEY configured in Settings."
        }
        try {
            val encoded = java.net.URLEncoder.encode(cityName, "UTF-8")
            val url = "https://api.openweathermap.org/data/2.5/weather?q=$encoded&appid=${AmyKeys.openWeatherKey()}&units=metric"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Could not fetch weather for $cityName."
                val body = response.body?.string() ?: return@withContext "Could not fetch weather."
                val obj = JSONObject(body)
                val main = obj.getJSONObject("main")
                val weatherArr = obj.getJSONArray("weather")
                val description = weatherArr.getJSONObject(0).optString("description", "")
                val temp = main.optDouble("temp", 0.0)
                "Weather in $cityName: $description, ${temp}°C"
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyWidgets", "weather failed", ex)
            "Weather lookup failed."
        }
    }

    // Feature 49: News
    suspend fun news(topic: String = "technology"): String = withContext(Dispatchers.IO) {
        if (!AmyKeys.hasKey("NEWSAPI_KEY")) {
            return@withContext "News needs a NEWSAPI_KEY configured in Settings."
        }
        try {
            val encoded = java.net.URLEncoder.encode(topic, "UTF-8")
            val url = "https://newsapi.org/v2/everything?q=$encoded&sortBy=publishedAt&apiKey=${AmyKeys.newsApiKey()}"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Could not fetch news."
                val body = response.body?.string() ?: return@withContext "Could not fetch news."
                val obj = JSONObject(body)
                val articles = obj.optJSONArray("articles") ?: return@withContext "No news found."
                val sb = StringBuilder()
                for (i in 0 until minOf(5, articles.length())) {
                    sb.append("- ${articles.getJSONObject(i).optString("title")}\n")
                }
                sb.toString().ifBlank { "No news found for $topic." }
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyWidgets", "news failed", ex)
            "News lookup failed."
        }
    }

    // Feature 50: Dashboard (combines several quick stats)
    suspend fun dashboard(context: Context): String = withContext(Dispatchers.IO) {
        val storage = AmyDownload.storageStatus()
        val battery = batteryStatus(context)
        val brainStatus = AmyOfflineBrain.status()
        val time = AmyTime.currentTime()
        val date = AmyTime.currentDate()
        """
        AMY Dashboard — $date, $time
        Battery: $battery
        Storage: $storage
        Offline brain: $brainStatus
        Guardian: ${if (AmyGuardian.isArmed) "Armed" else "Disarmed"}
        """.trimIndent()
    }

    // Feature 51: SystemStatus
    suspend fun systemStatus(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val battery = batteryStatus(context)
            "RAM: ${availMb}MB free / ${totalMb}MB total | Battery: $battery | " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        } catch (ex: Exception) {
            AmyLogger.e("AmyWidgets", "systemStatus failed", ex)
            "Could not read system status."
        }
    }

    private fun batteryStatus(context: Context): String {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "$level%"
        } catch (ex: Exception) {
            "unknown"
        }
    }
}
