package com.hereliesaz.quicloc

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manifest sanity — every receiver, service, and activity we depend on
 * must be declared in `AndroidManifest.xml`. A registration regression here
 * means the corresponding feature silently stops working.
 *
 * Note: Robolectric reads the merged manifest. If a test fails with
 * `NameNotFoundException`, the component is missing from the manifest or
 * is in the wrong package.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestRegistrationTest {

    private lateinit var context: Context
    private lateinit var pm: PackageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        pm = context.packageManager
    }

    // ---- broadcast receivers --------------------------------------------

    @Test
    fun `widget provider is registered and exported`() {
        val info = pm.getReceiverInfo(
            ComponentName(context, QuicLocWidgetProvider::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotNull(info)
        assertTrue("QuicLocWidgetProvider should be exported", info.exported)
    }

    @Test
    fun `widget provider responds to APPWIDGET_UPDATE`() {
        val receivers = pm.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            0
        )
        assertTrue(
            "QuicLocWidgetProvider should handle APPWIDGET_UPDATE",
            receivers.any { it.activityInfo.name == QuicLocWidgetProvider::class.java.name }
        )
    }

    @Test
    fun `SmsReceiver is registered`() {
        val info = pm.getReceiverInfo(
            ComponentName(context, SmsReceiver::class.java),
            0
        )
        assertNotNull(info)
        assertTrue("SmsReceiver must be exported for the system SMS broadcast", info.exported)
    }

    @Test
    fun `ToggleReceiver is registered and NOT exported`() {
        val info = pm.getReceiverInfo(
            ComponentName(context, ToggleReceiver::class.java),
            0
        )
        assertNotNull(info)
        // Internal-only — only the reminder notification's PendingIntent
        // should be able to fire it.
        assertTrue("ToggleReceiver must NOT be exported", !info.exported)
    }

    @Test
    fun `BootReceiver is registered and exported`() {
        val info = pm.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            0
        )
        assertNotNull(info)
        assertTrue(
            "BootReceiver must be exported to receive BOOT_COMPLETED",
            info.exported
        )
    }

    @Test
    fun `BootReceiver responds to BOOT_COMPLETED`() {
        val receivers = pm.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED),
            0
        )
        assertTrue(
            "BootReceiver should handle BOOT_COMPLETED",
            receivers.any { it.activityInfo.name == BootReceiver::class.java.name }
        )
    }

    // Note: QuicLocDeviceAdmin is intentionally NOT asserted here — it now
    // lives in the on-demand :feature_findmyphone module, so it is absent from
    // the base merged manifest that Robolectric reads (it's merged in only once
    // the split installs / is fused into a standalone APK).

    // ---- services --------------------------------------------------------

    @Test
    fun `LocationReplyService is registered`() {
        val info = pm.getServiceInfo(
            ComponentName(context, LocationReplyService::class.java),
            0
        )
        assertNotNull(info)
        assertTrue("LocationReplyService must NOT be exported", !info.exported)
    }

    // Note: TrackingService is intentionally NOT asserted here — it lives in the
    // on-demand :feature_findmyphone module (absent from the base manifest).

    @Test
    fun `NotificationListener service is registered with the correct permission`() {
        val info = pm.getServiceInfo(
            ComponentName(context, NotificationListener::class.java),
            0
        )
        assertNotNull(info)
        assertTrue("NotificationListener must be exported", info.exported)
        assertEquals(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            info.permission
        )
    }

    // ---- activities ------------------------------------------------------

    @Test
    fun `MainActivity is the launcher activity`() {
        val info = pm.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0
        )
        assertNotNull(info)
        assertTrue(info.exported)
    }

    // Note: TrackingLockActivity is intentionally NOT asserted here — it lives
    // in the on-demand :feature_findmyphone module (absent from the base manifest).

    @Test
    fun `WidgetHelpActivity is registered`() {
        val info = pm.getActivityInfo(
            ComponentName(context, WidgetHelpActivity::class.java),
            0
        )
        assertNotNull(info)
        assertTrue("WidgetHelpActivity must NOT be exported", !info.exported)
    }

    // ---- backup rules ----------------------------------------------------

    @Test
    fun `backup rules XML resources exist`() {
        // The manifest declares both android:fullBackupContent and
        // android:dataExtractionRules pointing to XML resources. Verify
        // the resources are resolvable.  (The ApplicationInfo fields for
        // these attributes were removed from the public SDK in API 35,
        // so we verify the resource directly instead.)
        val backupRes = context.resources.getIdentifier("backup_rules", "xml", context.packageName)
        assertTrue("backup_rules.xml must exist", backupRes != 0)
        val extractionRes = context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertTrue("data_extraction_rules.xml must exist", extractionRes != 0)
    }
}
