package com.hereliesaz.quicloc.lockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class PinAttemptDecisionTest {

    @Test
    fun `correct pin unlocks regardless of prior fail count`() {
        assertEquals(
            PinAttemptOutcome.Unlocked,
            PinAttemptDecision.evaluate("123456", "123456", failCountBefore = 2)
        )
    }

    @Test
    fun `null expected pin never unlocks`() {
        // Regression test: a removed PIN (expectedPin == null) must never
        // match any entered pin — "" == null is false in Kotlin, but this
        // pins the contract explicitly rather than relying on that being
        // remembered.
        val outcome = PinAttemptDecision.evaluate("123456", null, failCountBefore = 0)
        assertEquals(PinAttemptOutcome.WrongPin(1), outcome)
    }

    @Test
    fun `first and second wrong attempts are just wrong, no escalation`() {
        assertEquals(
            PinAttemptOutcome.WrongPin(1),
            PinAttemptDecision.evaluate("000000", "123456", failCountBefore = 0)
        )
        assertEquals(
            PinAttemptOutcome.WrongPin(2),
            PinAttemptDecision.evaluate("000000", "123456", failCountBefore = 1)
        )
    }

    @Test
    fun `third wrong attempt triggers panic exactly once`() {
        assertEquals(
            PinAttemptOutcome.PanicTriggered(3),
            PinAttemptDecision.evaluate("000000", "123456", failCountBefore = 2)
        )
    }

    @Test
    fun `every wrong attempt after the third is AlreadyLocked, never PanicTriggered again`() {
        // The bug this whole extraction exists to prevent: failCount >= 3
        // used to retrigger a fresh photo capture and a fresh panic-mode MMS
        // on every single wrong PIN past the third, unboundedly.
        for (before in 3..10) {
            val outcome = PinAttemptDecision.evaluate("000000", "123456", failCountBefore = before)
            assertEquals("failCountBefore=$before", PinAttemptOutcome.AlreadyLocked(before + 1), outcome)
        }
    }

    @Test
    fun `correct pin still unlocks even after panic mode was triggered`() {
        assertEquals(
            PinAttemptOutcome.Unlocked,
            PinAttemptDecision.evaluate("123456", "123456", failCountBefore = 5)
        )
    }
}
