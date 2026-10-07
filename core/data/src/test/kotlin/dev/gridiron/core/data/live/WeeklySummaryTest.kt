package dev.gridiron.core.data.live

import dev.gridiron.core.projections.LineupCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class WeeklySummaryTest {
    private fun at(text: String) = ZonedDateTime.parse(text)

    @Test
    fun `due once on Tuesday from nine`() {
        assertTrue(WeeklySummary.due(at("2026-10-06T09:05-04:00[America/New_York]"), lastSent = "2026:5", key = "2026:6"))
        assertFalse(WeeklySummary.due(at("2026-10-06T08:55-04:00[America/New_York]"), lastSent = null, key = "2026:6"))
        assertFalse(WeeklySummary.due(at("2026-10-07T10:00-04:00[America/New_York]"), lastSent = null, key = "2026:6"))
        assertFalse(WeeklySummary.due(at("2026-10-06T15:00-04:00[America/New_York]"), lastSent = "2026:6", key = "2026:6"))
    }

    @Test
    fun `the text joins whatever is known`() {
        val last = LastResult(5, 112.4, 98.0, "Rivals")
        val next = NextMatch(6, "Bees", 0.613)
        assertEquals(
            "Week 5: won 112.4–98.0 vs Rivals · report card 3rd of 12 · Week 6 vs Bees: 61% to win",
            WeeklySummary.text(last, 3 to 12, next),
        )
        assertEquals("Week 5: lost 90.0–98.0 vs Rivals", WeeklySummary.text(last.copy(mine = 90.0), null, null))
        assertEquals("Week 6 vs Bees: 99% to win", WeeklySummary.text(null, null, next.copy(winChance = 0.999)))
        assertNull(WeeklySummary.text(null, null, null))
    }

    @Test
    fun `a lineup's strength is its best lineup's total and combined spread`() {
        val slots = mapOf("QB" to 1, "RB" to 1)
        val roster = listOf(LineupCandidate("q", "QB", 20.0), LineupCandidate("r1", "RB", 15.0), LineupCandidate("r2", "RB", 10.0))
        val s = WeeklySummary.strength(slots, roster, mapOf("q" to 6.0, "r1" to 8.0, "r2" to 100.0))
        assertEquals(35.0, s.first, 1e-9)
        assertEquals(10.0, s.second, 1e-9)
    }

    @Test
    fun `win chance is normal on the margin`() {
        assertEquals(0.5, WeeklySummary.winChance(100.0 to 10.0, 100.0 to 10.0), 1e-9)
        assertTrue(WeeklySummary.winChance(110.0 to 10.0, 100.0 to 10.0) > 0.7)
        assertEquals(1.0, WeeklySummary.winChance(110.0 to 0.0, 100.0 to 0.0), 0.0)
    }
}
