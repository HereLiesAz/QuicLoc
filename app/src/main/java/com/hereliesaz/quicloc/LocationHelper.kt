package com.hereliesaz.quicloc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
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

object LocationHelper {
    private const val TAG = "QuicLoc.LocationHelper"
    private const val LOCATION_TIMEOUT_MS = 15000L

    // -------------------------------------------------------------------------
    // SMS reply path
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAndReply(
        context: Context,
        phoneNumber: String,
        pendingResult: BroadcastReceiver.PendingResult
    ) {
        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                sendSms(context, phoneNumber, "QuicLoc Location:\n$mapsLink")
            },
            onFailure = { msg ->
                sendSms(context, phoneNumber, "QuicLoc Error: $msg")
            },
            onComplete = {
                pendingResult.finish()
            }
        )
    }

    // -------------------------------------------------------------------------
    // Notification inline-reply path
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getCurrentLocationAndReplyViaNotification(
        context: Context,
        replyAction: Notification.Action,
        notificationKey: String
    ) {
        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                sendNotificationReply(context, replyAction, "QuicLoc: $mapsLink")
            },
            onFailure = { msg ->
                sendNotificationReply(context, replyAction, "QuicLoc Error: $msg")
            },
            onComplete = {}
        )
    }

    // -------------------------------------------------------------------------
    // Core location fetch — three-stage fallback:
    //   Stage 1: getCurrentLocation() HIGH_ACCURACY (GPS + network, up to ~5s)
    //   Stage 2: lastLocation (cached fix, instant but possibly stale)
    //   Stage 3: requestLocationUpdates() (forces a brand new fix, 15s timeout)
    //
    // Why this is needed:
    //   getCurrentLocation() returns null when the device has no cached fix AND
    //   GPS hasn't acquired a signal yet (e.g. cold start, screen was off).
    //   lastLocation fills the gap instantly if any app recently used GPS.
    //   requestLocationUpdates is the nuclear option — it always works if
    //   location services are on, just takes longer.
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun fetchLocation(
        context: Context,
        onSuccess: (Location) -> Unit,
        onFailure: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        try {
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).addOnCompleteListener { task ->
                val location = task.result
                if (task.isSuccessful && location != null) {
                    Log.d(TAG, "Stage 1 success: getCurrentLocation()")
                    onSuccess(location)
                    onComplete()
                } else {
                    Log.w(TAG, "Stage 1 null — trying lastLocation")
                    tryLastLocation(context, fusedClient, onSuccess, onFailure, onComplete)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission", e)
            onFailure("Location permission not granted.")
            onComplete()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastLocation(
        context: Context,
        fusedClient: FusedLocationProviderClient,
        onSuccess: (Location) -> Unit,
        onFailure: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        fusedClient.lastLocation.addOnCompleteListener { task ->
            val location = task.result
            if (task.isSuccessful && location != null) {
                Log.d(TAG, "Stage 2 success: lastLocation (age ${System.currentTimeMillis() - location.time}ms)")
                onSuccess(location)
                onComplete()
            } else {
                Log.w(TAG, "Stage 2 null — requesting fresh update")
                requestFreshUpdate(context, fusedClient, onSuccess, onFailure, onComplete)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshUpdate(
        context: Context,
        fusedClient: FusedLocationProviderClient,
        onSuccess: (Location) -> Unit,
        onFailure: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .setMaxUpdateDelayMillis(LOCATION_TIMEOUT_MS)
            .build()

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) {
                    Log.d(TAG, "Stage 3 success: requestLocationUpdates()")
                    onSuccess(loc)
                } else {
                    onFailure("Could not obtain a location fix.")
                }
                onComplete()
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable && !finished) {
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    fusedClient.removeLocationUpdates(this)
                    Log.e(TAG, "Location services unavailable")
                    onFailure("Location services are turned off on this device.")
                    onComplete()
                }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // Hard timeout — give up after 15 seconds
        handler.postDelayed({
            if (!finished) {
                finished = true
                fusedClient.removeLocationUpdates(callback)
                Log.e(TAG, "Stage 3 timed out after ${LOCATION_TIMEOUT_MS}ms")
                onFailure("Location timed out. Is GPS enabled?")
                onComplete()
            }
        }, LOCATION_TIMEOUT_MS)
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
