package com.hereliesaz.quicloc

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

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
    }

    private val whitelist by lazy { WhitelistManager(applicationContext) }
    private val history by lazy { RequestHistoryManager(applicationContext) }
    private val recentlyProcessed = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never react to our own notifications (reminder, foreground services).
        if (sbn.packageName == packageName) return

        // System / app-management noise — ignore.
        if (sbn.isOngoing) return

        if (!AppSettings.isEnabled(applicationContext)) {
            Log.d(TAG, "Ignoring notification from ${sbn.packageName} — QuicLoc disabled")
            return
        }

        val notification = sbn.notification ?: return

        val candidates = extractMessages(notification)
        if (candidates.isEmpty()) return

        // Use only the most recent message — older messages in the bundle were
        // already handled when their own notification was posted.
        val latest = candidates.last()
        val sender = latest.sender.trim()
        val body = latest.body.trim().lowercase()

        if (sender.isEmpty() || body.isEmpty()) return

        val dedupeKey = "${sbn.key}|$sender|$body"
        val now = System.currentTimeMillis()
        val lastSeen = recentlyProcessed[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEDUPE_WINDOW_MS) return
        recentlyProcessed[dedupeKey] = now
        // Opportunistic cleanup so the map doesn't grow unboundedly.
        if (recentlyProcessed.size > 200) {
            recentlyProcessed.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        }

        Log.d(TAG, "msg from $sender via ${sbn.packageName}: '$body'")

        val passphrase = whitelist.getPassphrase()
        val isPassphraseTrigger = !passphrase.isNullOrEmpty() &&
            (body == "$TRIGGER_PLAIN ${passphrase.lowercase()}" ||
             body == "$TRIGGER_PREFIXED ${passphrase.lowercase()}")

        if (isPassphraseTrigger) {
            Log.d(TAG, "Passphrase trigger from '$sender' via ${sbn.packageName}")
            whitelist.clearPassphraseSync()
            TrackingService.startForNotification(
                applicationContext,
                sender = sender,
                source = sbn.packageName
            )
            return
        }

        if (body != TRIGGER_PLAIN && body != TRIGGER_PREFIXED) return

        if (!whitelist.isWhitelistedByName(sender)) {
            Log.d(TAG, "Sender '$sender' not whitelisted, ignoring.")
            return
        }

        val replyAction = findReplyAction(notification)
        if (replyAction == null) {
            Log.w(TAG, "No inline-reply action on notification from ${sbn.packageName}")
            history.record(sender, sbn.packageName, succeeded = false)
            return
        }

        LocationReplyService.startForNotification(
            applicationContext,
            sender = sender,
            source = sbn.packageName,
            action = replyAction
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op. Required override.
    }

    /**
     * One extracted message with its sender and body. Group chats and
     * multi-message bundles produce multiple of these; we keep the latest.
     */
    private data class IncomingMessage(val sender: String, val body: String)

    private fun extractMessages(notification: Notification): List<IncomingMessage> {
        val extras: Bundle = notification.extras ?: return emptyList()
        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        // 1. MessagingStyle — the right shape for any modern chat app.
        try {
            val style = Notification.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
            if (style != null) {
                val msgs = style.messages
                if (msgs.isNotEmpty()) {
                    return msgs.map { m ->
                        // Person is null for messages the user sent themselves.
                        // For incoming, person.name is the sender. In 1:1 chats
                        // some apps leave person null and put the sender in the
                        // conversation title — fall back to that.
                        val sender = m.person?.name?.toString().orEmpty()
                            .ifEmpty { style.conversationTitle?.toString().orEmpty() }
                            .ifEmpty { notifTitle }
                        IncomingMessage(sender = sender, body = m.text?.toString().orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MessagingStyle extraction failed", e)
        }

        // 2. EXTRA_TEXT_LINES — used by some apps for bundled inbox-style.
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            return lines.mapNotNull { line ->
                val parsed = parseSenderColonBody(line.toString(), notifTitle)
                if (parsed.body.isEmpty()) null else parsed
            }
        }

        // 3. Plain title + text fallback.
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return emptyList()

        // Google Voice puts "<sender>: <body>" in EXTRA_TEXT.
        if (text.startsWith("$notifTitle: ")) {
            text = text.substringAfter("$notifTitle: ")
        }

        return listOf(IncomingMessage(sender = notifTitle, body = text))
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
