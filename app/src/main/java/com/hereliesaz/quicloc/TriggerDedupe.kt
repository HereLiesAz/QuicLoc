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

    /**
     * Records that a `loc` reply was just dispatched for [number]. Non-numeric
     * inputs (e.g. a contact display name that a caller passes through) are
     * ignored — only phone numbers are meaningful keys, and
     * [PhoneNumberUtils.compare] is undefined on non-numeric strings.
     */
    fun markHandled(number: String) {
        if (!hasDigits(number)) return
        val now = System.currentTimeMillis()
        handled[number] = now
        cleanUp(now)
    }

    /**
     * True if any (numeric) candidate in [candidates] matches a number handled
     * within the window. Numbers are compared with [PhoneNumberUtils.compare] so
     * differing formats still match; non-numeric candidates are skipped.
     */
    fun wasRecentlyHandled(candidates: Collection<String>): Boolean {
        val now = System.currentTimeMillis()
        val numbers = candidates.filter { hasDigits(it) }
        if (numbers.isEmpty()) return false
        return handled.any { (stored, ts) ->
            now - ts < WINDOW_MS && numbers.any { PhoneNumberUtils.compare(it, stored) }
        }
    }

    private fun hasDigits(s: String): Boolean = s.any { it.isDigit() }

    private fun cleanUp(now: Long) {
        if (handled.size > 50) {
            handled.entries.removeAll { now - it.value > WINDOW_MS }
        }
    }
}
