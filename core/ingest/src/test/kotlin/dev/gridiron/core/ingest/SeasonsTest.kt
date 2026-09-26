package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SeasonsTest {
    @Test
    fun `a season is current from September`() {
        assertEquals(2026, currentSeason(LocalDate.of(2026, 9, 1)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 8, 31)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 1, 15)))
    }
}
