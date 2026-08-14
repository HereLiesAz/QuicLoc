package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsFallbackMigrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("test_fallback")
        context.deleteSharedPreferences("test_real")
    }

    @Test
    fun `recovers data written to the fallback file into the real store`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putString("pin", "123456").putBoolean("onboarded", true).commit()

        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        migratePlaintextFallback(context, "test_fallback", real)

        assertEquals("123456", real.getString("pin", null))
        assertTrue(real.getBoolean("onboarded", false))
    }

    @Test
    fun `clears the fallback file after a successful migration`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putString("pin", "123456").commit()

        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        migratePlaintextFallback(context, "test_fallback", real)

        val fallbackAfter = context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
        assertTrue(fallbackAfter.all.isEmpty())
    }

    @Test
    fun `never overwrites a key the real store already has`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putString("pin", "OLD-FALLBACK-VALUE").commit()

        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        real.edit().putString("pin", "NEWER-REAL-VALUE").commit()

        migratePlaintextFallback(context, "test_fallback", real)

        assertEquals("NEWER-REAL-VALUE", real.getString("pin", null))
    }

    @Test
    fun `does nothing when the fallback file is empty`() {
        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        real.edit().putString("existing", "value").commit()

        migratePlaintextFallback(context, "test_fallback", real)

        assertEquals("value", real.getString("existing", null))
        assertFalse(real.contains("pin"))
    }
}
