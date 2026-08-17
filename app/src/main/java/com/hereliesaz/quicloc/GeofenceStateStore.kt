package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences

/**
 * Ephemeral, per-geofence "what did we last do" bookkeeping used to dedupe
 * [GeofenceBroadcastReceiver] callbacks. Plain (unencrypted) prefs, mirroring
 * `quicloc_tracking_state.xml`'s existing precedent: mid-flight runtime state
 * is meaningless on another device and carries no sensitive content beyond
 * an opaque geofence id, so it's excluded from Android Auto Backup (see
 * backup_rules.xml / data_extraction_rules.xml) rather than encrypted.
 *
 * Two distinct problems, two distinct responses:
 *   - Android can redeliver the *same* transition for a geofence it already
 *     reported (a documented `GeofencingEvent` behavior) — always silently
 *     skipped, no diagnostic noise.
 *   - Genuine rapid ENTER/EXIT flapping at a boundary (GPS jitter) — state is
 *     still recorded accurately, but the SMS is suppressed within a cooldown
 *     window per (geofence, direction) so a recipient doesn't get several
 *     texts in quick succession for one real crossing.
 */
object GeofenceStateStore {
    private const val PREFS_FILE = "quicloc_geofence_state"
    private const val MIN_FLAP_INTERVAL_MS = 60_000L

    enum class Result { PROCESS, DUPLICATE, FLAP_SUPPRESSED }

    /** Call once per (geofenceId, transitionType) pulled out of a GeofencingEvent. */
    fun evaluate(context: Context, geofenceId: String, transition: Int): Result {
        val prefs = prefs(context)
        val lastType = prefs.getInt(typeKey(geofenceId), -1)
        val lastAt = prefs.getLong(atKey(geofenceId), 0L)
        val now = System.currentTimeMillis()

        return when {
            lastType == transition -> Result.DUPLICATE
            lastAt != 0L && now - lastAt < MIN_FLAP_INTERVAL_MS -> {
                record(prefs, geofenceId, transition, now)
                Result.FLAP_SUPPRESSED
            }
            else -> {
                record(prefs, geofenceId, transition, now)
                Result.PROCESS
            }
        }
    }

    /** Drop bookkeeping for a geofence that's been deleted, so a stale entry can't confuse a future one reusing the same id (never happens with UUIDs, but keeps the store from growing unboundedly). */
    fun clear(context: Context, geofenceId: String) {
        prefs(context).edit()
            .remove(typeKey(geofenceId))
            .remove(atKey(geofenceId))
            .apply()
    }

    private fun record(prefs: SharedPreferences, geofenceId: String, transition: Int, at: Long) {
        prefs.edit()
            .putInt(typeKey(geofenceId), transition)
            .putLong(atKey(geofenceId), at)
            .apply()
    }

    private fun typeKey(id: String) = "type_$id"
    private fun atKey(id: String) = "at_$id"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
