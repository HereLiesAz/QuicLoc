package com.hereliesaz.quicloc.lockdown

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.hereliesaz.quicloc.FindMyPhone
import com.hereliesaz.quicloc.LocationHelper
import java.io.File

/**
 * Persistent foreground service that powers the find-my-phone path. Lives in
 * the on-demand `:feature_findmyphone` module along with the rest of the
 * lockdown feature.
 *
 * Started — via [FindMyPhone.trigger] in the base, which targets this service
 * by `ComponentName` — when a `loc <passphrase>` message is received via SMS
 * or chat-app notification (regardless of whitelist — the passphrase is the
 * credential). Locks the device (via [LockdownController]) and then posts
 * location updates back to the triggering number on a fixed interval:
 *
 *   - 5 minutes normally
 *   - 1 minute in panic mode (entered after 3 wrong PIN attempts)
 *
 * On entering panic mode, the captured intruder photo is sent via MMS
 * exactly once (not resent on every subsequent tick — see [doTick]).
 *
 * Service-level concerns:
 *
 *   - `START_STICKY` + state persisted in `quicloc_tracking_state` prefs so
 *     the service can resume on its own after an OS kill. The null-intent
 *     restart path in [onStartCommand] handles this, and [TrackingAlarmReceiver]
 *     handles the same recovery if the *process* (not just the service
 *     instance) was killed — see its class doc. [FindMyPhone.resumeTrackingAfterBoot]
 *     handles the reboot case, which START_STICKY does not cover at all.
 *   - Ticks are driven by [AlarmManager] wakeup alarms, not a `Handler` —
 *     `Handler.postDelayed` cannot fire while the CPU is suspended (deep
 *     sleep), which would silently stall tracking on a pocketed, screen-off
 *     device. See [scheduleNextTick].
 *   - `foregroundServiceType="location"` — Android 14+ FGS type requirement.
 *     The intruder photo is captured by the visible [TrackingLockActivity]
 *     (CAMERA only, no camera-typed FGS).
 *   - Falls back to the cover-screen [TrackingLockActivity] if Device Admin
 *     isn't granted (see [LockdownController]).
 *
 * Stopped only by [stopTracking], which is called when the user enters the
 * correct PIN in [TrackingLockActivity].
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "QuicLoc.TrackingService"
        private const val CHANNEL_ID = "quicloc_tracking"
        private const val NOTIF_ID = 2002
        private const val ALARM_REQUEST_CODE = 2003

        private const val NORMAL_INTERVAL_MS = 300_000L // 5 min
        private const val PANIC_INTERVAL_MS = 60_000L // 1 min
        private const val MMS_MAX_DIMENSION_PX = 800

        const val ACTION_STOP = "com.hereliesaz.quicloc.STOP_TRACKING"
        const val ACTION_PANIC_MODE = "com.hereliesaz.quicloc.PANIC_MODE"
        const val EXTRA_PHOTO_PATH = "photo_path"

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
         * one MMS photo). Called by [TrackingLockActivity] exactly once, on
         * the wrong-PIN attempt that crosses the 3-strike threshold — see
         * that class for why it must not call this more than once per
         * lock session. [photoPath] is the absolute path to the front-camera
         * capture; null if camera capture failed but we still want to
         * escalate the tracking cadence.
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
    private var photoPathToSend: String? = null

    /**
     * Persists the current sender/source/panic-mode/photo path into plain
     * `SharedPreferences` so [onStartCommand] can rehydrate everything after
     * either an OS-initiated restart of this exact service instance (the
     * null-intent path) or a full process kill ([TrackingAlarmReceiver]
     * restarting a fresh instance, or [FindMyPhone.resumeTrackingAfterBoot]
     * after a reboot).
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Defensive: if this instance is going away without stopTracking()
        // having run (e.g. the OS reclaims it under memory pressure without
        // an explicit stop), cancel this instance's scheduled alarm so a
        // still-pending one from a *replacement* instance (which reschedules
        // under the same stable request code, so it would simply overwrite
        // this one) is the only one left armed — never two overlapping
        // schedules ticking the same session twice.
        if (sender != null) cancelScheduledTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Any entry point that finds no in-memory session first tries to
        // restore one from disk. Covers the null-intent OS-restart path AND
        // a fresh process spun up by TrackingAlarmReceiver or
        // FindMyPhone.resumeTrackingAfterBoot — both start this service with
        // a non-null Intent, so without this they'd fall through to
        // "no session, no sender extra, stop" instead of resuming.
        val freshlyRestored = sender == null && loadState()

        if (intent == null) {
            if (sender == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            Log.d(TAG, "TrackingService restarted by system, restoring state for $sender")
            claimForeground()
            engageLockdown()
            doTick()
            scheduleNextTick()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping tracking service")
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_PANIC_MODE -> {
                if (sender == null) {
                    // A stray/duplicate broadcast with nothing persisted to
                    // escalate — bail without writing any state, so it can't
                    // resurrect a session that was already stopped.
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Entering panic mode")
                isPanicMode = true
                photoPathToSend = intent.getStringExtra(EXTRA_PHOTO_PATH)
                saveState()
                claimForeground()
                // Only a freshly-recreated process needs the lock screen
                // re-engaged — an already-running session's lock activity is
                // already up; relaunching it here would blow away any PIN
                // digits the person on the lock screen was mid-typing.
                if (freshlyRestored) engageLockdown()
                scheduleNextTick(immediate = true)
                return START_STICKY
            }
            FindMyPhone.ACTION_TICK -> {
                if (sender == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                claimForeground()
                if (freshlyRestored) engageLockdown()
                doTick()
                scheduleNextTick()
                return START_STICKY
            }
        }

        if (sender == null) {
            // Extra keys are defined in the base FindMyPhone bridge that built
            // this intent, so the two can never drift apart.
            sender = intent.getStringExtra(FindMyPhone.EXTRA_SENDER)
            source = intent.getStringExtra(FindMyPhone.EXTRA_SOURCE) ?: "Unknown"

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
            doTick()
            scheduleNextTick()
            claimForeground()
            engageLockdown()
        }

        return START_STICKY
    }

    /**
     * Claims (or refreshes) foreground-service status. Idempotent and cheap
     * to call on every tick — required at least once per process lifetime
     * (Android tears the process down if a `startForegroundService`-started
     * service never calls this promptly), and calling it again just updates
     * the existing notification.
     */
    private fun claimForeground() {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuicLoc Tracking Active")
            .setContentText("Enter your QuicLoc PIN to stop tracking.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(lockActivityPendingIntent())
            // Always wired up — the PIN gate must surface regardless of
            // whether Device Admin lockNow succeeds. On Android 14+ this
            // requires USE_FULL_SCREEN_INTENT to be granted (the in-app
            // permissions panel exposes it).
            .setFullScreenIntent(lockActivityPendingIntent(), true)
            .setOngoing(true)

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            builder.build(),
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
        )
    }

    /**
     * One-shot: bring up the PIN-gate lock activity and attempt a real
     * Device Admin lock. Called once per lock "session" (initial trigger, or
     * a freshly-recreated process resuming one) — NOT on every periodic
     * tick, which would repeatedly relaunch [TrackingLockActivity] over
     * whatever the person in front of the phone was doing (including typing
     * a PIN) and repeatedly re-invoke `lockNow()` for no benefit.
     */
    private fun engageLockdown() {
        val lockIntent = Intent(this, TrackingLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            startActivity(lockIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start lock activity directly — relying on FullScreenIntent", e)
        }
        val realLockSucceeded = LockdownController.lockNow(this)
        Log.d(TAG, "Lock attempt: realLockSucceeded=$realLockSucceeded")
    }

    // Explicit setClass()/setPackage() calls, not just the Intent(context,
    // Class) constructor -- static analysis (CodeQL's implicit-PendingIntent
    // check) pattern-matches on the explicit-targeting method calls
    // themselves rather than crediting the constructor overload, and a truly
    // implicit PendingIntent handed to AlarmManager/the notification manager
    // is interceptable by any app that can match it.

    private fun lockActivityPendingIntent(): PendingIntent {
        val lockIntent = Intent(this, TrackingLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            setClass(this@TrackingService, TrackingLockActivity::class.java)
            setPackage(packageName)
        }
        return PendingIntent.getActivity(
            this, 0, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun tickPendingIntent(): PendingIntent {
        val intent = Intent(this, TrackingAlarmReceiver::class.java).apply {
            setClass(this@TrackingService, TrackingAlarmReceiver::class.java)
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Schedules the next tick via [AlarmManager] instead of
     * `Handler.postDelayed`. `Handler.postDelayed` measures against
     * `SystemClock.uptimeMillis()`, which stops advancing while the CPU is
     * suspended — on a locked, screen-off device (exactly the state this
     * service runs in) that silently stalls the whole tracking cadence with
     * no error and no log. `AlarmManager` wakeup alarms are the platform's
     * actual mechanism for "run code at this time even during deep sleep".
     *
     * Deliberately a non-exact wakeup alarm (`set`, not
     * `setExactAndAllowWhileIdle`) — exact alarms need the user to grant
     * `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, another permission this
     * feature doesn't otherwise need. A non-exact wakeup alarm still forces
     * a real CPU wake (which is the actual bug being fixed here — Handler
     * couldn't do that at all), Doze may just defer delivery into the next
     * maintenance window by some margin rather than firing at the exact
     * millisecond.
     *
     * Re-scheduling with the same stable [ALARM_REQUEST_CODE]/[PendingIntent]
     * replaces any alarm already pending for this session — there is never
     * more than one tick alarm armed at a time, so a service instance being
     * recreated mid-session can't end up with two overlapping schedules.
     */
    private fun scheduleNextTick(immediate: Boolean = false) {
        val intervalMs = if (immediate) 0L else if (isPanicMode) PANIC_INTERVAL_MS else NORMAL_INTERVAL_MS
        val am = getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        try {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, tickPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not schedule next tracking tick", t)
        }
    }

    private fun cancelScheduledTick() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(tickPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not cancel scheduled tracking tick", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun doTick() {
        val targetSender = sender ?: return
        Log.d(TAG, "Fetching location for tracking update...")
        LocationHelper.getCurrentLocationAndReply(
            context = this,
            phoneNumber = targetSender,
            onResult = { succeeded ->
                // Send the panic-mode photo at most once: the tick right
                // after escalation (or the escalation's own immediate tick)
                // sends it and clears photoPathToSend, so later ticks in the
                // same panic session don't keep re-sending the identical
                // photo indefinitely (that used to be one MMS per minute,
                // forever, until the correct PIN was entered).
                val photo = photoPathToSend
                if (isPanicMode && photo != null) {
                    photoPathToSend = null
                    saveState()
                    sendMmsPhoto(targetSender, photo)
                }
            }
        )
    }

    private fun sendMmsPhoto(targetSender: String, photoPath: String) {
        // Run on background thread to avoid ANR
        Thread {
            // Never log the destination number itself -- logcat/bugreports
            // shouldn't carry a phone number tied to a security event.
            Log.d(TAG, "Sending MMS photo from $photoPath")
            try {
                val settings = com.klinker.android.send_message.Settings().apply {
                    useSystemSending = true
                }
                val transaction = com.klinker.android.send_message.Transaction(this@TrackingService, settings)
                val message = com.klinker.android.send_message.Message("QuicLoc Lock Image", targetSender)

                val file = File(photoPath)
                if (file.exists()) {
                    val bitmap = downsampleForMms(photoPath, MMS_MAX_DIMENSION_PX)
                    if (bitmap != null) {
                        message.setImage(bitmap)
                        transaction.sendNewMessage(message, com.klinker.android.send_message.Transaction.NO_THREAD_ID)
                        Log.d(TAG, "MMS enqueued via library")
                        // The photo already made it to the owner (or is
                        // enqueued to) — the local copy in externalMediaDirs
                        // is MediaStore-visible and backup-eligible, so don't
                        // leave it sitting there indefinitely. Kept on a
                        // decode/send failure below so a retry or manual
                        // recovery is still possible.
                        file.delete()
                    } else {
                        Log.e(TAG, "Could not decode photo for MMS: $photoPath")
                    }
                } else {
                    Log.e(TAG, "Photo file not found: $photoPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MMS", e)
            } catch (e: OutOfMemoryError) {
                // BitmapFactory/createScaledBitmap can throw OutOfMemoryError
                // (not Exception) on a very large source image — this runs on
                // a bare Thread, so an uncaught Error here would hit the
                // default uncaught-exception handler and kill the whole
                // process, taking the FGS and the lock screen down with it
                // mid-response to a theft.
                Log.e(TAG, "Out of memory decoding photo for MMS: $photoPath", e)
            }
        }.start()
    }

    /**
     * Decodes [path] downsampled so neither dimension exceeds
     * [maxDimensionPx] — MMS carriers reject/re-compress oversized images.
     * `inSampleSize` only supports power-of-two steps, so it alone can leave
     * an image up to ~2x the target on its long side depending on aspect
     * ratio; this finishes with an exact [Bitmap.createScaledBitmap] pass so
     * the cap is real, not "roughly". Returns null if the file can't be
     * decoded at all.
     */
    private fun downsampleForMms(path: String, maxDimensionPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (inSampleSize * 2) >= maxDimensionPx) inSampleSize *= 2

        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }) ?: return null

        val decodedLongSide = maxOf(decoded.width, decoded.height)
        if (decodedLongSide <= maxDimensionPx) return decoded

        val scale = maxDimensionPx.toFloat() / decodedLongSide
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun stopTracking() {
        cancelScheduledTick()
        clearState()
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
