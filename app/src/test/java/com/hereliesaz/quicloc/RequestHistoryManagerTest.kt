package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestHistoryManagerTest {

    private lateinit var context: Context
    private lateinit var history: RequestHistoryManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("quicloc_history")
        context.deleteSharedPreferences("quicloc_history_fallback")
        history = RequestHistoryManager(context)
    }

    @Test
    fun `empty history reads as empty list`() {
        assertEquals(emptyList<RequestEvent>(), history.getHistory())
    }

    @Test
    fun `record appends and getHistory returns newest first`() {
        history.record("Alice", "SMS", succeeded = true)
        // Sleep a millisecond so timestamps differ.
        Thread.sleep(2)
        history.record("Bob", "com.whatsapp", succeeded = false)

        val events = history.getHistory()
        assertEquals(2, events.size)
        // Newest first.
        assertEquals("Bob", events[0].sender)
        assertEquals("com.whatsapp", events[0].source)
        assertFalse(events[0].succeeded)
        assertEquals("Alice", events[1].sender)
        assertTrue(events[1].succeeded)
    }

    @Test
    fun `history caps at 100 entries`() {
        repeat(120) { i ->
            history.record("sender$i", "SMS", succeeded = true)
        }
        val events = history.getHistory()
        assertEquals(100, events.size)
        // The most recent 100 are kept; sender0..sender19 evicted.
        assertEquals("sender119", events[0].sender)
        assertEquals("sender20", events[99].sender)
    }

    @Test
    fun `clearHistory empties the log`() {
        history.record("Alice", "SMS", succeeded = true)
        assertEquals(1, history.getHistory().size)
        history.clearHistory()
        assertEquals(0, history.getHistory().size)
    }

    @Test
    fun `formattedTime renders something non-empty`() {
        history.record("Alice", "SMS", succeeded = true)
        val event = history.getHistory().first()
        assertTrue("formattedTime should not be blank", event.formattedTime.isNotBlank())
    }

    @Test
    fun `events persist across manager instances`() {
        history.record("Alice", "SMS", succeeded = true)
        val freshManager = RequestHistoryManager(context)
        assertEquals(1, freshManager.getHistory().size)
        assertEquals("Alice", freshManager.getHistory()[0].sender)
    }
}
