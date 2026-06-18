package com.hereliesaz.quicloc

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhitelistManagerTest {

    private lateinit var context: Context
    private lateinit var whitelist: WhitelistManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("quicloc_secure_prefs")
        context.deleteSharedPreferences("quicloc_secure_prefs_fallback")
        BackupVault.backupFile(context).delete()
        whitelist = WhitelistManager(context)
    }

    @After
    fun cleanup() {
        BackupVault.backupFile(context).delete()
    }

    // ---- add / remove ----------------------------------------------------

    @Test
    fun `add then remove a number`() {
        val n = "1234567890"
        whitelist.addNumber(n)
        assertTrue(whitelist.isWhitelisted(n))

        whitelist.removeNumber(n)
        assertFalse(whitelist.isWhitelisted(n))
    }

    @Test
    fun `add normalizes formatting characters`() {
        whitelist.addNumber("123-456-7890")
        assertTrue(whitelist.isWhitelisted("1234567890"))
        assertTrue(whitelist.isWhitelisted("(123) 456-7890"))
    }

    @Test
    fun `addNumber stores display name when input has no digits`() {
        whitelist.addNumber("Mom")
        assertTrue(whitelist.getNumbers().contains("Mom"))
    }

    @Test
    fun `addNumber stores trimmed display name`() {
        whitelist.addNumber("  Mom  ")
        assertTrue(whitelist.getNumbers().contains("Mom"))
        assertEquals(1, whitelist.getNumbers().size)
    }

    @Test
    fun `addNumber with display name enables isWhitelistedByName matching`() {
        whitelist.addNumber("Mom")
        assertTrue(whitelist.isWhitelistedByName("Mom"))
        assertTrue(whitelist.isWhitelistedByName("mom"))
        assertTrue(whitelist.isWhitelistedByName("MOM"))
    }

    @Test
    fun `addNumber with display name and phone number stores both`() {
        whitelist.addNumber("Mom")
        whitelist.addNumber("+15551234567")
        assertEquals(2, whitelist.getNumbers().size)
        assertTrue(whitelist.getNumbers().contains("Mom"))
        assertTrue(whitelist.isWhitelisted("+15551234567"))
        assertTrue(whitelist.isWhitelistedByName("Mom"))
    }

    @Test
    fun `addNumber drops blank and whitespace-only input`() {
        whitelist.addNumber("")
        whitelist.addNumber("   ")
        assertTrue(whitelist.getNumbers().isEmpty())
    }

    @Test
    fun `add ignores empty input`() {
        whitelist.addNumber("")
        assertTrue(whitelist.getNumbers().isEmpty())
    }

    @Test
    fun `add deduplicates Set semantics`() {
        whitelist.addNumber("+15551234567")
        whitelist.addNumber("+15551234567")
        assertEquals(1, whitelist.getNumbers().size)
    }

    @Test
    fun `removing a number also un-stars it`() {
        whitelist.addNumber("Alice")
        whitelist.toggleStarred("Alice")
        assertTrue(whitelist.getStarredNumbers().contains("Alice"))

        whitelist.removeNumber("Alice")
        assertFalse(whitelist.getStarredNumbers().contains("Alice"))
    }

    // ---- starred limit ---------------------------------------------------

    @Test
    fun `starring more than 3 is rejected`() {
        assertTrue(whitelist.toggleStarred("111"))
        assertTrue(whitelist.toggleStarred("222"))
        assertTrue(whitelist.toggleStarred("333"))
        assertFalse(whitelist.toggleStarred("444"))
        assertEquals(3, whitelist.getStarredNumbers().size)
    }

    @Test
    fun `un-starring frees a slot for someone new`() {
        whitelist.toggleStarred("111")
        whitelist.toggleStarred("222")
        whitelist.toggleStarred("333")
        whitelist.toggleStarred("222") // unstar
        assertTrue(whitelist.toggleStarred("444"))
        assertEquals(setOf("111", "333", "444"), whitelist.getStarredNumbers())
    }

    // ---- name vs number matching ----------------------------------------

    @Test
    fun `isWhitelistedByName matches case-insensitive display name`() {
        whitelist.addNumber("Alice")
        assertTrue(whitelist.isWhitelistedByName("alice"))
        assertTrue(whitelist.isWhitelistedByName("ALICE"))
        assertTrue(whitelist.isWhitelistedByName("Alice"))
        assertFalse(whitelist.isWhitelistedByName("Alyssa"))
    }

    @Test
    fun `isWhitelistedByName also matches numbers tolerating format`() {
        whitelist.addNumber("+15551234567")
        assertTrue(whitelist.isWhitelistedByName("+15551234567"))
        assertTrue(whitelist.isWhitelistedByName("(555) 123-4567"))
    }

    /**
     * The chat-app fix: the whitelist holds only a phone *number*, but the
     * notification surfaces a contact *name*. With READ_CONTACTS granted, the
     * name resolves through Contacts to the number, which is whitelisted.
     */
    @Test
    fun `isWhitelistedByName resolves a contact name to a whitelisted number`() {
        grantContacts()
        // Notification shows "Mom ❤️"; the contact's number is +15551234567.
        stubContactNumbers("Mom ❤️", "+15551234567")
        whitelist.addNumber("+15551234567")

        assertTrue(whitelist.isWhitelistedByName("Mom ❤️"))
    }

    @Test
    fun `isWhitelistedByName rejects a contact whose number isn't whitelisted`() {
        grantContacts()
        stubContactNumbers("Stranger", "+15559999999")
        whitelist.addNumber("+15551234567")

        assertFalse(whitelist.isWhitelistedByName("Stranger"))
    }

    @Test
    fun `isWhitelistedByName without READ_CONTACTS falls back to direct matching`() {
        // No permission granted: contact resolution is skipped, so a
        // number-only whitelist can't match a name.
        stubContactNumbers("Mom", "+15551234567")
        whitelist.addNumber("+15551234567")

        assertFalse(whitelist.isWhitelistedByName("Mom"))
    }

    // ---- own number is implicitly whitelisted ---------------------------

    @Test
    fun `own number is whitelisted without being in the list`() {
        whitelist.setMyNumber("+15551234567")
        assertFalse(whitelist.getNumbers().contains("+15551234567"))
        assertTrue(whitelist.isWhitelisted("+15551234567"))
        assertTrue(whitelist.isWhitelisted("(555) 123-4567"))
        assertTrue(whitelist.isWhitelistedByName("+15551234567"))
    }

    @Test
    fun `unset own number does not match`() {
        // myNumber defaults to empty — must not whitelist everyone.
        assertFalse(whitelist.isWhitelisted("+15551234567"))
    }

    private fun grantContacts() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    /** Registers a contacts lookup result for [displayName] → [numbers]. */
    private fun stubContactNumbers(displayName: String, vararg numbers: String) {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(displayName)
        )
        // ShadowContentResolver.setCursor requires Robolectric's BaseCursor.
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(ContactsContract.CommonDataKinds.Phone.NUMBER))
        cursor.setResults(numbers.map { arrayOf<Any>(it) }.toTypedArray())
        shadowOf(context.contentResolver).setCursor(uri, cursor)
    }

    // ---- bulk replace (used by BackupVault restore) ---------------------

    @Test
    fun `replaceAllNumbers wipes and resets`() {
        whitelist.addNumber("Alice")
        whitelist.addNumber("Bob")
        whitelist.replaceAllNumbers(setOf("Carol"))
        assertEquals(setOf("Carol"), whitelist.getNumbers())
    }

    @Test
    fun `replaceStarred wipes and resets`() {
        whitelist.toggleStarred("Alice")
        whitelist.toggleStarred("Bob")
        whitelist.replaceStarred(setOf("Carol"))
        assertEquals(setOf("Carol"), whitelist.getStarredNumbers())
    }

    // ---- my number, passphrase, PIN -------------------------------------

    @Test
    fun `myNumber defaults to empty string`() {
        assertEquals("", whitelist.getMyNumber())
    }

    @Test
    fun `myNumber persists`() {
        whitelist.setMyNumber("+15551234567")
        assertEquals("+15551234567", whitelist.getMyNumber())
    }

    @Test
    fun `passphrase defaults to null`() {
        assertNull(whitelist.getPassphrase())
    }

    @Test
    fun `passphrase round-trips and clears synchronously`() {
        whitelist.setPassphrase("my secret passphrase")
        assertEquals("my secret passphrase", whitelist.getPassphrase())
        whitelist.clearPassphraseSync()
        assertNull(whitelist.getPassphrase())
    }

    @Test
    fun `pin defaults to null and round-trips`() {
        assertNull(whitelist.getPin())
        whitelist.setPin("123456")
        assertEquals("123456", whitelist.getPin())
        whitelist.setPin(null)
        assertNull(whitelist.getPin())
    }

    // ---- onboarding flag -------------------------------------------------

    @Test
    fun `onboarding flag defaults to false and round-trips`() {
        assertFalse(whitelist.isOnboardingCompleted())
        whitelist.setOnboardingCompleted(true)
        assertTrue(whitelist.isOnboardingCompleted())
    }

    // ---- persistence across instances ------------------------------------

    @Test
    fun `data persists across new WhitelistManager instances`() {
        whitelist.addNumber("Alice")
        whitelist.setMyNumber("+15551234567")
        whitelist.setPin("123456")

        val fresh = WhitelistManager(context)
        assertEquals(setOf("Alice"), fresh.getNumbers())
        assertEquals("+15551234567", fresh.getMyNumber())
        assertEquals("123456", fresh.getPin())
    }
}
