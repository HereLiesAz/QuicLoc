package com.hereliesaz.quicloc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun `identical numbers match`() {
        assertTrue(PhoneNumbers.match("+15551234567", "+15551234567"))
    }

    @Test
    fun `formatting differences are tolerated`() {
        assertTrue(PhoneNumbers.match("+15551234567", "(555) 123-4567"))
    }

    @Test
    fun `missing or extra leading US country code is tolerated`() {
        assertTrue(PhoneNumbers.match("+15551234567", "5551234567"))
        assertTrue(PhoneNumbers.match("5551234567", "+15551234567"))
    }

    @Test
    fun `two different real numbers sharing only a 7-digit suffix do not match`() {
        assertFalse(PhoneNumbers.match("+12125551234", "+17185551234"))
    }

    @Test
    fun `two blank numbers never match`() {
        assertFalse(PhoneNumbers.match("", ""))
    }

    @Test
    fun `a number never matches blank`() {
        assertFalse(PhoneNumbers.match("+15551234567", ""))
        assertFalse(PhoneNumbers.match("", "+15551234567"))
    }

    @Test
    fun `a short non-national number does not get the leading-1 exemption`() {
        // 6 digits + a leading "1" is 7 -- far short of a full national
        // number, so this must not be treated as a country-code artifact.
        assertFalse(PhoneNumbers.match("123456", "1123456"))
    }
}
