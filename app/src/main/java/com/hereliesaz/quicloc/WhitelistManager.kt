package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Owner of the sensitive on-device store: whitelist contacts, starred
 * priority list, your own number, find-my-phone passphrase and PIN, and the
 * onboarding-complete flag.
 */
class WhitelistManager(context: Context) {

    data class ContactEntry(val name: String?, val number: String) {
        fun toJsonString(): String {
            return JSONObject().apply {
                put("name", name ?: "")
                put("number", number)
            }.toString()
        }

        companion object {
            fun fromJsonString(jsonStr: String): ContactEntry? {
                return try {
                    val json = JSONObject(jsonStr)
                    val name = json.optString("name", "").takeIf { it.isNotBlank() }
                    val number = json.getString("number")
                    ContactEntry(name, number)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

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

        private val WHITESPACE_RUN = Regex("\\s+")
    }

    private val appContext: Context = context.applicationContext

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
        BackupVault.snapshotAsync(appContext)
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
                return false
            }
            currentStarred.add(number)
        }
        prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
        BackupVault.snapshotAsync(appContext)
        return true
    }

    fun addContact(name: String?, number: String) {
        val cleanNum = cleanPhoneNumber(number)
        if (cleanNum.isEmpty() && name.isNullOrBlank()) return

        val finalName = name?.trim()?.takeIf { it.isNotBlank() }
        val finalNumber = if (cleanNum.isNotEmpty()) cleanNum else number.trim()
        val entry = ContactEntry(finalName, finalNumber)

        val currentJsonSet = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        val mutableSet = currentJsonSet.toMutableSet()
        
        mutableSet.add(entry.toJsonString())

        saveNumbers(mutableSet)
        BackupVault.snapshotAsync(appContext)
    }

    fun addNumber(number: String) {
        addContact(null, number)
    }

    fun removeNumber(number: String) {
        val currentJsonSet = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        // If the number param is actually a JSON string (due to MainActivity list displaying JSON string)
        if (number.startsWith("{")) {
            val mutableSet = currentJsonSet.toMutableSet()
            mutableSet.remove(number)
            saveNumbers(mutableSet)

            val entry = ContactEntry.fromJsonString(number)
            if (entry != null) {
                val currentStarred = getStarredNumbers().toMutableSet()
                if (currentStarred.contains(entry.number)) {
                    currentStarred.remove(entry.number)
                    prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
                }
            }
        } else {
            val newSet = currentJsonSet.filterNot { jsonStr ->
                val entry = ContactEntry.fromJsonString(jsonStr)
                entry != null && entry.number == number
            }.toSet()
            saveNumbers(newSet)

            val currentStarred = getStarredNumbers().toMutableSet()
            if (currentStarred.contains(number)) {
                currentStarred.remove(number)
                prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
            }
        }
        BackupVault.snapshotAsync(appContext)
    }

    fun replaceAllNumbers(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELIST, numbers).apply()
        BackupVault.snapshotAsync(appContext)
    }

    fun replaceStarred(starred: Set<String>) {
        prefs.edit().putStringSet(KEY_STARRED, starred).apply()
        BackupVault.snapshotAsync(appContext)
    }

    fun getPassphrase(): String? {
        return prefs.getString(KEY_PASSPHRASE, null)
    }

    fun setPassphrase(passphrase: String?) {
        prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
        BackupVault.snapshotAsync(appContext)
    }

    @SuppressLint("ApplySharedPref")
    fun clearPassphraseSync() {
        prefs.edit().remove(KEY_PASSPHRASE).commit()
        BackupVault.snapshotAsync(appContext)
    }

    fun getPin(): String? {
        return prefs.getString(KEY_PIN, null)
    }

    fun setPin(pin: String?) {
        prefs.edit().putString(KEY_PIN, pin).apply()
        BackupVault.snapshotAsync(appContext)
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        BackupVault.snapshotAsync(appContext)
    }

    fun getContacts(): List<ContactEntry> {
        val strings = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        return strings.mapNotNull { ContactEntry.fromJsonString(it) }
    }

    fun getNumbers(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    fun isWhitelisted(number: String): Boolean {
        val cleanIncoming = cleanPhoneNumber(number)
        if (matchesMyNumber(cleanIncoming)) return true
        return getContacts().any { entry ->
            PhoneNumberUtils.compare(cleanIncoming, cleanPhoneNumber(entry.number))
        }
    }

    private fun matchesMyNumber(cleanIncoming: String): Boolean {
        val myNumber = getMyNumber()
        return myNumber.isNotEmpty() && PhoneNumberUtils.compare(cleanIncoming, myNumber)
    }

    fun isWhitelistedByName(displayName: String): Boolean {
        val nameNorm = normalizeName(displayName)
        val cleanIncoming = cleanPhoneNumber(displayName)
        val contacts = getContacts()

        if (matchesMyNumber(cleanIncoming)) return true

        val directMatch = contacts.any { entry ->
            val entryNameNorm = entry.name?.let { normalizeName(it) } ?: ""
            entryNameNorm == nameNorm ||
                PhoneNumberUtils.compare(cleanIncoming, cleanPhoneNumber(entry.number))
        }
        return directMatch
    }

    fun numbersForName(displayName: String): List<String> {
        val nameNorm = normalizeName(displayName)
        val contacts = getContacts()
        return contacts.filter { entry ->
            val entryNameNorm = entry.name?.let { normalizeName(it) } ?: ""
            entryNameNorm == nameNorm
        }.map { it.number }
    }

    private fun saveNumbers(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELIST, numbers).apply()
    }

    private fun cleanPhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    private fun normalizeName(s: String): String =
        s.trim().removePrefix("@").trim().replace(WHITESPACE_RUN, " ").lowercase()

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
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plaintext", e)
            context.getSharedPreferences(ENCRYPTED_PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacyPrefs(context: Context, encryptedPrefs: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        val legacyNumbers = legacyPrefs.getStringSet(KEY_WHITELIST, null) ?: emptySet()

        val alreadyMigrated = encryptedPrefs.contains(KEY_WHITELIST)
        
        if (!alreadyMigrated && legacyNumbers.isNotEmpty()) {
            encryptedPrefs.edit().putStringSet(KEY_WHITELIST, legacyNumbers).apply()
            legacyPrefs.edit().clear().apply()
        }

        val currentSet = encryptedPrefs.getStringSet(KEY_WHITELIST, null) ?: emptySet()
        val needsJsonMigration = currentSet.any { !it.startsWith("{") }
        if (needsJsonMigration) {
            val jsonSet = currentSet.map { str ->
                if (str.startsWith("{")) {
                    str
                } else {
                    ContactEntry(null, str).toJsonString()
                }
            }.toSet()
            encryptedPrefs.edit().putStringSet(KEY_WHITELIST, jsonSet).apply()
            Log.i(TAG, "Migrated ${jsonSet.size} entries to JSON format")
        }
    }
}
