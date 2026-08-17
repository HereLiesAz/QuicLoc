package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Geofence
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeofenceStateStoreTest {

    private lateinit var context: Context

    private val enter = Geofence.GEOFENCE_TRANSITION_ENTER
    private val exit = Geofence.GEOFENCE_TRANSITION_EXIT

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("quicloc_geofence_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `first transition for a geofence is processed`() {
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `an exact repeat of the same transition is a duplicate`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        assertEquals(GeofenceStateStore.Result.DUPLICATE, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `a genuine direction change right after is flap-suppressed, not processed`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        assertEquals(GeofenceStateStore.Result.FLAP_SUPPRESSED, GeofenceStateStore.evaluate(context, "home", exit))
    }

    @Test
    fun `different geofences are tracked independently`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        // "work" has never fired before -- must not be affected by "home"'s state.
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "work", enter))
    }

    @Test
    fun `clear resets bookkeeping for a geofence`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        GeofenceStateStore.clear(context, "home")
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", enter))
    }
}
