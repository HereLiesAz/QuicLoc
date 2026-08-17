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

        // Registration-time gating (GeofenceRegistrar.sync) can lag reality
        // if a removeGeofences() call failed silently -- checking the master
        // toggle here too means turning Loc Notice off actually stops texts
        // from going out, not just stops future registrations. Re-sync so
        // the stale Play Services registration gets another chance to clear.
        if (!AppSettings.isLocNoticeEnabled(context)) {
            Log.w(TAG, "Geofence event received while Loc Notice is off — ignoring, re-syncing registration")
            GeofenceRegistrar.sync(context)
            return
        }

        val store = GeofenceStore(context)
        val whitelist = WhitelistManager(context)
        val history = RequestHistoryManager(context)
        val diagnostics = DiagnosticLogManager(context)

        for (geofence in event.triggeringGeofences.orEmpty()) {
            processTransition(context, store, whitelist, history, diagnostics, geofence.requestId, isEnter, transition)
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
        transition: Int,
    ) {
        val entry = store.get(geofenceId)
        // The actual "does this fire a text, and to whom" decision is a
        // pure function (see LocNoticeDecision) so it's unit-testable
        // without a real GeofencingEvent -- this method is just the I/O
        // shell around it (state-store dedupe, SMS send, logging).
        val decision = LocNoticeDecision.decide(entry, isEnter, whitelist.getContacts())
        if (decision is LocNoticeAction.Skip) {
            diagnostics.record(diagEvent(geofenceId, isEnter, entry?.name ?: "(deleted)", decision.outcome, decision.reason))
            if (decision.outcome == DiagOutcome.LOCNOTICE_NO_CONTACTS) {
                history.record(sender = entry!!.name, source = "Loc Notice (${if (isEnter) "arrived" else "left"})", succeeded = false)
            }
            return
        }
        decision as LocNoticeAction.Send
        val name = entry!!.name

        // Dedupe/flap-suppress only among transitions that would actually
        // send a text -- checked after the direction gate above (folded
        // into LocNoticeDecision.decide), so a direction the user never
        // wanted can't poison the cooldown state for the direction they do
        // want (see GeofenceStateStore's doc).
        when (GeofenceStateStore.evaluate(context, geofenceId, transition)) {
            GeofenceStateStore.Result.DUPLICATE -> return
            GeofenceStateStore.Result.FLAP_SUPPRESSED -> {
                diagnostics.record(diagEvent(geofenceId, isEnter, name, DiagOutcome.LOCNOTICE_FLAP_SUPPRESSED, "Repeated crossing within the cooldown window — no text sent."))
                return
            }
            GeofenceStateStore.Result.PROCESS -> Unit
        }

        val allSent = decision.recipients.map { SmsSender.send(context, it, decision.message) }.all { it }

        history.record(sender = name, source = "Loc Notice (${if (isEnter) "arrived" else "left"})", succeeded = allSent)
        diagnostics.record(diagEvent(
            geofenceId, isEnter, name,
            if (allSent) DiagOutcome.LOCNOTICE_SENT else DiagOutcome.LOCNOTICE_SEND_FAILED,
            "Notified ${decision.recipients.size} contact(s)."
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
