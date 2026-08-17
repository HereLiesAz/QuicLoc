package com.hereliesaz.quicloc

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named place Loc Notice watches. When the device crosses its boundary,
 * [GeofenceBroadcastReceiver] texts whichever of [contactTokens] apply to
 * that direction.
 *
 * @property contactTokens [WhitelistManager.ContactEntry.displayToken] values
 *   — not copied names/numbers — so a whitelist *removal* doesn't leave a
 *   dangling reference (the token just stops resolving, which the receiver
 *   treats as "no contact", not an error). It does NOT survive a *rename*:
 *   `displayToken` for a name-only-known contact is that name, so renaming a
 *   contact (remove + re-add under a new name) silently orphans any location
 *   that referenced the old token. [LocNoticeListScreen] surfaces this by
 *   showing a location's *resolved* contact count against its stored token
 *   count, so a mismatch is visible rather than silently going stale.
 * @property address The free-text address last typed when defining this
 *   place, kept purely so "Open in Maps" stays available when re-editing an
 *   existing location — [latitude]/[longitude] are always the source of
 *   truth for where the geofence actually is.
 * @property createdAt Epoch millis when this entry was first created.
 *   Used only to break ties deterministically when [GeofenceRegistrar]
 *   truncates to [MAX_GEOFENCES] — without it, truncation order depends on
 *   `SharedPreferences`' `StringSet` hash order, which can silently drop an
 *   arbitrary (not necessarily the newest) location. `0L` for entries
 *   created before this field existed, which sorts them oldest.
 */
data class GeofenceEntry(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val notifyOnEnter: Boolean,
    val notifyOnExit: Boolean,
    val enabled: Boolean = true,
    val contactTokens: Set<String> = emptySet(),
    val address: String = "",
    val createdAt: Long = 0L,
) {
    fun toJsonString(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", latitude)
        put("lng", longitude)
        put("radius", radiusMeters.toDouble())
        put("notify_enter", notifyOnEnter)
        put("notify_exit", notifyOnExit)
        put("enabled", enabled)
        put("contacts", JSONArray(contactTokens.toList()))
        put("address", address)
        put("created_at", createdAt)
    }.toString()

    companion object {
        /** Below this, GPS jitter near the boundary can produce false transitions. */
        const val MIN_RADIUS_M = 100f
        const val MAX_RADIUS_M = 2000f
        const val DEFAULT_RADIUS_M = 150f

        /** Android's platform-wide cap on active geofences per app. */
        const val MAX_GEOFENCES = 100

        fun fromJsonString(jsonStr: String): GeofenceEntry? {
            if (jsonStr.isBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                val contacts = json.optJSONArray("contacts")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        try { arr.getString(i) } catch (_: Exception) { null }
                    }.toSet()
                } ?: emptySet()
                GeofenceEntry(
                    id = json.getString("id"),
                    name = json.optString("name", ""),
                    latitude = json.getDouble("lat"),
                    longitude = json.getDouble("lng"),
                    radiusMeters = json.optDouble("radius", DEFAULT_RADIUS_M.toDouble()).toFloat(),
                    notifyOnEnter = json.optBoolean("notify_enter", true),
                    notifyOnExit = json.optBoolean("notify_exit", true),
                    enabled = json.optBoolean("enabled", true),
                    contactTokens = contacts,
                    address = json.optString("address", ""),
                    createdAt = json.optLong("created_at", 0L),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
