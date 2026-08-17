package com.hereliesaz.quicloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Persistent (ongoing) reminder notification that shows the current
 * enabled/disabled state and offers a one-tap toggle action. Lets the user
 * pause QuicLoc without opening the app.
 *
 * Survives app close. Restored after reboot by [BootReceiver].
 */
object ReminderNotification {
    private const val CHANNEL_ID = "quicloc_status"
    private const val NOTIF_ID = 3001

    /**
     * Posts (or updates) the reminder notification iff the user has opted in
     * via [AppSettings.isReminderNotificationEnabled]. If they've opted out
     * but a notification is somehow still present, cancels it.
     */
    fun refresh(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!AppSettings.isReminderNotificationEnabled(context)) {
            nm.cancel(NOTIF_ID)
            return
        }
        ensureChannel(context)
        nm.notify(NOTIF_ID, build(context))
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
    }

    private fun build(context: Context): Notification {
        val enabled = AppSettings.isEnabled(context)

        // Explicit setClass()/setPackage() calls, not just the Intent(context,
        // Class) constructor -- static analysis (CodeQL's implicit-PendingIntent
        // check) pattern-matches on the explicit-targeting method calls
        // themselves rather than crediting the constructor overload, and a
        // truly implicit PendingIntent handed to the system notification
        // manager is interceptable by any app that can match it.
        val toggleIntent = Intent(context, ToggleReceiver::class.java).apply {
            action = ToggleReceiver.ACTION_TOGGLE
            setClass(context, ToggleReceiver::class.java)
            setPackage(context.packageName)
        }
        val togglePending = PendingIntent.getBroadcast(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setClass(context, MainActivity::class.java)
            setPackage(context.packageName)
        }
        val openPending = PendingIntent.getActivity(
            context,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (enabled) "QuicLoc is enabled" else "QuicLoc is disabled"
        val text = if (enabled) {
            "Listening for location requests"
        } else {
            "Triggers are paused — tap Enable to resume"
        }
        val actionLabel = if (enabled) "Disable" else "Enable"

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    actionLabel,
                    togglePending
                ).build()
            )
            .build()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QuicLoc Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether QuicLoc is currently enabled, with a one-tap toggle."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
