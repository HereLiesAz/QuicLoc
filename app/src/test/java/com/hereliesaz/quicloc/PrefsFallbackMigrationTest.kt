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

    // ---- StringSet stores (GeofenceStore, WhitelistManager) --------------
    // A single-key StringSet store (GeofenceStore has exactly one key) would
    // hit the "real store already has this key" skip on every migration
    // once there's any pre-outage data at all, silently discarding every
    // record added only during the outage. These pin the union-merge fix.

    @Test
    fun `unions a StringSet key instead of skipping it when the real store already has it`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putStringSet("entries", setOf("added-during-outage")).commit()

        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        real.edit().putStringSet("entries", setOf("pre-existing")).commit()

        migratePlaintextFallback(context, "test_fallback", real)

        assertEquals(
            setOf("pre-existing", "added-during-outage"),
            real.getStringSet("entries", emptySet())
        )
    }

    @Test
    fun `StringSet union still recovers when the real store has no prior value for that key`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putStringSet("entries", setOf("only-in-fallback")).commit()

        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        migratePlaintextFallback(context, "test_fallback", real)

        assertEquals(setOf("only-in-fallback"), real.getStringSet("entries", emptySet()))
    }

    @Test
    fun `StringSet union clears the fallback file same as a scalar migration`() {
        context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE)
            .edit().putStringSet("entries", setOf("x")).commit()
        val real = context.getSharedPreferences("test_real", Context.MODE_PRIVATE)
        real.edit().putStringSet("entries", setOf("y")).commit()

        migratePlaintextFallback(context, "test_fallback", real)

        assertTrue(context.getSharedPreferences("test_fallback", Context.MODE_PRIVATE).all.isEmpty())
    }
}
