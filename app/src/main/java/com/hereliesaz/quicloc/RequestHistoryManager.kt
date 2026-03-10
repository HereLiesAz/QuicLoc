package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestEvent(
    val timestamp: Long,
    val sender: String,
    val source: String,       // "SMS", "WhatsApp", "Telegram", etc.
    val succeeded: Boolean,   // false if location fetch failed
) {
    val formattedTime: String
        get() = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
            .format(Date(timestamp))
}

class RequestHistoryManager(context: Context) {

    companion object {
        private const val TAG = "QuicLoc.History"
        private const val PREFS_FILE = "quicloc_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_ENTRIES = 100
    }

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun record(sender: String, source: String, succeeded: Boolean) {
        val events = getHistory().toMutableList()
        events.add(0, RequestEvent(
            timestamp = System.currentTimeMillis(),
            sender = sender,
            source = source,
            succeeded = succeeded
        ))
        // Cap at MAX_ENTRIES to avoid unbounded growth
        val trimmed = if (events.size > MAX_ENTRIES) events.take(MAX_ENTRIES) else events
        saveHistory(trimmed)
        Log.d(TAG, "Recorded request from $sender via $source (success=$succeeded)")
    }

    fun getHistory(): List<RequestEvent> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RequestEvent(
                    timestamp = obj.getLong("timestamp"),
                    sender = obj.getString("sender"),
                    source = obj.getString("source"),
                    succeeded = obj.getBoolean("succeeded")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse history", e)
            emptyList()
        }
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun saveHistory(events: List<RequestEvent>) {
        val array = JSONArray()
        for (event in events) {
            val obj = JSONObject()
            obj.put("timestamp", event.timestamp)
            obj.put("sender", event.sender)
            obj.put("source", event.source)
            obj.put("succeeded", event.succeeded)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Falling back to plaintext prefs for history", e)
            context.getSharedPreferences("${PREFS_FILE}_fallback", Context.MODE_PRIVATE)
        }
    }
}
