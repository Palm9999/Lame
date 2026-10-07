package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchupPreviewTest {
    private fun r(id: String, pts: Double, floor: Double, ceiling: Double) = ProjectionRow(id, "P$id", "WR", "KC", pts, floor, ceiling)
    private fun view(vararg lines: LineupLine) = LineupView("T", 5, lines.sumOf { it.row?.points ?: 0.0 }, lines.toList(), emptyList(), emptyList(), false)

    @Test
    fun `slots pair in order and the widest ranges swing it`() {
        val mine = view(LineupLine("QB", r("q1", 20.0, 12.0, 28.0)), LineupLine("WR", r("w1", 15.0, 5.0, 30.0)), LineupLine("WR", r("w2", 10.0, 6.0, 14.0)))
        val theirs = view(LineupLine("QB", r("q2", 18.0, 10.0, 26.0)), LineupLine("WR", r("w3", 12.0, 2.0, 24.0)), LineupLine("K", r("k", 8.0, 4.0, 12.0)))
        val p = matchupPreview(mine, theirs, swingCount = 2)
        assertEquals(listOf("QB", "WR", "WR", "K"), p.slots.map { it.slot })
        assertEquals(listOf(2.0, 3.0, 10.0, -8.0), p.slots.map { it.edge })
        assertEquals(listOf("w1", "w3"), p.swing.map { it.playerId })
    }

    @Test
    fun `the widget summary names the week, both totals and the win chance`() {
        val mine = view(LineupLine("QB", r("q1", 20.0, 12.0, 28.0)))
        val theirs = view(LineupLine("QB", r("q2", 18.0, 10.0, 26.0))).copy(teamName = "Rivals")
        assertEquals("Wk 5 · 20.0 vs Rivals 18.0 · ${winLine(winChance(mine, theirs))}", widgetSummary(mine, theirs))
        assertEquals("Wk 5 · 20.0 projected", widgetSummary(mine, null))
    }
}
