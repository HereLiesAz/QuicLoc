package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Fires on `ACTION_GEOFENCE_EVENT`, delivered by Play Services when a
 * monitored place is entered or exited (see [GeofenceRegistrar]).
 *
 * Uses [goAsync] rather than the foreground-service pattern
 * [LocationReplyService] uses for on-demand requests: unlike that path, this
 * one needs no fresh GPS fetch — [GeofencingEvent.triggeringLocation]
 * already carries a location — so there's no 20-60s wait to cover, and
 * `goAsync()`'s ~10s budget is comfortably enough for prefs reads + SMS
 * sends.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuicLoc.GeofenceRx"
        const val ACTION_GEOFENCE_EVENT = "com.hereliesaz.quicloc.ACTION_GEOFENCE_EVENT"
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_EVENT) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        executor.execute {
            try {
                handleEvent(appContext, event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling geofence event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleEvent(context: Context, event: GeofencingEvent) {
        if (event.hasError()) {
            Log.e(TAG, "GeofencingEvent error code ${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) return
        val isEnter = transition == Geofence.GEOFENCE_TRANSITION_ENTER

        val store = GeofenceStore(context)
        val whitelist = WhitelistManager(context)
        val history = RequestHistoryManager(context)
        val diagnostics = DiagnosticLogManager(context)

        for (geofence in event.triggeringGeofences.orEmpty()) {
            val id = geofence.requestId
            when (GeofenceStateStore.evaluate(context, id, transition)) {
                GeofenceStateStore.Result.DUPLICATE -> continue
                GeofenceStateStore.Result.FLAP_SUPPRESSED -> {
                    diagnostics.record(diagEvent(id, isEnter, "(suppressed)", DiagOutcome.LOCNOTICE_FLAP_SUPPRESSED, "Repeated crossing within the cooldown window — no text sent."))
                    continue
                }
                GeofenceStateStore.Result.PROCESS -> {
                    processTransition(context, store, whitelist, history, diagnostics, id, isEnter)
                }
            }
        }
    }

    private fun processTransition(
        context: Context,
        store: GeofenceStore,
        whitelist: WhitelistManager,
        history: RequestHistoryManager,
        diagnostics: DiagnosticLogManager,
        geofenceId: String,
        isEnter: Boolean,
    ) {
        val entry = store.get(geofenceId)
        if (entry == null || !entry.enabled) {
            diagnostics.record(diagEvent(geofenceId, isEnter, "(deleted)", DiagOutcome.LOCNOTICE_ENTRY_MISSING, "Geofence fired but the location no longer exists or is disabled."))
            return
        }

        val directionOn = if (isEnter) entry.notifyOnEnter else entry.notifyOnExit
        if (!directionOn) {
            diagnostics.record(diagEvent(geofenceId, isEnter, entry.name, DiagOutcome.LOCNOTICE_DIRECTION_OFF, "Notify on ${if (isEnter) "arrival" else "departure"} is off for this location."))
            return
        }

        val contacts = whitelist.getContacts()
            .filter { it.displayToken in entry.contactTokens }
            .mapNotNull { it.number.takeIf(String::isNotEmpty) }

        if (contacts.isEmpty()) {
            diagnostics.record(diagEvent(geofenceId, isEnter, entry.name, DiagOutcome.LOCNOTICE_NO_CONTACTS, "No configured contact resolves to a dialable number."))
            history.record(sender = entry.name, source = "Loc Notice (${if (isEnter) "arrived" else "left"})", succeeded = false)
            return
        }

        val verb = if (isEnter) "arrived at" else "left"
        val message = "QuicLoc Loc Notice: $verb \"${entry.name}\""
        val allSent = contacts.map { SmsSender.send(context, it, message) }.all { it }

        history.record(sender = entry.name, source = "Loc Notice (${if (isEnter) "arrived" else "left"})", succeeded = allSent)
        diagnostics.record(diagEvent(
            geofenceId, isEnter, entry.name,
            if (allSent) DiagOutcome.LOCNOTICE_SENT else DiagOutcome.LOCNOTICE_SEND_FAILED,
            "Notified ${contacts.size} contact(s)."
        ))
    }

    private fun diagEvent(geofenceId: String, isEnter: Boolean, source: String, outcome: DiagOutcome, reason: String) =
        DiagnosticEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            channel = DiagChannel.GEOFENCE,
            source = source,
            rawSender = "geofence:$geofenceId",
            rawBody = if (isEnter) "ENTER" else "EXIT",
            triggerMatched = true,
            whitelistMatched = null,
            extractionPath = null,
            outcome = outcome,
            reason = reason,
        )
}
