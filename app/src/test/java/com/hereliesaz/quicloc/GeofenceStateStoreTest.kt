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

    /**
     * Seeds a past "last fired" timestamp for (id, transition) directly,
     * bypassing [GeofenceStateStore.evaluate]'s own real-wall-clock write --
     * this is how the 60s-recovery path is tested without either sleeping
     * in a unit test or mocking `System.currentTimeMillis()` (which
     * [GeofenceStateStore] calls directly). Deliberately reaches into the
     * same plain prefs file/key format `evaluate` itself writes
     * (`at_<id>_<transition>`); a rename there needs a matching update here.
     */
    private fun seedLastFiredAgo(id: String, transition: Int, msAgo: Long) {
        context.getSharedPreferences("quicloc_geofence_state", Context.MODE_PRIVATE)
            .edit()
            .putLong("at_${id}_$transition", System.currentTimeMillis() - msAgo)
            .commit()
    }

    @Test
    fun `first transition for a geofence is processed`() {
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `an exact repeat of the same transition within seconds is a duplicate`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        assertEquals(GeofenceStateStore.Result.DUPLICATE, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `a same-direction repeat outside the duplicate window but inside the flap window is suppressed`() {
        seedLastFiredAgo("home", enter, msAgo = 30_000L)
        assertEquals(GeofenceStateStore.Result.FLAP_SUPPRESSED, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `a same-direction repeat after the flap window has fully elapsed is processed again`() {
        // The path a prior review found completely untested: does the
        // cooldown actually expire, or does a location get permanently
        // stuck once it flaps once?
        seedLastFiredAgo("home", enter, msAgo = 61_000L)
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", enter))
    }

    @Test
    fun `the opposite direction firing right after is never suppressed by the other direction`() {
        // The actual bug this design fixes: an ENTER that never sent a text
        // (direction was off, so GeofenceBroadcastReceiver never calls
        // evaluate() for it) must not be able to poison EXIT's cooldown --
        // and per this store's own contract, evaluate() is only ever
        // called for actionable transitions, so ENTER and EXIT are
        // completely independent regardless of how close together they
        // fire. A genuine short stop (arrive, leave 45s later) must still
        // send both texts.
        GeofenceStateStore.evaluate(context, "home", enter)
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", exit))
    }

    @Test
    fun `each direction tracks its own independent cooldown`() {
        seedLastFiredAgo("home", enter, msAgo = 30_000L)
        // "enter" is inside its own flap window -- suppressed.
        assertEquals(GeofenceStateStore.Result.FLAP_SUPPRESSED, GeofenceStateStore.evaluate(context, "home", enter))
        // "exit" has never fired before -- unaffected by enter's state.
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", exit))
    }

    @Test
    fun `different geofences are tracked independently`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        // "work" has never fired before -- must not be affected by "home"'s state.
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "work", enter))
    }

    @Test
    fun `clear resets bookkeeping for both directions of a geofence`() {
        GeofenceStateStore.evaluate(context, "home", enter)
        GeofenceStateStore.evaluate(context, "home", exit)
        GeofenceStateStore.clear(context, "home")
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", enter))
        assertEquals(GeofenceStateStore.Result.PROCESS, GeofenceStateStore.evaluate(context, "home", exit))
    }
}
