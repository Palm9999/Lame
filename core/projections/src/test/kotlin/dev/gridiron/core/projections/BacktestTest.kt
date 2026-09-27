package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BacktestTest {
    private fun stats(receptions: Double, yards: Double, tds: Double = 0.0) = mapOf(
        Component("receptions") to receptions,
        Component("receiving_yards") to yards,
        Component("receiving_tds") to tds,
    )

    /** Zero variance simulates as a point mass, so the floor and ceiling equal the projected points. */
    private fun projection(id: String, position: String, week: Int, receptions: Double, yards: Double) = ProjectedWeek(
        id, position, week,
        listOf(ProjectionComponent("receptions", receptions, 0.0), ProjectionComponent("receiving_yards", yards, 0.0)),
    )

    // Full PPR: a reception is 1 point, 10 receiving yards 1 point, a receiving TD 6.
    private val played = listOf(
        PlayedWeek("w", 2024, 17, stats(2.0, 20.0)), // 4
        PlayedWeek("w", 2025, 1, stats(5.0, 50.0)), // 10
        PlayedWeek("w", 2025, 2, stats(8.0, 100.0, 1.0)), // 24
        PlayedWeek("w", 2025, 4, stats(4.0, 40.0)), // 8
    )

    @Test
    fun `errorStats is the mean absolute error, the mean error and R squared`() {
        val stats = errorStats(listOf(12.0 to 24.0, 8.0 to 8.0))
        assertEquals(6.0, stats.mae, 1e-9)
        assertEquals(-6.0, stats.bias, 1e-9)
        // Actuals average 16: total sum of squares 64 + 64, residual 144 + 0.
        assertEquals(1.0 - 144.0 / 128.0, stats.r2!!, 1e-9)
    }

    @Test
    fun `R squared is null when every actual score is the same`() {
        assertNull(errorStats(listOf(5.0 to 7.0)).r2)
        // 0.3 three times doesn't average to exactly 0.3 in floating point: still no spread.
        assertNull(errorStats(listOf(0.1 to 0.3, 0.2 to 0.3, 0.9 to 0.3)).r2)
    }

    @Test
    fun `the model and both baselines are measured on the same player-weeks`() {
        val projected = listOf(
            projection("w", "WR", 1, 6.0, 60.0), // his first game of 2025: no season-to-date average, so left out
            projection("w", "WR", 2, 6.0, 60.0), // 12 projected, 24 scored
            projection("w", "WR", 3, 6.0, 60.0), // he didn't play: left out, never scored as zero
            projection("w", "WR", 4, 4.0, 40.0), // 8 projected, 8 scored
        )

        val wr = backtest(2025, projected, played, ScoringPresets.PPR).single()

        assertEquals("WR", wr.position)
        assertEquals(2, wr.playerWeeks)
        assertEquals(6.0, wr.model.mae, 1e-9)
        assertEquals(-6.0, wr.model.bias, 1e-9)
        // Season to date: week 2 has week 1's 10; week 4 has (10 + 24) / 2 = 17.
        assertEquals((14.0 + 9.0) / 2, wr.seasonAverage.mae, 1e-9)
        assertEquals((-14.0 + 9.0) / 2, wr.seasonAverage.bias, 1e-9)
        // Last four reaches back into 2024: week 2 has (4 + 10) / 2 = 7; week 4 has (4 + 10 + 24) / 3.
        val week4 = 38.0 / 3 - 8.0
        assertEquals((17.0 + week4) / 2, wr.lastFour.mae, 1e-9)
        assertEquals((-17.0 + week4) / 2, wr.lastFour.bias, 1e-9)
        // Floor and ceiling were 12 (scored 24, outside) and 8 (scored 8, inside).
        assertEquals(0.5, wr.calibration, 1e-9)
    }

    @Test
    fun `last four keeps only the four most recent games`() {
        val games = (1..6).map { PlayedWeek("r", 2025, it, stats(it.toDouble(), 0.0)) } // week n scores n

        val rb = backtest(2025, listOf(projection("r", "RB", 6, 6.0, 0.0)), games, ScoringPresets.PPR).single()

        assertEquals(-2.5, rb.lastFour.bias, 1e-9) // (2 + 3 + 4 + 5) / 4 against 6
        assertEquals(-3.0, rb.seasonAverage.bias, 1e-9) // (1 + 2 + 3 + 4 + 5) / 5 against 6
    }

    @Test
    fun `a projection under the minimum doesn't count, and a position with nothing to count is left out`() {
        val projected = listOf(projection("w", "WR", 2, 2.0, 20.0)) // 4 points
        assertTrue(backtest(2025, projected, played, ScoringPresets.PPR).isEmpty())
    }

    @Test
    fun `projections and real games are scored with the player's position`() {
        val tePremium = ScoringPresets.PPR.copy(id = "te", name = "TE premium", receptionByPosition = mapOf(Position.TE to 1.5))
        val te = listOf(PlayedWeek("t", 2025, 1, stats(4.0, 0.0)), PlayedWeek("t", 2025, 2, stats(4.0, 0.0)))

        // Four receptions at 1.5: 6 projected, 6 scored. Under plain PPR the projection is 4 and wouldn't count.
        val result = backtest(2025, listOf(projection("t", "TE", 2, 4.0, 0.0)), te, tePremium).single()

        assertEquals(0.0, result.model.mae, 1e-9)
        assertEquals(0.0, result.seasonAverage.mae, 1e-9)
        assertTrue(backtest(2025, listOf(projection("t", "TE", 2, 4.0, 0.0)), te, ScoringPresets.PPR).isEmpty())
    }

    @Test
    fun `kickers and team defenses are measured like everyone else`() {
        fun kick(made: Double) = mapOf(Component("g") to 1.0, Component("fg_made_0_39") to made, Component("xp_made") to 3.0)
        val played = listOf(
            PlayedWeek("k", 2025, 1, kick(1.0)), // 3 + 3
            PlayedWeek("k", 2025, 2, kick(2.0)), // 6 + 3
            PlayedWeek("d", 2025, 1, mapOf(Component("g") to 1.0, Component("dst_sacks") to 3.0, Component("points_allowed") to 20.0)), // 3 + 0
            PlayedWeek("d", 2025, 2, mapOf(Component("g") to 1.0, Component("dst_sacks") to 5.0, Component("points_allowed") to 10.0)), // 5 + 3
        )
        val projected = listOf(
            ProjectedWeek("k", "K", 2, listOf(ProjectionComponent("fg_made_0_39", 1.0, 0.0), ProjectionComponent("xp_made", 3.0, 0.0))),
            ProjectedWeek(
                "d", "DST", 2,
                listOf(
                    ProjectionComponent("dst_sacks", 3.0, 0.0),
                    // Exactly 10 allowed, so the projection is the 7-13 tier's 3.
                    ProjectionComponent("points_allowed", 10.0, 0.0, "normal"),
                    ProjectionComponent("g", 1.0, 0.0),
                ),
            ),
        )

        val results = backtest(2025, projected, played, ScoringPresets.PPR)

        assertEquals(listOf("K", "DST"), results.map { it.position })
        assertEquals(3.0, results[0].model.mae, 1e-9) // projected 6, scored 9
        assertEquals(2.0, results[1].model.mae, 1e-9) // projected 6, scored 8
    }
}
