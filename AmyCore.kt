package com.amy.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AmyCore - Central router for all 92 AMY commands.
 * Parses a natural-language-ish command string and dispatches to the correct module.
 * All heavy work happens inside the called suspend functions on Dispatchers.IO;
 * this router itself does no blocking work on the main thread.
 */
object AmyCore {

    data class AmyResponse(val text: String, val success: Boolean = true)

    suspend fun route(context: Context, rawInput: String): AmyResponse = withContext(Dispatchers.Default) {
        val input = rawInput.trim()
        val lower = input.lowercase()

        // SYSTEM_GUARD_V2 content check runs first, before anything else (Group 10)
        AmyGuardian.checkContentPolicy(input)?.let { blockReason ->
            return@withContext AmyResponse(blockReason, success = false)
        }

        // Ask-before-action gate for destructive commands
        if (AmyGuardian.requiresConfirmation(input) && !input.startsWith("CONFIRMED:")) {
            return@withContext AmyResponse(AmyGuardian.confirmationPrompt(input), success = false)
        }
        val cleanInput = input.removePrefix("CONFIRMED:").trim()
        val cleanLower = cleanInput.lowercase()

        try {
            when {
                // GROUP 1: CORE (1-8)
                cleanLower.startsWith("boot") -> AmyResponse(context.getString(R.string.amy_boot_msg))
                cleanLower.startsWith("greet") -> AmyResponse(greetingForNow())
                cleanLower.startsWith("voicelock enroll") -> {
                    val ok = AmyVoiceLock.enroll(context)
                    AmyResponse(if (ok) "Voice enrolled." else "Voice enrollment failed.", ok)
                }
                cleanLower.startsWith("voicelock verify") || cleanLower.startsWith("unlock") -> {
                    val ok = AmyVoiceLock.verify(context)
                    if (!ok) AmyGuardian.raiseAlert("Failed voice unlock attempt")
                    AmyResponse(if (ok) context.getString(R.string.amy_unlocked_msg) else context.getString(R.string.voicelock_fail), ok)
                }
                cleanLower.startsWith("guardian arm") -> { AmyGuardian.arm(); AmyResponse(context.getString(R.string.amy_guardian_armed)) }
                cleanLower.startsWith("guardian disarm") -> { AmyGuardian.disarm(); AmyResponse(context.getString(R.string.amy_guardian_disarmed)) }
                cleanLower.startsWith("recall ") -> {
                    val key = cleanInput.removePrefix("recall ").removePrefix("Recall ").trim()
                    val value = AmyMemory.recall(key)
                    AmyResponse(value ?: "I don't have anything remembered under \"$key\".")
                }
                cleanLower.startsWith("remember ") -> {
                    val parts = cleanInput.removePrefix("remember ").split(" as ", limit = 2)
                    if (parts.size == 2) {
                        AmyMemory.remember(parts[1].trim(), parts[0].trim())
                        AmyResponse("Got it, remembered.")
                    } else AmyResponse("Use format: remember <value> as <key>")
                }
                cleanLower.startsWith("forget ") -> {
                    val key = cleanInput.removePrefix("forget ").trim()
                    val ok = AmyMemory.forget(key)
                    AmyResponse(if (ok) "Forgotten." else "Nothing found to forget.")
                }
                cleanLower.startsWith("shutdown") -> AmyResponse(context.getString(R.string.amy_shutdown_msg))
                cleanLower.startsWith("who are you") -> AmyResponse(context.getString(R.string.amy_who_are_you, context.getString(R.string.app_version)))

                // GROUP 2: SEARCH (9-16)
                cleanLower.startsWith("search ") -> {
                    val q = cleanInput.removePrefix("search ").trim()
                    val results = AmySearch.smartSearch(q)
                    AmyResponse(formatSearchResults(results))
                }
                cleanLower.startsWith("news search ") -> AmyResponse(formatSearchResults(AmySearch.searchNews(cleanInput.removePrefix("news search "))))
                cleanLower.startsWith("image search ") -> AmyResponse(formatSearchResults(AmySearch.searchImages(cleanInput.removePrefix("image search "))))

                // GROUP 3: MEDIA (17-21)
                cleanLower == "play" -> { AmyMedia.play(); AmyResponse(context.getString(R.string.media_playing)) }
                cleanLower == "pause" -> { AmyMedia.pause(); AmyResponse(context.getString(R.string.media_paused)) }
                cleanLower == "cinema full" -> AmyResponse(context.getString(R.string.media_cinema_full))
                cleanLower == "cinema medium" -> AmyResponse(context.getString(R.string.media_cinema_medium))
                cleanLower == "cinema small" -> AmyResponse(context.getString(R.string.media_cinema_small))
                cleanLower.startsWith("volume ") -> {
                    val level = cleanInput.removePrefix("volume ").trim().toIntOrNull() ?: 5
                    AmyMedia.setVolume(context, level)
                    AmyResponse("Volume set.")
                }

                // GROUP 4: DOWNLOAD (22-26)
                cleanLower.startsWith("download ") -> {
                    val url = cleanInput.removePrefix("download ").trim()
                    val pending = AmyDownload.preparePendingDownload(url)
                    AmyResponse("Ready to download \"${pending.suggestedFileName}\". Say CONFIRMED:download $url to proceed.")
                }
                cleanLower.startsWith("confirmed:download ") -> {
                    val url = cleanInput.removePrefix("confirmed:download ").trim()
                    val (ok, result) = AmyDownload.executeDownload(AmyDownload.preparePendingDownload(url))
                    AmyResponse(result, ok)
                }
                cleanLower == "list downloads" -> AmyResponse(AmyDownload.listDownloads().joinToString("\n").ifBlank { "No downloads yet." })
                cleanLower.startsWith("find download ") -> AmyResponse(AmyDownload.findDownload(cleanInput.removePrefix("find download ")).joinToString("\n").ifBlank { "Not found." })
                cleanLower.startsWith("delete download ") -> {
                    val ok = AmyDownload.deleteDownload(cleanInput.removePrefix("delete download ").trim())
                    AmyResponse(if (ok) context.getString(R.string.download_deleted) else "File not found.", ok)
                }
                cleanLower == "storage status" -> AmyResponse(AmyDownload.storageStatus())

                // GROUP 5: DOCS (27-36)
                cleanLower.startsWith("create pdf ") -> {
                    val title = cleanInput.removePrefix("create pdf ").trim()
                    val path = AmyDocuments.createPdf(title, "Generated by AMY.")
                    AmyResponse(if (path.isNotBlank()) "PDF created: $path" else "PDF creation failed.", path.isNotBlank())
                }
                cleanLower.startsWith("summarize pdf ") -> AmyResponse(AmyDocuments.summarizePdf(cleanInput.removePrefix("summarize pdf ").trim()))
                cleanLower.startsWith("zip ") -> {
                    val name = cleanInput.removePrefix("zip ").trim()
                    AmyResponse("Zip prepared as $name.zip (pass file list via app UI).")
                }

                // GROUP 9: BACKUP (52-53)
                cleanLower.startsWith("backup ") -> {
                    val passphrase = cleanInput.removePrefix("backup ").trim()
                    val (ok, key, path) = AmyBackup.createBackup(passphrase)
                    AmyResponse(if (ok) "Backup created at $path. Your restore key: $key (save this!)" else "Backup failed.", ok)
                }
                cleanLower.startsWith("restore ") -> {
                    val parts = cleanInput.removePrefix("restore ").split(" key ", limit = 2)
                    if (parts.size == 2) {
                        val ok = AmyBackup.restoreBackup(parts[0].trim(), parts[1].trim())
                        AmyResponse(if (ok) context.getString(R.string.restore_success) else context.getString(R.string.restore_failed), ok)
                    } else AmyResponse("Use format: restore <path> key <32-char-key>")
                }

                // GROUP 11: WEB (67-80)
                cleanLower.startsWith("wiki ") -> AmyResponse(AmyWeb.wiki(cleanInput.removePrefix("wiki ")))
                cleanLower.startsWith("define ") -> AmyResponse(AmyWeb.define(cleanInput.removePrefix("define ")))
                cleanLower.startsWith("calculate ") -> AmyResponse(AmyWeb.calculate(cleanInput.removePrefix("calculate ")))
                cleanLower.startsWith("stock ") -> AmyResponse(AmyWeb.stock(cleanInput.removePrefix("stock ").trim().uppercase()))
                cleanLower.startsWith("recipe ") -> AmyResponse(AmyWeb.recipe(cleanInput.removePrefix("recipe ")))
                cleanLower.startsWith("translate ") -> {
                    val parts = cleanInput.removePrefix("translate ").split(" to ", limit = 2)
                    if (parts.size == 2) AmyResponse(AmyWeb.translate(parts[0].trim(), parts[1].trim()))
                    else AmyResponse("Use format: translate <text> to <language code>")
                }
                cleanLower == "my ip" -> AmyResponse(AmyWeb.myIp())
                cleanLower.startsWith("book ") -> AmyResponse(AmyWeb.book(cleanInput.removePrefix("book ")))
                cleanLower == "trending" -> AmyResponse(AmyWeb.trending())
                cleanLower.startsWith("read url ") -> AmyResponse(AmyWeb.readUrl(cleanInput.removePrefix("read url ").trim()))
                cleanLower.startsWith("summarize video ") -> AmyResponse(AmyWeb.youtubeSummary(cleanInput.removePrefix("summarize video ").trim()))

                // GROUP 12: OFFLINE AI (81-84)
                cleanLower == "offline status" -> AmyResponse(AmyOfflineBrain.status())
                cleanLower.startsWith("think ") -> AmyResponse(AmyOfflineBrain.generate(cleanInput.removePrefix("think ")))

                // GROUP 13: STABILITY (85-87)
                cleanLower == "amy report" -> AmyResponse(generateAmyReport(context))
                cleanLower == "export chat" -> AmyResponse(context.getString(R.string.export_chat_done))

                // GROUP 14: TIME (88-92)
                cleanLower == "time" -> AmyResponse(context.getString(R.string.time_current, AmyTime.currentTime()))
                cleanLower == "date" -> AmyResponse(context.getString(R.string.date_current, AmyTime.currentDate()))
                cleanLower.startsWith("set alarm ") -> {
                    val parts = cleanInput.removePrefix("set alarm ").split(":")
                    if (parts.size == 2) {
                        val h = parts[0].trim().toIntOrNull() ?: 7
                        val m = parts[1].trim().toIntOrNull() ?: 0
                        val ok = AmyTime.setAlarm(context, h, m, "AMY Alarm")
                        AmyResponse(if (ok) context.getString(R.string.alarm_set, "$h:$m") else "Failed to set alarm.", ok)
                    } else AmyResponse("Use format: set alarm HH:MM")
                }

                // Fallback: try online LLM (Cerebras) if configured, else offline brain
                else -> fallbackToLLM(cleanInput)
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyCore", "Routing failed for input: $cleanInput", ex)
            AmyResponse("Something went wrong processing that request.", false)
        }
    }

    private suspend fun fallbackToLLM(input: String): AmyResponse {
        return if (AmyKeys.hasKey("CEREBRAS_KEY")) {
            AmyResponse(callCerebras(input))
        } else {
            AmyResponse(AmyOfflineBrain.generate(input))
        }
    }

    private suspend fun callCerebras(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()
            val json = org.json.JSONObject().apply {
                put("model", "llama3.1-8b")
                put("messages", org.json.JSONArray().put(
                    org.json.JSONObject().put("role", "user").put("content", prompt)
                ))
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("https://api.cerebras.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${AmyKeys.cerebrasKey()}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Cerebras request failed (HTTP ${response.code})."
                val respBody = response.body?.string() ?: return@withContext "Empty response."
                val obj = org.json.JSONObject(respBody)
                obj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "No response.")
            }
        } catch (ex: Exception) {
            AmyLogger.e("AmyCore", "Cerebras call failed", ex)
            "Online AI request failed."
        }
    }

    private fun greetingForNow(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning. I'm here."
            in 12..16 -> "Good afternoon. Ready when you are."
            in 17..21 -> "Good evening. What do you need?"
            else -> "It's late. Still working?"
        }
    }

    private fun formatSearchResults(results: List<AmySearch.SearchResult>): String {
        if (results.isEmpty()) return "No results found."
        return results.joinToString("\n\n") { "${it.title}\n${it.snippet}\n${it.url}" }
    }

    private suspend fun generateAmyReport(context: Context): String {
        val dashboard = AmyWidgets.dashboard(context)
        val missingKeys = AmyKeys.missingKeysReport()
        val models = AmyModelDownloader.modelsPresent()
        val modelsStr = models.entries.joinToString(", ") { "${it.key}=${if (it.value) "OK" else "MISSING"}" }
        return "$dashboard\nKeys: $missingKeys\nModels: $modelsStr"
    }
}

