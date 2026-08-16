package com.hereliesaz.quicloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ServiceCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that fetches the device's location and sends a reply.
 *
 * Why a Service instead of goAsync():
 *   BroadcastReceiver.goAsync() only buys 10 seconds before Android kills the
 *   process. GPS acquisition from a cold start can take 20-30 seconds in poor
 *   conditions. A foreground service has no such time limit and survives as
 *   long as needed (up to our own 60-second timeout), then stops itself.
 *
 * Started by SmsReceiver, NotificationListener, and the home-screen widget.
 * Stops itself once a reply is sent or the timeout is reached.
 */
class LocationReplyService : Service() {

    companion object {
        private const val TAG = "QuicLoc.ReplyService"
        private const val CHANNEL_ID = "quicloc_reply"
        private const val NOTIF_ID = 1001

        const val ACTION_WIDGET_TAP = "com.hereliesaz.quicloc.ACTION_WIDGET_TAP"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_SOURCE = "source"           // "SMS", package name for notifs, "Widget"
        const val EXTRA_REPLY_MODE = "reply_mode"   // "sms" or "notification"
        const val EXTRA_ACTION_TOKEN = "action_token"
        const val EXTRA_DIAG_ID = "diag_id"          // row to patch in DiagnosticLogManager

        // Keyed by per-trigger UUID so concurrent triggers don't clobber each
        // other (fixes the previous static-singleton race).
        private val pendingActions = ConcurrentHashMap<String, Notification.Action>()

        fun startForSms(context: Context, sender: String, diagId: String? = null) {
            val intent = Intent(context, LocationReplyService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, "SMS")
                putExtra(EXTRA_REPLY_MODE, "sms")
                putExtra(EXTRA_DIAG_ID, diagId)
            }
            context.startForegroundService(intent)
        }

        fun startForNotification(
            context: Context,
            sender: String,
            source: String,
            action: Notification.Action,
            diagId: String? = null
        ) {
            val token = UUID.randomUUID().toString()
            pendingActions[token] = action
            val intent = Intent(context, LocationReplyService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_REPLY_MODE, "notification")
                putExtra(EXTRA_ACTION_TOKEN, token)
                putExtra(EXTRA_DIAG_ID, diagId)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 14+ rejects starting a location-typed foreground service
        // without ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION already granted
        // -- and this service can be started by a bare widget tap before the
        // user has ever granted anything. Without this check, that throws
        // here in onCreate() and crashes the whole process instead of the
        // graceful "no location permission" failure LocationHelper already
        // produces once the permission check happens downstream.
        val hasLocationPermission =
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34 && hasLocationPermission) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Fetching location…"), fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    // For handling widget taps (delay to distinguish single/double/triple taps)
    private var widgetTapCount = 0
    private val widgetTapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // How many async fetch/send operations this service instance is currently
    // tracking, across ALL intake paths (SMS, notification, widget taps).
    // Service.stopSelf(startId) alone can't express "don't stop while
    // something else I started is still running" -- it only knows about the
    // single most-recent start request delivered to onStartCommand. Multiple
    // triggers can land on the SAME running service instance (e.g. a widget
    // tap arriving while an earlier SMS-triggered fetch is still in flight,
    // or two overlapping widget tap bursts), so every exit path goes through
    // maybeStopSelf() instead of calling stopSelf(startId) directly.
    private val activeOperations = java.util.concurrent.atomic.AtomicInteger(0)

    private fun maybeStopSelf() {
        if (activeOperations.get() <= 0) {
            stopSelf()
        }
    }
    private val widgetTapRunnable = Runnable {
        val count = widgetTapCount
        widgetTapCount = 0
        Log.d(TAG, "Widget tap timer expired, tap count: $count")

        val statusText = when (count) {
            1 -> "Help"
            2 -> "Parking"
            3 -> "Safety Check"
            in 4..Int.MAX_VALUE -> "Emergency"
            else -> null
        }

        if (statusText != null) {
            performActionHapticFeedback()
            updateWidgetStatus(this, statusText)
            // Clear the on-widget status after a few seconds. VISUAL ONLY — do
            // NOT stop the service here. The location fetch + reply below can
            // take up to 60s; stopping at 3s would tear the service down before
            // the SMS is sent (this was the "widget shows status but never sends"
            // bug). The service is stopped only once the reply completes.
            widgetTapHandler.postDelayed({ updateWidgetStatus(this, null) }, 3000)
        }

        if (count == 1) {
            val intent = Intent(this, WidgetHelpActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            // A single tap only opens help — there's no location reply, so stop
            // the service once the status has faded (unless something else
            // this instance started, e.g. an in-flight emergency send from an
            // earlier tap burst, is still running).
            widgetTapHandler.postDelayed({ maybeStopSelf() }, 3000)
            return@Runnable
        }

        if (count < 2) {
            maybeStopSelf()
            return@Runnable
        }

        // Counts >= 2 actually transmit the location. Respect the master
        // switch here specifically (not for the count==1 help screen, which
        // sends nothing and stays useful for self-diagnosing why the switch
        // is off) -- the setup checklist tells the user that toggling QuicLoc
        // off silences every trigger including the widget, so a stray or
        // pocket tap must not send a location behind that promise.
        if (!AppSettings.isEnabled(applicationContext)) {
            Log.d(TAG, "QuicLoc disabled — ignoring widget tap ($count)")
            updateWidgetStatus(this, "Off")
            widgetTapHandler.postDelayed({ updateWidgetStatus(this, null); maybeStopSelf() }, 3000)
            return@Runnable
        }

        // Fetch the location (up to 60s) and send, THEN stop the service and
        // clear the status — so the send always completes.
        activeOperations.incrementAndGet()
        LocationHelper.handleWidgetTaps(this, count) { succeeded ->
            RequestHistoryManager(this).record("Widget ($count taps)", "Widget", succeeded)
            updateWidgetStatus(this, null)
            activeOperations.decrementAndGet()
            maybeStopSelf()
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
            maybeStopSelf()
            return START_NOT_STICKY
        }

        // Hard gate: if user disabled QuicLoc between trigger and service
        // start, abort. Widget taps are user-initiated so they still fire —
        // the widget-specific master-switch gate (only for taps that
        // actually send, i.e. count >= 2) lives in widgetTapRunnable instead.
        if (intent.action != ACTION_WIDGET_TAP && !AppSettings.isEnabled(applicationContext)) {
            Log.d(TAG, "QuicLoc disabled — aborting reply")
            intent.getStringExtra(EXTRA_DIAG_ID)?.let {
                DiagnosticLogManager(this).updateOutcome(it, DiagOutcome.APP_DISABLED,
                    "QuicLoc was toggled off before the reply could be sent")
            }
            maybeStopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_WIDGET_TAP) {
            widgetTapCount++
            performHapticFeedback()
            widgetTapHandler.removeCallbacks(widgetTapRunnable)
            widgetTapHandler.postDelayed(widgetTapRunnable, 400)
            return START_NOT_STICKY
        }

        val sender = intent.getStringExtra(EXTRA_SENDER) ?: run {
            maybeStopSelf()
            return START_NOT_STICKY
        }
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "Unknown"
        val replyMode = intent.getStringExtra(EXTRA_REPLY_MODE) ?: "sms"
        val diagId = intent.getStringExtra(EXTRA_DIAG_ID)

        Log.d(TAG, "Starting location fetch for $sender via $source (mode=$replyMode)")

        when (replyMode) {
            "sms" -> {
                activeOperations.incrementAndGet()
                LocationHelper.getCurrentLocationAndReply(
                    context = this,
                    phoneNumber = sender,
                    onResult = { succeeded ->
                        RequestHistoryManager(this).record(sender, source, succeeded)
                        patchDiag(diagId, succeeded, source)
                        activeOperations.decrementAndGet()
                        maybeStopSelf()
                    }
                )
            }
            "notification" -> {
                val token = intent.getStringExtra(EXTRA_ACTION_TOKEN)
                val action = token?.let { pendingActions.remove(it) }
                if (action == null) {
                    Log.e(TAG, "No pending notification action for token $token")
                    RequestHistoryManager(this).record(sender, source, false)
                    diagId?.let {
                        DiagnosticLogManager(this).updateOutcome(it, DiagOutcome.NO_PENDING_ACTION,
                            "The reply action expired before the service could use it")
                    }
                    maybeStopSelf()
                    return START_NOT_STICKY
                }
                activeOperations.incrementAndGet()
                LocationHelper.getCurrentLocationAndReplyViaNotification(
                    context = this,
                    replyAction = action,
                    notificationKey = "",
                    onResult = { succeeded ->
                        RequestHistoryManager(this).record(sender, source, succeeded)
                        patchDiag(diagId, succeeded, source)
                        activeOperations.decrementAndGet()
                        maybeStopSelf()
                    }
                )
            }
            else -> maybeStopSelf()
        }

        return START_NOT_STICKY
    }

    /** Flips the interim DISPATCHED diagnostic row to its final state. */
    private fun patchDiag(diagId: String?, succeeded: Boolean, source: String) {
        diagId ?: return
        DiagnosticLogManager(this).updateOutcome(
            diagId,
            if (succeeded) DiagOutcome.REPLY_SENT else DiagOutcome.REPLY_FAILED,
            if (succeeded) "Location reply sent via $source"
            else "Couldn't fetch location or send the reply via $source"
        )
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
