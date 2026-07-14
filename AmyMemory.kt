package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AmyMemory - Recall / Forget / persistent memory store.
 * Backs Feature 5 (Recall) and Feature 6 (Forget).
 * Storage: /storage/emulated/0/AmyBrain/memory.json
 */
object AmyMemory {

    private const val MEMORY_DIR = "/storage/emulated/0/AmyBrain"
    private const val MEMORY_FILE = "$MEMORY_DIR/memory.json"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class MemoryEntry(val key: String, val value: String, val timestamp: String)

    private fun ensureFile(): File {
        val dir = File(MEMORY_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(MEMORY_FILE)
        if (!file.exists()) file.writeText("[]")
        return file
    }

    suspend fun remember(key: String, value: String) = withContext(Dispatchers.IO) {
        try {
            val file = ensureFile()
            val arr = JSONArray(file.readText())
            // Remove existing entry with same key first
            val filtered = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("key") != key) filtered.put(obj)
            }
            val entry = JSONObject()
            entry.put("key", key)
            entry.put("value", value)
            entry.put("timestamp", sdf.format(Date()))
            filtered.put(entry)
            file.writeText(filtered.toString())
            AmyLogger.i("AmyMemory", "Remembered: $key")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyMemory", "Failed to remember", ex)
            false
        }
    }

    suspend fun recall(key: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = ensureFile()
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("key").equals(key, ignoreCase = true)) {
                    return@withContext obj.optString("value")
                }
            }
            null
        } catch (ex: Exception) {
            AmyLogger.e("AmyMemory", "Failed to recall", ex)
            null
        }
    }

    suspend fun recallAll(): List<MemoryEntry> = withContext(Dispatchers.IO) {
        try {
            val file = ensureFile()
            val arr = JSONArray(file.readText())
            val list = mutableListOf<MemoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    MemoryEntry(
                        obj.optString("key"),
                        obj.optString("value"),
                        obj.optString("timestamp")
                    )
                )
            }
            list
        } catch (ex: Exception) {
            AmyLogger.e("AmyMemory", "Failed to recall all", ex)
            emptyList()
        }
    }

    suspend fun forget(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = ensureFile()
            val arr = JSONArray(file.readText())
            val filtered = JSONArray()
            var found = false
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("key").equals(key, ignoreCase = true)) {
                    found = true
                } else {
                    filtered.put(obj)
                }
            }
            file.writeText(filtered.toString())
            AmyLogger.i("AmyMemory", "Forgot: $key ($found)")
            found
        } catch (ex: Exception) {
            AmyLogger.e("AmyMemory", "Failed to forget", ex)
            false
        }
    }

    suspend fun forgetAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = ensureFile()
            file.writeText("[]")
            AmyLogger.i("AmyMemory", "All memory wiped")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyMemory", "Failed to forget all", ex)
            false
        }
    }
}
