package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json's real implementation isn't on the plain-JVM unit test classpath
// (it's stubbed to throw) -- Robolectric provides a working one, matching
// how WhitelistManagerTest covers ContactEntry's identical JSON round-trip.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeofenceEntryTest {

    private fun sample() = GeofenceEntry(
        id = "abc-123",
        name = "Home",
        latitude = 37.422131,
        longitude = -122.084801,
        radiusMeters = 150f,
        notifyOnEnter = true,
        notifyOnExit = false,
        enabled = true,
        contactTokens = setOf("Mom", "+15551234567"),
    )

    @Test
    fun `round-trips through toJsonString and fromJsonString`() {
        val entry = sample()
        val restored = GeofenceEntry.fromJsonString(entry.toJsonString())
        assertEquals(entry, restored)
    }

    @Test
    fun `fromJsonString returns null for blank input`() {
        assertNull(GeofenceEntry.fromJsonString(""))
    }

    @Test
    fun `fromJsonString returns null for garbage input`() {
        assertNull(GeofenceEntry.fromJsonString("not json"))
    }

    @Test
    fun `fromJsonString defaults missing optional fields`() {
        val minimal = """{"id":"x","lat":1.0,"lng":2.0}"""
        val restored = GeofenceEntry.fromJsonString(minimal)
        assertEquals("x", restored?.id)
        assertEquals(true, restored?.notifyOnEnter)
        assertEquals(true, restored?.notifyOnExit)
        assertEquals(true, restored?.enabled)
        assertEquals(emptySet<String>(), restored?.contactTokens)
        assertEquals(GeofenceEntry.DEFAULT_RADIUS_M, restored?.radiusMeters)
    }
}
