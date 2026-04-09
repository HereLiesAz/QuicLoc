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

        fun startForSms(context: Context, sender: String) {
            val intent = Intent(context, TrackingService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, "SMS")
            }
            context.startForegroundService(intent)
        }

        fun startForNotification(context: Context, sender: String, source: String) {
            val intent = Intent(context, TrackingService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SOURCE, source)
            }
            context.startForegroundService(intent)
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

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
            showForegroundNotification()
            handler.post(trackingRunnable)
        }

        return START_STICKY
    }

    private fun showForegroundNotification() {
        val lockIntent = Intent(this, TrackingLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(lockIntent)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuicLoc Tracking Active")
            .setContentText("Your device is currently locked and transmitting its location.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else 0
        )
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
