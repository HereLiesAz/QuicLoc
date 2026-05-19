package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Defaults + round-trip for every flag in [AppSettings]. These are the
 * settings that auto-back-up via Android Auto Backup (plain prefs); any
 * regression here breaks user-visible "remembered my preference" behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate for every test — these prefs persist across the same
        // application instance otherwise.
        context.deleteSharedPreferences("quicloc_app_settings")
    }

    @Test
    fun `isEnabled defaults to true`() {
        assertTrue(AppSettings.isEnabled(context))
    }

    @Test
    fun `setEnabled persists across reads`() {
        AppSettings.setEnabled(context, false)
        assertFalse(AppSettings.isEnabled(context))
        AppSettings.setEnabled(context, true)
        assertTrue(AppSettings.isEnabled(context))
    }

    @Test
    fun `reminder notification opt-in defaults to false`() {
        assertFalse(AppSettings.isReminderNotificationEnabled(context))
    }

    @Test
    fun `reminder notification opt-in round-trips`() {
        AppSettings.setReminderNotificationEnabled(context, true)
        assertTrue(AppSettings.isReminderNotificationEnabled(context))
        AppSettings.setReminderNotificationEnabled(context, false)
        assertFalse(AppSettings.isReminderNotificationEnabled(context))
    }

    @Test
    fun `device-admin-prompted flag defaults to false and persists once marked`() {
        assertFalse(AppSettings.wasDeviceAdminPrompted(context))
        AppSettings.markDeviceAdminPrompted(context)
        assertTrue(AppSettings.wasDeviceAdminPrompted(context))
    }

    @Test
    fun `full-screen-intent-prompted flag defaults to false and persists once marked`() {
        assertFalse(AppSettings.wasFullScreenIntentPrompted(context))
        AppSettings.markFullScreenIntentPrompted(context)
        assertTrue(AppSettings.wasFullScreenIntentPrompted(context))
    }

    @Test
    fun `phone-hint-prompted flag defaults to false and persists once marked`() {
        assertFalse(AppSettings.wasPhoneHintAutoPrompted(context))
        AppSettings.markPhoneHintAutoPrompted(context)
        assertTrue(AppSettings.wasPhoneHintAutoPrompted(context))
    }

    @Test
    fun `prompted flags are independent of each other`() {
        AppSettings.markDeviceAdminPrompted(context)
        assertFalse(AppSettings.wasPhoneHintAutoPrompted(context))
        assertFalse(AppSettings.wasFullScreenIntentPrompted(context))
    }
}
