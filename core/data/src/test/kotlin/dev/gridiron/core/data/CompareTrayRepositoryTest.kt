package dev.gridiron.core.data

import dev.gridiron.core.data.CompareTrayRepository.AddResult
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CompareTrayRepositoryTest {
    private val tray = CompareTrayRepository(FakePrefsSource())
    private fun slot(id: String, season: Int = 2025, weeks: WeekRange = WeekRange(1, 18)) = CompareSlot(id, season, weeks)

    @Test
    fun `adds up to four, then reports full`() = runTest {
        for (id in listOf("a", "b", "c", "d")) assertEquals(AddResult.ADDED, tray.add(slot(id)))
        assertEquals(AddResult.FULL, tray.add(slot("e")))
        assertEquals(listOf("a", "b", "c", "d"), tray.slots.first().map { it.playerId })
    }

    @Test
    fun `the same player with another season or range is a new slot, the identical slot is not`() = runTest {
        assertEquals(AddResult.ADDED, tray.add(slot("a")))
        assertEquals(AddResult.ADDED, tray.add(slot("a", season = 2024)))
        assertEquals(AddResult.ADDED, tray.add(slot("a", weeks = WeekRange(1, 8))))
        assertEquals(AddResult.ALREADY_THERE, tray.add(slot("a")))
        assertEquals(3, tray.slots.first().size)
    }

    @Test
    fun `replace edits a slot in place and refuses to create a duplicate`() = runTest {
        tray.add(slot("a"))
        tray.add(slot("b"))
        assertEquals(true, tray.replace(slot("a"), slot("a", weeks = WeekRange(9, 18))))
        assertEquals(listOf(slot("a", weeks = WeekRange(9, 18)), slot("b")), tray.slots.first())
        assertFalse(tray.replace(slot("b"), slot("a", weeks = WeekRange(9, 18))))
    }

    @Test
    fun `remove and clear`() = runTest {
        tray.add(slot("a"))
        tray.add(slot("b"))
        tray.remove(slot("a"))
        assertEquals(listOf(slot("b")), tray.slots.first())
        tray.clear()
        assertEquals(emptyList<CompareSlot>(), tray.slots.first())
    }
}
