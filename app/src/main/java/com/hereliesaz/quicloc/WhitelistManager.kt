package com.hereliesaz.quicloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Owner of the sensitive on-device store: whitelist contacts, starred
 * priority list, your own number, find-my-phone passphrase and PIN, and the
 * onboarding-complete flag.
 *
 * Backed by `EncryptedSharedPreferences` (`quicloc_secure_prefs`) — values
 * AES-256-GCM, keys AES-256-SIV, master key in the Android Keystore. If
 * Keystore is unavailable (e.g. Direct Boot before user unlock), falls back
 * to a plain `quicloc_secure_prefs_fallback` so the app doesn't hard-crash.
 *
 * Every mutation method calls [BackupVault.snapshotAsync] so the
 * PIN-encrypted backup blob stays in sync. The vault debounces, so calling
 * `snapshotAsync` from a burst of mutations only writes the blob once.
 *
 * Also handles a one-time migration from a legacy plaintext
 * `quicloc_prefs` store, in case anyone is upgrading from a pre-encryption
 * build.
 */
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

    // Held so mutation methods can re-snapshot the PIN-encrypted backup
    // without needing a context passed in at each call site.
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

    /**
     * Star or un-star [number]. Returns `false` (without changing state) if
     * the user is trying to star a 4th contact — the limit is 3, which
     * matches the widget's 3-tap "Safety Check" semantics.
     */
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
        BackupVault.snapshotAsync(appContext)
        return true
    }

    /**
     * Add a phone number **or display name** to the whitelist. If the input
     * contains at least one digit, it is normalized through [cleanPhoneNumber]
     * (digits and `+` only). If it contains no digits at all (i.e. it is a
     * display name like "Mom"), the trimmed original string is stored as-is
     * so notification-based matching via [isWhitelistedByName] works.
     * Duplicates are silently ignored (`Set` semantics).
     */
    fun addNumber(number: String) {
        val clean = cleanPhoneNumber(number)
        val toStore = if (clean.isEmpty()) number.trim() else clean
        if (toStore.isNotEmpty()) {
            val numbers = getNumbers().toMutableSet()
            numbers.add(toStore)
            saveNumbers(numbers)
            BackupVault.snapshotAsync(appContext)
        }
    }

    /**
     * Remove a number from both the whitelist and the starred set (so the
     * starred set doesn't end up referencing nonexistent entries).
     */
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
        BackupVault.snapshotAsync(appContext)
    }

    /**
     * Used by [BackupVault] during restore. Bypasses the cleanPhoneNumber
     * normalization so display-name entries survive a round trip.
     */
    fun replaceAllNumbers(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELIST, numbers).apply()
        BackupVault.snapshotAsync(appContext)
    }

    /**
     * Used by [BackupVault] during restore.
     */
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

    /**
     * Synchronously clears the single-use passphrase. Use this from the
     * trigger path so a crash between trigger and reboot can't leave the
     * passphrase reusable.
     */
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
     * The full whitelist. Entries may be either normalized phone numbers
     * (from [addNumber]) or arbitrary strings (display names, restored
     * entries via [replaceAllNumbers]). Matching against incoming
     * SMS/notification senders uses [isWhitelisted] or [isWhitelistedByName]
     * respectively.
     */
    fun getNumbers(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    /**
     * Used for SMS: matches by phone number, tolerating formatting differences
     * (+1, dashes, spaces, etc.)
     */
    fun isWhitelisted(number: String): Boolean {
        val cleanIncoming = cleanPhoneNumber(number)
        if (matchesMyNumber(cleanIncoming)) return true
        return getNumbers().any { whitelisted ->
            PhoneNumberUtils.compare(cleanIncoming, whitelisted)
        }
    }

    /**
     * The user's own number is always implicitly whitelisted, so a `loc` they
     * send from another device (or to themselves) is answered without having to
     * add it to the list manually. [getMyNumber] is set during onboarding.
     */
    private fun matchesMyNumber(cleanIncoming: String): Boolean {
        val myNumber = getMyNumber()
        return myNumber.isNotEmpty() && PhoneNumberUtils.compare(cleanIncoming, myNumber)
    }

    /**
     * Used for notifications: matches by display name OR phone number.
     * Notification senders almost always show a contact *name* rather than a
     * raw number, while the whitelist often holds *numbers* (e.g. added via the
     * contact picker, or typed in). Matching strategy, cheapest first:
     *
     *   1. Direct string equality against a whitelist entry (covers whitelisting
     *      a display name like "Mom" when the notification shows exactly "Mom").
     *   2. Phone-number compare (covers apps that surface a raw number).
     *   3. Contacts resolution: look up the incoming display name in the device
     *      Contacts, and if any of that contact's phone numbers is whitelisted,
     *      it's a match. This is what makes a *number-only* whitelist work for
     *      WhatsApp / Messenger / Google Voice, where the sender is a name.
     *
     * Step 3 requires `READ_CONTACTS`; without it we degrade to steps 1–2 (the
     * previous behavior).
     */
    fun isWhitelistedByName(displayName: String): Boolean {
        val nameLower = displayName.trim().lowercase()
        val numbers = getNumbers()

        // The user's own number is always allowed (covers a notification that
        // surfaces the raw number).
        if (matchesMyNumber(cleanPhoneNumber(displayName))) return true

        val directMatch = numbers.any { entry ->
            val entryLower = entry.trim().lowercase()
            entryLower == nameLower ||
                PhoneNumberUtils.compare(cleanPhoneNumber(displayName), cleanPhoneNumber(entry))
        }
        if (directMatch) return true

        // Resolve the notification's display name to the contact's phone
        // numbers, then check those against the whitelist (and the user's own
        // number, for a self-message that shows up as a contact name).
        val contactNumbers = resolveContactNumbers(displayName)
        return contactNumbers.any { contactNumber ->
            matchesMyNumber(cleanPhoneNumber(contactNumber)) ||
                numbers.any { entry -> PhoneNumberUtils.compare(contactNumber, entry) }
        }
    }

    /**
     * Public wrapper over [resolveContactNumbers] so callers (e.g.
     * [NotificationListener]'s cross-path dedupe) can map a notification's
     * display name to the contact's phone numbers. Empty without `READ_CONTACTS`.
     */
    fun numbersForName(displayName: String): List<String> = resolveContactNumbers(displayName)

    /**
     * Looks up [displayName] in the device Contacts and returns every phone
     * number associated with the matching contact(s). Uses
     * [ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI], which matches
     * on name prefix — so "Mom ❤️" still resolves the contact named "Mom".
     * Returns empty if `READ_CONTACTS` isn't granted or nothing matches.
     */
    private fun resolveContactNumbers(displayName: String): List<String> {
        val name = displayName.trim()
        if (name.isEmpty()) return emptyList()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name)
        )
        val results = mutableListOf<String>()
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIdx < 0) return@use
                while (cursor.moveToNext()) {
                    cursor.getString(numberIdx)?.takeIf { it.isNotBlank() }?.let { results.add(it) }
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed for '$name'", e)
            emptyList()
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
