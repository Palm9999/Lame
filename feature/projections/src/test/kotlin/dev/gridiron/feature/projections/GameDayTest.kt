package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class GameDayTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val thu = Instant.parse("2026-10-09T00:15:00Z")
    private val early = Instant.parse("2026-10-11T17:00:00Z")
    private val late = Instant.parse("2026-10-11T20:25:00Z")

    private fun row(id: String, position: String, team: String, points: Double) =
        ProjectionRow(id, "P$id", position, team, points, points - 5, points + 5)

    private fun view(starters: List<LineupLine>, bench: List<ProjectionRow>) =
        LineupView("Mine", 6, starters.sumOf { it.row?.points ?: 0.0 }, starters, bench, emptyList(), false)

    private val kickoffs = mapOf("KC" to thu, "DEN" to thu, "BUF" to early, "MIA" to early, "SF" to late, "SEA" to late, "DAL" to now.minusSeconds(60))

    @Test
    fun `players are grouped by kickoff, started games left out`() {
        val v = view(
            listOf(
                LineupLine("QB", row("1", "QB", "KC", 20.0)),
                LineupLine("RB", row("2", "RB", "BUF", 14.0)),
                LineupLine("WR", row("3", "WR", "DAL", 12.0), locked = true),
                LineupLine("FLEX", row("4", "WR", "SF", 11.0)),
                LineupLine("TE", null),
            ),
            listOf(row("5", "RB", "SF", 8.0), row("6", "WR", "NYJ", 7.0)),
        )
        val day = gameDay(v, kickoffs, emptyMap(), now)!!
        assertEquals(listOf(thu, early, late), day.windows.map { it.kickoff })
        assertEquals("Start P1 (QB)", windowText(day.windows[0]))
        assertEquals("Start P4 (FLEX) · bench P5", windowText(day.windows[2]))
        assertEquals(emptyList<Pivot>(), day.pivots)
    }

    @Test
    fun `a Questionable starter gets the bench players who fit his slot and play then or later`() {
        val q = row("2", "RB", "BUF", 14.0)
        val v = view(
            listOf(LineupLine("RB", q), LineupLine("WR", row("3", "WR", "SEA", 12.0))),
            listOf(
                row("5", "RB", "SF", 8.0), // later, fits
                row("6", "RB", "MIA", 9.0), // same time, fits
                row("7", "RB", "KC", 10.0), // earlier: locked by then
                row("8", "WR", "SF", 11.0), // doesn't fit RB
                row("9", "RB", "SEA", 12.0), // Out
            ),
        )
        val day = gameDay(v, kickoffs, mapOf("2" to "Q", "9" to "O"), now)!!
        val pivot = day.pivots.single()
        assertEquals(listOf("6", "5"), pivot.options.map { it.row.playerId })
        assertEquals(
            "P2 (RB) is Questionable, kicking off Sun 5:00 PM. If he's out, P6 (Sun 5:00 PM, 9.0) or P5 (Sun 8:25 PM, 8.0) can still go in.",
            pivotText(pivot, ZoneOffset.UTC),
        )
    }

    @Test
    fun `a late Questionable starter with only earlier backups is told to decide first`() {
        val v = view(listOf(LineupLine("WR", row("3", "WR", "SF", 12.0))), listOf(row("8", "WR", "KC", 9.0)))
        val pivot = gameDay(v, kickoffs, mapOf("3" to "Q"), now)!!.pivots.single()
        assertEquals(emptyList<PivotOption>(), pivot.options)
        assertEquals(
            "P3 (WR) is Questionable, kicking off Sun 8:25 PM. No bench player who fits his slot plays then or later: " +
                "decide before his game, or keep a later one ready.",
            pivotText(pivot, ZoneOffset.UTC),
        )
    }

    @Test
    fun `no pivot once his inactives are posted, and no game day without kickoffs`() {
        val v = view(listOf(LineupLine("RB", row("2", "RB", "BUF", 14.0))), emptyList())
        assertEquals(emptyList<Pivot>(), gameDay(v, kickoffs, mapOf("2" to "Q"), now, posted = setOf("BUF"))!!.pivots)
        assertNull(gameDay(v, emptyMap(), emptyMap(), now))
    }
}
