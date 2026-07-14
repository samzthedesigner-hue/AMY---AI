package com.amy.assistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AmyLogger - Central logging for AMY.
 * Writes to logcat immediately, and appends to a rolling log file on IO dispatcher.
 */
object AmyLogger {

    private const val TAG = "AMY"
    private val LOG_DIR = "/storage/emulated/0/AmyBrain/logs"
    private val LOG_FILE = "$LOG_DIR/amy_log.txt"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun timestamp(): String = sdf.format(Date())

    fun i(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        appendAsync("INFO", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", throwable)
        appendAsync("ERROR", tag, msg + (throwable?.let { " | " + it.message } ?: ""))
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
        appendAsync("WARN", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
    }

    private fun appendAsync(level: String, tag: String, msg: String) {
        // Fire-and-forget on a background thread; never touches main thread.
        Thread {
            try {
                val dir = File(LOG_DIR)
                if (!dir.exists()) dir.mkdirs()
                val file = File(LOG_FILE)
                FileWriter(file, true).use { writer ->
                    writer.append("${timestamp()} [$level] [$tag] $msg\n")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Logger write failed", ex)
            }
        }.start()
    }

    suspend fun readRecentLogs(maxLines: Int = 200): String = withContext(Dispatchers.IO) {
        try {
            val file = File(LOG_FILE)
            if (!file.exists()) return@withContext "No logs yet."
            val lines = file.readLines()
            val start = if (lines.size > maxLines) lines.size - maxLines else 0
            lines.subList(start, lines.size).joinToString("\n")
        } catch (ex: Exception) {
            "Failed to read logs: ${ex.message}"
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        try {
            val file = File(LOG_FILE)
            if (file.exists()) file.delete()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed clearing logs", ex)
        }
    }
}
