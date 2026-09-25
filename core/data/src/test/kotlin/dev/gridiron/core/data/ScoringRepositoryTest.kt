package dev.gridiron.core.data

import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScoringRepositoryTest {
    private val prefs = FakePrefsSource()
    private var n = 0
    private val repo = ScoringRepository(prefs) { "u${++n}" }

    @Test
    fun `presets come first and PPR is active by default`() = runTest {
        assertEquals(ScoringPresets.all, repo.profiles.first().take(3))
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `duplicating a preset makes an editable copy that remembers its preset`() = runTest {
        val copy = repo.duplicate(ScoringPresets.HALF_PPR.id, "Sleeper league")
        assertEquals("u1", copy.id)
        assertEquals("Sleeper league", copy.name)
        assertEquals(ScoringPresets.HALF_PPR.id, copy.basedOn)
        assertEquals(ScoringPresets.HALF_PPR.weights, copy.weights)
        assertFalse(copy.isPreset)
        assertEquals(copy, repo.profiles.first().last())
    }

    @Test
    fun `duplicating a user profile keeps the original preset lineage`() = runTest {
        val first = repo.duplicate(ScoringPresets.PPR.id, "A")
        val second = repo.duplicate(first.id, "B")
        assertEquals(ScoringPresets.PPR.id, second.basedOn)
    }

    @Test
    fun `saving replaces by id and presets can't be saved`() = runTest {
        val copy = repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.save(copy.copy(weights = copy.weights + (ScoringRule.PASS_TD to 6.0)))
        assertEquals(6.0, prefs.current.profiles.single().weight(ScoringRule.PASS_TD))
        assertThrows<IllegalArgumentException> { repo.save(ScoringPresets.PPR) }
    }

    @Test
    fun `deleting the active profile activates the first remaining one, else PPR`() = runTest {
        val a = repo.duplicate(ScoringPresets.PPR.id, "A")
        val b = repo.duplicate(ScoringPresets.PPR.id, "B")
        repo.setActive(b.id)
        repo.delete(b.id)
        assertEquals(a, repo.active.first())
        repo.delete(a.id)
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `presets can't be deleted and unknown ids can't be activated`() = runTest {
        repo.delete(ScoringPresets.PPR.id)
        assertEquals(ScoringPresets.all, repo.profiles.first())
        repo.setActive("nope")
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `reset to preset restores the preset's rules but keeps id and name`() = runTest {
        val copy = repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.save(copy.copy(weights = mapOf(ScoringRule.PASS_TD to 6.0)))
        repo.resetToPreset(copy.id)
        val reset = prefs.current.profiles.single()
        assertEquals(ScoringPresets.PPR.weights, reset.weights)
        assertEquals("Mine", reset.name)
        assertEquals(copy.id, reset.id)
    }

    @Test
    fun `the reset notice can be dismissed`() = runTest {
        val flagged = FakePrefsSource(UserPrefs.DEFAULT.copy(resetNotice = true))
        val r = ScoringRepository(flagged)
        assertTrue(r.resetNotice.first())
        r.dismissResetNotice()
        assertFalse(r.resetNotice.first())
    }
}
