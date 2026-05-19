package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-sensitive app-wide settings. Lives in plain SharedPreferences so the
 * BroadcastReceivers / NotificationListenerService can read it without paying
 * the EncryptedSharedPreferences decryption cost on every event.
 */
object AppSettings {
    private const val PREFS_FILE = "quicloc_app_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REMINDER_NOTIF = "reminder_notification_enabled"
    private const val KEY_DEVICE_ADMIN_PROMPTED = "device_admin_prompted"
    private const val KEY_FULL_SCREEN_PROMPTED = "full_screen_intent_prompted"
    private const val KEY_PHONE_HINT_PROMPTED = "phone_hint_prompted"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Whether the persistent reminder notification (with the quick-toggle
     * action) is shown. Opt-in — default off.
     */
    fun isReminderNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER_NOTIF, false)

    fun setReminderNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER_NOTIF, enabled).apply()
    }

    fun wasDeviceAdminPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_ADMIN_PROMPTED, false)

    fun markDeviceAdminPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEVICE_ADMIN_PROMPTED, true).apply()
    }

    fun wasFullScreenIntentPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_SCREEN_PROMPTED, false)

    fun markFullScreenIntentPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_FULL_SCREEN_PROMPTED, true).apply()
    }

    /**
     * Whether we've already auto-shown the Phone Number Hint sheet for this
     * install. Used so we only auto-prompt once — if the user cancels, they
     * can still trigger it manually via the button in the My Phone Number card.
     */
    fun wasPhoneHintAutoPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PHONE_HINT_PROMPTED, false)

    fun markPhoneHintAutoPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PHONE_HINT_PROMPTED, true).apply()
    }
}
