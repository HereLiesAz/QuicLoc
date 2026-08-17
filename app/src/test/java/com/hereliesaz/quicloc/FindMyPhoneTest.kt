package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindMyPhoneTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // These pin FindMyPhone.ENABLED's current shipped value (false): every
    // entry point must degrade to a safe no-op/false, never throw, and never
    // touch the module it can't reference. When ENABLED flips to true this
    // test starts asserting the opposite half of the contract and needs a
    // matching update (see TutorialsTest for the established both-branches
    // pattern this codebase uses for that flag).
    @Test
    fun `every entry point is a safe no-op while the feature is disabled`() {
        assertFalse(FindMyPhone.isInstalled(context))
        assertFalse(FindMyPhone.isAdminActive(context))
        assertFalse(FindMyPhone.trigger(context, "+15551234567", "SMS"))
        assertFalse(FindMyPhone.wasTrackingActive(context))
        // No exception, no crash, nothing started.
        FindMyPhone.requestInstall(context)
        FindMyPhone.resumeTrackingAfterBoot(context)
    }

    @Test
    fun `wasTrackingActive stays false even with a persisted session, while disabled`() {
        // A persisted "sender" in quicloc_tracking_state would mean a real
        // tracking session while enabled, but the ENABLED gate must still
        // win here -- a disabled build must never act on state a previous
        // enabled build (or a restored backup) might have left behind.
        context.getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
            .edit()
            .putString("sender", "+15551234567")
            .commit()

        assertFalse(FindMyPhone.wasTrackingActive(context))
    }

    @Test
    fun `resumeTrackingAfterBoot does not start anything without a persisted session`() {
        // isInstalled() also returns false while disabled, so this is
        // already covered by the ENABLED gate, but pin the intermediate
        // wasTrackingActive() contract too: an empty/absent tracking-state
        // file must never be read as "was tracking".
        assertFalse(FindMyPhone.wasTrackingActive(context))
        FindMyPhone.resumeTrackingAfterBoot(context)
    }
}
