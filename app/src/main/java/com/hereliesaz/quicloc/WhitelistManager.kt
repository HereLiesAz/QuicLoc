package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class WhitelistManager(context: Context) {

    companion object {
        private const val TAG = "QuicLoc.Whitelist"
        private const val ENCRYPTED_PREFS_FILE = "quicloc_secure_prefs"
        private const val LEGACY_PREFS_FILE = "quicloc_prefs"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_MY_NUMBER = "my_number"
        private const val KEY_STARRED = "starred"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_PIN = "pin"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }

    private val prefs: SharedPreferences = createEncryptedPrefs(context).also {
        migrateLegacyPrefs(context, it)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun getMyNumber(): String {
        return prefs.getString(KEY_MY_NUMBER, "") ?: ""
    }

    fun setMyNumber(number: String) {
        prefs.edit().putString(KEY_MY_NUMBER, number).apply()
    }

    fun getStarredNumbers(): Set<String> {
        return prefs.getStringSet(KEY_STARRED, emptySet()) ?: emptySet()
    }

    fun toggleStarred(number: String): Boolean {
        val currentStarred = getStarredNumbers().toMutableSet()
        if (currentStarred.contains(number)) {
            currentStarred.remove(number)
        } else {
            if (currentStarred.size >= 3) {
                return false // Limit reached
            }
            currentStarred.add(number)
        }
        prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
        return true
    }


    fun addNumber(number: String) {
        val clean = cleanPhoneNumber(number)
        if (clean.isNotEmpty()) {
            val numbers = getNumbers().toMutableSet()
            numbers.add(clean)
            saveNumbers(numbers)
        }
    }

    fun removeNumber(number: String) {
        val numbers = getNumbers().toMutableSet()
        numbers.remove(number)
        saveNumbers(numbers)

        // Also remove from starred if deleted
        val currentStarred = getStarredNumbers().toMutableSet()
        if (currentStarred.contains(number)) {
            currentStarred.remove(number)
            prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
        }
    }

    fun getPassphrase(): String? {
        return prefs.getString(KEY_PASSPHRASE, null)
    }

    fun setPassphrase(passphrase: String?) {
        prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
    }

    fun getPin(): String? {
        return prefs.getString(KEY_PIN, null)
    }

    fun setPin(pin: String?) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun getNumbers(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    /**
     * Used for SMS: matches by phone number, tolerating formatting differences
     * (+1, dashes, spaces, etc.)
     */
    fun isWhitelisted(number: String): Boolean {
        val cleanIncoming = cleanPhoneNumber(number)
        return getNumbers().any { whitelisted ->
            PhoneNumberUtils.compare(cleanIncoming, whitelisted)
        }
    }

    /**
     * Used for notifications: matches by display name OR phone number.
     * Notification senders may show a contact name rather than a raw number.
     */
    fun isWhitelistedByName(displayName: String): Boolean {
        val nameLower = displayName.trim().lowercase()
        return getNumbers().any { entry ->
            val entryLower = entry.trim().lowercase()
            entryLower == nameLower ||
                PhoneNumberUtils.compare(cleanPhoneNumber(displayName), cleanPhoneNumber(entry))
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun saveNumbers(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELIST, numbers).apply()
    }

    private fun cleanPhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    // -------------------------------------------------------------------------
    // Encryption setup
    // -------------------------------------------------------------------------

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If the Keystore is unavailable (e.g. after a factory reset with
            // Direct Boot), fall back to plaintext so the app doesn't hard-crash.
            // The next cold boot after the user unlocks will recreate the key.
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plaintext", e)
            context.getSharedPreferences(ENCRYPTED_PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    // -------------------------------------------------------------------------
    // One-time migration from old plaintext prefs
    // -------------------------------------------------------------------------

    private fun migrateLegacyPrefs(context: Context, encryptedPrefs: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        val legacyNumbers = legacyPrefs.getStringSet(KEY_WHITELIST, null) ?: return

        // Only migrate if the encrypted store is empty and legacy store has data
        val alreadyMigrated = encryptedPrefs.contains(KEY_WHITELIST)
        if (!alreadyMigrated && legacyNumbers.isNotEmpty()) {
            Log.i(TAG, "Migrating ${legacyNumbers.size} entries from plaintext to encrypted prefs")
            encryptedPrefs.edit().putStringSet(KEY_WHITELIST, legacyNumbers).apply()
            // Wipe the old plaintext store
            legacyPrefs.edit().clear().apply()
        }
    }
}
