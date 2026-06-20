package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Persistent foreground service that powers the find-my-phone path.
 *
 * Started when a `loc <passphrase>` message is received via SMS or chat-app
 * notification (regardless of whitelist — passphrase is the credential).
 * Locks the device (via [LockdownController]) and then posts location
 * updates back to the triggering number on a fixed interval:
 *
 *   - 5 minutes normally
 *   - 1 minute in panic mode (entered after 3 wrong PIN attempts)
 *
 * In panic mode, each tick also sends the captured intruder photo as MMS.
 *
 * Service-level concerns:
 *
 *   - `START_STICKY` + state persisted in `quicloc_tracking_state` prefs so
 *     the service can resume on its own after an OS kill. The null-intent
 *     restart path in [onStartCommand] handles this.
 *   - `foregroundServiceType="location"` — Android 14+ FGS type requirement.
 *     The intruder photo is captured by the visible [TrackingLockActivity]
 *     (CAMERA only, no camera-typed FGS) via the on-demand `:feature_camera`
 *     module, so the service itself needs only the location type.
 *   - Falls back to the cover-screen [TrackingLockActivity] if Device Admin
 *     isn't granted (see [LockdownController]).
 *
 * Stopped only by [TrackingService.stopTracking], which is called when the
 * user enters the correct PIN in [TrackingLockActivity].
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "QuicLoc.TrackingService"
        private const val CHANNEL_ID = "quicloc_tracking"
        private const val NOTIF_ID = 2002

        const val EXTRA_SENDER = "sender"
        const val EXTRA_SOURCE = "source"
        const val ACTION_STOP = "com.hereliesaz.quicloc.STOP_TRACKING"
        const val ACTION_PANIC_MODE = "com.hereliesaz.quicloc.PANIC_MODE"
        const val EXTRA_PHOTO_PATH = "photo_path"

        /**
         * Start tracking for a passphrase trigger that arrived via SMS.
         * [sender] is the originating phone number — all subsequent
         * location/photo replies go here.
         */
        fun startForSms(context: Context, sender: String) {
            val intent = Intent(context, TrackingService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, "SMS")
            }
            context.startForegroundService(intent)
        }

        /**
         * Start tracking for a passphrase trigger that arrived via chat-app
         * notification. [sender] is the display name from the notification;
         * [source] is the originating app's package name (used as the
         * History tab's "source" column).
         *
         * The actual location reply still goes via SMS to the sender's
         * resolved phone number — we don't reply to the chat app for the
         * tracking case (the trigger may have come from an attacker who
         * already has the device).
         */
        fun startForNotification(context: Context, sender: String, source: String) {
            val intent = Intent(context, TrackingService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, source)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the running service. Called by [TrackingLockActivity] after
         * the user enters the correct PIN. Safe to call when the service
         * isn't running.
         */
        fun stopTracking(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Escalate the running service into panic mode (1-minute interval,
         * MMS photo on every tick). Called by [TrackingLockActivity] after
         * 3 wrong PIN entries. [photoPath] is the absolute path to the
         * front-camera capture; null if camera capture failed but we still
         * want to escalate the tracking cadence.
         */
        fun enterPanicMode(context: Context, photoPath: String?) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_PANIC_MODE
                putExtra(EXTRA_PHOTO_PATH, photoPath)
            }
            context.startService(intent)
        }
    }

    private var sender: String? = null
    private var source: String = "Unknown"
    private var isPanicMode = false
    private var handler = Handler(Looper.getMainLooper())
    private var photoPathToSend: String? = null


    /**
     * Persists the current sender/source/panic-mode/photo path into plain
     * `SharedPreferences` so [onStartCommand]'s null-intent restart branch
     * can rehydrate everything if the OS kills the service.
     */
    private fun saveState() {
        val prefs = getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("sender", sender)
            .putString("source", source)
            .putBoolean("isPanicMode", isPanicMode)
            .putString("photoPathToSend", photoPathToSend)
            .apply()
    }

    private fun loadState(): Boolean {
        val prefs = getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
        sender = prefs.getString("sender", null)
        source = prefs.getString("source", "Unknown") ?: "Unknown"
        isPanicMode = prefs.getBoolean("isPanicMode", false)
        photoPathToSend = prefs.getString("photoPathToSend", null)
        return sender != null
    }

    private fun clearState() {
        getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private val trackingRunnable = object : Runnable {
        override fun run() {
            sendLocationUpdate()
            val interval = if (isPanicMode) 60_000L else 300_000L // 1 min or 5 min
            handler.postDelayed(this, interval)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restore state from SharedPreferences
            if (!loadState()) {
                stopSelf()
                return START_NOT_STICKY
            }
            Log.d(TAG, "TrackingService restarted by system, restoring state for $sender")
            showForegroundNotification()
            handler.post(trackingRunnable)
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping tracking service")
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_PANIC_MODE -> {
                Log.d(TAG, "Entering panic mode")
                isPanicMode = true
                photoPathToSend = intent.getStringExtra(EXTRA_PHOTO_PATH)
                saveState()
                handler.removeCallbacks(trackingRunnable)
                handler.post(trackingRunnable)
                return START_STICKY
            }
        }

        if (sender == null) {
            sender = intent.getStringExtra(EXTRA_SENDER)
            source = intent.getStringExtra(EXTRA_SOURCE) ?: "Unknown"

            if (sender == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            saveState()
            Log.d(TAG, "Starting tracking for $sender via $source")
            // Fire the first location fetch immediately, BEFORE we lock the
            // device. fetchLocation is async — it just kicks off the
            // FusedLocationProvider request and returns. Doing it here gives
            // the GPS subsystem a head start while the screen is still on.
            // The subsequent periodic ticks are scheduled by trackingRunnable
            // re-posting itself.
            sendLocationUpdate()
            handler.postDelayed(trackingRunnable, if (isPanicMode) 60_000L else 300_000L)
            showForegroundNotification()
        }

        return START_STICKY
    }

    /**
     * Ordering matters here. We must:
     *
     *   1. Claim FGS status FIRST, so we have background-activity-start
     *      privileges and so the location-typed FGS is registered with the
     *      OS before anything else runs. If we lock first, the OS-level
     *      "no background work" rules kick in before our FGS is recognised
     *      and the immediate location fetch silently times out.
     *   2. Launch the PIN-gate Activity SECOND, before the device actually
     *      locks. Combined with the activity's manifest `showWhenLocked=true`,
     *      it draws over the keyguard AND stays on top once the user unlocks
     *      it — so the QuicLoc PIN is required even after a Device Admin
     *      `lockNow`. Without this step a thief could just enter the system
     *      PIN and walk away from the tracking.
     *   3. Lock via Device Admin LAST. If admin isn't granted, the
     *      `FullScreenIntent` + `startActivity` above are the only barrier,
     *      but they're always wired up regardless of grant state.
     */
    private fun showForegroundNotification() {
        val lockIntent = Intent(this, TrackingLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuicLoc Tracking Active")
            .setContentText("Enter your QuicLoc PIN to stop tracking.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            // Always wired up — the PIN gate must surface regardless of
            // whether Device Admin lockNow succeeds. On Android 14+ this
            // requires USE_FULL_SCREEN_INTENT to be granted (the in-app
            // permissions panel exposes it).
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)

        // Step 1: claim FGS status. Location only — the intruder photo is
        // captured by the visible TrackingLockActivity (CAMERA permission, no
        // camera-typed FGS needed), and that capture now lives in the on-demand
        // :feature_camera module.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            builder.build(),
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
        )

        // Step 2: bring the PIN-gate Activity up BEFORE locking.
        try {
            startActivity(lockIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start lock activity directly — relying on FullScreenIntent", e)
        }

        // Step 3: lock via Device Admin. Best-effort — no-op if admin not granted.
        val realLockSucceeded = LockdownController.lockNow(this)
        Log.d(TAG, "Lock attempt: realLockSucceeded=$realLockSucceeded")
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationUpdate() {
        val targetSender = sender ?: return
        Log.d(TAG, "Fetching location for tracking update...")
        LocationHelper.getCurrentLocationAndReply(
            context = this,
            phoneNumber = targetSender,
            onResult = { succeeded ->
                // Also send photo if in panic mode, regardless of location success
                if (isPanicMode && photoPathToSend != null) {
                    sendMmsPhoto(targetSender, photoPathToSend!!)
                }
            }
        )
    }

    private fun sendMmsPhoto(targetSender: String, photoPath: String) {
        // Run on background thread to avoid ANR
        Thread {
            Log.d(TAG, "Sending MMS photo to $targetSender from $photoPath")
            try {
                val settings = com.klinker.android.send_message.Settings().apply {
                    useSystemSending = true
                }
                val transaction = com.klinker.android.send_message.Transaction(this@TrackingService, settings)
                val message = com.klinker.android.send_message.Message("QuicLoc Lock Image", targetSender)

                // Add image
                val file = java.io.File(photoPath)
                if (file.exists()) {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)

                    // Calculate inSampleSize
                    var inSampleSize = 1
                    val reqWidth = 800
                    val reqHeight = 800
                    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                        val halfHeight = options.outHeight / 2
                        val halfWidth = options.outWidth / 2
                        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                            inSampleSize *= 2
                        }
                    }

                    options.inJustDecodeBounds = false
                    options.inSampleSize = inSampleSize

                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    message.setImage(bitmap)
                    transaction.sendNewMessage(message, com.klinker.android.send_message.Transaction.NO_THREAD_ID)
                    Log.d(TAG, "MMS enqueued via library")
                } else {
                    Log.e(TAG, "Photo file not found: $photoPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MMS", e)
            }
        }.start()
    }

    private fun stopTracking() {
        clearState()
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QuicLoc Tracking",
            NotificationManager.IMPORTANCE_HIGH // HIGH required for full screen intent
        ).apply {
            description = "Active when your device is locked and being tracked."
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
