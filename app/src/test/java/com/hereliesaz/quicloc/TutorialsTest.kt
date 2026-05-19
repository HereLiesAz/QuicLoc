package com.hereliesaz.quicloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the static [Tutorials] catalog. No Robolectric
 * needed — these are pure data assertions.
 */
class TutorialsTest {

    @Test
    fun `MAIN_ID is present in the all list`() {
        assertNotNull(Tutorials.byId(Tutorials.MAIN_ID))
        assertTrue(Tutorials.all.any { it.id == Tutorials.MAIN_ID })
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(Tutorials.byId("definitely-not-a-real-tutorial"))
    }

    @Test
    fun `every tutorial has non-empty title summary and body`() {
        for (t in Tutorials.all) {
            assertTrue("tutorial ${t.id} has empty title", t.title.isNotBlank())
            assertTrue("tutorial ${t.id} has empty summary", t.summary.isNotBlank())
            assertTrue("tutorial ${t.id} has empty body", t.body.isNotBlank())
        }
    }

    @Test
    fun `tutorial ids are unique`() {
        val ids = Tutorials.all.map { it.id }
        assertEquals("duplicate tutorial ids: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `tutorial ids match kebab-case`() {
        val pattern = Regex("^[a-z][a-z0-9-]*$")
        for (t in Tutorials.all) {
            assertTrue(
                "tutorial id '${t.id}' is not kebab-case",
                pattern.matches(t.id)
            )
        }
    }

    @Test
    fun `the catalog has at least the eight core tutorials`() {
        // The hub is small enough that we can name them. If you add or
        // remove tutorials intentionally, update this list.
        val expected = setOf(
            "why-quicloc",
            "trusted-contacts",
            "trigger-word",
            "find-my-phone",
            "real-lockdown",
            "widget",
            "toggle",
            "backup-restore",
            "permissions",
        )
        val actual = Tutorials.all.map { it.id }.toSet()
        assertEquals(expected, actual)
    }
}
