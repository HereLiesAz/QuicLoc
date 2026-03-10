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
        private val MESSAGING_PACKAGES = mapOf(
            "com.whatsapp"                        to "WhatsApp",
            "com.whatsapp.w4b"                    to "WhatsApp Business",
            "org.telegram.messenger"              to "Telegram",
            "org.thoughtcrime.securesms"          to "Signal",
            "com.google.android.apps.messaging"   to "Google Messages",
            "com.samsung.android.messaging"       to "Samsung Messages",
            "com.facebook.orca"                   to "Messenger",
            "com.facebook.mlite"                  to "Messenger Lite",
            "com.viber.voip"                      to "Viber",
            "com.skype.raider"                    to "Skype",
            "com.discord"                         to "Discord",
            "io.github.nickcox.slank"             to "Slack",
            "com.Slack"                           to "Slack",
            "com.microsoft.teams"                 to "Teams",
            "com.snapchat.android"                to "Snapchat",
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val appName = MESSAGING_PACKAGES[packageName] ?: packageName

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
            Log.d(TAG, "Starting LocationReplyService for '$title' via $appName")
            LocationReplyService.startForNotification(
                applicationContext,
                sender = title,
                source = appName,
                action = replyAction
            )
        } else {
            Log.w(TAG, "No reply action found for notification from $packageName")
            RequestHistoryManager(applicationContext).record(
                sender = title,
                source = appName,
                succeeded = false
            )
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
