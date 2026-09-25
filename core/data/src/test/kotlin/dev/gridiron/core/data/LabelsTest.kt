package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LabelsTest {
    private val s2025 = SeasonInfo(2025, 22)
    private val s2026 = SeasonInfo(2026, 2)
    private val catalog = Catalog(persistentListOf(s2025, s2026), persistentMapOf())

    @Test
    fun `week labels clip to the weeks played`() {
        assertEquals("Wk 1–18", weeksLabel(s2025, WeekRange(1, 18)))
        assertEquals("Week 3", weeksLabel(s2025, WeekRange(3, 3)))
        assertEquals("Wk 1–2", weeksLabel(s2026, WeekRange(1, 18)))
        assertEquals("Wk 10–18, not played yet", weeksLabel(s2026, WeekRange(10, 18)))
    }

    @Test
    fun `a slot describes its season and range, or says its season is gone`() {
        assertEquals("2025 · Wk 1–8", describeSlot(CompareSlot("p", 2025, WeekRange(1, 8)), catalog))
        assertEquals("2023 · no data", describeSlot(CompareSlot("p", 2023, WeekRange(1, 8)), catalog))
    }
}
