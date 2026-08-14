package com.hereliesaz.quicloc

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PIN-encrypted backup of the user's sensitive settings (whitelist, starred,
 * my_number, passphrase, PIN, onboarding flag). The encrypted blob lives at
 * files/quicloc_backup.qlb so Android Auto Backup ships it to Google Drive
 * automatically, AND it can be exported/imported manually via SAF.
 *
 * Why a custom blob instead of just unencrypting the prefs files: the master
 * key for EncryptedSharedPreferences lives in the Android Keystore and does
 * NOT survive a device restore. So we keep on-device encryption (Keystore)
 * AND add a separate at-rest encrypted blob that DOES survive restore,
 * keyed by something the user remembers — their PIN.
 *
 * Crypto: PBKDF2-HMAC-SHA256(pin, salt, 600k) → AES-256-GCM(plaintext, iv).
 * Each snapshot uses a fresh random salt + IV. The GCM auth tag detects
 * both tampering and wrong-PIN attempts (they're indistinguishable).
 *
 * File layout:
 *   byte  0          : version (currently 1)
 *   bytes 1..16      : PBKDF2 salt (16 bytes)
 *   bytes 17..28     : GCM IV (12 bytes)
 *   bytes 29..end    : AES-256-GCM ciphertext (includes 16-byte auth tag)
 */
object BackupVault {
    private const val TAG = "QuicLoc.Backup"
    const val BACKUP_FILE_NAME = "quicloc_backup.qlb"
    const val MIME_TYPE = "application/octet-stream"

    private const val VERSION: Byte = 1
    private const val ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val HEADER_BYTES = 1 + SALT_BYTES + GCM_IV_BYTES

    // Debounce window for snapshotAsync. Multiple mutations within this
    // span (a restore touches 6+ prefs in rapid succession; a UI batch
    // edit can touch several too) collapse into a single PBKDF2 +
    // encrypt + write.
    private const val SNAPSHOT_DEBOUNCE_MS = 250L

    // Single-threaded scheduler so overlapping mutation calls serialize
    // AND debounce. Snapshot is typically ~100-200ms on a modern phone
    // (PBKDF2 dominates).
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "QuicLoc-Backup").apply { isDaemon = true }
    }
    private val pendingSnapshot = AtomicReference<ScheduledFuture<*>?>()

    /**
     * Internal-storage backup file. Lives in files/ so Auto Backup and
     * device-transfer pick it up by default.
     */
    fun backupFile(context: Context): File =
        File(context.applicationContext.filesDir, BACKUP_FILE_NAME)

    fun isAvailable(context: Context): Boolean = backupFile(context).exists()

    /**
     * Queue a snapshot on the backup thread, debounced. Safe to call from
     * any mutation site, including the UI thread. If multiple calls land
     * within [SNAPSHOT_DEBOUNCE_MS], only the last one actually runs.
     *
     * If no PIN is set, the existing file (if any) is deleted — no PIN
     * means no key, and a stale file would just confuse a later restore
     * attempt.
     */
    fun snapshotAsync(context: Context) {
        val appCtx = context.applicationContext
        val newTask = scheduler.schedule({
            try {
                snapshot(appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot failed", e)
            }
        }, SNAPSHOT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        // Cancel any earlier pending task so only the most recently
        // queued snapshot runs. If a snapshot has already started, the
        // cancel is a no-op and the new task simply queues behind it.
        val previous = pendingSnapshot.getAndSet(newTask)
        previous?.cancel(false)
    }

    /**
     * Force any pending debounced snapshot to run immediately on the
     * caller's thread, then block until it completes. Use this from
     * lifecycle hooks (onPause, etc.) if you want to guarantee the latest
     * state is on disk before the process can be killed.
     */
    fun flush(context: Context) {
        pendingSnapshot.getAndSet(null)?.cancel(false)
        try {
            snapshot(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Flush snapshot failed", e)
        }
    }

    private fun snapshot(context: Context) {
        val whitelist = WhitelistManager(context)
        val pin = whitelist.getPin()
        val file = backupFile(context)
        if (pin.isNullOrEmpty()) {
            if (file.exists()) file.delete()
            return
        }

        val json = JSONObject().apply {
            put("version", 1)
            // Full contact records (name + number), not just display tokens —
            // getNumbers() collapses a name+number entry down to whichever one
            // is the display token, so restoring from that would silently
            // drop the number for any contact added via "Pick from Contacts".
            put("whitelist", JSONArray(whitelist.getContacts().map { it.toJsonString() }))
            put("starred", JSONArray(whitelist.getStarredNumbers().toList()))
            put("my_number", whitelist.getMyNumber())
            put("passphrase", whitelist.getPassphrase() ?: JSONObject.NULL)
            put("pin", pin)
            put("onboarding_completed", whitelist.isOnboardingCompleted())
        }
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
        }
        val ciphertext = cipher.doFinal(plaintext)

        // Write to a tmp file first then rename so a kill mid-write can't
        // leave a half-written backup that fails to decrypt.
        val tmp = File(file.parentFile, "$BACKUP_FILE_NAME.tmp")
        tmp.outputStream().use { out ->
            out.write(VERSION.toInt())
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
        }
        if (!tmp.renameTo(file)) {
            // Fallback: copy + delete if rename fails (e.g. cross-filesystem)
            tmp.inputStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.delete()
        }
    }

    /**
     * Decrypt the on-device backup with [pin] and apply it to the
     * encrypted prefs. Failure cases throw a [RestoreException] whose
     * [RestoreException.category] tells the caller whether retrying with
     * a different PIN could possibly help.
     */
    fun restoreFromInternal(context: Context, pin: String): Result<RestoreSummary> {
        val file = backupFile(context)
        if (!file.exists()) {
            return Result.failure(RestoreException(RestoreException.Category.NO_BACKUP, "No backup file found on this device."))
        }
        return restoreFromBytes(context, file.readBytes(), pin)
    }

    /**
     * Same as [restoreFromInternal] but reads from a user-chosen URI.
     */
    fun restoreFromUri(context: Context, uri: Uri, pin: String): Result<RestoreSummary> {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(RestoreException(RestoreException.Category.IO_ERROR, "Could not read the chosen file."))
        } catch (e: Exception) {
            return Result.failure(RestoreException(RestoreException.Category.IO_ERROR, "Could not open file: ${e.message}"))
        }
        return restoreFromBytes(context, bytes, pin)
    }

    /**
     * Copy the current backup blob to a user-chosen URI for off-device
     * storage (Drive, email, USB, sideload, etc.). Caller must have set
     * a PIN — if there's no backup yet, returns failure.
     */
    fun exportToUri(context: Context, uri: Uri): Result<Unit> {
        val file = backupFile(context)
        if (!file.exists()) {
            return Result.failure(IllegalStateException("No backup yet. Set a PIN first — backups are auto-created once a PIN exists."))
        }
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return Result.failure(IllegalStateException("Could not write to chosen location"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Export failed: ${e.message}"))
        }
    }

    private fun restoreFromBytes(context: Context, bytes: ByteArray, pin: String): Result<RestoreSummary> {
        // 1. Header sanity — distinguishable file-level errors. Wrong-PIN
        //    can never produce these because they don't touch the key.
        if (bytes.size < HEADER_BYTES + GCM_TAG_BYTES) {
            return Result.failure(RestoreException(
                RestoreException.Category.TRUNCATED,
                "This backup file is too short to be valid (${bytes.size} bytes). Try a different file."
            ))
        }
        val version = bytes[0].toInt() and 0xff
        if (version != VERSION.toInt()) {
            return Result.failure(RestoreException(
                RestoreException.Category.UNSUPPORTED_VERSION,
                "This backup is version $version, but this app only understands version ${VERSION.toInt()}. Update QuicLoc or use a different backup."
            ))
        }
        val salt = bytes.copyOfRange(1, 1 + SALT_BYTES)
        val iv = bytes.copyOfRange(1 + SALT_BYTES, HEADER_BYTES)
        val ciphertext = bytes.copyOfRange(HEADER_BYTES, bytes.size)

        // 2. Decrypt. By far the most likely failure here is a wrong PIN;
        //    cryptographic tampering would normally show up as a header
        //    issue above. We label this WRONG_PIN — it's the actionable
        //    case — and note in the message that corruption is a remote
        //    second possibility.
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
        }
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            return Result.failure(RestoreException(
                RestoreException.Category.WRONG_PIN,
                "Incorrect PIN. (If you're certain the PIN is right, the backup file may have been damaged in transit.)"
            ))
        }

        // 3. Decrypted, but the contents don't parse as our JSON shape.
        //    Could happen if the file was crafted by something else with
        //    the same crypto envelope. Vanishingly rare.
        val json = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return Result.failure(RestoreException(
                RestoreException.Category.PARSE_ERROR,
                "Backup decrypted, but its contents don't look like a QuicLoc backup."
            ))
        }

        return try {
            Result.success(applyJson(context, json))
        } catch (e: Exception) {
            Result.failure(RestoreException(
                RestoreException.Category.APPLY_ERROR,
                "Backup decrypted, but applying it failed: ${e.message}"
            ))
        }
    }

    private fun applyJson(context: Context, json: JSONObject): RestoreSummary {
        val whitelist = WhitelistManager(context)

        // Each element is a ContactEntry's JSON-string form (current backups),
        // but WhitelistManager.ContactEntry.fromJsonString also accepts a
        // plain display-token string for backups written before this field
        // carried full contact records, classifying it the same way addNumber
        // does (no digits -> name-only entry; otherwise -> number).
        val whitelistTokens = json.optJSONArray("whitelist")?.toStringSet() ?: emptySet()
        val contacts = whitelistTokens.mapNotNull { WhitelistManager.ContactEntry.fromJsonString(it) }
        whitelist.replaceAllContacts(contacts)

        val starred = json.optJSONArray("starred")?.toStringSet() ?: emptySet()
        whitelist.replaceStarred(starred)

        whitelist.setMyNumber(json.optString("my_number", ""))

        val passphrase = if (json.isNull("passphrase")) {
            null
        } else {
            json.optString("passphrase").takeIf { it.isNotEmpty() }
        }
        whitelist.setPassphrase(passphrase)

        val pin = json.optString("pin").takeIf { it.isNotEmpty() }
        whitelist.setPin(pin)

        whitelist.setOnboardingCompleted(json.optBoolean("onboarding_completed", false))

        return RestoreSummary(
            whitelistCount = contacts.size,
            starredCount = starred.size,
            myNumberSet = whitelist.getMyNumber().isNotEmpty(),
            passphraseSet = passphrase != null,
            pinSet = pin != null,
        )
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapNotNull {
            try { getString(it) } catch (_: Exception) { null }
        }.toSet()

    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    data class RestoreSummary(
        val whitelistCount: Int,
        val starredCount: Int,
        val myNumberSet: Boolean,
        val passphraseSet: Boolean,
        val pinSet: Boolean,
    )

    /**
     * Categorized restore failure. [isRecoverable] is the actionable bit:
     * `true` means "the user can fix this by retrying with a different
     * PIN", `false` means "this file is no good — retry won't help".
     */
    class RestoreException(
        val category: Category,
        message: String,
    ) : Exception(message) {

        val isRecoverable: Boolean = category == Category.WRONG_PIN

        enum class Category {
            /** Internal-backup path: no quicloc_backup.qlb on disk. */
            NO_BACKUP,
            /** Could not open/read the source URI or file. */
            IO_ERROR,
            /** File is shorter than the minimum valid envelope. */
            TRUNCATED,
            /** Version byte is something this app doesn't recognize. */
            UNSUPPORTED_VERSION,
            /** AES-GCM auth failed — almost always wrong PIN. */
            WRONG_PIN,
            /** Decrypted bytes don't parse as the expected JSON. */
            PARSE_ERROR,
            /** Decrypted + parsed, but writing to prefs threw. */
            APPLY_ERROR,
        }
    }
}
