package com.amy.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * AmyTime - Group 14: Time, Date, Alarm, Reminder, Schedule (Features 88-92)
 */
object AmyTime {

    private const val SCHEDULE_FILE = "/storage/emulated/0/AmyBrain/schedule.json"
    private const val REMINDER_FILE = "/storage/emulated/0/AmyBrain/reminders.json"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)

    // Feature 88: Time
    fun currentTime(): String = timeFormat.format(Date())

    // Feature 89: Date
    fun currentDate(): String = dateFormat.format(Date())

    // Feature 90: Alarm
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String): Boolean {
        return try {
            val intent = Intent(AlarmClockCompatAction).apply {
                putExtra("android.intent.extra.alarm.HOUR", hour)
                putExtra("android.intent.extra.alarm.MINUTES", minute)
                putExtra("android.intent.extra.alarm.MESSAGE", label)
                putExtra("android.intent.extra.alarm.SKIP_UI", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.sendBroadcast(intent)
            AmyLogger.i("AmyTime", "Alarm set for $hour:$minute - $label")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyTime", "Failed to set alarm", ex)
            false
        }
    }

    // Feature 91: Reminder (stored locally + fires a local notification via AlarmManager)
    suspend fun setReminder(context: Context, message: String, triggerAtMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                saveReminderRecord(message, triggerAtMillis)

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AmyReminderReceiver::class.java).apply {
                    putExtra("reminder_message", message)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    triggerAtMillis.toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                AmyLogger.i("AmyTime", "Reminder set: $message @ $triggerAtMillis")
                true
            } catch (ex: Exception) {
                AmyLogger.e("AmyTime", "Failed to set reminder", ex)
                false
            }
        }

    private fun saveReminderRecord(message: String, triggerAtMillis: Long) {
        val dir = File("/storage/emulated/0/AmyBrain")
        if (!dir.exists()) dir.mkdirs()
        val file = File(REMINDER_FILE)
        val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        val obj = JSONObject()
        obj.put("message", message)
        obj.put("triggerAt", triggerAtMillis)
        arr.put(obj)
        file.writeText(arr.toString())
    }

    // Feature 92: Schedule
    suspend fun addToSchedule(title: String, whenMillis: Long, notes: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = File("/storage/emulated/0/AmyBrain")
                if (!dir.exists()) dir.mkdirs()
                val file = File(SCHEDULE_FILE)
                val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
                val obj = JSONObject()
                obj.put("title", title)
                obj.put("when", whenMillis)
                obj.put("notes", notes)
                arr.put(obj)
                file.writeText(arr.toString())
                AmyLogger.i("AmyTime", "Schedule added: $title")
                true
            } catch (ex: Exception) {
                AmyLogger.e("AmyTime", "Failed to add schedule", ex)
                false
            }
        }

    suspend fun getSchedule(): List<Triple<String, Long, String>> = withContext(Dispatchers.IO) {
        try {
            val file = File(SCHEDULE_FILE)
            if (!file.exists()) return@withContext emptyList()
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Triple<String, Long, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Triple(obj.optString("title"), obj.optLong("when"), obj.optString("notes")))
            }
            list
        } catch (ex: Exception) {
            AmyLogger.e("AmyTime", "Failed to read schedule", ex)
            emptyList()
        }
    }

    fun millisFromNowMinutes(minutesFromNow: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, minutesFromNow)
        return cal.timeInMillis
    }

    private const val AlarmClockCompatAction = "android.intent.action.SET_ALARM"
}

/**
 * Lightweight BroadcastReceiver that fires when a reminder's AlarmManager trigger goes off.
 * Kept in this file (not a separate file) to stay within the 26-file cap.
 */
class AmyReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("reminder_message") ?: "Reminder"
        AmyLogger.i("AmyReminderReceiver", "Firing reminder: $message")
        try {
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "amy_reminders"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId, "AMY Reminders", android.app.NotificationManager.IMPORTANCE_HIGH
                )
                notifManager.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle("AMY Reminder")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notifManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (ex: Exception) {
            AmyLogger.e("AmyReminderReceiver", "Failed to show notification", ex)
        }
    }
}
