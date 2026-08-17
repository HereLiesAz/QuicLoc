package com.hereliesaz.quicloc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeofenceStoreTest {

    private lateinit var context: Context
    private lateinit var store: GeofenceStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("quicloc_locnotice_prefs")
        context.deleteSharedPreferences("quicloc_locnotice_prefs_fallback")
        BackupVault.backupFile(context).delete()
        store = GeofenceStore(context)
    }

    @After
    fun cleanup() {
        BackupVault.backupFile(context).delete()
    }

    private fun entry(name: String = "Home", id: String = "") = GeofenceEntry(
        id = id,
        name = name,
        latitude = 1.0,
        longitude = 2.0,
        radiusMeters = 150f,
        notifyOnEnter = true,
        notifyOnExit = true,
        contactTokens = setOf("Mom"),
    )

    @Test
    fun `starts empty`() {
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `add assigns an id when none is given`() {
        val saved = store.add(entry(id = ""))
        assertTrue(saved.id.isNotBlank())
        assertEquals(saved, store.get(saved.id))
    }

    @Test
    fun `add preserves a caller-supplied id`() {
        val saved = store.add(entry(id = "custom-id"))
        assertEquals("custom-id", saved.id)
    }

    @Test
    fun `update replaces the entry with the same id`() {
        val saved = store.add(entry(name = "Home"))
        store.update(saved.copy(name = "House"))
        assertEquals("House", store.get(saved.id)?.name)
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun `remove deletes by id`() {
        val saved = store.add(entry())
        store.remove(saved.id)
        assertNull(store.get(saved.id))
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `setEnabled flips only the enabled flag`() {
        val saved = store.add(entry())
        store.setEnabled(saved.id, false)
        val updated = store.get(saved.id)
        assertEquals(false, updated?.enabled)
        assertEquals(saved.name, updated?.name)
    }

    @Test
    fun `replaceAll wipes and resets`() {
        store.add(entry("Home"))
        store.add(entry("Work"))
        val replacement = entry("Gym", id = "gym-id")
        store.replaceAll(listOf(replacement))
        assertEquals(listOf(replacement), store.getAll())
    }

    @Test
    fun `data persists across new GeofenceStore instances`() {
        val saved = store.add(entry("Home"))
        val fresh = GeofenceStore(context)
        assertEquals(saved, fresh.get(saved.id))
    }

    @Test
    fun `multiple entries coexist independently`() {
        val a = store.add(entry("Home"))
        val b = store.add(entry("Work"))
        assertEquals(2, store.getAll().size)
        store.remove(a.id)
        assertEquals(listOf(b), store.getAll())
    }
}
