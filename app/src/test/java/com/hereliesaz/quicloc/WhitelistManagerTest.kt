package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhitelistManagerTest {

    private lateinit var context: Context
    private lateinit var whitelistManager: WhitelistManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // We use a clean environment for each test
        whitelistManager = WhitelistManager(context)
    }

    @Test
    fun testAddAndRemoveNumber() {
        val number = "1234567890"
        whitelistManager.addNumber(number)
        assertTrue(whitelistManager.isWhitelisted(number))
        
        whitelistManager.removeNumber(number)
        assertFalse(whitelistManager.isWhitelisted(number))
    }

    @Test
    fun testPhoneNumberNormalization() {
        // Test normalization for comparison
        whitelistManager.addNumber("123-456-7890")
        
        // Should match even with dashes/spaces
        assertTrue(whitelistManager.isWhitelisted("1234567890"))
        assertTrue(whitelistManager.isWhitelisted("(123) 456-7890"))
    }

    @Test
    fun testStarredNumbersLimit() {
        val n1 = "111"
        val n2 = "222"
        val n3 = "333"
        val n4 = "444"
        
        assertTrue(whitelistManager.toggleStarred(n1))
        assertTrue(whitelistManager.toggleStarred(n2))
        assertTrue(whitelistManager.toggleStarred(n3))
        
        // Limit is 3
        assertFalse(whitelistManager.toggleStarred(n4))
        assertEquals(3, whitelistManager.getStarredNumbers().size)
    }
}
