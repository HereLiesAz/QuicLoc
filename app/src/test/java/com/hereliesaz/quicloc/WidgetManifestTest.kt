package com.hereliesaz.quicloc

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Explicitly set SDK for Robolectric consistency
class WidgetManifestTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testWidgetProviderIsRegistered() {
        val packageManager = context.packageManager
        val componentName = ComponentName(context, QuicLocWidgetProvider::class.java)
        
        try {
            val receiverInfo = packageManager.getReceiverInfo(componentName, PackageManager.GET_META_DATA)
            assertNotNull("QuicLocWidgetProvider should be registered in AndroidManifest.xml", receiverInfo)
            assertTrue("QuicLocWidgetProvider should be exported", receiverInfo.exported)
            // Note: Robolectric might not always load meta-data perfectly from the merged manifest in unit tests
            // but the existence of the receiver is the main thing we're testing.
        } catch (e: PackageManager.NameNotFoundException) {
            val receivers = packageManager.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE), 0)
            val receiverNames = receivers.map { it.activityInfo.name }.joinToString(", ")
            throw AssertionError("QuicLocWidgetProvider not found in manifest. Registered receivers: $receiverNames", e)
        }
    }
}
