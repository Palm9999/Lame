package dev.gridiron.core.data

import dev.gridiron.core.model.Roster
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RosterRepositoryTest {
    private var next = 0
    private val repo = RosterRepository(FakePrefsSource()) { "r${++next}" }

    @Test
    fun `create, add and remove players`() = runTest {
        val home = repo.create("  Home league ")
        assertEquals(Roster("r1", "Home league", emptyList()), home)
        repo.add("r1", "p1")
        repo.add("r1", "p2")
        repo.add("r1", "p1")
        repo.remove("r1", "p2")
        assertEquals(listOf(Roster("r1", "Home league", listOf("p1"))), repo.rosters.first())
    }

    @Test
    fun `a player can be on two rosters, and edits touch only one`() = runTest {
        repo.create("Home")
        repo.create("Work")
        repo.add("r1", "p1")
        repo.add("r2", "p1")
        repo.rename("r2", "Office")
        repo.delete("r1")
        assertEquals(listOf(Roster("r2", "Office", listOf("p1"))), repo.rosters.first())
    }

    @Test
    fun `blank names are refused and a missing roster is a no-op`() = runTest {
        assertThrows<IllegalArgumentException> { repo.create(" ") }
        repo.create("Home")
        assertThrows<IllegalArgumentException> { repo.rename("r1", "") }
        repo.add("gone", "p1")
        assertEquals(listOf(Roster("r1", "Home", emptyList())), repo.rosters.first())
    }
}
