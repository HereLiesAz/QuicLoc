package com.hereliesaz.quicloc

/**
 * Parses the `"lat, lng"` decimal-pair text Google Maps' own "Copy
 * coordinates" action puts on the clipboard (e.g. `"37.422131, -122.084801"`).
 * Pure Kotlin, no `android.*` import — unit-testable without Robolectric,
 * same as [PhoneNumbers].
 */
object CoordinateParser {
    private val PATTERN = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** @return the parsed (lat, lng) pair, or null if [text] isn't a valid coordinate pair. */
    fun parse(text: String?): Pair<Double, Double>? {
        val match = text?.let { PATTERN.matchEntire(it) } ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }
}
