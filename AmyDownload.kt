package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AmyDownload - Group 4: Download, List, Find, Delete, StorageStatus (Features 22-26)
 * Storage target: /storage/emulated/0/AmyDownloads/
 */
object AmyDownload {

    private const val DOWNLOAD_DIR = "/storage/emulated/0/AmyDownloads"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class PendingDownload(val url: String, val suggestedFileName: String)

    /** Feature 22 (part 1): prepare a pending download that the UI should confirm before calling execute(). */
    fun preparePendingDownload(url: String): PendingDownload {
        val name = url.substringAfterLast('/').ifBlank { "amy_download_${System.currentTimeMillis()}" }
        return PendingDownload(url, name)
    }

    /** Feature 22 (part 2): executes the download after user confirmation. */
    suspend fun executeDownload(pending: PendingDownload): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(DOWNLOAD_DIR)
            if (!dir.exists()) dir.mkdirs()

            val request = Request.Builder().url(pending.url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AmyLogger.e("AmyDownload", "Download HTTP ${response.code}")
                    return@withContext Pair(false, "Download failed with HTTP ${response.code}")
                }
                val outFile = File(dir, pending.suggestedFileName)
                response.body?.byteStream()?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                AmyLogger.i("AmyDownload", "Downloaded to ${outFile.absolutePath}")
                Pair(true, outFile.absolutePath)
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyDownload", "Download failed", ex)
            Pair(false, "Download failed: ${ex.message}")
        }
    }

    // Feature 23: List
    suspend fun listDownloads(): List<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(DOWNLOAD_DIR)
            if (!dir.exists()) return@withContext emptyList()
            dir.listFiles()?.map { it.name } ?: emptyList()
        } catch (ex: Exception) {
            AmyLogger.e("AmyDownload", "List failed", ex)
            emptyList()
        }
    }

    // Feature 24: Find
    suspend fun findDownload(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(DOWNLOAD_DIR)
            if (!dir.exists()) return@withContext emptyList()
            dir.listFiles()
                ?.filter { it.name.contains(query, ignoreCase = true) }
                ?.map { it.absolutePath } ?: emptyList()
        } catch (ex: Exception) {
            AmyLogger.e("AmyDownload", "Find failed", ex)
            emptyList()
        }
    }

    // Feature 25: Delete
    suspend fun deleteDownload(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(DOWNLOAD_DIR, fileName)
            if (file.exists()) {
                val ok = file.delete()
                AmyLogger.i("AmyDownload", "Deleted $fileName: $ok")
                ok
            } else {
                AmyLogger.w("AmyDownload", "File not found for delete: $fileName")
                false
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyDownload", "Delete failed", ex)
            false
        }
    }

    // Feature 26: StorageStatus
    suspend fun storageStatus(): String = withContext(Dispatchers.IO) {
        try {
            val brainDir = File("/storage/emulated/0/AmyBrain")
            val downloadDir = File(DOWNLOAD_DIR)
            val brainSize = if (brainDir.exists()) dirSize(brainDir) else 0L
            val downloadSize = if (downloadDir.exists()) dirSize(downloadDir) else 0L
            val stat = android.os.StatFs("/storage/emulated/0")
            val totalBytes = stat.totalBytes
            val availBytes = stat.availableBytes
            "AmyBrain: ${formatBytes(brainSize)} | AmyDownloads: ${formatBytes(downloadSize)} | " +
                "Free space: ${formatBytes(availBytes)} / ${formatBytes(totalBytes)}"
        } catch (ex: Exception) {
            AmyLogger.e("AmyDownload", "StorageStatus failed", ex)
            "Could not determine storage status."
        }
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$bytes B"
        }
    }
}
