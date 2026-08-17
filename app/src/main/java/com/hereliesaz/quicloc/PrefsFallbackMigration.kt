package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * If a Keystore/EncryptedSharedPreferences init failure ever forced writes
 * into a plaintext `_fallback` file (see each manager's `createEncryptedPrefs`),
 * and Keystore is working again by the time this runs, recovers that data
 * into the real encrypted store instead of leaving it silently orphaned.
 *
 * Without this, data written during a transient Keystore failure (a real,
 * documented AndroidX Security / Keystore failure mode -- e.g. after an OS
 * upgrade) is lost the moment Keystore starts working again on a later
 * launch: the manager starts reading from the now-empty real store, and
 * nothing ever looks at the fallback file again.
 *
 * @param encryptedPrefs The just-created real encrypted store. Must NOT be
 *   the fallback file itself (i.e. only call this on the success path of
 *   `createEncryptedPrefs`, never from its catch branch).
 */
fun migratePlaintextFallback(
    context: Context,
    fallbackFileName: String,
    encryptedPrefs: SharedPreferences,
) {
    val fallback = context.getSharedPreferences(fallbackFileName, Context.MODE_PRIVATE)
    val fallbackData = fallback.all
    if (fallbackData.isEmpty()) return

    val editor = encryptedPrefs.edit()
    var migratedAny = false
    for ((key, value) in fallbackData) {
        if (value is Set<*>) {
            // StringSet stores (WhitelistManager's contacts/starred,
            // GeofenceStore's entries) hold their real data as one element
            // per record under a single key -- if the real store already
            // has that key (near-certain once there's any pre-outage data:
            // GeofenceStore has exactly one key total), a same-key skip
            // would silently discard every record added only during the
            // outage instead of merging them in. Union instead: recovers
            // outage-only records, keeps everything already there, and
            // naturally dedupes an element present in both.
            @Suppress("UNCHECKED_CAST")
            val fallbackSet = value as Set<String>
            val existing = encryptedPrefs.getStringSet(key, emptySet()) ?: emptySet()
            val merged = existing + fallbackSet
            if (merged.size != existing.size) {
                migratedAny = true
                editor.putStringSet(key, merged)
            }
            continue
        }
        // Scalars (a single number/string/flag) can only hold one value, so
        // the real store's post-recovery copy -- strictly newer, since it's
        // only reachable once Keystore is healthy again -- wins as-is.
        if (encryptedPrefs.contains(key)) continue
        migratedAny = true
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            else -> Unit
        }
    }
    if (migratedAny) {
        editor.apply()
        Log.i("QuicLoc.PrefsFallback", "Recovered ${fallbackData.size} key(s) from $fallbackFileName into encrypted storage")
    }
    fallback.edit().clear().apply()
}
