package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the BackupVault encrypted-blob lifecycle: snapshot writes, error
 * categories, wrong-PIN detection, manual export/import round-trip, and
 * the PIN-change regenerate behavior.
 *
 * Uses [BackupVault.flush] to force synchronous snapshots so tests don't
 * have to wait on the 250 ms debounce window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupVaultTest {

    private lateinit var context: Context
    private lateinit var whitelist: WhitelistManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Make sure each test starts with no backup file and clean prefs.
        BackupVault.backupFile(context).delete()
        context.deleteSharedPreferences("quicloc_secure_prefs")
        context.deleteSharedPreferences("quicloc_secure_prefs_fallback")
        whitelist = WhitelistManager(context)
    }

    @After
    fun cleanup() {
        BackupVault.backupFile(context).delete()
    }

    // ---- file lifecycle --------------------------------------------------

    @Test
    fun `isAvailable returns false when no backup file exists`() {
        assertFalse(BackupVault.isAvailable(context))
    }

    @Test
    fun `no backup file is created when PIN is unset`() {
        whitelist.addNumber("+15551234567")
        BackupVault.flush(context)
        assertFalse(BackupVault.isAvailable(context))
    }

    @Test
    fun `backup file appears once PIN is set`() {
        whitelist.setPin("123456")
        BackupVault.flush(context)
        assertTrue(BackupVault.isAvailable(context))
        assertTrue(BackupVault.backupFile(context).length() > 0)
    }

    @Test
    fun `backup file is deleted when PIN is cleared`() {
        whitelist.setPin("123456")
        BackupVault.flush(context)
        assertTrue(BackupVault.isAvailable(context))

        whitelist.setPin(null)
        BackupVault.flush(context)
        assertFalse(BackupVault.isAvailable(context))
    }

    @Test
    fun `changing the PIN regenerates the blob with new salt and IV`() {
        whitelist.setPin("123456")
        BackupVault.flush(context)
        val firstBytes = BackupVault.backupFile(context).readBytes()

        whitelist.setPin("654321")
        BackupVault.flush(context)
        val secondBytes = BackupVault.backupFile(context).readBytes()

        // Same version byte (offset 0), but salt + IV + ciphertext should
        // all differ since they're random per snapshot.
        assertEquals(firstBytes[0], secondBytes[0])
        assertFalse(firstBytes.contentEquals(secondBytes))
    }

    // ---- round-trip ------------------------------------------------------

    @Test
    fun `restore with correct PIN brings everything back`() {
        whitelist.setPin("123456")
        whitelist.setPassphrase("my-secret-passphrase-here")
        whitelist.addNumber("+15551234567")
        whitelist.addNumber("Alice")
        whitelist.toggleStarred("Alice")
        whitelist.setMyNumber("+15559999999")
        whitelist.setOnboardingCompleted(true)
        BackupVault.flush(context)

        val savedBytes = BackupVault.backupFile(context).readBytes()
        assertTrue(savedBytes.isNotEmpty())

        // Wipe the prefs directly so the snapshot file isn't deleted as
        // a side effect of clearing the PIN through WhitelistManager.
        context.deleteSharedPreferences("quicloc_secure_prefs")
        context.deleteSharedPreferences("quicloc_secure_prefs_fallback")
        // Restore the blob (the WhitelistManager constructor below would
        // otherwise see an empty PIN and trigger a debounced delete on
        // its next snapshot — we beat that by restoring immediately).
        BackupVault.backupFile(context).writeBytes(savedBytes)

        val freshWhitelist = WhitelistManager(context)
        assertTrue(freshWhitelist.getNumbers().isEmpty())
        assertNull(freshWhitelist.getPin())

        val result = BackupVault.restoreFromInternal(context, "123456")
        assertTrue("restore failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val summary = result.getOrNull()!!
        assertEquals(2, summary.whitelistCount)
        assertEquals(1, summary.starredCount)
        assertTrue(summary.myNumberSet)
        assertTrue(summary.pinSet)
        assertTrue(summary.passphraseSet)

        val restored = WhitelistManager(context)
        assertEquals(setOf("+15551234567", "Alice"), restored.getNumbers())
        assertEquals(setOf("Alice"), restored.getStarredNumbers())
        assertEquals("+15559999999", restored.getMyNumber())
        assertEquals("123456", restored.getPin())
        assertEquals("my-secret-passphrase-here", restored.getPassphrase())
        assertTrue(restored.isOnboardingCompleted())
    }

    @Test
    fun `restore preserves the phone number of a name+number contact`() {
        // The regression this guards: snapshot() used to store only display
        // tokens, so a contact added via "Pick from Contacts" (both a name
        // AND a number) would restore as a name-only entry with no number,
        // silently breaking the SMS trigger for that contact on a new device.
        whitelist.setPin("123456")
        whitelist.addContact("Mom", "+15551234567")
        BackupVault.flush(context)
        val savedBytes = BackupVault.backupFile(context).readBytes()

        context.deleteSharedPreferences("quicloc_secure_prefs")
        context.deleteSharedPreferences("quicloc_secure_prefs_fallback")
        BackupVault.backupFile(context).writeBytes(savedBytes)

        val result = BackupVault.restoreFromInternal(context, "123456")
        assertTrue("restore failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrNull()!!.whitelistCount)

        val restored = WhitelistManager(context)
        assertTrue(restored.isWhitelisted("+15551234567"))
        assertEquals(listOf("+15551234567"), restored.getDialableNumbers())
        assertTrue(restored.isWhitelistedByName("Mom"))
    }

    @Test
    fun `export and import via byte arrays round-trip cleanly`() {
        whitelist.setPin("111111")
        whitelist.addNumber("+15551234567")
        BackupVault.flush(context)

        val exportedBytes = BackupVault.backupFile(context).readBytes()

        // Simulate moving to a fresh device.
        context.deleteSharedPreferences("quicloc_secure_prefs")
        context.deleteSharedPreferences("quicloc_secure_prefs_fallback")
        BackupVault.backupFile(context).delete()

        // Write the export to a different location and restore from it
        // via the URI path.
        val importLocation = File(context.cacheDir, "imported-backup.qlb")
        importLocation.writeBytes(exportedBytes)

        val result = BackupVault.restoreFromUri(
            context,
            android.net.Uri.fromFile(importLocation),
            "111111"
        )
        assertTrue("restore failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrNull()!!.whitelistCount)
    }

    // ---- error categories ------------------------------------------------

    @Test
    fun `restore returns NO_BACKUP when file is missing`() {
        BackupVault.backupFile(context).delete()
        val result = BackupVault.restoreFromInternal(context, "123456")
        assertCategory(result, BackupVault.RestoreException.Category.NO_BACKUP)
    }

    @Test
    fun `restore returns TRUNCATED when file is too short`() {
        BackupVault.backupFile(context).writeBytes(ByteArray(10))
        val result = BackupVault.restoreFromInternal(context, "123456")
        assertCategory(result, BackupVault.RestoreException.Category.TRUNCATED)
    }

    @Test
    fun `restore returns UNSUPPORTED_VERSION when version byte is unknown`() {
        // Header-shaped file with version=99 followed by enough zero bytes
        // to pass the length check. AES-GCM never even runs.
        val bogus = ByteArray(1 + 16 + 12 + 16) // header + minimum tag space
        bogus[0] = 99.toByte()
        BackupVault.backupFile(context).writeBytes(bogus)

        val result = BackupVault.restoreFromInternal(context, "123456")
        assertCategory(result, BackupVault.RestoreException.Category.UNSUPPORTED_VERSION)
    }

    @Test
    fun `restore returns WRONG_PIN when GCM auth fails`() {
        whitelist.setPin("123456")
        whitelist.addNumber("+15551234567")
        BackupVault.flush(context)

        val result = BackupVault.restoreFromInternal(context, "999999")
        assertCategory(result, BackupVault.RestoreException.Category.WRONG_PIN)
    }

    @Test
    fun `WRONG_PIN is the only recoverable category`() {
        // All other categories should report isRecoverable = false.
        val categories = BackupVault.RestoreException.Category.values()
        for (cat in categories) {
            val ex = BackupVault.RestoreException(cat, "test")
            if (cat == BackupVault.RestoreException.Category.WRONG_PIN) {
                assertTrue("WRONG_PIN should be recoverable", ex.isRecoverable)
            } else {
                assertFalse("$cat should not be recoverable", ex.isRecoverable)
            }
        }
    }

    @Test
    fun `restore failure carries a non-empty message`() {
        val result = BackupVault.restoreFromInternal(context, "123456")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("message should be non-empty", !ex!!.message.isNullOrBlank())
    }

    // ---- export semantics ------------------------------------------------

    @Test
    fun `export fails gracefully when no backup exists`() {
        val target = File(context.cacheDir, "out.qlb")
        val result = BackupVault.exportToUri(context, android.net.Uri.fromFile(target))
        assertTrue(result.isFailure)
    }

    @Test
    fun `export copies the blob verbatim`() {
        whitelist.setPin("123456")
        BackupVault.flush(context)

        val target = File(context.cacheDir, "out.qlb")
        val result = BackupVault.exportToUri(context, android.net.Uri.fromFile(target))
        assertTrue(result.isSuccess)

        assertTrue(target.exists())
        assertTrue(
            "exported file should match the backup byte-for-byte",
            BackupVault.backupFile(context).readBytes().contentEquals(target.readBytes())
        )
    }

    // ---- helpers ---------------------------------------------------------

    private fun assertCategory(
        result: Result<BackupVault.RestoreSummary>,
        expected: BackupVault.RestoreException.Category,
    ) {
        assertTrue("expected failure, got success", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected RestoreException, got ${ex?.javaClass?.simpleName}",
            ex is BackupVault.RestoreException
        )
        assertEquals(expected, (ex as BackupVault.RestoreException).category)
    }
}
