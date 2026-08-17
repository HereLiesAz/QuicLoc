package com.hereliesaz.quicloc.lockdown

/**
 * What a PIN attempt on [TrackingLockActivity]'s lock screen should do, as a
 * pure function of the attempt and the fail count so far — extracted out of
 * the activity so the escalate-exactly-once-at-3 rule is directly
 * unit-testable without Compose or Robolectric.
 */
sealed class PinAttemptOutcome {
    /** The correct PIN was entered — stop tracking. */
    object Unlocked : PinAttemptOutcome()

    /** Wrong PIN, still under the panic threshold. */
    data class WrongPin(val failCount: Int) : PinAttemptOutcome()

    /**
     * Wrong PIN, and this is the exact attempt that crosses the panic
     * threshold — escalate [TrackingService] into panic mode. Returned only
     * once per lock session: further wrong attempts after this one are
     * [AlreadyLocked], not another [PanicTriggered].
     */
    data class PanicTriggered(val failCount: Int) : PinAttemptOutcome()

    /** Wrong PIN, already past the panic threshold — nothing new to do. */
    data class AlreadyLocked(val failCount: Int) : PinAttemptOutcome()
}

object PinAttemptDecision {
    /**
     * Number of wrong attempts that triggers panic mode. Matches
     * [TrackingLockActivity]'s documented "3 wrong PIN entries" behavior.
     */
    const val PANIC_THRESHOLD = 3

    /**
     * @param pin what was just entered.
     * @param expectedPin the currently-set QuicLoc PIN, or null if none is
     *   set (e.g. it was removed — see `MainActivity.onClearAppPin`). A null
     *   [expectedPin] can never match any [pin], by design: there is no
     *   "any PIN works" fallback.
     * @param failCountBefore the fail count going into this attempt.
     */
    fun evaluate(pin: String, expectedPin: String?, failCountBefore: Int): PinAttemptOutcome {
        if (expectedPin != null && pin == expectedPin) return PinAttemptOutcome.Unlocked
        val failCount = failCountBefore + 1
        return when {
            failCount == PANIC_THRESHOLD -> PinAttemptOutcome.PanicTriggered(failCount)
            failCount > PANIC_THRESHOLD -> PinAttemptOutcome.AlreadyLocked(failCount)
            else -> PinAttemptOutcome.WrongPin(failCount)
        }
    }
}
