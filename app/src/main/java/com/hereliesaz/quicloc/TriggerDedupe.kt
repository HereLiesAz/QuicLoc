package com.hereliesaz.quicloc

import android.telephony.PhoneNumberUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide guard against replying twice to the same `loc` request.
 *
 * A single carrier SMS is delivered to QuicLoc through *both* intake paths:
 * [SmsReceiver] (the `SMS_RECEIVED` broadcast) and [NotificationListener] (the
 * default SMS app posts a notification for that same message). Without
 * coordination each path fetches GPS and replies independently, so the sender
 * gets two location texts.
 *
 * Both paths live in the same process, so a shared in-memory map is enough —
 * the duplicate always arrives within a second or two, never across a process
 * restart. Whichever path handles a sender first calls [markHandled]; the other
 * sees [wasRecentlyHandled] and defers. Matching is by phone number with
 * [PhoneNumberUtils.compare] so formatting differences (`(504) 326-9451` vs
 * `5043269451`) still collapse to one.
 */
object TriggerDedupe {

    private const val WINDOW_MS = 15_000L

    // Normalized-ish number string -> last-handled timestamp.
    private val handled = ConcurrentHashMap<String, Long>()

    /** Records that a `loc` reply was just dispatched for [number]. */
    fun markHandled(number: String) {
        val now = System.currentTimeMillis()
        handled[number] = now
        cleanUp(now)
    }

    /**
     * True if any of [candidates] matches a number handled within the window.
     * Numbers are compared with [PhoneNumberUtils.compare] so differing formats
     * still match.
     */
    fun wasRecentlyHandled(candidates: Collection<String>): Boolean {
        val now = System.currentTimeMillis()
        return handled.any { (stored, ts) ->
            now - ts < WINDOW_MS && candidates.any { PhoneNumberUtils.compare(it, stored) }
        }
    }

    private fun cleanUp(now: Long) {
        if (handled.size > 50) {
            handled.entries.removeAll { now - it.value > WINDOW_MS }
        }
    }
}
