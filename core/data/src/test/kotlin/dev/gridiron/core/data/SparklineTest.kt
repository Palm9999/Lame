package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SparklineTest {
    private val full = SeasonInfo(2025, lastWeek = 22)

    @Test
    fun `the window is the last six played weeks inside the range`() {
        assertEquals(13..18, sparklineWeeks(full, WeekRange(1, 18)))
        assertEquals(3..8, sparklineWeeks(full, WeekRange(1, 8)))
        assertEquals(5..7, sparklineWeeks(full, WeekRange(5, 7)))
        // In progress: week 2 is the last played.
        assertEquals(1..2, sparklineWeeks(SeasonInfo(2026, lastWeek = 2), WeekRange(1, 18)))
    }

    @Test
    fun `no window when there aren't two played weeks`() {
        assertNull(sparklineWeeks(full, WeekRange(7, 7)))
        assertNull(sparklineWeeks(SeasonInfo(2026, lastWeek = 2), WeekRange(5, 18)))
        assertNull(sparklineWeeks(SeasonInfo(2026, lastWeek = 1), WeekRange(1, 18)))
    }

    @Test
    fun `drawable needs two points`() {
        assertFalse(Sparkline(1..3, listOf(null, 4.0, null), listOf("–", "4", "–")).drawable)
        assertTrue(Sparkline(1..3, listOf(1.0, null, 4.0), listOf("1", "–", "4")).drawable)
    }
}
