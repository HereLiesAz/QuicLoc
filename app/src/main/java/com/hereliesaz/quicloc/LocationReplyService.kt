package com.hereliesaz.quicloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews

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

        const val ACTION_WIDGET_TAP = "com.hereliesaz.quicloc.ACTION_WIDGET_TAP"
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

    // For handling widget taps (delay to distinguish single/double/triple taps)
    private var widgetTapCount = 0
    private var widgetStartId = -1
    private val widgetTapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val widgetTapRunnable = Runnable {
        val count = widgetTapCount
        val startId = widgetStartId
        widgetTapCount = 0
        Log.d(TAG, "Widget tap timer expired, tap count: $count")

        val statusText = when(count) {
            1 -> "Help"
            2 -> "Parking"
            3 -> "Safety Check"
            4 -> "Emergency"
            else -> null
        }

        if (statusText != null) {
            performActionHapticFeedback()
            updateWidgetStatus(this, statusText)
            // Fade away after 3 seconds
            widgetTapHandler.postDelayed({
                updateWidgetStatus(this, null)
                stopSelf(startId)
            }, 3000)
        }

        if (count == 1) {
            val intent = Intent(this, WidgetHelpActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            // Will be stopped by the fade away runnable
            return@Runnable
        }

        LocationHelper.handleWidgetTaps(this, count) { succeeded ->
            RequestHistoryManager(this).record("Widget ($count taps)", "Widget", succeeded)
            // Only stopSelf if not waiting for fade away
            if (statusText == null) stopSelf(startId)
        }
    }

    private fun updateWidgetStatus(context: Context, statusText: String?) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, QuicLocWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quicloc)
            if (statusText != null) {
                views.setTextViewText(R.id.widget_status, statusText)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_status, View.GONE)
            }
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
        }
    }

    private fun performHapticFeedback() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }

    private fun performActionHapticFeedback() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        widgetTapHandler.removeCallbacksAndMessages(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_WIDGET_TAP) {
            widgetTapCount++
            performHapticFeedback()
            widgetStartId = startId
            widgetTapHandler.removeCallbacks(widgetTapRunnable)
            widgetTapHandler.postDelayed(widgetTapRunnable, 400)
            return START_NOT_STICKY
        }

        val sender = intent.getStringExtra(EXTRA_SENDER) ?: run { stopSelf(startId); return START_NOT_STICKY }
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
                        stopSelf(startId)
                    }
                )
            }
            "notification" -> {
                val action = pendingNotificationAction
                if (action == null) {
                    Log.e(TAG, "No pending notification action found")
                    RequestHistoryManager(this).record(sender, source, false)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                pendingNotificationAction = null
                LocationHelper.getCurrentLocationAndReplyViaNotification(
                    context = this,
                    replyAction = action,
                    notificationKey = "",
                    onResult = { succeeded ->
                        RequestHistoryManager(this).record(sender, source, succeeded)
                        stopSelf(startId)
                    }
                )
            }
            else -> stopSelf(startId)
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
