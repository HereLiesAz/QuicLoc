package com.hereliesaz.quicloc

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * The only file that talks to `GeofencingClient`. [sync] fully reconciles
 * the OS-level registration with [GeofenceStore]'s current desired state —
 * remove everything, then re-add whatever should be active. At Loc Notice's
 * expected scale (a handful of named places) a full remove-then-re-add is
 * cheap, and it means every mutation call site is just "mutate the store,
 * then sync" with no way for an OS-level registration to drift from the
 * store (e.g. a deleted place that keeps firing).
 */
object GeofenceRegistrar {
    private const val TAG = "QuicLoc.GeofenceReg"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val client = LocationServices.getGeofencingClient(appContext)
        val pi = pendingIntent(appContext)

        client.removeGeofences(pi).addOnCompleteListener {
            registerCurrentSet(appContext, client, pi)
        }
    }

    private fun registerCurrentSet(
        context: Context,
        client: com.google.android.gms.location.GeofencingClient,
        pi: PendingIntent,
    ) {
        if (!AppSettings.isLocNoticeEnabled(context)) return
        if (!hasBackgroundLocation(context)) {
            Log.w(TAG, "Loc Notice enabled but background location isn't granted — nothing registered")
            return
        }

        val entries = GeofenceStore(context).getAll()
            .filter { it.enabled }
            .take(GeofenceEntry.MAX_GEOFENCES)
        if (entries.isEmpty()) return

        // Every enabled entry is registered for BOTH directions regardless of
        // its own notifyOnEnter/notifyOnExit — GeofencingRequest's
        // setInitialTrigger is a single bitmask for the whole batched
        // request, not per-geofence, so per-entry direction filtering has to
        // happen downstream in GeofenceBroadcastReceiver instead. This also
        // means flipping a direction toggle later never needs a re-sync.
        val request = GeofencingRequest.Builder()
            // Deliberately suppress the synthetic "already inside" callback
            // Play Services would otherwise fire immediately on registration.
            // Without this, every reboot re-registration (see BootReceiver)
            // would refire "arrived" for anyone already at a monitored place
            // when their phone restarts — this is meant to alert on genuine
            // crossings, not report current status.
            .setInitialTrigger(0)
            .addGeofences(entries.map { it.toGeofence() })
            .build()

        try {
            client.addGeofences(request, pi)
                .addOnFailureListener { e -> Log.e(TAG, "addGeofences failed", e) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission for geofencing", e)
        }
    }

    private fun hasBackgroundLocation(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            .setAction(GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT)
        // MUTABLE is required — Play Services fills in the GeofencingEvent
        // extras itself when it delivers the broadcast. FLAG_IMMUTABLE would
        // silently break delivery.
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun GeofenceEntry.toGeofence(): Geofence = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(latitude, longitude, radiusMeters)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()
}
