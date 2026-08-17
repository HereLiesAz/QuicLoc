package com.hereliesaz.quicloc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsHandoffTest {

    private lateinit var activity: Activity
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    /**
     * Registers a fake resolver for `geo:` VIEW intents, matching what a
     * real device with Google Maps (or any maps app) installed looks like
     * once AndroidManifest.xml's `<queries>` block makes it visible to this
     * app's package-visibility-filtered `resolveActivity` check. Without
     * this, Robolectric's default shadow `PackageManager` has nothing
     * registered for an implicit `geo:` intent, so `MapsHandoff` correctly
     * falls back to the chooser -- see the "no maps app installed" tests
     * below, which deliberately do NOT call this.
     */
    private fun registerFakeMapsApp() {
        val packageManager = activity.packageManager
        val shadowPm = shadowOf(packageManager)
        val component = ComponentName("com.example.fakemaps", "com.example.fakemaps.MapsActivity")
        shadowPm.addActivityIfNotPresent(component)
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("geo")
        }
        shadowPm.addIntentFilterForActivity(component, filter)
    }

    // ---- openInMaps --------------------------------------------------------

    @Test
    fun `openInMaps copies the address to the clipboard`() {
        MapsHandoff.openInMaps(activity, "1 Infinite Loop")
        val clipped = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()
        assertEquals("1 Infinite Loop", clipped)
    }

    @Test
    fun `openInMaps launches Maps directly when a maps app is installed`() {
        registerFakeMapsApp()
        MapsHandoff.openInMaps(activity, "1 Infinite Loop")
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started != null)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals("geo", started.data?.scheme)
        assertTrue("expected the address in the geo query", started.data.toString().contains(Uri.encode("1 Infinite Loop")))
    }

    @Test
    fun `openInMaps falls back to a chooser when no maps app is installed`() {
        MapsHandoff.openInMaps(activity, "1 Infinite Loop")
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started != null)
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
    }

    // ---- pasteFromClipboard -------------------------------------------------

    @Test
    fun `pasteFromClipboard parses a valid coordinate pair from the clipboard`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("", "37.422131, -122.084801"))
        val result = MapsHandoff.pasteFromClipboard(activity)
        assertEquals(37.422131 to -122.084801, result)
    }

    @Test
    fun `pasteFromClipboard returns null for non-coordinate clipboard content`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("", "just some text"))
        assertNull(MapsHandoff.pasteFromClipboard(activity))
    }

    @Test
    fun `pasteFromClipboard returns null when the clipboard is empty`() {
        clipboard.clearPrimaryClip()
        assertNull(MapsHandoff.pasteFromClipboard(activity))
    }

    // ---- viewCoordinatesInMaps ----------------------------------------------

    @Test
    fun `viewCoordinatesInMaps launches Maps directly when a maps app is installed`() {
        registerFakeMapsApp()
        MapsHandoff.viewCoordinatesInMaps(activity, 37.422131, -122.084801)
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started != null)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals("geo", started.data?.scheme)
        assertTrue(started.data.toString().contains("37.422131,-122.084801"))
    }

    @Test
    fun `viewCoordinatesInMaps falls back to a chooser when no maps app is installed`() {
        MapsHandoff.viewCoordinatesInMaps(activity, 37.422131, -122.084801)
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started != null)
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
    }
}
