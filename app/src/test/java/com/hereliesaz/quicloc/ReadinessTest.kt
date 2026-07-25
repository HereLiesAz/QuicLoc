package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup checklist is the screen that tells a user whether QuicLoc will
 * actually answer anyone, so its logic is worth pinning down. Pure data in,
 * pure data out — no Robolectric needed.
 */
class ReadinessTest {

    /** Every permission the checklist looks at, all granted. */
    private fun allGranted(): List<PermissionStatus> = listOf(
        Readiness.PERM_RECEIVE_SMS,
        Readiness.PERM_SEND_SMS,
        Readiness.PERM_FINE_LOCATION,
        Readiness.PERM_COARSE_LOCATION,
        Readiness.PERM_BACKGROUND_LOCATION,
        Readiness.PERM_POST_NOTIFICATIONS,
        PermKeys.NOTIF_LISTENER,
        PermKeys.BATTERY,
    ).map { PermissionStatus(it, it, "Runtime", PermStatus.GRANTED) }

    private fun withRevoked(vararg keys: String): List<PermissionStatus> =
        allGranted().map {
            if (it.key in keys) it.copy(state = PermStatus.NOT_GRANTED) else it
        }

    private fun steps(
        enabled: Boolean = true,
        whitelistCount: Int = 1,
        myNumber: String = "+15550100",
        permissions: List<PermissionStatus> = allGranted(),
    ) = Readiness.steps(enabled, whitelistCount, myNumber, permissions)

    @Test
    fun `fully configured app reports ready with nothing outstanding`() {
        val steps = steps()
        assertTrue(Readiness.isReady(steps))
        assertEquals(0, Readiness.requiredRemaining(steps))
        assertEquals(0, Readiness.optionalRemaining(steps))
        assertEquals("QuicLoc is ready", Readiness.headline(steps))
    }

    @Test
    fun `master switch off blocks readiness`() {
        val steps = steps(enabled = false)
        assertFalse(Readiness.isReady(steps))
        assertEquals(StepState.TODO, steps.first { it.id == "enabled" }.state)
        assertEquals("1 step left before QuicLoc works", Readiness.headline(steps))
    }

    @Test
    fun `empty whitelist blocks readiness`() {
        val steps = steps(whitelistCount = 0)
        assertFalse(Readiness.isReady(steps))
        assertEquals(StepState.TODO, steps.first { it.id == "whitelist" }.state)
    }

    @Test
    fun `missing either half of SMS is one step, and points at the missing half`() {
        val sendOnly = steps(permissions = withRevoked(Readiness.PERM_RECEIVE_SMS))
        val smsStep = sendOnly.first { it.id == "sms" }
        assertEquals(StepState.TODO, smsStep.state)
        assertEquals(Readiness.PERM_RECEIVE_SMS, smsStep.actionKey)

        val receiveOnly = steps(permissions = withRevoked(Readiness.PERM_SEND_SMS))
        assertEquals(Readiness.PERM_SEND_SMS, receiveOnly.first { it.id == "sms" }.actionKey)

        // Exactly one row for SMS either way — not two.
        assertEquals(1, sendOnly.count { it.id == "sms" })
    }

    @Test
    fun `coarse location alone satisfies the location step`() {
        val steps = steps(permissions = withRevoked(Readiness.PERM_FINE_LOCATION))
        assertEquals(StepState.DONE, steps.first { it.id == "location" }.state)
    }

    @Test
    fun `losing both location permissions blocks readiness and asks for fine first`() {
        val steps = steps(
            permissions = withRevoked(Readiness.PERM_FINE_LOCATION, Readiness.PERM_COARSE_LOCATION)
        )
        val step = steps.first { it.id == "location" }
        assertEquals(StepState.TODO, step.state)
        assertEquals(Readiness.PERM_FINE_LOCATION, step.actionKey)
        assertFalse(Readiness.isReady(steps))
    }

    @Test
    fun `background location is required when the platform offers it`() {
        val steps = steps(permissions = withRevoked(Readiness.PERM_BACKGROUND_LOCATION))
        val step = steps.first { it.id == "background-location" }
        assertTrue(step.required)
        assertEquals(StepState.TODO, step.state)
        assertFalse(Readiness.isReady(steps))
    }

    @Test
    fun `steps for permissions the platform does not have are omitted, not failed`() {
        // Pre-Android 10 / pre-Android 13: those rows never appear in the
        // permission snapshot, so they must not show up as outstanding work.
        val old = allGranted().filterNot {
            it.key == Readiness.PERM_BACKGROUND_LOCATION || it.key == Readiness.PERM_POST_NOTIFICATIONS
        }
        val steps = steps(permissions = old)
        assertTrue(steps.none { it.id == "background-location" })
        assertTrue(steps.none { it.id == "post-notifications" })
        assertTrue(Readiness.isReady(steps))
    }

    @Test
    fun `notification access and battery are recommended, never required`() {
        val steps = steps(
            permissions = withRevoked(PermKeys.NOTIF_LISTENER, PermKeys.BATTERY)
        )
        assertTrue("chat apps and battery are nice-to-have", Readiness.isReady(steps))
        assertEquals(2, Readiness.optionalRemaining(steps))
        assertFalse(steps.first { it.id == "notification-access" }.required)
        assertFalse(steps.first { it.id == "battery" }.required)
    }

    @Test
    fun `own number is optional and only tracks whether it is set`() {
        assertEquals(StepState.TODO, steps(myNumber = "").first { it.id == "my-number" }.state)
        assertEquals(StepState.TODO, steps(myNumber = "   ").first { it.id == "my-number" }.state)
        assertEquals(StepState.DONE, steps().first { it.id == "my-number" }.state)
        assertFalse(steps(myNumber = "").first { it.id == "my-number" }.required)
        assertTrue(Readiness.isReady(steps(myNumber = "")))
    }

    @Test
    fun `install-time auto-granted permissions count as granted`() {
        val autoGranted = allGranted().map {
            if (it.key == Readiness.PERM_POST_NOTIFICATIONS)
                it.copy(state = PermStatus.AUTO_GRANTED)
            else it
        }
        assertEquals(
            StepState.DONE,
            steps(permissions = autoGranted).first { it.id == "post-notifications" }.state
        )
    }

    @Test
    fun `headline pluralises`() {
        assertEquals(
            "1 step left before QuicLoc works",
            Readiness.headline(steps(enabled = false))
        )
        assertEquals(
            "2 steps left before QuicLoc works",
            Readiness.headline(steps(enabled = false, whitelistCount = 0))
        )
    }

    @Test
    fun `every step has a distinct id and a usable action`() {
        val steps = steps(enabled = false, whitelistCount = 0, myNumber = "", permissions = emptyList())
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
        for (step in steps) {
            assertTrue("step ${step.id} has no title", step.title.isNotBlank())
            assertTrue("step ${step.id} has no detail", step.detail.isNotBlank())
            assertTrue("step ${step.id} has no action key", step.actionKey.isNotBlank())
            assertTrue("step ${step.id} has no action label", step.actionLabel.isNotBlank())
        }
    }

    @Test
    fun `a totally unconfigured install reports every core step outstanding`() {
        // An empty permission list is what a fresh, all-denied install looks
        // like on an old platform: only the steps that don't depend on a
        // platform-specific permission should be present.
        val steps = steps(enabled = false, whitelistCount = 0, myNumber = "", permissions = emptyList())
        assertEquals(
            listOf("enabled", "sms", "location", "whitelist"),
            steps.filter { it.required }.map { it.id }
        )
        assertEquals(4, Readiness.requiredRemaining(steps))
    }
}
