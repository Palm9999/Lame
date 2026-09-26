package dev.gridiron.feature.projections

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.ProjectionComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ProjectionListTest {
    private fun row(id: String, position: String, points: Double) =
        ProjectionRow(id, "Player $id", position, "KC", points, points - 5, points + 5)

    private val rows = listOf(row("q", "QB", 20.0), row("r", "RB", 14.0), row("w", "WR", 16.0), row("t", "TE", 9.0))

    @Test
    fun `a tab shows its positions, best first`() {
        assertEquals(listOf("w", "r", "t"), visibleRows(rows, PositionTab.FLEX, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("q"), visibleRows(rows, PositionTab.QB, emptyMap(), week = true).map { it.playerId })
    }

    @Test
    fun `an Out or IR player scores zero this week but keeps his rest of season`() {
        val badges = mapOf("w" to "O", "r" to "Q")

        val week = visibleRows(rows, PositionTab.FLEX, badges, week = true)
        assertEquals(listOf("r", "t", "w"), week.map { it.playerId })
        assertTrue(week.last().out)
        assertEquals(0.0, week.last().points, 0.0)

        assertEquals(16.0, visibleRows(rows, PositionTab.WR, badges, week = false).single().points, 0.0)
    }

    @Test
    fun `the status line names the week and when it was built`() {
        assertEquals(
            "Projections for week 4 · built Tue 11:02 AM",
            statusLine(4, Instant.parse("2026-09-22T11:02:00Z"), ZoneOffset.UTC),
        )
        assertEquals("Projections for week 4", statusLine(4, null, ZoneOffset.UTC))
    }

    @Test
    fun `listed projections are scored with the profile and each player's position`() {
        val listed = listOf(
            ListedProjection("w", "Wide Out", "WR", "KC", listOf(ProjectionComponent("receptions", 5.0, 5.0, "binomial"), ProjectionComponent("receiving_yards", 60.0, 900.0, "gamma"))),
            ListedProjection("k", "Kicker", null, "KC", listOf(ProjectionComponent("receptions", 1.0, 1.0))),
        )

        val scored = toRows(listed, ScoringPresets.PPR)

        assertEquals(listOf("w"), scored.map { it.playerId }) // no position, no row
        assertEquals(11.0, scored.single().points, 1e-9)
        assertTrue(scored.single().floor < 11.0 && scored.single().ceiling > 11.0)
    }
}
