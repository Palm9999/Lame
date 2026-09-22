package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WeekRangeTest {
    @Test
    fun `regular season is 18 weeks from 2021 and 17 before`() {
        assertEquals(WeekRange(1, 18), WeekRange.regularSeason(2021))
        assertEquals(WeekRange(1, 18), WeekRange.regularSeason(2025))
        assertEquals(WeekRange(1, 17), WeekRange.regularSeason(2020))
    }

    @Test
    fun `2020 regular season excludes the wild card round`() {
        // Week 18 of 2020 is the Wild Card round in nflverse numbering.
        assertFalse(18 in WeekRange.regularSeason(2020))
        assertTrue(18 in WeekRange.postseason(2020))
    }

    @Test
    fun `postseason spans four rounds after the regular season`() {
        assertEquals(WeekRange(19, 22), WeekRange.postseason(2025))
        assertEquals(WeekRange(18, 21), WeekRange.postseason(2020))
    }

    @Test
    fun `rejects inverted and out of bounds ranges`() {
        assertThrows<IllegalArgumentException> { WeekRange(5, 4) }
        assertThrows<IllegalArgumentException> { WeekRange(0, 4) }
        assertThrows<IllegalArgumentException> { WeekRange(1, 23) }
    }
}
