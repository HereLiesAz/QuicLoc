package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the reminder notification after device reboot so the user can see
 * QuicLoc's enabled/disabled state and toggle without opening the app.
 *
 * The notification channel and notification itself are re-created from
 * persisted [AppSettings] state. Also re-registers Loc Notice's geofences,
 * since Android drops all `GeofencingClient` registrations on reboot —
 * [GeofenceRegistrar.sync] is a cheap no-op if Loc Notice is off or has no
 * locations, so no extra gating is needed here. Also resumes an in-progress
 * find-my-phone tracking session — see [FindMyPhone.resumeTrackingAfterBoot]
 * for why that needs its own explicit boot-time recovery.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        ReminderNotification.refresh(context)
        GeofenceRegistrar.sync(context)
        FindMyPhone.resumeTrackingAfterBoot(context)
    }
}
