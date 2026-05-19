package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Single source of truth for "get the device's current GPS location and
 * reply to the requester". Used by [LocationReplyService] for both the SMS
 * and notification-inline-reply paths, and by [TrackingService] for the
 * find-my-phone periodic updates.
 *
 * The location fetch is a three-stage fallback with a single 30-second
 * overall deadline:
 *
 *   1. [FusedLocationProviderClient.getCurrentLocation] HIGH_ACCURACY —
 *      fast if there's a recent fix.
 *   2. [FusedLocationProviderClient.lastLocation] — instant, may be stale,
 *      but better than nothing if 1 and 3 fail.
 *   3. [FusedLocationProviderClient.requestLocationUpdates] — forces the
 *      GPS radio on. May take 20–30 s on a cold start outdoors.
 *
 * If all three fall through or the deadline expires, we send an error
 * reply back to the requester so they aren't left wondering whether the
 * trigger reached us at all.
 *
 * Three send modes:
 *
 *   - [getCurrentLocationAndReply] — SMS send via [SmsManager].
 *   - [getCurrentLocationAndReplyViaNotification] — chat-app inline reply
 *     via the original notification's [Notification.Action] `RemoteInput`.
 *   - [handleWidgetTaps] — SMS fan-out to multiple destinations based on
 *     widget tap count (2/3/4 taps).
 */
object LocationHelper {
    private const val TAG = "QuicLoc.LocationHelper"

    // Total time we'll wait across all three stages before giving up.
    // 30 seconds is enough for a cold GPS fix in most conditions.
    private const val LOCATION_TIMEOUT_MS = 30_000L

    // -------------------------------------------------------------------------
    // SMS reply — called from LocationReplyService (no PendingResult needed)
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAndReply(
        context: Context,
        phoneNumber: String,
        onResult: ((succeeded: Boolean) -> Unit)? = null
    ) {
        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                sendSms(context, phoneNumber, "QuicLoc Location:\n$mapsLink")
                onResult?.invoke(true)
            },
            onFailure = { msg ->
                sendSms(context, phoneNumber, "QuicLoc Error: $msg")
                onResult?.invoke(false)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Notification inline-reply — called from LocationReplyService
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAndReplyViaNotification(
        context: Context,
        replyAction: Notification.Action,
        notificationKey: String,
        onResult: ((succeeded: Boolean) -> Unit)? = null
    ) {
        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                sendNotificationReply(context, replyAction, "QuicLoc: $mapsLink")
                onResult?.invoke(true)
            },
            onFailure = { msg ->
                sendNotificationReply(context, replyAction, "QuicLoc Error: $msg")
                onResult?.invoke(false)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Widget tap handling
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun handleWidgetTaps(context: Context, tapCount: Int, onResult: ((succeeded: Boolean) -> Unit)? = null) {
        val whitelistManager = WhitelistManager(context)
        val destinations = mutableListOf<String>()
        var suffix = ""

        if (tapCount == 2) {
            val myNumber = whitelistManager.getMyNumber()
            if (myNumber.isNotBlank()) {
                destinations.add(myNumber)
            }
            suffix = " #Parking"
        } else if (tapCount == 3) {
            destinations.addAll(whitelistManager.getStarredNumbers())
            suffix = " #SafetyCheck"
        } else if (tapCount >= 4) {
            destinations.addAll(whitelistManager.getNumbers())
            suffix = " #Emergency"
        }

        if (destinations.isEmpty()) {
            Log.e(TAG, "No destinations configured for tap count $tapCount")
            onResult?.invoke(false)
            return
        }

        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                val message = "QuicLoc Location:\n$mapsLink$suffix"
                for (dest in destinations) {
                    sendSms(context, dest, message)
                }
                onResult?.invoke(true)
            },
            onFailure = { msg ->
                val message = "QuicLoc Error: $msg$suffix"
                for (dest in destinations) {
                    sendSms(context, dest, message)
                }
                onResult?.invoke(false)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Core location fetch — three-stage fallback with a single 30s deadline:
    //
    //   Stage 1: getCurrentLocation() HIGH_ACCURACY
    //            Fast if a recent fix exists. Returns null on cold start.
    //
    //   Stage 2: lastLocation
    //            Instant. May be stale but better than nothing if stages 1 & 3
    //            both fail.
    //
    //   Stage 3: requestLocationUpdates()
    //            Forces the GPS radio on. Always works if location services are
    //            enabled. May take 20-30 seconds on a cold start outdoors.
    //
    // The 30-second deadline applies to the entire chain. If all three stages
    // are exhausted or time runs out, onFailure is called and the service stops.
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun fetchLocation(
        context: Context,
        onSuccess: (Location) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val deadline = System.currentTimeMillis() + LOCATION_TIMEOUT_MS
        val handler = Handler(Looper.getMainLooper())
        var done = false

        fun finish(success: Boolean, location: Location? = null, msg: String = "") {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            if (success && location != null) onSuccess(location) else onFailure(msg)
        }

        // Hard deadline — fires if all stages are still running
        handler.postDelayed({
            finish(false, msg = "Location timed out after ${LOCATION_TIMEOUT_MS / 1000}s. Is GPS enabled?")
        }, LOCATION_TIMEOUT_MS)

        try {
            // Stage 1
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnCompleteListener { task ->
                    if (done) return@addOnCompleteListener
                    val loc = task.result
                    if (task.isSuccessful && loc != null) {
                        Log.d(TAG, "Stage 1 success")
                        finish(true, loc)
                    } else {
                        Log.w(TAG, "Stage 1 null — trying lastLocation")
                        // Stage 2
                        fusedClient.lastLocation.addOnCompleteListener { t2 ->
                            if (done) return@addOnCompleteListener
                            val loc2 = t2.result
                            if (t2.isSuccessful && loc2 != null) {
                                Log.d(TAG, "Stage 2 success (age ${System.currentTimeMillis() - loc2.time}ms)")
                                finish(true, loc2)
                            } else {
                                Log.w(TAG, "Stage 2 null — requesting fresh update")
                                // Stage 3
                                val timeLeft = deadline - System.currentTimeMillis()
                                if (timeLeft <= 0) {
                                    finish(false, msg = "Location timed out before stage 3 could start.")
                                    return@addOnCompleteListener
                                }
                                val request = LocationRequest.Builder(
                                    Priority.PRIORITY_HIGH_ACCURACY, 1000L
                                )
                                    .setMaxUpdates(1)
                                    .setWaitForAccurateLocation(false)
                                    .setMaxUpdateDelayMillis(timeLeft)
                                    .build()

                                val callback = object : LocationCallback() {
                                    override fun onLocationResult(result: LocationResult) {
                                        fusedClient.removeLocationUpdates(this)
                                        val loc3 = result.lastLocation
                                        if (loc3 != null) {
                                            Log.d(TAG, "Stage 3 success")
                                            finish(true, loc3)
                                        } else {
                                            finish(false, msg = "Could not obtain a location fix.")
                                        }
                                    }

                                    override fun onLocationAvailability(avail: LocationAvailability) {
                                        if (!avail.isLocationAvailable) {
                                            fusedClient.removeLocationUpdates(this)
                                            finish(false, msg = "Location services are disabled on this device.")
                                        }
                                    }
                                }
                                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission", e)
            finish(false, msg = "Location permission not granted.")
        }
    }

    // -------------------------------------------------------------------------
    // SMS sending
    // -------------------------------------------------------------------------

    private fun sendSms(context: Context, phoneNumber: String, message: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (smsManager != null) {
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                }
            } else {
                Log.e(TAG, "Failed to get SmsManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        }
    }

    // -------------------------------------------------------------------------
    // Notification inline reply
    // -------------------------------------------------------------------------

    private fun sendNotificationReply(
        context: Context,
        action: Notification.Action,
        replyText: String
    ) {
        try {
            val remoteInputs = action.remoteInputs ?: return
            val intent = Intent()
            val bundle = Bundle()
            for (remoteInput in remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(context, 0, intent)
            Log.d(TAG, "Sent notification reply: $replyText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification reply", e)
        }
    }
}
