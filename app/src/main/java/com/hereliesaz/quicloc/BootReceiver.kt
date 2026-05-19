package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the reminder notification after device reboot so the user can see
 * QuicLoc's enabled/disabled state and toggle without opening the app.
 *
 * The notification channel and notification itself are re-created from
 * persisted [AppSettings] state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        ReminderNotification.refresh(context)
    }
}
