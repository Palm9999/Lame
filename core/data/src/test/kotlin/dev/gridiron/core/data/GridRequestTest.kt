package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GridRequestTest {
    @Test
    fun `default weeks stop at the last week played`() {
        assertEquals(WeekRange(1, 18), SeasonInfo(2025, lastWeek = 22).defaultWeeks) // postseason excluded
        assertEquals(WeekRange(1, 17), SeasonInfo(2020, lastWeek = 21).defaultWeeks)
        assertEquals(WeekRange(1, 2), SeasonInfo(2026, lastWeek = 2).defaultWeeks) // in progress
    }

    @Test
    fun `played weeks ignore weeks not yet played`() {
        val inProgress = GridRequest(SeasonInfo(2026, lastWeek = 3), WeekRange(1, 18), StatPack.OPPORTUNITY)
        assertEquals(3, inProgress.playedWeeks)
    }

    @Test
    fun `sort defaults to the pack's lead stat, best first`() {
        val passing = GridRequest(SeasonInfo(2025, 22), WeekRange(1, 18), StatPack.PASSING)
        assertEquals(StatColumn.PASSING_YARDS, passing.sort)
        assertEquals(Direction.DESCENDING, passing.direction)
        assertEquals(Direction.ASCENDING, GridRequest.defaultDirection(StatColumn.INTERCEPTIONS))
    }
}

class SampleThresholdTest {
    @Test
    fun `rate sorts get a floor that scales with weeks played`() {
        val t = SampleThreshold.forRequest(StatColumn.CATCH_RATE, StatPack.RECEIVING, playedWeeks = 18, perGame = false)!!
        assertEquals(StatColumn.TARGETS, t.qualifier.column)
        assertEquals(Condition.AtLeast(54.0), t.qualifier.condition)
        assertEquals("min 54 targets", t.description)
    }

    @Test
    fun `per-game mode floors the per-game value and requires enough games`() {
        val t = SampleThreshold.forRequest(StatColumn.RUSH_SUCCESS_RATE, StatPack.RUSHING, playedWeeks = 8, perGame = true)!!
        assertEquals(Condition.AtLeast(6.0), t.qualifier.condition)
        assertEquals(4, t.minGames)
        assertEquals("min 6 carries per game, 4+ games", t.description)
    }

    @Test
    fun `counting sorts use the pack's population`() {
        val t = SampleThreshold.forRequest(StatColumn.RUSHING_YARDS, StatPack.RUSHING, playedWeeks = 18, perGame = false)!!
        assertEquals(StatColumn.CARRIES, t.qualifier.column)
        assertEquals("min 108 carries", t.description)
    }

    @Test
    fun `a rate sort's own sample beats the pack's population`() {
        val t = SampleThreshold.forRequest(StatColumn.EPA_PER_DROPBACK, StatPack.EFFICIENCY, playedWeeks = 18, perGame = false)!!
        assertEquals(StatColumn.DROPBACKS, t.qualifier.column)
    }

    @Test
    fun `packs without a population rank everyone on counting sorts`() {
        assertNull(SampleThreshold.forRequest(StatColumn.GZ_CARRIES, StatPack.RED_ZONE, playedWeeks = 18, perGame = false))
    }
}
