package dev.gridiron.feature.projections

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.ProjectionComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.round

class ProjectionListTest {
    private fun row(id: String, position: String, points: Double) =
        ProjectionRow(id, "Player $id", position, "KC", points, points - 5, points + 5)

    private val rows = listOf(row("q", "QB", 20.0), row("r", "RB", 14.0), row("w", "WR", 16.0), row("t", "TE", 9.0))

    @Test
    fun `a Questionable player's discount lifts once his team's inactives are posted and ESPN hasn't ruled him out`() {
        val q = row("r", "RB", 7.8).copy(questionable = 0.78)
        val back = confirmedActive(q, mapOf("r" to "Q"), setOf("KC"))
        assertEquals(10.0, back.points, 1e-9)
        assertEquals(2.8 / 0.78, back.floor, 1e-9)
        assertEquals(null, back.questionable)
        // His TD chance and usage come back from the same discount: 1 - 0.7^(1/0.78), and 7.8 carries to 10.
        val withTd = q.copy(tdChance = 0.3, usage = Usage(7.8, 3.9, 3.9, 3.12))
        val td = confirmedActive(withTd, mapOf("r" to "Q"), setOf("KC"))
        assertEquals(1 - Math.pow(0.7, 1 / 0.78), td.tdChance!!, 1e-12)
        assertTrue(td.tdChance > 0.3 && td.tdChance < 1.0)
        assertEquals(Usage(10.0, 5.0, 5.0, 4.0), td.usage!!.let { u -> Usage(round(u.carries), round(u.targets), round(u.rushingPoints), round(u.receivingPoints)) })
        // Inactives not posted yet, or ruled out: unchanged.
        assertEquals(q, confirmedActive(q, mapOf("r" to "Q"), setOf("BUF")))
        assertEquals(q, confirmedActive(q, mapOf("r" to "O"), setOf("KC")))
        assertEquals(q, confirmedActive(q, mapOf("r" to "D"), setOf("KC")))
        // No discount: nothing to lift.
        assertEquals(rows[1], confirmedActive(rows[1], emptyMap(), setOf("KC")))
    }

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

    @Test
    fun `kickers and team defenses have their own tabs, outside FLEX`() {
        val all = rows + row("k", "K", 8.0) + row("d", "DST", 7.0)
        assertEquals(listOf("k"), visibleRows(all, PositionTab.K, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("d"), visibleRows(all, PositionTab.DST, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("w", "r", "t"), visibleRows(all, PositionTab.FLEX, emptyMap(), week = true).map { it.playerId })
        assertEquals("D/ST", PositionTab.DST.label)
    }
}
