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
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Single source of truth for "get the device's current GPS location and
 * reply to the requester". Used by [LocationReplyService] for both the SMS
 * and notification-inline-reply paths, and by [TrackingService] for the
 * find-my-phone periodic updates.
 *
 * The location fetch is a four-stage fallback with a single 60-second
 * overall deadline:
 *
 *   1. [FusedLocationProviderClient.getCurrentLocation] HIGH_ACCURACY —
 *      fast if there's a recent fix.
 *   2. [FusedLocationProviderClient.lastLocation] — instant, may be stale,
 *      but better than nothing if 1 and 3/4 fail.
 *   3. [FusedLocationProviderClient.requestLocationUpdates] HIGH_ACCURACY —
 *      forces the GPS radio on. May take 20–30 s on a cold start outdoors,
 *      so it gets the bulk of the deadline to work with.
 *   4. A network/cell-based [FusedLocationProviderClient.getCurrentLocation]
 *      BALANCED_POWER_ACCURACY fix, raced alongside stage 3 once it's taken
 *      more than [COARSE_FALLBACK_DELAY_MS] — resolves fast even where GPS
 *      can't (indoors, dense foliage). Whichever of 3/4 finishes first wins;
 *      the loser is cancelled. Its reply is marked approximate.
 *
 * This means a slow or unavailable GPS fix no longer means "no reply": worst
 * case the requester gets an approximate, network-based location instead of
 * waiting out the full deadline. Only if every stage fails outright (no
 * location services at all, permission revoked, or truly no signal of any
 * kind for the full 60s) do we send an error reply back, so the requester
 * isn't left wondering whether the trigger reached us at all.
 *
 * Three send modes:
 *
 *   - [getCurrentLocationAndReply] — SMS send via [SmsSender].
 *   - [getCurrentLocationAndReplyViaNotification] — chat-app inline reply
 *     via the original notification's [Notification.Action] `RemoteInput`.
 *   - [handleWidgetTaps] — SMS fan-out to multiple destinations based on
 *     widget tap count (2/3/4 taps).
 */
object LocationHelper {
    private const val TAG = "QuicLoc.LocationHelper"

    // Total time we'll wait across all stages before giving up. Extended
    // from the original 30s: the foreground service that calls this has no
    // OS-imposed time limit, so it's worth giving a stubborn GPS cold start
    // more room rather than erroring out on someone who'd rather wait a bit
    // longer for a fix than get nothing.
    private const val LOCATION_TIMEOUT_MS = 60_000L

    // If stage 3's forced high-accuracy fix hasn't resolved within this long,
    // race a fast, low-accuracy network/cell fallback alongside it.
    private const val COARSE_FALLBACK_DELAY_MS = 15_000L

    // A location this coarse (radius, meters) is assumed to be a network/cell
    // fix rather than a real GPS one, worth flagging to the recipient as
    // approximate rather than precise.
    private const val APPROXIMATE_ACCURACY_THRESHOLD_M = 100f

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
                val sent = SmsSender.send(context, phoneNumber, "QuicLoc Location:\n$mapsLink${locationQualityNote(location)}")
                onResult?.invoke(sent)
            },
            onFailure = { msg ->
                SmsSender.send(context, phoneNumber, "QuicLoc Error: $msg")
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
                val sent = sendNotificationReply(context, replyAction, "QuicLoc: $mapsLink${locationQualityNote(location)}")
                onResult?.invoke(sent)
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
        // Real, dialable phone numbers only -- getNumbers()/getStarredNumbers()
        // return *display tokens*, which are the contact's NAME for a
        // name-only whitelist entry. Passing a name straight to SmsManager as
        // a destination address doesn't send anything.
        val destinations = mutableListOf<String>()
        var suffix = ""

        if (tapCount == 2) {
            val myNumber = whitelistManager.getMyNumber()
            if (myNumber.isNotBlank()) {
                destinations.add(myNumber)
            }
            suffix = " #Parking"
        } else if (tapCount == 3) {
            destinations.addAll(whitelistManager.getDialableStarredNumbers())
            suffix = " #SafetyCheck"
        } else if (tapCount >= 4) {
            destinations.addAll(whitelistManager.getDialableNumbers())
            suffix = " #Emergency"
        }

        if (destinations.isEmpty()) {
            Log.e(TAG, "No dialable destinations configured for tap count $tapCount")
            onResult?.invoke(false)
            return
        }

        fetchLocation(context,
            onSuccess = { location ->
                val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                val message = "QuicLoc Location:\n$mapsLink${locationQualityNote(location)}$suffix"
                // Report success only if every destination's send call actually
                // went through -- History/Diagnostics should never claim an
                // emergency alert succeeded when it silently didn't.
                val allSent = destinations.map { SmsSender.send(context, it, message) }.all { it }
                onResult?.invoke(allSent)
            },
            onFailure = { msg ->
                val message = "QuicLoc Error: $msg$suffix"
                for (dest in destinations) {
                    SmsSender.send(context, dest, message)
                }
                onResult?.invoke(false)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Core location fetch — four-stage fallback with a single 60s deadline:
    //
    //   Stage 1: getCurrentLocation() HIGH_ACCURACY
    //            Fast if a recent fix exists. Returns null on cold start.
    //
    //   Stage 2: lastLocation
    //            Instant. May be stale but better than nothing if stages 1,
    //            3 & 4 all fail.
    //
    //   Stage 3: requestLocationUpdates() HIGH_ACCURACY
    //            Forces the GPS radio on. Always works if location services are
    //            enabled. May take 20-30 seconds on a cold start outdoors.
    //
    //   Stage 4: getCurrentLocation() BALANCED_POWER_ACCURACY, raced alongside
    //            stage 3 once it's run for COARSE_FALLBACK_DELAY_MS without
    //            resolving. Network/cell-based, so it can return a fix where
    //            GPS can't (indoors, no sky view). Whichever of 3/4 finishes
    //            first wins; the loser is cancelled.
    //
    // The 60-second deadline applies to the entire chain. If every stage is
    // exhausted or time runs out, onFailure is called and the service stops.
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
        // Stage 3's callback, if one gets registered, so the deadline handler
        // can unregister it instead of leaking a live LocationCallback (and
        // potentially keeping the GPS radio on) past the point this whole
        // fetch has already given up.
        var activeCallback: LocationCallback? = null
        // Stage 4's cancellation token, if a coarse fallback attempt is in
        // flight, so it can be called off the moment stage 3 (or the
        // deadline) beats it to a result instead of running to completion
        // pointlessly.
        var activeCoarseCancellation: CancellationTokenSource? = null

        fun finish(success: Boolean, location: Location? = null, msg: String = "") {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            activeCallback?.let { fusedClient.removeLocationUpdates(it) }
            activeCallback = null
            activeCoarseCancellation?.cancel()
            activeCoarseCancellation = null
            if (success && location != null) onSuccess(location) else onFailure(msg)
        }

        // Hard deadline — fires if all stages are still running
        handler.postDelayed({
            finish(false, msg = "Location timed out after ${LOCATION_TIMEOUT_MS / 1000}s. Is GPS enabled?")
        }, LOCATION_TIMEOUT_MS)

        // Each stage gets its own try/catch for SecurityException: they run
        // inside async completion-listener callbacks, not lexically inside a
        // single outer try block's protected frame, so revoking location
        // permission in the gap between stages would otherwise crash the app
        // (an uncaught SecurityException on a later stage) instead of failing
        // gracefully like a permission that was never granted.
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
                        try {
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
                                            activeCallback = null
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
                                                activeCallback = null
                                                fusedClient.removeLocationUpdates(this)
                                                finish(false, msg = "Location services are disabled on this device.")
                                            }
                                        }
                                    }
                                    try {
                                        activeCallback = callback
                                        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                                    } catch (e: SecurityException) {
                                        Log.e(TAG, "Missing location permission (stage 3)", e)
                                        activeCallback = null
                                        finish(false, msg = "Location permission not granted.")
                                        return@addOnCompleteListener
                                    }

                                    // Stage 4 (raced against stage 3): if the forced
                                    // GPS fix hasn't resolved after
                                    // COARSE_FALLBACK_DELAY_MS, also try a fast
                                    // network/cell fix. A slow or unavailable GPS
                                    // fix should mean "approximate reply", not
                                    // "no reply". Skipped if there isn't enough
                                    // time left for it to plausibly help.
                                    if (timeLeft > COARSE_FALLBACK_DELAY_MS) {
                                        handler.postDelayed({
                                            if (done) return@postDelayed
                                            try {
                                                val coarseCts = CancellationTokenSource()
                                                activeCoarseCancellation = coarseCts
                                                fusedClient.getCurrentLocation(
                                                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, coarseCts.token
                                                ).addOnCompleteListener { coarseTask ->
                                                    activeCoarseCancellation = null
                                                    if (done) return@addOnCompleteListener
                                                    val coarseLoc = coarseTask.result
                                                    if (coarseTask.isSuccessful && coarseLoc != null) {
                                                        Log.d(TAG, "Stage 4 (coarse) success while stage 3 still pending")
                                                        finish(true, coarseLoc)
                                                    }
                                                    // Failure here just means stage 3 (or the
                                                    // deadline) decides the outcome instead.
                                                }
                                            } catch (e: SecurityException) {
                                                Log.w(TAG, "Stage 4 (coarse) failed to start", e)
                                            }
                                        }, COARSE_FALLBACK_DELAY_MS)
                                    }
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Missing location permission (stage 2)", e)
                            finish(false, msg = "Location permission not granted.")
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission (stage 1)", e)
            finish(false, msg = "Location permission not granted.")
        }
    }

    /**
     * A short note to append to a location reply when the fix might be
     * noticeably stale (Stage 2's cached `lastLocation`) and/or approximate
     * (Stage 4's network/cell fallback), so the recipient doesn't mistake an
     * old or coarse position for a live, precise one. Silent for a fresh,
     * precise fix.
     */
    private fun locationQualityNote(location: Location): String {
        val notes = mutableListOf<String>()
        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs >= 60_000L) notes.add("as of ~${ageMs / 60_000L} min ago")
        if (location.hasAccuracy() && location.accuracy > APPROXIMATE_ACCURACY_THRESHOLD_M) {
            notes.add("approximate — accurate to ~${location.accuracy.toInt()}m")
        }
        return if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
    }

    // -------------------------------------------------------------------------
    // Notification inline reply
    // -------------------------------------------------------------------------

    /** @return Whether the reply's PendingIntent was actually sent without throwing. */
    private fun sendNotificationReply(
        context: Context,
        action: Notification.Action,
        replyText: String
    ): Boolean {
        return try {
            val remoteInputs = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()
            for (remoteInput in remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(context, 0, intent)
            Log.d(TAG, "Sent notification reply: $replyText")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification reply", e)
            false
        }
    }
}
