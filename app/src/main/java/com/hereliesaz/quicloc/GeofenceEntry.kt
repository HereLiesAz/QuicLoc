package com.hereliesaz.quicloc

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named place Loc Notice watches. When the device crosses its boundary,
 * [GeofenceBroadcastReceiver] texts whichever of [contactTokens] apply to
 * that direction.
 *
 * @property contactTokens [WhitelistManager.ContactEntry.displayToken] values
 *   — not copied names/numbers — so a rename or removal on the whitelist is
 *   reflected here automatically instead of silently going stale. A token
 *   that no longer resolves to a whitelist entry at fire time is skipped,
 *   not an error: contacts can be removed independently of Loc Notice.
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
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
