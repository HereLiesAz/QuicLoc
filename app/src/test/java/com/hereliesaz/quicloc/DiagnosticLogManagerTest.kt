package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DiagnosticLogManager serializes every write onto a single background
 * executor (see the class doc), so assertions here poll briefly instead of
 * reading state synchronously right after a call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLogManagerTest {

    private lateinit var context: Context
    private lateinit var diag: DiagnosticLogManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("quicloc_diagnostics")
        context.deleteSharedPreferences("quicloc_diagnostics_fallback")
        diag = DiagnosticLogManager(context)
        diag.clear()
        awaitEmpty()
    }

    private fun event(id: String = java.util.UUID.randomUUID().toString()) = DiagnosticEvent(
        id = id,
        timestamp = System.currentTimeMillis(),
        channel = DiagChannel.SMS,
        source = "SMS",
        rawSender = "+15551234567",
        rawBody = "loc",
        triggerMatched = true,
        whitelistMatched = true,
        extractionPath = null,
        outcome = DiagOutcome.DISPATCHED,
        reason = "test",
    )

    private fun awaitCount(expected: Int, timeoutMs: Long = 2000): List<DiagnosticEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var events = diag.getEvents()
        while (events.size != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            events = diag.getEvents()
        }
        return events
    }

    private fun awaitEmpty() = awaitCount(0)

    @Test
    fun `record then getEvents eventually reflects the write`() {
        diag.record(event())
        val events = awaitCount(1)
        assertEquals(1, events.size)
    }

    @Test
    fun `clear removes previously recorded events`() {
        diag.record(event())
        awaitCount(1)

        diag.clear()
        val events = awaitEmpty()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `updateOutcome patches an existing row by id`() {
        val id = "fixed-id"
        diag.record(event(id))
        awaitCount(1)

        diag.updateOutcome(id, DiagOutcome.REPLY_SENT, "sent")
        val deadline = System.currentTimeMillis() + 2000
        var patched = diag.getEvents().firstOrNull()
        while (patched?.outcome != DiagOutcome.REPLY_SENT && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            patched = diag.getEvents().firstOrNull()
        }
        assertEquals(DiagOutcome.REPLY_SENT, patched?.outcome)
    }
}
