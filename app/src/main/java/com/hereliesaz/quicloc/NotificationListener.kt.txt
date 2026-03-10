package com.hereliesaz.quicloc

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens for notifications from ANY messaging app and responds via
 * the notification's inline reply action when a whitelisted contact
 * sends the trigger word "loc" or "quicloc".
 *
 * Supports: WhatsApp, Telegram, Signal, Google Messages, Samsung Messages,
 * Facebook Messenger, and any app that posts notifications with a reply action.
 *
 * Requires: User grants "Notification Access" to QuicLoc in system settings.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "QuicLoc.NotifListener"

        // Packages we care about — covers the major messaging apps.
        // Empty set = listen to all apps (broader but noisier).
        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",               // WhatsApp Business
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",      // Signal
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging",
            "com.facebook.orca",               // Messenger
            "com.facebook.mlite",
            "com.viber.voip",
            "com.skype.raider",
            "com.discord",
            "io.github.nickcox.slank",         // Slack
            "com.Slack",
            "com.microsoft.teams",
            "com.snapchat.android",
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Filter to known messaging apps (remove this check to catch everything)
        if (packageName !in MESSAGING_PACKAGES) return

        val notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: return

        // Extract message text from notification
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val body = text.trim().lowercase()
        Log.d(TAG, "Notification from $packageName | title='$title' | body='$body'")

        if (body != "loc" && body != "quicloc") return

        // Check whitelist — use title as sender identifier (display name or number)
        val whitelistManager = WhitelistManager(applicationContext)
        if (!whitelistManager.isWhitelistedByName(title)) {
            Log.d(TAG, "Sender '$title' not whitelisted, ignoring.")
            return
        }

        Log.d(TAG, "Trigger from '$title' via $packageName — fetching location")

        // Get the inline reply action from the notification
        val replyAction = findReplyAction(notification)

        if (replyAction != null) {
            // Reply via the notification's own reply action (works for WhatsApp, Signal, etc.)
            LocationHelper.getCurrentLocationAndReplyViaNotification(
                applicationContext,
                replyAction,
                sbn.key
            )
        } else {
            Log.w(TAG, "No reply action found for notification from $packageName")
        }
    }

    /**
     * Finds the inline reply RemoteInput action on a notification.
     * This is the action that powers "Reply" from notification shade.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed, but required to override
    }
}
