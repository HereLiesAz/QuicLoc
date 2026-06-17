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
import java.util.concurrent.Executors

/** Which intake path observed the message. */
enum class DiagChannel { SMS, NOTIFICATION, WIDGET }

/**
 * The exact decision QuicLoc made about an incoming message. Surfaced in the
 * Diagnostics screen so the user can see *why* a `loc` request did or didn't
 * produce a location reply — without needing a computer / adb.
 */
enum class DiagOutcome {
    // --- pre-trigger filters (only recorded under capture-all) ---
    OWN_NOTIFICATION,        // our own notification — ignored
    ONGOING_IGNORED,         // ongoing/system notification — ignored
    APP_DISABLED,            // QuicLoc master toggle is off
    NO_NOTIFICATION_OBJECT,  // StatusBarNotification had no Notification
    NO_MESSAGES_EXTRACTED,   // couldn't pull any sender/body out of the notification
    EMPTY_SENDER_OR_BODY,    // extracted sender or body was blank
    DEDUPED,                 // duplicate re-post within the dedupe window
    NOT_A_TRIGGER,           // message wasn't "loc"/"quicloc"
    EVALUATED_NO_ACTION,     // capture-all: notification seen, nothing to do

    // --- trigger word matched, then… ---
    PASSPHRASE_TRIGGER,           // find-my-phone passphrase → TrackingService
    WHITELIST_NO_MATCH_BY_NAME,   // notif: trigger OK but sender not whitelisted (the key one)
    WHITELIST_NO_MATCH_BY_NUMBER, // sms: trigger OK but sender not whitelisted
    NO_REPLY_ACTION,              // notif: whitelisted but no inline-reply affordance
    DISPATCHED,                   // handed to LocationReplyService (interim state)

    // --- final outcomes, patched in by LocationReplyService ---
    REPLY_SENT,
    REPLY_FAILED,
    NO_PENDING_ACTION,            // service couldn't find the reply action
}

/**
 * One row in the diagnostic log.
 *
 * @property id Stable UUID so [LocationReplyService] can patch this row's
 *   final outcome (DISPATCHED → REPLY_SENT/REPLY_FAILED) after the async send.
 * @property rawBody Truncated copy of the message body — enough to see what
 *   the app actually received from each messaging app.
 * @property extractionPath For notifications, which extraction branch matched
 *   ("MessagingStyle" / "TextLines" / "PlainText" / "None"). `null` for SMS.
 * @property whitelistMatched `null` until the whitelist check runs.
 */
data class DiagnosticEvent(
    val id: String,
    val timestamp: Long,
    val channel: DiagChannel,
    val source: String,
    val rawSender: String,
    val rawBody: String,
    val triggerMatched: Boolean,
    val whitelistMatched: Boolean?,
    val extractionPath: String?,
    val outcome: DiagOutcome,
    val reason: String,
) {
    /** Human-readable timestamp for the Diagnostics list, in the device locale. */
    val formattedTime: String
        get() = SimpleDateFormat("MMM d  h:mm:ss a", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Append-only diagnostic log (capped at [MAX_ENTRIES]) recording every `loc`
 * message the app evaluates and the decision it reached. The companion to the
 * user-facing [RequestHistoryManager], but far more verbose — it captures the
 * silent drops (not whitelisted, wrong body, no reply action, …) that
 * `RequestHistoryManager` never sees, so the user can diagnose why chat apps
 * (WhatsApp, Messenger, Google Voice) aren't triggering replies.
 *
 * Stored in `EncryptedSharedPreferences` (`quicloc_diagnostics`) — like the
 * history, it's a record of who's been messaging the user, so it's encrypted
 * and, crucially, **not** added to [BackupVault] (per-device only).
 *
 * All writes run on a single background [executor]: [NotificationListener]
 * calls us from a binder thread, and under capture-all that can be every
 * notification on the device, so the AES + JSON re-serialize must stay off the
 * caller's thread. Serializing on one thread also makes [updateOutcome]'s
 * read-modify-write patch-by-id race-free against concurrent [record] calls.
 */
class DiagnosticLogManager(context: Context) {

    companion object {
        private const val TAG = "QuicLoc.Diag"
        private const val PREFS_FILE = "quicloc_diagnostics"
        private const val KEY_EVENTS = "events"
        private const val MAX_ENTRIES = 200

        // Shared so all instances serialize their writes onto one thread.
        private val executor = Executors.newSingleThreadExecutor()
    }

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun record(event: DiagnosticEvent) {
        executor.execute {
            val events = readEvents().toMutableList()
            events.add(0, event)
            val trimmed = if (events.size > MAX_ENTRIES) events.take(MAX_ENTRIES) else events
            writeEvents(trimmed)
        }
    }

    /**
     * Patches the outcome + reason of an existing row by [id]. Used by
     * [LocationReplyService] to flip an interim `DISPATCHED` row to its final
     * `REPLY_SENT` / `REPLY_FAILED` state once the async location fetch and
     * reply complete. No-op if the row has already aged out of the buffer.
     */
    fun updateOutcome(id: String, outcome: DiagOutcome, reason: String) {
        executor.execute {
            val events = readEvents()
            var changed = false
            val patched = events.map { e ->
                if (e.id == id) {
                    changed = true
                    e.copy(outcome = outcome, reason = reason)
                } else e
            }
            if (changed) writeEvents(patched)
        }
    }

    fun getEvents(): List<DiagnosticEvent> = readEvents()

    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    /** Flattens the log to shareable plain text for the Copy/Share button. */
    fun exportAsText(): String {
        val events = readEvents()
        if (events.isEmpty()) return "QuicLoc diagnostics: (empty)"
        val sb = StringBuilder("QuicLoc diagnostics (${events.size} entries)\n")
        for (e in events) {
            sb.append("\n[${e.formattedTime}] ${e.channel} · ${e.source}\n")
            sb.append("  from: ${e.rawSender}\n")
            sb.append("  body: ${e.rawBody}\n")
            e.extractionPath?.let { sb.append("  via:  $it\n") }
            sb.append("  → ${e.outcome}: ${e.reason}\n")
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Internal — JSON (de)serialization
    // -------------------------------------------------------------------------

    private fun readEvents(): List<DiagnosticEvent> {
        val json = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DiagnosticEvent(
                    id = obj.getString("id"),
                    timestamp = obj.getLong("timestamp"),
                    channel = DiagChannel.valueOf(obj.getString("channel")),
                    source = obj.getString("source"),
                    rawSender = obj.getString("rawSender"),
                    rawBody = obj.getString("rawBody"),
                    triggerMatched = obj.getBoolean("triggerMatched"),
                    whitelistMatched = if (obj.isNull("whitelistMatched")) null else obj.getBoolean("whitelistMatched"),
                    extractionPath = if (obj.isNull("extractionPath")) null else obj.getString("extractionPath"),
                    outcome = DiagOutcome.valueOf(obj.getString("outcome")),
                    reason = obj.getString("reason"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse diagnostics", e)
            emptyList()
        }
    }

    private fun writeEvents(events: List<DiagnosticEvent>) {
        val array = JSONArray()
        for (e in events) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("timestamp", e.timestamp)
            obj.put("channel", e.channel.name)
            obj.put("source", e.source)
            obj.put("rawSender", e.rawSender)
            obj.put("rawBody", e.rawBody)
            obj.put("triggerMatched", e.triggerMatched)
            obj.put("whitelistMatched", e.whitelistMatched ?: JSONObject.NULL)
            obj.put("extractionPath", e.extractionPath ?: JSONObject.NULL)
            obj.put("outcome", e.outcome.name)
            obj.put("reason", e.reason)
            array.put(obj)
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
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
            Log.e(TAG, "Falling back to plaintext prefs for diagnostics", e)
            context.getSharedPreferences("${PREFS_FILE}_fallback", Context.MODE_PRIVATE)
        }
    }
}
