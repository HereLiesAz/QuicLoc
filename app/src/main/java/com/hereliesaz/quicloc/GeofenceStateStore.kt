package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.location.Geofence

/**
 * Ephemeral, per-(geofence, direction) "did this just fire" bookkeeping used
 * to dedupe [GeofenceBroadcastReceiver] callbacks. Plain (unencrypted) prefs,
 * mirroring `quicloc_tracking_state.xml`'s existing precedent: mid-flight
 * runtime state is meaningless on another device and carries no sensitive
 * content beyond an opaque geofence id, so it's excluded from Android Auto
 * Backup (see backup_rules.xml / data_extraction_rules.xml) rather than
 * encrypted.
 *
 * Callers must only call [evaluate] for a transition that's actually
 * actionable for the entry (the direction is turned on) — see
 * [GeofenceBroadcastReceiver], which checks that first. That's what makes
 * "per (geofence, direction)" true rather than aspirational: a direction the
 * user never wanted notifications for is never recorded here, so it can't
 * poison the cooldown for the direction they do want. It also means a
 * genuine short stop (arrive, then leave 45s later) isn't suppressed just
 * because *some* transition happened recently — only a repeat of the *same*
 * direction within the window is.
 *
 * Two distinct problems, two distinct responses, both keyed the same way:
 *   - Android can redeliver the identical transition for a geofence it
 *     already reported (a documented `GeofencingEvent` behavior), typically
 *     within seconds — treated as pure noise, no diagnostic entry.
 *   - Genuine rapid same-direction re-triggering at a boundary (GPS jitter),
 *     on a slower cadence — the SMS is suppressed within a cooldown window,
 *     logged to Diagnostics so it's not silently invisible, and the cooldown
 *     resets so continued jitter doesn't produce a text either.
 */
object GeofenceStateStore {
    private const val PREFS_FILE = "quicloc_geofence_state"

    // GMS's own redelivery of an identical transition arrives within
    // seconds; anything slower than this but still under the flap window is
    // genuine (if unwanted) real-world bouncing, not a network/API retry.
    private const val DUPLICATE_WINDOW_MS = 10_000L
    private const val MIN_REPEAT_INTERVAL_MS = 60_000L

    enum class Result { PROCESS, DUPLICATE, FLAP_SUPPRESSED }

    /**
     * Call once per (geofenceId, transitionType) pulled out of a
     * GeofencingEvent, but only for a transition the caller has already
     * confirmed is actionable (entry exists, enabled, and this direction is
     * turned on) — see the class doc.
     */
    fun evaluate(context: Context, geofenceId: String, transition: Int): Result {
        val prefs = prefs(context)
        val key = atKey(geofenceId, transition)
        val lastAt = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        val sinceLast = now - lastAt

        return when {
            lastAt == 0L -> {
                record(prefs, key, now)
                Result.PROCESS
            }
            sinceLast < DUPLICATE_WINDOW_MS -> Result.DUPLICATE
            sinceLast < MIN_REPEAT_INTERVAL_MS -> {
                record(prefs, key, now)
                Result.FLAP_SUPPRESSED
            }
            else -> {
                record(prefs, key, now)
                Result.PROCESS
            }
        }
    }

    /** Drop bookkeeping for a geofence — on delete (id is never reused), and on disable so a missed opposite transition can't wedge future alerts (see [GeofenceRegistrar]/callers). */
    fun clear(context: Context, geofenceId: String) {
        prefs(context).edit()
            .remove(atKey(geofenceId, Geofence.GEOFENCE_TRANSITION_ENTER))
            .remove(atKey(geofenceId, Geofence.GEOFENCE_TRANSITION_EXIT))
            .apply()
    }

    private fun record(prefs: SharedPreferences, key: String, at: Long) {
        prefs.edit().putLong(key, at).apply()
    }

    private fun atKey(id: String, transition: Int) = "at_${id}_$transition"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
