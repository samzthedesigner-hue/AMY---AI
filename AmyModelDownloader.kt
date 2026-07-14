package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AmyModelDownloader - downloads the Phi-3 GGUF model (and optional TFLite vision models)
 * into /storage/emulated/0/AmyBrain/models/ with progress reporting via Flow.
 *
 * NOTE: You must supply a real, licensed download URL for phi3.gguf (e.g. from
 * Hugging Face). No URL is hardcoded here since model hosting locations change
 * and requiring you to supply your own respects licensing/hosting terms.
 */
object AmyModelDownloader {

    private const val MODELS_DIR = "/storage/emulated/0/AmyBrain/models"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // large model files need generous timeout
            .build()
    }

    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long, val percent: Int, val done: Boolean, val error: String? = null)

    fun downloadModel(url: String, fileName: String): Flow<DownloadProgress> = flow {
        try {
            val dir = File(MODELS_DIR)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadProgress(0, 0, 0, true, "HTTP ${response.code}"))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(DownloadProgress(0, 0, 0, true, "Empty response body"))
                    return@flow
                }
                val totalBytes = body.contentLength()
                var downloaded = 0L

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                            emit(DownloadProgress(downloaded, totalBytes, percent, false))
                        }
                    }
                }
                emit(DownloadProgress(downloaded, totalBytes, 100, true))
                AmyLogger.i("AmyModelDownloader", "Model download complete: ${outFile.absolutePath}")
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyModelDownloader", "Model download failed", ex)
            emit(DownloadProgress(0, 0, 0, true, ex.message))
        }
    }.flowOn(Dispatchers.IO)

    fun modelsPresent(): Map<String, Boolean> {
        val dir = File(MODELS_DIR)
        return mapOf(
            "phi3.gguf" to File(dir, "phi3.gguf").exists(),
            "object_detect.tflite" to File(dir, "object_detect.tflite").exists(),
            "ocr.tflite" to File(dir, "ocr.tflite").exists(),
            "face_detect.tflite" to File(dir, "face_detect.tflite").exists()
        )
    }

    fun deleteModel(fileName: String): Boolean {
        val file = File(MODELS_DIR, fileName)
        return if (file.exists()) file.delete() else false
    }
}
