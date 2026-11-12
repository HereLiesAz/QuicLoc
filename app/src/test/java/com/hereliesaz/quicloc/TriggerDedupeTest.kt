package com.hereliesaz.quicloc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TriggerDedupeTest {

    @Test
    fun `a just-handled number is reported as recent`() {
        TriggerDedupe.markHandled("+15551112222")
        assertTrue(TriggerDedupe.wasRecentlyHandled(listOf("+15551112222")))
    }

    @Test
    fun `matching tolerates number formatting differences`() {
        TriggerDedupe.markHandled("+15553334444")
        assertTrue(TriggerDedupe.wasRecentlyHandled(listOf("(555) 333-4444")))
    }

    @Test
    fun `an un-handled number is not reported as recent`() {
        // A number never marked must not be considered a duplicate.
        assertFalse(TriggerDedupe.wasRecentlyHandled(listOf("+15557776666")))
    }

    @Test
    fun `any matching candidate counts`() {
        TriggerDedupe.markHandled("+15558889999")
        assertTrue(
            TriggerDedupe.wasRecentlyHandled(listOf("Mom", "+15558889999"))
        )
    }

    @Test
    fun `does not collapse two different numbers sharing only a 7-digit suffix`() {
        TriggerDedupe.markHandled("+12125550001")
        assertFalse(TriggerDedupe.wasRecentlyHandled(listOf("+17185550001")))
    }
}
