package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure Kotlin — no Robolectric needed, same as [PhoneNumbersTest]. */
class CoordinateParserTest {

    @Test
    fun `parses a typical Google Maps copy-coordinates string`() {
        val result = CoordinateParser.parse("37.422131, -122.084801")
        assertEquals(37.422131 to -122.084801, result)
    }

    @Test
    fun `tolerates missing space after the comma`() {
        assertEquals(37.0 to -122.0, CoordinateParser.parse("37.0,-122.0"))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(37.0 to -122.0, CoordinateParser.parse("  37.0, -122.0  "))
    }

    @Test
    fun `handles positive longitude and integer-looking values`() {
        assertEquals(1.0 to 2.0, CoordinateParser.parse("1, 2"))
    }

    @Test
    fun `returns null for out-of-range latitude`() {
        assertNull(CoordinateParser.parse("91.0, 0.0"))
        assertNull(CoordinateParser.parse("-91.0, 0.0"))
    }

    @Test
    fun `returns null for out-of-range longitude`() {
        assertNull(CoordinateParser.parse("0.0, 181.0"))
        assertNull(CoordinateParser.parse("0.0, -181.0"))
    }

    @Test
    fun `returns null for missing comma`() {
        assertNull(CoordinateParser.parse("37.0 -122.0"))
    }

    @Test
    fun `returns null for non-numeric text`() {
        assertNull(CoordinateParser.parse("Home Sweet Home"))
    }

    @Test
    fun `returns null for blank or null input`() {
        assertNull(CoordinateParser.parse(""))
        assertNull(CoordinateParser.parse(null))
    }

    @Test
    fun `returns null for a single number with no pair`() {
        assertNull(CoordinateParser.parse("37.422131"))
    }
}
