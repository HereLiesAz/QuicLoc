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
        // Never clobber data the real store already has -- if both somehow
        // have the same key, the encrypted store's copy is the newer one
        // (it's only reachable once Keystore is healthy again, which is
        // strictly after whatever wrote the fallback entry).
        if (encryptedPrefs.contains(key)) continue
        migratedAny = true
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            else -> Unit
        }
    }
    if (migratedAny) {
        editor.apply()
        Log.i("QuicLoc.PrefsFallback", "Recovered ${fallbackData.size} key(s) from $fallbackFileName into encrypted storage")
    }
    fallback.edit().clear().apply()
}
