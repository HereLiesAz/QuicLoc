package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit -- no Robolectric, no Play Services types involved. This is
 * exactly the "swap the enter/exit branches and see if anything notices"
 * surface a prior review found completely untested.
 */
class LocNoticeDecisionTest {

    private fun entry(
        notifyOnEnter: Boolean = true,
        notifyOnExit: Boolean = true,
        enabled: Boolean = true,
        contactTokens: Set<String> = setOf("Mom"),
    ) = GeofenceEntry(
        id = "home-id",
        name = "Home",
        latitude = 1.0,
        longitude = 2.0,
        radiusMeters = 150f,
        notifyOnEnter = notifyOnEnter,
        notifyOnExit = notifyOnExit,
        enabled = enabled,
        contactTokens = contactTokens,
    )

    private fun contact(name: String, number: String) =
        WhitelistManager.ContactEntry(name, number)

    @Test
    fun `missing entry is skipped as entry-missing`() {
        val decision = LocNoticeDecision.decide(null, isEnter = true, contacts = emptyList())
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_ENTRY_MISSING, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `disabled entry is skipped as entry-missing`() {
        val decision = LocNoticeDecision.decide(entry(enabled = false), isEnter = true, contacts = listOf(contact("Mom", "+15551234567")))
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_ENTRY_MISSING, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `arrival is skipped when notifyOnEnter is off, even with departure on`() {
        val decision = LocNoticeDecision.decide(
            entry(notifyOnEnter = false, notifyOnExit = true),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_DIRECTION_OFF, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `departure is skipped when notifyOnExit is off, even with arrival on`() {
        val decision = LocNoticeDecision.decide(
            entry(notifyOnEnter = true, notifyOnExit = false),
            isEnter = false,
            contacts = listOf(contact("Mom", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_DIRECTION_OFF, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `arrival sends when notifyOnEnter is on`() {
        val decision = LocNoticeDecision.decide(
            entry(notifyOnEnter = true, notifyOnExit = false),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Send)
        assertEquals("QuicLoc Loc Notice: arrived at \"Home\"", (decision as LocNoticeAction.Send).message)
    }

    @Test
    fun `departure sends when notifyOnExit is on`() {
        val decision = LocNoticeDecision.decide(
            entry(notifyOnEnter = false, notifyOnExit = true),
            isEnter = false,
            contacts = listOf(contact("Mom", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Send)
        assertEquals("QuicLoc Loc Notice: left \"Home\"", (decision as LocNoticeAction.Send).message)
    }

    @Test
    fun `a name-only contact with no number is excluded from recipients`() {
        // The exact trap a prior UI bug let through: a whitelist entry added
        // by typing a handle with no digits has no dialable number.
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = setOf("Mom")),
            isEnter = true,
            contacts = listOf(contact("Mom", "")),
        )
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_NO_CONTACTS, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `no contacts selected at all is skipped as no-contacts`() {
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = emptySet()),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_NO_CONTACTS, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `a renamed contact whose token no longer resolves is treated as no-contacts`() {
        // GeofenceEntry.contactTokens store the display token (name) at
        // selection time; a whitelist rename (remove + re-add) orphans it.
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = setOf("Old Name")),
            isEnter = true,
            contacts = listOf(contact("New Name", "+15551234567")),
        )
        assertTrue(decision is LocNoticeAction.Skip)
        assertEquals(DiagOutcome.LOCNOTICE_NO_CONTACTS, (decision as LocNoticeAction.Skip).outcome)
    }

    @Test
    fun `only tokens matching this entry are included, not every whitelist contact`() {
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = setOf("Mom")),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551111111"), contact("Dad", "+15552222222")),
        )
        assertTrue(decision is LocNoticeAction.Send)
        assertEquals(listOf("+15551111111"), (decision as LocNoticeAction.Send).recipients)
    }

    @Test
    fun `multiple selected contacts all become recipients`() {
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = setOf("Mom", "Dad")),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551111111"), contact("Dad", "+15552222222")),
        )
        assertTrue(decision is LocNoticeAction.Send)
        assertEquals(setOf("+15551111111", "+15552222222"), (decision as LocNoticeAction.Send).recipients.toSet())
    }

    @Test
    fun `a duplicate display token backing two entries contributes both numbers`() {
        // Matches WhitelistManager's own documented "a token can back more
        // than one entry" semantics.
        val decision = LocNoticeDecision.decide(
            entry(contactTokens = setOf("Mom")),
            isEnter = true,
            contacts = listOf(contact("Mom", "+15551111111"), contact("Mom", "+15553333333")),
        )
        assertTrue(decision is LocNoticeAction.Send)
        assertEquals(setOf("+15551111111", "+15553333333"), (decision as LocNoticeAction.Send).recipients.toSet())
    }
}
