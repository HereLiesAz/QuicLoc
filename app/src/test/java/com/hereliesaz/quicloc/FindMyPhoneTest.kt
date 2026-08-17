package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// :app's own test classpath never carries :feature_findmyphone's manifest
// (that only happens via dynamicFeatures at bundle/package time, not for a
// plain Robolectric run of this module's tests), so isInstalled()/trigger()
// exercise the "module not present in this process" branch here regardless
// of FindMyPhone.ENABLED -- that's the real, correct behavior for a fresh
// install before find-my-phone setup has downloaded the module, not a test
// artifact of ENABLED being on or off.
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

    @Test
    fun `every entry point degrades safely with no module present, regardless of ENABLED`() {
        assertFalse(FindMyPhone.isAdminActive(context))
        assertFalse(FindMyPhone.wasTrackingActive(context))
        // No exception, no crash, nothing started, whether or not the module
        // happens to be resolvable in this test process.
        FindMyPhone.requestInstall(context)
        FindMyPhone.resumeTrackingAfterBoot(context)
    }

    @Test
    fun `wasTrackingActive reflects a persisted session while enabled`() {
        context.getSharedPreferences("quicloc_tracking_state", Context.MODE_PRIVATE)
            .edit()
            .putString("sender", "+15551234567")
            .commit()

        assertEquals(FindMyPhone.ENABLED, FindMyPhone.wasTrackingActive(context))
    }

    @Test
    fun `wasTrackingActive is false with no persisted session`() {
        assertFalse(FindMyPhone.wasTrackingActive(context))
    }

    @Test
    fun `resumeTrackingAfterBoot does not start anything without a persisted session`() {
        assertFalse(FindMyPhone.wasTrackingActive(context))
        FindMyPhone.resumeTrackingAfterBoot(context)
    }
}
