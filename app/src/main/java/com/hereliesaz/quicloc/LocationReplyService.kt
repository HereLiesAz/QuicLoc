package com.hereliesaz.quicloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that fetches the device's location and sends a reply.
 *
 * Why a Service instead of goAsync():
 *   BroadcastReceiver.goAsync() only buys 10 seconds before Android kills the
 *   process. GPS acquisition from a cold start can take 20-30 seconds in poor
 *   conditions. A foreground service has no such time limit and survives as long
 *   as needed (up to our own 30-second timeout), then stops itself.
 *
 * Started by SmsReceiver and NotificationListener. Stops itself once a reply
 * is sent or the timeout is reached.
 */
class LocationReplyService : Service() {

    companion object {
        private const val TAG = "QuicLoc.ReplyService"
        private const val CHANNEL_ID = "quicloc_reply"
        private const val NOTIF_ID = 1001

        const val EXTRA_SENDER = "sender"
        const val EXTRA_SOURCE = "source"         // "SMS", "WhatsApp", etc.
        const val EXTRA_REPLY_MODE = "reply_mode" // "sms" or "notification"

        // Parcelable-friendly: pass notification action fields separately
        // (Notification.Action isn't easily parcelable across process boundaries)
        // For notification replies we pass the action via a static holder instead.
        var pendingNotificationAction: Notification.Action? = null

        fun startForSms(context: Context, sender: String) {
            val intent = Intent(context, LocationReplyService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, "SMS")
                putExtra(EXTRA_REPLY_MODE, "sms")
            }
            context.startForegroundService(intent)
        }

        fun startForNotification(
            context: Context,
            sender: String,
            source: String,
            action: Notification.Action
        ) {
            pendingNotificationAction = action
            val intent = Intent(context, LocationReplyService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_REPLY_MODE, "notification")
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Fetching location…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sender = intent.getStringExtra(EXTRA_SENDER) ?: run { stopSelf(); return START_NOT_STICKY }
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "Unknown"
        val replyMode = intent.getStringExtra(EXTRA_REPLY_MODE) ?: "sms"

        Log.d(TAG, "Starting location fetch for $sender via $source (mode=$replyMode)")

        when (replyMode) {
            "sms" -> {
                LocationHelper.getCurrentLocationAndReply(
                    context = this,
                    phoneNumber = sender,
                    onResult = { succeeded ->
                        RequestHistoryManager(this).record(sender, source, succeeded)
                        stopSelf()
                    }
                )
            }
            "notification" -> {
                val action = pendingNotificationAction
                if (action == null) {
                    Log.e(TAG, "No pending notification action found")
                    RequestHistoryManager(this).record(sender, source, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                pendingNotificationAction = null
                LocationHelper.getCurrentLocationAndReplyViaNotification(
                    context = this,
                    replyAction = action,
                    notificationKey = "",
                    onResult = { succeeded ->
                        RequestHistoryManager(this).record(sender, source, succeeded)
                        stopSelf()
                    }
                )
            }
            else -> stopSelf()
        }

        return START_NOT_STICKY
    }

    // -------------------------------------------------------------------------
    // Foreground notification (required to keep the service alive)
    // -------------------------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuicLoc")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QuicLoc Location Reply",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown briefly while QuicLoc is fetching your location to reply to a request."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
