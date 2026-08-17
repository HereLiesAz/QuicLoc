package com.hereliesaz.quicloc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        address = "1 Infinite Loop",
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `round-trips through toJsonString and fromJsonString`() {
        val entry = sample()
        val restored = GeofenceEntry.fromJsonString(entry.toJsonString())
        assertEquals(entry, restored)
    }

    /**
     * A symmetric round-trip (above) passes even if a key is silently
     * renamed on both sides at once -- it only proves the two functions
     * agree with each other, not that either matches a stable wire format.
     * This pins every key's exact name and value, so a rename that isn't
     * mirrored in [GeofenceEntry.fromJsonString] (e.g. a rename that misses
     * one call site, or lands on disk before an app update ships the
     * matching read-side change) is caught here instead of silently
     * defaulting a stored boolean back to `true` on the next launch.
     */
    @Test
    fun `toJsonString uses the exact pinned key names and values`() {
        val json = JSONObject(sample().toJsonString())
        assertEquals("abc-123", json.getString("id"))
        assertEquals("Home", json.getString("name"))
        assertEquals(37.422131, json.getDouble("lat"), 0.0)
        assertEquals(-122.084801, json.getDouble("lng"), 0.0)
        assertEquals(150.0, json.getDouble("radius"), 0.0)
        assertEquals(true, json.getBoolean("notify_enter"))
        assertEquals(false, json.getBoolean("notify_exit"))
        assertEquals(true, json.getBoolean("enabled"))
        assertEquals("1 Infinite Loop", json.getString("address"))
        assertEquals(1_700_000_000_000L, json.getLong("created_at"))
        val contacts = json.getJSONArray("contacts")
        val contactSet = (0 until contacts.length()).map { contacts.getString(it) }.toSet()
        assertEquals(setOf("Mom", "+15551234567"), contactSet)
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
        assertEquals("", restored?.address)
        assertEquals(0L, restored?.createdAt)
    }

    /**
     * A pre-Loc-Notice-upgrade record on disk literally cannot have
     * `notify_enter`/`notify_exit` keys renamed out from under it -- this
     * documents (and would catch) the exact failure mode the pinned-key
     * test above guards against: a renamed key silently defaulting a
     * user's "off" preference back to "on" via `optBoolean(_, true)`.
     */
    @Test
    fun `a stored notify-off preference is never silently flipped back on by a missing key`() {
        val json = JSONObject(sample().copy(notifyOnEnter = false, notifyOnExit = false).toJsonString())
        assertTrue(json.has("notify_enter"))
        assertTrue(json.has("notify_exit"))
        val restored = GeofenceEntry.fromJsonString(json.toString())
        assertEquals(false, restored?.notifyOnEnter)
        assertEquals(false, restored?.notifyOnExit)
    }
}
