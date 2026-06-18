package com.hereliesaz.quicloc

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Listens for notifications from any messaging app and replies via the
 * notification's inline-reply action when a whitelisted contact sends the
 * trigger word "loc" or "quicloc".
 *
 * Handles three notification shapes:
 *   1. [Notification.MessagingStyle] — the preferred shape used by WhatsApp,
 *      Signal, Telegram, Google Messages, etc. Per-message sender + body are
 *      extracted from the last message in the conversation, so group chats
 *      and multi-message bundles work correctly.
 *   2. Plain notifications — title is the sender, EXTRA_TEXT is the body.
 *      Falls back to EXTRA_BIG_TEXT and the last line of EXTRA_TEXT_LINES.
 *   3. Google Voice — strips the "<name>: " prefix from EXTRA_TEXT.
 *
 * Drops duplicates (apps re-post notifications when state changes; we only
 * want to fire once per real new message).
 *
 * Requires "Notification Access" granted in system settings.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "QuicLoc.NotifListener"
        private const val TRIGGER_PLAIN = "loc"
        private const val TRIGGER_PREFIXED = "quicloc"

        // Notifications get re-posted constantly. Drop anything we've already
        // processed for the same key+content within this window.
        private const val DEDUPE_WINDOW_MS = 60_000L

        /**
         * Whether a (lowercased) body is worth logging by default — i.e. it
         * contains the trigger word. Broader than the exact `== "loc"` match so
         * near-misses ("loca", "loc " with stray characters, "John: loc") still
         * get a diagnostic row. Note "quicloc" contains "loc", so one check
         * covers both triggers.
         */
        private fun bodyLooksLikeTrigger(body: String): Boolean =
            body.contains(TRIGGER_PLAIN)
    }

    private val whitelist by lazy { WhitelistManager(applicationContext) }
    private val history by lazy { RequestHistoryManager(applicationContext) }
    private val diag by lazy { DiagnosticLogManager(applicationContext) }
    private val recentlyProcessed = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val captureAll = AppSettings.isDiagCaptureAll(applicationContext)
        val pkg = sbn.packageName

        // Never react to our own notifications (reminder, foreground services).
        if (pkg == packageName) return

        // System / app-management noise — ignore.
        if (sbn.isOngoing) {
            if (captureAll) recordDiag(pkg, "", "", DiagOutcome.ONGOING_IGNORED,
                "Ongoing/system notification — ignored")
            return
        }

        if (!AppSettings.isEnabled(applicationContext)) {
            Log.d(TAG, "Ignoring notification from $pkg — QuicLoc disabled")
            if (captureAll) recordDiag(pkg, "", "", DiagOutcome.APP_DISABLED,
                "QuicLoc master toggle is off")
            return
        }

        val notification = sbn.notification ?: run {
            if (captureAll) recordDiag(pkg, "", "", DiagOutcome.NO_NOTIFICATION_OBJECT,
                "Notification object was null")
            return
        }

        val extraction = extractMessages(notification)
        val candidates = extraction.messages
        if (candidates.isEmpty()) {
            if (captureAll) recordDiag(pkg, "", "", DiagOutcome.NO_MESSAGES_EXTRACTED,
                "Couldn't extract any message. Extras present: ${extrasSummary(notification)}",
                extractionPath = extraction.path)
            return
        }

        // Use only the most recent message — older messages in the bundle were
        // already handled when their own notification was posted.
        val latest = candidates.last()
        val sender = latest.sender.trim()
        val body = latest.body.trim().lowercase()

        if (sender.isEmpty() || body.isEmpty()) {
            if (captureAll) recordDiag(pkg, sender, body, DiagOutcome.EMPTY_SENDER_OR_BODY,
                "Sender or body was blank after extraction. Extras: ${extrasSummary(notification)}",
                extractionPath = extraction.path)
            return
        }

        val looksLikeTrigger = bodyLooksLikeTrigger(body)

        val dedupeKey = "${sbn.key}|$sender|$body"
        val now = System.currentTimeMillis()
        val lastSeen = recentlyProcessed[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEDUPE_WINDOW_MS) {
            if (looksLikeTrigger) recordDiag(pkg, sender, body, DiagOutcome.DEDUPED,
                "Duplicate re-post within ${DEDUPE_WINDOW_MS / 1000}s — already handled",
                extractionPath = extraction.path)
            return
        }
        recentlyProcessed[dedupeKey] = now
        // Opportunistic cleanup so the map doesn't grow unboundedly.
        if (recentlyProcessed.size > 200) {
            recentlyProcessed.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        }

        Log.d(TAG, "msg from $sender via $pkg: '$body'")

        val passphrase = whitelist.getPassphrase()
        val isPassphraseTrigger = !passphrase.isNullOrEmpty() &&
            (body == "$TRIGGER_PLAIN ${passphrase.lowercase()}" ||
             body == "$TRIGGER_PREFIXED ${passphrase.lowercase()}")

        if (isPassphraseTrigger) {
            Log.d(TAG, "Passphrase trigger from '$sender' via $pkg")
            recordDiag(pkg, sender, body, DiagOutcome.PASSPHRASE_TRIGGER,
                "Find-my-phone passphrase matched — starting tracking",
                triggerMatched = true, extractionPath = extraction.path)
            whitelist.clearPassphraseSync()
            TrackingService.startForNotification(
                applicationContext,
                sender = sender,
                source = pkg
            )
            return
        }

        if (body != TRIGGER_PLAIN && body != TRIGGER_PREFIXED) {
            if (looksLikeTrigger || captureAll) recordDiag(pkg, sender, body,
                if (looksLikeTrigger) DiagOutcome.NOT_A_TRIGGER else DiagOutcome.EVALUATED_NO_ACTION,
                if (looksLikeTrigger)
                    "Body resembles but isn't exactly 'loc'/'quicloc' — no reply"
                else "Notification seen; not a trigger word",
                extractionPath = extraction.path)
            return
        }

        if (!whitelist.isWhitelistedByName(sender)) {
            Log.d(TAG, "Sender '$sender' not whitelisted, ignoring.")
            recordDiag(pkg, sender, body, DiagOutcome.WHITELIST_NO_MATCH_BY_NAME,
                "Trigger '$body' from \"$sender\" via $pkg, but sender not in whitelist. " +
                    "Whitelist: [${whitelist.getNumbers().joinToString(", ")}]",
                triggerMatched = true, whitelistMatched = false, extractionPath = extraction.path)
            return
        }

        // A carrier SMS is handled by SmsReceiver too. The default SMS app's
        // notification is the duplicate of that SMS — dedupe against it. (RCS
        // and chat apps never fire SmsReceiver, so they fall through and reply
        // as normal; only the default SMS app's package is checked here.)
        val defaultSms = android.provider.Telephony.Sms.getDefaultSmsPackage(applicationContext)
        if (pkg == defaultSms) {
            val candidates = buildList {
                add(sender)
                addAll(whitelist.numbersForName(sender))
            }
            if (TriggerDedupe.wasRecentlyHandled(candidates)) {
                Log.d(TAG, "Duplicate of an SMS already handled — skipping notification from $pkg")
                recordDiag(pkg, sender, body, DiagOutcome.DUPLICATE_SUPPRESSED,
                    "Same request already handled via SMS — not replying twice",
                    triggerMatched = true, whitelistMatched = true, extractionPath = extraction.path)
                return
            }
            candidates.forEach { TriggerDedupe.markHandled(it) }
        }

        val replyAction = findReplyAction(notification)
        if (replyAction == null) {
            Log.w(TAG, "No inline-reply action on notification from $pkg")
            recordDiag(pkg, sender, body, DiagOutcome.NO_REPLY_ACTION,
                "Whitelisted sender, but this notification from $pkg has no inline-reply " +
                    "action to send the location through",
                triggerMatched = true, whitelistMatched = true, extractionPath = extraction.path)
            history.record(sender, pkg, succeeded = false)
            return
        }

        val diagId = java.util.UUID.randomUUID().toString()
        recordDiag(pkg, sender, body, DiagOutcome.DISPATCHED,
            "Trigger matched, sender whitelisted — fetching location to reply",
            id = diagId, triggerMatched = true, whitelistMatched = true,
            extractionPath = extraction.path)
        LocationReplyService.startForNotification(
            applicationContext,
            sender = sender,
            source = pkg,
            action = replyAction,
            diagId = diagId
        )
    }

    /** Constructs and records a NOTIFICATION-channel diagnostic event. */
    private fun recordDiag(
        pkg: String,
        sender: String,
        body: String,
        outcome: DiagOutcome,
        reason: String,
        id: String = java.util.UUID.randomUUID().toString(),
        triggerMatched: Boolean = false,
        whitelistMatched: Boolean? = null,
        extractionPath: String? = null,
    ) {
        diag.record(
            DiagnosticEvent(
                id = id,
                timestamp = System.currentTimeMillis(),
                channel = DiagChannel.NOTIFICATION,
                source = pkg,
                rawSender = sender,
                rawBody = body.take(80),
                triggerMatched = triggerMatched,
                whitelistMatched = whitelistMatched,
                extractionPath = extractionPath,
                outcome = outcome,
                reason = reason,
            )
        )
    }

    /** Lists which message-bearing extras were present — a debugging clue. */
    private fun extrasSummary(notification: Notification): String {
        val extras = notification.extras ?: return "(none)"
        val present = buildList {
            if (extras.getCharSequence(Notification.EXTRA_TITLE) != null) add("title")
            if (extras.getCharSequence(Notification.EXTRA_TEXT) != null) add("text")
            if (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) add("bigText")
            if (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null) add("textLines")
        }
        return if (present.isEmpty()) "(none)" else present.joinToString(", ")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op. Required override.
    }

    /**
     * One extracted message with its sender and body. Group chats and
     * multi-message bundles produce multiple of these; we keep the latest.
     */
    private data class IncomingMessage(val sender: String, val body: String)

    /**
     * The result of [extractMessages] plus which branch produced it, so the
     * diagnostic log can show *how* a message was (or wasn't) read out of each
     * app's notification. [path] is one of "MessagingStyle", "TextLines",
     * "PlainText", or "None".
     */
    private data class Extraction(val path: String, val messages: List<IncomingMessage>)

    private fun extractMessages(notification: Notification): Extraction {
        val extras: Bundle = notification.extras ?: return Extraction("None", emptyList())
        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        // 1. MessagingStyle — the right shape for any modern chat app.
        //    Use the AndroidX compat version so we don't depend on
        //    platform-class API levels that vary by device SDK.
        try {
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
            if (style != null) {
                val msgs = style.messages
                if (msgs.isNotEmpty()) {
                    return Extraction("MessagingStyle", msgs.map { m ->
                        // Person is null for messages the user sent themselves.
                        // For incoming, person.name is the sender. In 1:1 chats
                        // some apps leave person null and put the sender in the
                        // conversation title — fall back to that.
                        val sender = m.person?.name?.toString().orEmpty()
                            .ifEmpty { style.conversationTitle?.toString().orEmpty() }
                            .ifEmpty { notifTitle }
                        IncomingMessage(sender = sender, body = m.text?.toString().orEmpty())
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MessagingStyle extraction failed", e)
        }

        // 2. EXTRA_TEXT_LINES — used by some apps for bundled inbox-style.
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            return Extraction("TextLines", lines.mapNotNull { line ->
                val parsed = parseSenderColonBody(line.toString(), notifTitle)
                if (parsed.body.isEmpty()) null else parsed
            })
        }

        // 3. Plain title + text fallback.
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return Extraction("None", emptyList())

        // Google Voice puts "<sender>: <body>" in EXTRA_TEXT.
        if (text.startsWith("$notifTitle: ")) {
            text = text.substringAfter("$notifTitle: ")
        }

        return Extraction("PlainText", listOf(IncomingMessage(sender = notifTitle, body = text)))
    }

    /**
     * Splits a "Sender: body" line into its parts. If the line has no colon,
     * the whole line is treated as body with [fallbackSender].
     */
    private fun parseSenderColonBody(line: String, fallbackSender: String): IncomingMessage {
        val idx = line.indexOf(':')
        if (idx > 0 && idx < line.length - 1) {
            return IncomingMessage(
                sender = line.substring(0, idx).trim(),
                body = line.substring(idx + 1).trim()
            )
        }
        return IncomingMessage(sender = fallbackSender, body = line.trim())
    }

    /**
     * Returns the first action on the notification that accepts a
     * RemoteInput — that's the inline "Reply" affordance.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
    }
}
