package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Owner of the Loc Notice place definitions: name, coordinates, radius,
 * enter/exit flags, and which whitelist contacts get told. Sensitive in the
 * same way the whitelist is (named places + who's alerted there), so it gets
 * the same `EncryptedSharedPreferences` + plaintext-fallback-recovery
 * treatment as [WhitelistManager] — see that class for the pattern this
 * copies.
 *
 * Deliberately has zero dependency on Play Services / `GeofencingClient` —
 * that's [GeofenceRegistrar]'s job. Keeping storage pure means this class
 * stays Robolectric-testable the same way `WhitelistManagerTest` is, with no
 * risk of a test depending on GMS shadowing behavior. Every mutator here
 * updates the store and the [BackupVault] snapshot only; callers are
 * responsible for calling [GeofenceRegistrar.sync] afterward to reflect the
 * change at the OS level.
 */
class GeofenceStore(context: Context) {

    companion object {
        private const val TAG = "QuicLoc.GeofenceStore"
        private const val PREFS_FILE = "quicloc_locnotice_prefs"
        private const val KEY_ENTRIES = "entries"
    }

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    fun getAll(): List<GeofenceEntry> {
        val strings = prefs.getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet()
        return strings.mapNotNull { GeofenceEntry.fromJsonString(it) }
    }

    fun get(id: String): GeofenceEntry? = getAll().firstOrNull { it.id == id }

    /** Assigns a fresh id if [entry] doesn't have one, and returns the saved entry. */
    fun add(entry: GeofenceEntry): GeofenceEntry {
        val saved = if (entry.id.isBlank()) entry.copy(id = UUID.randomUUID().toString()) else entry
        val current = prefs.getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet()
        saveAll(current + saved.toJsonString())
        BackupVault.snapshotAsync(appContext)
        return saved
    }

    fun update(entry: GeofenceEntry) {
        val current = getAll()
        val newSet = current.filterNot { it.id == entry.id }.map { it.toJsonString() }.toSet()
        saveAll(newSet + entry.toJsonString())
        BackupVault.snapshotAsync(appContext)
    }

    fun remove(id: String) {
        val newSet = getAll().filterNot { it.id == id }.map { it.toJsonString() }.toSet()
        saveAll(newSet)
        BackupVault.snapshotAsync(appContext)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val entry = get(id) ?: return
        update(entry.copy(enabled = enabled))
    }

    /** Used by [BackupVault] restore. */
    fun replaceAll(entries: List<GeofenceEntry>) {
        saveAll(entries.map { it.toJsonString() }.toSet())
        BackupVault.snapshotAsync(appContext)
    }

    private fun saveAll(entries: Set<String>) {
        prefs.edit().putStringSet(KEY_ENTRIES, entries).apply()
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migratePlaintextFallback(context, "${PREFS_FILE}_fallback", prefs)
            prefs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plaintext", e)
            context.getSharedPreferences("${PREFS_FILE}_fallback", Context.MODE_PRIVATE)
        }
    }
}
