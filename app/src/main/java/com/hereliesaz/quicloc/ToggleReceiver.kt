package com.hereliesaz.quicloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles taps on the reminder-notification action button. Flips
 * [AppSettings.isEnabled] and re-posts the notification so the button label
 * updates immediately.
 */
class ToggleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE = "com.hereliesaz.quicloc.ACTION_TOGGLE_ENABLED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return
        val now = AppSettings.isEnabled(context)
        AppSettings.setEnabled(context, !now)
        ReminderNotification.refresh(context)
    }
}
