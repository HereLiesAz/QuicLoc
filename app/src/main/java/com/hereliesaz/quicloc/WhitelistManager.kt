package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
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

    /**
     * @param canRequestLocation Whether this contact is authorized to
     *   request the location by sending the trigger word. Every contact —
     *   regardless of this flag — still receives the home-screen widget's
     *   safety-check / emergency alerts; that's the superset "emergency
     *   contacts" list. `false` marks an "alerts only" contact: someone who
     *   should be notified in an emergency but who can't themselves ask for
     *   the location by texting. Defaults to `true` so existing entries (and
     *   anything restored from an older backup with no such field) keep
     *   today's behavior.
     */
    data class ContactEntry(
        val name: String?,
        val number: String,
        val canRequestLocation: Boolean = true,
    ) {
        /** The token users see for this entry and use to remove/star it. */
        val displayToken: String
            get() = name?.takeIf { it.isNotBlank() } ?: number

        fun toJsonString(): String {
            return JSONObject().apply {
                put("name", name ?: "")
                put("number", number)
                put("can_request_location", canRequestLocation)
            }.toString()
        }

        companion object {
            fun fromJsonString(jsonStr: String): ContactEntry? {
                if (jsonStr.isBlank()) return null
                return try {
                    val json = JSONObject(jsonStr)
                    val name = json.optString("name", "").takeIf { it.isNotBlank() }
                    val number = json.optString("number", "")
                    val canRequestLocation = json.optBoolean("can_request_location", true)
                    ContactEntry(name, number, canRequestLocation)
                } catch (e: Exception) {
                    // A plain (non-JSON) token: pre-migration legacy data, or a
                    // value round-tripped through BackupVault, which stores and
                    // restores display tokens rather than JSON (see
                    // WhitelistManager.replaceAllNumbers). Classify it the same
                    // way addNumber does: no digits means it's a display name.
                    val trimmed = jsonStr.trim()
                    if (trimmed.any { it.isDigit() }) {
                        ContactEntry(null, trimmed)
                    } else {
                        ContactEntry(trimmed, "")
                    }
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

    fun addContact(name: String?, number: String, canRequestLocation: Boolean = true) {
        val cleanNum = cleanPhoneNumber(number)
        if (cleanNum.isEmpty() && name.isNullOrBlank()) return

        val finalName = name?.trim()?.takeIf { it.isNotBlank() }
        val finalNumber = if (cleanNum.isNotEmpty()) cleanNum else number.trim()
        val entry = ContactEntry(finalName, finalNumber, canRequestLocation)

        val currentJsonSet = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        val mutableSet = currentJsonSet.toMutableSet()
        
        mutableSet.add(entry.toJsonString())

        saveNumbers(mutableSet)
        BackupVault.snapshotAsync(appContext)
    }

    /**
     * Adds a single free-form whitelist token. A token with no digits at all
     * (e.g. "Mom", "@dril") is a display name/handle with no phone number;
     * anything else is treated as a phone number.
     */
    fun addNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        if (cleanPhoneNumber(trimmed).isEmpty()) {
            addContact(trimmed, "")
        } else {
            addContact(null, trimmed)
        }
    }

    fun removeNumber(token: String) {
        val currentJsonSet = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        // Legacy callers that still pass a raw JSON blob.
        if (token.startsWith("{")) {
            val mutableSet = currentJsonSet.toMutableSet()
            mutableSet.remove(token)
            saveNumbers(mutableSet)

            val entry = ContactEntry.fromJsonString(token)
            if (entry != null) {
                unstar(entry.displayToken)
            }
        } else {
            val newSet = currentJsonSet.filterNot { jsonStr ->
                val entry = ContactEntry.fromJsonString(jsonStr)
                entry != null && entry.displayToken == token
            }.toSet()
            saveNumbers(newSet)
            unstar(token)
        }
        BackupVault.snapshotAsync(appContext)
    }

    /**
     * Flips whether the contact(s) identified by [token] (a [ContactEntry.displayToken])
     * are allowed to request the location by texting, without touching their
     * name, number, or whether they're starred. A token can back more than
     * one entry (e.g. a name with two numbers on file) — all of them move
     * together so the row this corresponds to in the UI stays one consistent
     * state rather than silently splitting.
     */
    fun setCanRequestLocation(token: String, canRequestLocation: Boolean) {
        val currentJsonSet = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        val newSet = currentJsonSet.mapNotNull { jsonStr ->
            val entry = ContactEntry.fromJsonString(jsonStr) ?: return@mapNotNull jsonStr
            if (entry.displayToken == token) {
                entry.copy(canRequestLocation = canRequestLocation).toJsonString()
            } else {
                jsonStr
            }
        }.toSet()
        saveNumbers(newSet)
        BackupVault.snapshotAsync(appContext)
    }

    private fun unstar(token: String) {
        val currentStarred = getStarredNumbers().toMutableSet()
        if (currentStarred.contains(token)) {
            currentStarred.remove(token)
            prefs.edit().putStringSet(KEY_STARRED, currentStarred).apply()
        }
    }

    fun replaceAllNumbers(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELIST, numbers).apply()
        BackupVault.snapshotAsync(appContext)
    }

    /**
     * Like [replaceAllNumbers], but takes full [ContactEntry] records instead
     * of bare tokens — used by [BackupVault] restore so a contact added via
     * "Pick from Contacts" (name AND number) keeps its number after
     * restoring on a new device, instead of losing it the way round-tripping
     * through [getNumbers]'s display tokens would.
     */
    fun replaceAllContacts(contacts: List<ContactEntry>) {
        prefs.edit().putStringSet(KEY_WHITELIST, contacts.map { it.toJsonString() }.toSet()).apply()
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

    /**
     * Every emergency contact — the full superset the widget's safety-check
     * and emergency alerts fan out to, regardless of each entry's
     * [ContactEntry.canRequestLocation]. Use [getSharingNumbers] or filter
     * by that field when "who can text me for my location" is what's
     * actually needed.
     */
    fun getContacts(): List<ContactEntry> {
        val strings = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        return strings.mapNotNull { ContactEntry.fromJsonString(it) }
    }

    fun getNumbers(): Set<String> {
        return getContacts().map { it.displayToken }.toSet()
    }

    /**
     * Real, dialable phone numbers for every emergency contact that has one
     * — deliberately including alerts-only entries: the widget's own taps
     * are the one path an alerts-only contact CAN be reached through, since
     * they can't request the location for themselves. Name-only entries
     * (added by typing a handle with no digits, e.g. "Mom") are excluded —
     * [ContactEntry.displayToken] would return their *name*, and sending SMS
     * to that literal string as a destination address silently fails.
     * Callers that need to actually text the whitelist (the widget's SMS
     * fan-out) must use this instead of [getNumbers].
     */
    fun getDialableNumbers(): List<String> =
        getContacts().mapNotNull { it.number.takeIf(String::isNotEmpty) }

    /** [getDialableNumbers], restricted to starred entries. */
    fun getDialableStarredNumbers(): List<String> {
        val starred = getStarredNumbers()
        return getContacts()
            .filter { it.displayToken in starred }
            .mapNotNull { it.number.takeIf(String::isNotEmpty) }
    }

    fun isWhitelisted(number: String): Boolean {
        val cleanIncoming = cleanPhoneNumber(number)
        if (matchesMyNumber(cleanIncoming)) return true
        return getContacts().any { entry ->
            entry.canRequestLocation && numbersMatch(cleanIncoming, entry.number)
        }
    }

    /**
     * Display tokens of contacts allowed to request the location by texting
     * — i.e. [getContacts] filtered to [ContactEntry.canRequestLocation].
     * Narrower than [getNumbers], which returns every emergency contact
     * including alerts-only ones. Used where "who could plausibly have sent
     * this trigger" matters, e.g. diagnostic messages.
     */
    fun getSharingNumbers(): Set<String> =
        getContacts().filter { it.canRequestLocation }.map { it.displayToken }.toSet()

    private fun matchesMyNumber(cleanIncoming: String): Boolean {
        return numbersMatch(cleanIncoming, getMyNumber())
    }

    private fun numbersMatch(cleanA: String, rawB: String): Boolean = PhoneNumbers.match(cleanA, rawB)

    /**
     * @param trustName Whether the caller has verified [displayName] actually
     *   identifies the sender (e.g. resolved from the platform's Contacts
     *   provider by the posting app), rather than being an arbitrary,
     *   sender-controlled string (a self-set chat-app display name, or text
     *   parsed out of the message body itself). When `false`, a name match is
     *   never sufficient on its own — only an exact number match (or matching
     *   the user's own number) authorizes the reply. Without this, anyone
     *   could get a whitelisted user's location by setting their own display
     *   name in any chat app to a name in that user's whitelist (e.g. "Mom")
     *   and sending the trigger word — a real whitelist bypass, since chat
     *   apps don't cryptographically verify a sender's self-declared identity
     *   to this app.
     */
    fun isWhitelistedByName(displayName: String, trustName: Boolean = true): Boolean {
        val nameNorm = normalizeName(displayName)
        val cleanIncoming = cleanPhoneNumber(displayName)
        val contacts = getContacts()

        if (matchesMyNumber(cleanIncoming)) return true

        return contacts.any { entry ->
            entry.canRequestLocation && (
                (trustName && nameNorm.isNotEmpty() && normalizeName(entry.name.orEmpty()) == nameNorm) ||
                    numbersMatch(cleanIncoming, entry.number)
            )
        }
    }

    fun numbersForName(displayName: String): List<String> {
        val nameNorm = normalizeName(displayName)
        if (nameNorm.isEmpty()) return emptyList()
        return getContacts()
            .filter { entry -> normalizeName(entry.name.orEmpty()) == nameNorm }
            .map { it.number }
            .filter { it.isNotEmpty() }
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

            val prefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // Recover anything written to the plaintext fallback during an
            // earlier, transient Keystore failure -- otherwise it's silently
            // orphaned forever the moment Keystore starts working again.
            migratePlaintextFallback(context, ENCRYPTED_PREFS_FILE + "_fallback", prefs)
            prefs
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
