package com.amy.assistant

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AmyGuardian - Feature 4 (Guardian) + Group 10 Guardrails (54-66)
 *
 * Responsibilities:
 *  - Device binding via ANDROID_ID (app only "belongs" to one device)
 *  - SYSTEM_GUARD_V2: content policy checks (NSFW block, destructive-action confirm)
 *  - Ask-before-action confirmation gate for irreversible operations
 */
object AmyGuardian {

    private const val DEVICE_LOCK_FILE = "/storage/emulated/0/AmyBrain/device_lock.txt"

    private val NSFW_BLOCK_TERMS = listOf(
        "nsfw", "explicit sexual", "porn", "nude photo of a minor"
    )

    private val DESTRUCTIVE_ACTION_KEYWORDS = listOf(
        "delete", "forget all", "wipe", "format", "restore", "factory reset", "unzip over"
    )

    var isArmed: Boolean = true
        private set

    fun arm() {
        isArmed = true
        AmyLogger.i("AmyGuardian", "Guardian armed")
    }

    fun disarm() {
        isArmed = false
        AmyLogger.i("AmyGuardian", "Guardian disarmed")
    }

    /** Returns the current device's ANDROID_ID. */
    fun currentDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Binds AMY to this device on first run. On subsequent runs, verifies the
     * stored ANDROID_ID matches the current device. Returns true if bound/verified OK.
     */
    suspend fun verifyOrBindDevice(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File("/storage/emulated/0/AmyBrain")
            if (!dir.exists()) dir.mkdirs()
            val file = File(DEVICE_LOCK_FILE)
            val currentId = currentDeviceId(context)
            if (!file.exists()) {
                file.writeText(currentId)
                AmyLogger.i("AmyGuardian", "Device bound: $currentId")
                return@withContext true
            }
            val storedId = file.readText().trim()
            val match = storedId == currentId
            if (!match) {
                AmyLogger.e("AmyGuardian", "DEVICE MISMATCH! stored=$storedId current=$currentId")
            }
            match
        } catch (ex: Exception) {
            AmyLogger.e("AmyGuardian", "Device verification failed", ex)
            false
        }
    }

    /** SYSTEM_GUARD_V2 content check. Returns null if OK, or a block reason string if blocked. */
    fun checkContentPolicy(input: String): String? {
        val lower = input.lowercase()
        for (term in NSFW_BLOCK_TERMS) {
            if (lower.contains(term)) {
                AmyLogger.w("AmyGuardian", "SYSTEM_GUARD_V2 blocked input containing: $term")
                return "Blocked by SYSTEM_GUARD_V2: this request isn't something AMY can help with."
            }
        }
        return null
    }

    /** Returns true if the given command requires an explicit "Are you sure?" confirmation. */
    fun requiresConfirmation(command: String): Boolean {
        val lower = command.lowercase()
        return DESTRUCTIVE_ACTION_KEYWORDS.any { lower.contains(it) }
    }

    fun confirmationPrompt(command: String): String {
        return "AMY wants to confirm before proceeding: \"$command\". This action cannot be easily undone. Continue?"
    }

    /** Simple guardian alert log, e.g. wrong voice-lock attempts. */
    suspend fun raiseAlert(reason: String) = withContext(Dispatchers.IO) {
        AmyLogger.e("AmyGuardian", "ALERT: $reason")
        try {
            val dir = File("/storage/emulated/0/AmyBrain/alerts")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "alert_${System.currentTimeMillis()}.txt")
            file.writeText(reason)
        } catch (ex: Exception) {
            AmyLogger.e("AmyGuardian", "Failed writing alert file", ex)
        }
    }
}
