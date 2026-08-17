package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [GeofenceRegistrar.selectForRegistration], the pure ordering/cap
 * logic extracted specifically so it's testable without a real
 * `GeofencingClient`. The actual Play Services calls in `sync()`/
 * `registerCurrentSet` aren't unit-tested here -- they need a live or faked
 * GMS client, which isn't available in this test environment.
 */
class GeofenceRegistrarTest {

    private fun entry(id: String, enabled: Boolean = true, createdAt: Long = 0L) = GeofenceEntry(
        id = id,
        name = id,
        latitude = 1.0,
        longitude = 2.0,
        radiusMeters = 150f,
        notifyOnEnter = true,
        notifyOnExit = true,
        enabled = enabled,
        createdAt = createdAt,
    )

    @Test
    fun `disabled entries are excluded`() {
        val result = GeofenceRegistrar.selectForRegistration(
            listOf(entry("a", enabled = true), entry("b", enabled = false))
        )
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `entries are ordered newest-first by createdAt`() {
        val result = GeofenceRegistrar.selectForRegistration(
            listOf(entry("old", createdAt = 100), entry("new", createdAt = 300), entry("mid", createdAt = 200))
        )
        assertEquals(listOf("new", "mid", "old"), result.map { it.id })
    }

    @Test
    fun `over-the-cap truncation keeps the newest entries, not an arbitrary subset`() {
        val entries = (1..GeofenceEntry.MAX_GEOFENCES + 5).map {
            entry(id = "e$it", createdAt = it.toLong())
        }
        val result = GeofenceRegistrar.selectForRegistration(entries)
        assertEquals(GeofenceEntry.MAX_GEOFENCES, result.size)
        // The 5 oldest (lowest createdAt: e1..e5) must be the ones dropped.
        val keptIds = result.map { it.id }.toSet()
        for (i in 1..5) assertTrue("e$i should have been dropped", "e$i" !in keptIds)
        for (i in 6..GeofenceEntry.MAX_GEOFENCES + 5) assertTrue("e$i should have been kept", "e$i" in keptIds)
    }

    @Test
    fun `entries created before this field existed (createdAt=0) sort as oldest`() {
        val result = GeofenceRegistrar.selectForRegistration(
            listOf(entry("legacy", createdAt = 0L), entry("new", createdAt = 500L))
        )
        assertEquals(listOf("new", "legacy"), result.map { it.id })
    }

    @Test
    fun `an empty store selects nothing`() {
        assertTrue(GeofenceRegistrar.selectForRegistration(emptyList()).isEmpty())
    }

    @Test
    fun `at or under the cap, nothing is dropped`() {
        val entries = (1..GeofenceEntry.MAX_GEOFENCES).map { entry(id = "e$it", createdAt = it.toLong()) }
        val result = GeofenceRegistrar.selectForRegistration(entries)
        assertEquals(GeofenceEntry.MAX_GEOFENCES, result.size)
    }
}
