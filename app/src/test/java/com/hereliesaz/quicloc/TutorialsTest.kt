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
    fun `the catalog has exactly the expected tutorials`() {
        // The hub is small enough that we can name them. If you add or
        // remove tutorials intentionally, update this list.
        val expected = setOf(
            "why-quicloc",
            "getting-started",
            "trusted-contacts",
            "trigger-word",
            "find-my-phone",
            "real-lockdown",
            "widget",
            "toggle",
            "app-lock",
            "not-working",
            "backup-restore",
            "permissions",
        )
        val actual = Tutorials.all.map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `visible list hides find-my-phone tutorials while the feature is off`() {
        val visibleIds = Tutorials.visible.map { it.id }
        if (FindMyPhone.ENABLED) {
            assertEquals(Tutorials.all.map { it.id }, visibleIds)
        } else {
            assertTrue(
                "find-my-phone tutorials must not be listed while the feature is disabled",
                visibleIds.none { it == "find-my-phone" || it == "real-lockdown" }
            )
            // Everything else still shows.
            assertEquals(
                Tutorials.all.filterNot { it.requiresFindMyPhone }.map { it.id },
                visibleIds
            )
        }
    }

    @Test
    fun `the permissions tutorial does not describe find-my-phone while the feature is disabled`() {
        // requiresFindMyPhone hides two whole tutorials, but "permissions" is
        // a single doc covering every permission -- most of which are real
        // regardless of find-my-phone, so it stays visible with its
        // find-my-phone-only sections (Camera, Device Admin, Full-Screen
        // Intent) conditionally omitted instead of hiding the whole thing.
        val body = Tutorials.byId("permissions")!!.body
        if (!FindMyPhone.ENABLED) {
            assertTrue(
                "should not mention Camera permission while find-my-phone is off",
                !body.contains("Camera:")
            )
            assertTrue(
                "should not mention Device Admin while find-my-phone is off",
                !body.contains("Bind Device Admin")
            )
            assertTrue(
                "should not mention Full-Screen Intent while find-my-phone is off",
                !body.contains("Full-Screen Intent:")
            )
        }
        // Regardless of the flag, this permission is genuinely unused.
        assertTrue(
            "should never claim READ_CONTACTS is requested -- it isn't declared",
            !body.contains("Read Contacts:")
        )
    }

    @Test
    fun `the first-launch tutorial is always visible`() {
        assertTrue(Tutorials.visible.any { it.id == Tutorials.MAIN_ID })
    }
}
