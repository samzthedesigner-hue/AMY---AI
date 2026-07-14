package com.amy.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AmySteps - Orchestrates the first-run / every-boot startup sequence for AMY:
 *  1. Ensure AmyBrain + AmyDownloads directories exist
 *  2. Initialize AmyKeys storage
 *  3. Bind/verify device via AmyGuardian (ANDROID_ID)
 *  4. Arm Guardian
 *  5. Report readiness (models present? keys configured?)
 */
object AmySteps {

    data class BootResult(
        val success: Boolean,
        val message: String,
        val deviceBound: Boolean,
        val missingKeys: String,
        val modelsReady: Boolean
    )

    suspend fun runBootSequence(context: Context): BootResult = withContext(Dispatchers.IO) {
        AmyLogger.i("AmySteps", "Boot sequence starting")

        ensureDirectories()
        AmyKeys.init(context)

        val deviceOk = AmyGuardian.verifyOrBindDevice(context)
        if (!deviceOk) {
            AmyGuardian.raiseAlert("Device mismatch detected at boot")
            return@withContext BootResult(
                success = false,
                message = "Device verification failed. AMY is locked to its original device.",
                deviceBound = false,
                missingKeys = AmyKeys.missingKeysReport(),
                modelsReady = false
            )
        }
        AmyGuardian.arm()

        val models = AmyModelDownloader.modelsPresent()
        val modelsReady = models["phi3.gguf"] == true

        val missingKeys = AmyKeys.missingKeysReport()

        AmyLogger.i("AmySteps", "Boot sequence complete")
        BootResult(
            success = true,
            message = "AMY is online.",
            deviceBound = true,
            missingKeys = missingKeys,
            modelsReady = modelsReady
        )
    }

    private fun ensureDirectories() {
        val dirs = listOf(
            "/storage/emulated/0/AmyBrain",
            "/storage/emulated/0/AmyBrain/documents",
            "/storage/emulated/0/AmyBrain/models",
            "/storage/emulated/0/AmyBrain/enhanced",
            "/storage/emulated/0/AmyBrain/captures",
            "/storage/emulated/0/AmyBrain/logs",
            "/storage/emulated/0/AmyBrain/alerts",
            "/storage/emulated/0/AmyDownloads",
            "/storage/emulated/0/AmyDownloads/backups"
        )
        dirs.forEach { path ->
            val dir = File(path)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                AmyLogger.i("AmySteps", "Created dir $path: $created")
            }
        }
    }

    /** Called on graceful shutdown (Feature 7) to release resources cleanly. */
    suspend fun runShutdownSequence() = withContext(Dispatchers.IO) {
        AmyLogger.i("AmySteps", "Shutdown sequence running")
        AmyMedia.release()
        AmyTTS.shutdown()
        AmyOfflineBrain.unloadModel()
        AmyGuardian.disarm()
    }
}
