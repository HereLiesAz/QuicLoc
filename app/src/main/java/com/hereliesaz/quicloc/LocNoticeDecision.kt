package com.hereliesaz.quicloc

/** What [GeofenceBroadcastReceiver] should do about one geofence transition, before dedupe/flap suppression is even considered. */
sealed class LocNoticeAction {
    /** Text [recipients] with [message]. */
    data class Send(val recipients: List<String>, val message: String) : LocNoticeAction()

    /** Nothing to send, and why — becomes the Diagnostics row. */
    data class Skip(val outcome: DiagOutcome, val reason: String) : LocNoticeAction()
}

/**
 * The pure decision logic behind a Loc Notice transition: given the place
 * and the current whitelist, is this an arrival/departure worth a text, and
 * to whom? Deliberately has no Android/Play Services dependency (unlike
 * [GeofenceBroadcastReceiver], which needs a real `GeofencingEvent`) so the
 * one line that decides whether an SMS goes out —
 * `if (isEnter) entry.notifyOnEnter else entry.notifyOnExit` — is directly
 * unit-testable rather than only reachable through a live geofence event.
 *
 * Dedupe/flap suppression ([GeofenceStateStore]) is deliberately NOT part of
 * this decision — it's evaluated by the caller only for a [Send] result, so
 * a direction the user never wanted (a [Skip]) never touches that state.
 */
object LocNoticeDecision {
    fun decide(
        entry: GeofenceEntry?,
        isEnter: Boolean,
        contacts: List<WhitelistManager.ContactEntry>,
    ): LocNoticeAction {
        if (entry == null || !entry.enabled) {
            return LocNoticeAction.Skip(
                DiagOutcome.LOCNOTICE_ENTRY_MISSING,
                "Geofence fired but the location no longer exists or is disabled."
            )
        }

        val directionOn = if (isEnter) entry.notifyOnEnter else entry.notifyOnExit
        if (!directionOn) {
            return LocNoticeAction.Skip(
                DiagOutcome.LOCNOTICE_DIRECTION_OFF,
                "Notify on ${if (isEnter) "arrival" else "departure"} is off for this location."
            )
        }

        val recipients = contacts
            .filter { it.displayToken in entry.contactTokens }
            .mapNotNull { it.number.takeIf(String::isNotEmpty) }
        if (recipients.isEmpty()) {
            return LocNoticeAction.Skip(
                DiagOutcome.LOCNOTICE_NO_CONTACTS,
                "No configured contact resolves to a dialable number."
            )
        }

        val verb = if (isEnter) "arrived at" else "left"
        return LocNoticeAction.Send(recipients, "QuicLoc Loc Notice: $verb \"${entry.name}\"")
    }
}
