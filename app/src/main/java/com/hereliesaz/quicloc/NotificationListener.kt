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

        // Any messaging app that provides a standard Android inline reply notification
        // should work automatically. The title of the notification (the sender's name)
        // is checked against the whitelist.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val appName = packageName // Fallback to package name if we don't have a label

        val notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: return

        // Extract message text from notification
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // ... keep the Google Voice special case ...
        if (packageName == "com.google.android.apps.googlevoice") {
            if (title.isNotEmpty() && text.startsWith("$title: ")) {
                text = text.substringAfter("$title: ")
            }
        }

        val body = text.trim().lowercase()
        Log.d(TAG, "Notification from $packageName | title='$title' | body='$body'")

        val whitelistManager = WhitelistManager(applicationContext)
        val passphrase = whitelistManager.getPassphrase()
        val isPassphraseTrigger = passphrase != null && passphrase.isNotEmpty() &&
            (body == "loc ${passphrase.lowercase()}" || body == "quicloc ${passphrase.lowercase()}")

        if (isPassphraseTrigger) {
            Log.d(TAG, "Passphrase trigger from '$title' via $packageName — starting TrackingService")
            whitelistManager.setPassphrase(null) // single-use

            // Start tracking service
            TrackingService.startForNotification(
                applicationContext,
                sender = title,
                source = appName
            )
            return
        }

        if (body != "loc" && body != "quicloc") return

        // Check whitelist — use title as sender identifier (display name or number)
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
