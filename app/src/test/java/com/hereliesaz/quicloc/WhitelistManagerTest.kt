package com.hereliesaz.quicloc

import android.content.Context
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
import org.robolectric.annotation.Config

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

    // ---- dialable numbers (widget SMS fan-out) ---------------------------

    @Test
    fun `getDialableNumbers excludes name-only entries`() {
        whitelist.addNumber("Mom") // no digits -- name-only, no number
        whitelist.addNumber("+15551234567")
        assertEquals(listOf("+15551234567"), whitelist.getDialableNumbers())
    }

    @Test
    fun `getDialableStarredNumbers excludes a starred name-only entry`() {
        whitelist.addContact("Mom", "")
        whitelist.addContact("Dad", "+15551234567")
        whitelist.toggleStarred("Mom")
        whitelist.toggleStarred("Dad")
        assertEquals(listOf("+15551234567"), whitelist.getDialableStarredNumbers())
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
    fun `isWhitelistedByName ignores a leading at-sign on either side`() {
        // Stored without @, sender shows the handle with @ (and vice versa).
        whitelist.addNumber("hereliesaz")
        assertTrue(whitelist.isWhitelistedByName("@hereliesaz"))
        assertTrue(whitelist.isWhitelistedByName("@HereLiesAz"))

        whitelist.addNumber("@dril")
        assertTrue(whitelist.isWhitelistedByName("dril"))
        assertTrue(whitelist.isWhitelistedByName("@DRIL"))
    }

    @Test
    fun `isWhitelistedByName collapses whitespace and trims handles`() {
        whitelist.addNumber("@john doe")
        assertTrue(whitelist.isWhitelistedByName("  John   Doe "))
        assertFalse(whitelist.isWhitelistedByName("johndoe"))
    }

    @Test
    fun `isWhitelistedByName also matches numbers tolerating format`() {
        whitelist.addNumber("+15551234567")
        assertTrue(whitelist.isWhitelistedByName("+15551234567"))
        assertTrue(whitelist.isWhitelistedByName("(555) 123-4567"))
    }

    /**
     * The chat-app fix: a notification shows the sender's contact *name*
     * ("Mom ❤️"), not a phone number. "Pick from Contacts" (no READ_CONTACTS
     * needed — see MainActivity.launchContactPicker) stores the name+number
     * pair locally via addContact, and isWhitelistedByName matches against
     * that local record — never a live Contacts query.
     */
    @Test
    fun `isWhitelistedByName matches a name stored via addContact`() {
        whitelist.addContact("Mom ❤️", "+15551234567")
        assertTrue(whitelist.isWhitelistedByName("Mom ❤️"))
    }

    @Test
    fun `isWhitelistedByName rejects a name that was never stored`() {
        whitelist.addContact("Mom ❤️", "+15551234567")
        assertFalse(whitelist.isWhitelistedByName("Stranger"))
    }

    @Test
    fun `numbersForName returns the stored number for a matching name`() {
        whitelist.addContact("Mom ❤️", "+15551234567")
        assertEquals(listOf("+15551234567"), whitelist.numbersForName("Mom ❤️"))
    }

    // ---- name-spoofing bypass (trustName) ---------------------------------

    @Test
    fun `isWhitelistedByName with trustName=false does not authorize on a name match alone`() {
        // An attacker who isn't whitelisted sets their own chat-app display
        // name to "Mom" -- without the trustName gate this would incorrectly
        // authorize them.
        whitelist.addContact("Mom", "+15551234567")
        assertFalse(whitelist.isWhitelistedByName("Mom", trustName = false))
    }

    @Test
    fun `isWhitelistedByName with trustName=false still matches by number`() {
        whitelist.addContact("Mom", "+15551234567")
        assertTrue(whitelist.isWhitelistedByName("+15551234567", trustName = false))
    }

    @Test
    fun `isWhitelistedByName with trustName=false still matches the user's own number`() {
        whitelist.setMyNumber("+15551234567")
        assertTrue(whitelist.isWhitelistedByName("+15551234567", trustName = false))
    }

    @Test
    fun `isWhitelistedByName defaults to trusting the name`() {
        whitelist.addContact("Mom", "+15551234567")
        assertTrue(whitelist.isWhitelistedByName("Mom"))
    }

    // ---- strict number matching (no suffix-collision bypass) -------------

    @Test
    fun `isWhitelisted does not match a different number sharing only a 7-digit suffix`() {
        // Two different real numbers, same last 7 digits, different area code.
        whitelist.addNumber("+12125551234")
        assertFalse(whitelist.isWhitelisted("+17185551234"))
    }

    @Test
    fun `isWhitelisted still tolerates a missing or extra leading US country code`() {
        whitelist.addNumber("+15551234567")
        assertTrue(whitelist.isWhitelisted("5551234567"))
        assertTrue(whitelist.isWhitelisted("15551234567"))
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

    // ---- emergency vs sharing tier -----------------------------------------

    @Test
    fun `contacts default to being able to request location`() {
        whitelist.addNumber("+15551234567")
        assertTrue(whitelist.getContacts().single().canRequestLocation)
        assertTrue(whitelist.isWhitelisted("+15551234567"))
    }

    @Test
    fun `an alerts-only contact cannot trigger a reply by number`() {
        whitelist.addContact("Grandma", "+15551234567", canRequestLocation = false)
        assertFalse(whitelist.isWhitelisted("+15551234567"))
        assertFalse(whitelist.isWhitelistedByName("Grandma"))
    }

    @Test
    fun `an alerts-only contact still counts as an emergency contact`() {
        // getContacts / getNumbers / getDialableNumbers are the widget's
        // fan-out lists -- alerts-only contacts must still be reachable
        // through them, since the widget is the only way to reach them at all.
        whitelist.addContact("Grandma", "+15551234567", canRequestLocation = false)
        assertEquals(1, whitelist.getContacts().size)
        assertTrue(whitelist.getNumbers().contains("Grandma"))
        assertEquals(listOf("+15551234567"), whitelist.getDialableNumbers())
    }

    @Test
    fun `an alerts-only contact can still be starred for the safety check`() {
        whitelist.addContact("Grandma", "+15551234567", canRequestLocation = false)
        assertTrue(whitelist.toggleStarred("Grandma"))
        assertEquals(listOf("+15551234567"), whitelist.getDialableStarredNumbers())
    }

    @Test
    fun `getSharingNumbers excludes alerts-only contacts`() {
        whitelist.addContact("Mom", "+15551234567")
        whitelist.addContact("Grandma", "+15559999999", canRequestLocation = false)
        assertEquals(setOf("Mom"), whitelist.getSharingNumbers())
        assertEquals(setOf("Mom", "Grandma"), whitelist.getNumbers())
    }

    @Test
    fun `setCanRequestLocation flips an existing contact without touching name or number`() {
        whitelist.addContact("Mom", "+15551234567")
        assertTrue(whitelist.isWhitelisted("+15551234567"))

        whitelist.setCanRequestLocation("Mom", false)
        assertFalse(whitelist.isWhitelisted("+15551234567"))
        assertFalse(whitelist.isWhitelistedByName("Mom"))
        assertEquals("+15551234567", whitelist.getContacts().single().number)

        whitelist.setCanRequestLocation("Mom", true)
        assertTrue(whitelist.isWhitelisted("+15551234567"))
    }

    @Test
    fun `setCanRequestLocation on an unknown token is a no-op`() {
        whitelist.addContact("Mom", "+15551234567")
        whitelist.setCanRequestLocation("Nobody", false)
        assertTrue(whitelist.isWhitelisted("+15551234567"))
        assertEquals(1, whitelist.getContacts().size)
    }

    @Test
    fun `own number can still request location regardless of contact tiers`() {
        whitelist.setMyNumber("+15551234567")
        whitelist.addContact("Grandma", "+15559999999", canRequestLocation = false)
        assertTrue(whitelist.isWhitelisted("+15551234567"))
        assertTrue(whitelist.isWhitelistedByName("+15551234567"))
    }

    @Test
    fun `an alerts-only entry round-trips through toJsonString and fromJsonString`() {
        val entry = WhitelistManager.ContactEntry("Grandma", "+15551234567", canRequestLocation = false)
        val restored = WhitelistManager.ContactEntry.fromJsonString(entry.toJsonString())
        assertEquals(entry, restored)
    }

    @Test
    fun `a legacy plain-token entry with no tier field defaults to can-request`() {
        // Pre-tier backups / legacy data have no "can_request_location" key
        // at all -- must not silently become alerts-only.
        val restored = WhitelistManager.ContactEntry.fromJsonString("+15551234567")
        assertEquals(true, restored?.canRequestLocation)
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
