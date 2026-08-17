package com.hereliesaz.quicloc

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The only file that talks to `GeofencingClient`. [sync] fully reconciles
 * the OS-level registration with [GeofenceStore]'s current desired state —
 * remove everything, then re-add whatever should be active. At Loc Notice's
 * expected scale (a handful of named places) a full remove-then-re-add is
 * cheap, and it means every mutation call site is just "mutate the store,
 * then sync" with no way for an OS-level registration to drift from the
 * store (e.g. a deleted place that keeps firing).
 *
 * `sync()` calls are serialized onto a single background thread rather than
 * fired as independent async chains: `removeGeofences`/`addGeofences` are
 * both async Play Services calls, and two overlapping sync()s (e.g. saving a
 * location and immediately deleting it) could otherwise interleave —
 * a stale add from the first call landing after the second call's remove
 * would resurrect a just-deleted geofence. Running each sync to completion
 * (via [Tasks.await], safe here because it's off the calling thread) before
 * the next one starts removes that race entirely.
 */
object GeofenceRegistrar {
    private const val TAG = "QuicLoc.GeofenceReg"
    private const val AWAIT_TIMEOUT_S = 30L

    private val executor = Executors.newSingleThreadExecutor()

    fun sync(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                syncBlocking(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
            }
        }
    }

    private fun syncBlocking(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)

        try {
            Tasks.await(client.removeGeofences(pi), AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Whatever the cause (transient GMS error, timeout, no prior
            // registration to remove), don't let a failed removal silently
            // leave stale geofences un-reconciled -- GeofenceBroadcastReceiver
            // separately re-checks the master toggle at fire time as a
            // backstop, but logging this loudly means it isn't invisible.
            Log.e(TAG, "removeGeofences failed — registration may be stale until the next sync", e)
        }

        registerCurrentSet(context, client, pi)
    }

    private fun registerCurrentSet(
        context: Context,
        client: GeofencingClient,
        pi: PendingIntent,
    ) {
        if (!AppSettings.isLocNoticeEnabled(context)) return
        if (!hasBackgroundLocation(context)) {
            Log.w(TAG, "Loc Notice enabled but background location isn't granted — nothing registered")
            return
        }

        val entries = selectForRegistration(GeofenceStore(context).getAll())
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
            Tasks.await(client.addGeofences(request, pi), AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission for geofencing", e)
        } catch (e: Exception) {
            Log.e(TAG, "addGeofences failed", e)
        }
    }

    /**
     * Which stored entries should actually be registered with Play
     * Services: enabled only, capped at [GeofenceEntry.MAX_GEOFENCES],
     * newest-first. A pure function of the input list — deliberately
     * separated from [registerCurrentSet] so the truncation/ordering logic
     * is directly unit-testable without a real `GeofencingClient`.
     *
     * Sorting by [GeofenceEntry.createdAt] before truncating matters
     * because `SharedPreferences`' `StringSet` has no defined iteration
     * order — without it, an over-the-cap truncation would silently drop an
     * arbitrary location (which one changes on every add/remove) rather
     * than consistently keeping the newest ones.
     */
    fun selectForRegistration(entries: List<GeofenceEntry>): List<GeofenceEntry> =
        entries
            .filter { it.enabled }
            .sortedByDescending { it.createdAt }
            .take(GeofenceEntry.MAX_GEOFENCES)

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
