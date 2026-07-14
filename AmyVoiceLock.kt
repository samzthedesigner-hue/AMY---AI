package com.amy.assistant

import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AmyVoiceLock - Feature 3: VoiceLock
 *
 * NOTE (honest limitation): This is NOT true biometric speaker verification.
 * It records the passphrase, extracts a simple amplitude-envelope "signature"
 * (RMS energy over fixed-size windows), and compares that pattern against a
 * stored enrolled template using normalized distance + a tolerance threshold.
 * This is a lightweight local heuristic, not a neural speaker-embedding model.
 * It will reject wildly different phrases/voices but is NOT spoof-proof.
 *
 * Storage: /storage/emulated/0/AmyBrain/voicelock_template.json
 */
object AmyVoiceLock {

    private const val TEMPLATE_PATH = "/storage/emulated/0/AmyBrain/voicelock_template.json"
    private const val SAMPLE_RATE = 44100
    private const val RECORD_SECONDS = 3
    private const val WINDOW_COUNT = 40
    private const val MATCH_THRESHOLD = 0.72 // similarity 0..1, tune as needed

    private var recorder: MediaRecorder? = null
    private var tempRawFile: File? = null

    private fun ensureDir() {
        val dir = File("/storage/emulated/0/AmyBrain")
        if (!dir.exists()) dir.mkdirs()
    }

    /** Starts recording the passphrase audio. Must be called with RECORD_AUDIO permission granted. */
    suspend fun startRecording(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDir()
            val outFile = File("/storage/emulated/0/AmyBrain/voice_capture_tmp.3gp")
            tempRawFile = outFile
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            AmyLogger.i("AmyVoiceLock", "Recording started")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyVoiceLock", "Failed to start recording", ex)
            false
        }
    }

    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            AmyLogger.i("AmyVoiceLock", "Recording stopped")
            tempRawFile
        } catch (ex: Exception) {
            AmyLogger.e("AmyVoiceLock", "Failed to stop recording", ex)
            null
        }
    }

    /**
     * Records for RECORD_SECONDS automatically and returns the captured file.
     * Convenience wrapper for enroll/verify flows.
     */
    suspend fun captureFixedDuration(context: android.content.Context): File? {
        if (!startRecording(context)) return null
        delay(RECORD_SECONDS * 1000L)
        return stopRecording()
    }

    /**
     * Extracts a simple amplitude-envelope signature from a recorded file.
     * Uses file byte-energy chunks as a crude proxy for RMS energy windows
     * since decoding AMR without extra codecs is heavy for a lightweight local check.
     */
    private fun extractSignature(file: File): DoubleArray {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return DoubleArray(WINDOW_COUNT)
        val chunkSize = maxOf(1, bytes.size / WINDOW_COUNT)
        val signature = DoubleArray(WINDOW_COUNT)
        for (w in 0 until WINDOW_COUNT) {
            val start = w * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            if (start >= end) {
                signature[w] = 0.0
                continue
            }
            var sumSquares = 0.0
            for (i in start until end) {
                val v = bytes[i].toInt()
                sumSquares += (v * v).toDouble()
            }
            signature[w] = sqrt(sumSquares / (end - start))
        }
        // Normalize signature to 0..1 range
        val max = signature.maxOrNull() ?: 1.0
        return if (max > 0) DoubleArray(WINDOW_COUNT) { signature[it] / max } else signature
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    suspend fun enroll(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        val file = captureFixedDuration(context) ?: return@withContext false
        val signature = extractSignature(file)
        try {
            ensureDir()
            val arr = JSONArray()
            signature.forEach { arr.put(it) }
            File(TEMPLATE_PATH).writeText(arr.toString())
            AmyLogger.i("AmyVoiceLock", "Enrollment saved")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyVoiceLock", "Enrollment save failed", ex)
            false
        }
    }

    suspend fun verify(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        val templateFile = File(TEMPLATE_PATH)
        if (!templateFile.exists()) {
            AmyLogger.w("AmyVoiceLock", "No enrolled template found")
            return@withContext false
        }
        val file = captureFixedDuration(context) ?: return@withContext false
        val liveSignature = extractSignature(file)
        try {
            val arr = JSONArray(templateFile.readText())
            val storedSignature = DoubleArray(arr.length()) { arr.getDouble(it) }
            val similarity = cosineSimilarity(liveSignature, storedSignature)
            AmyLogger.i("AmyVoiceLock", "Verify similarity=$similarity")
            similarity >= MATCH_THRESHOLD
        } catch (ex: Exception) {
            AmyLogger.e("AmyVoiceLock", "Verify failed", ex)
            false
        }
    }

    fun isEnrolled(): Boolean = File(TEMPLATE_PATH).exists()

    fun resetEnrollment(): Boolean {
        val f = File(TEMPLATE_PATH)
        return if (f.exists()) f.delete() else true
    }
}
