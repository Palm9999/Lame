package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LeagueHistoryTest {
    private fun t(id: Int, name: String, owner: String?, w: Int, l: Int, pf: Double, rank: Int? = null, seed: Int? = null, ties: Int = 0) =
        HistoryTeam(id, name, owner, w, l, ties, pf, 1000.0, rank, seed)

    private fun g(week: Int, home: Int, away: Int, hp: Double, ap: Double, playoff: Boolean = false, winner: String? = null) =
        HistoryGame(week, home, away, hp, ap, winner ?: if (hp > ap) "HOME" else if (ap > hp) "AWAY" else "TIE", playoff)

    // 2024: Ann (team 1) champion by final rank; Bo (2) out of the playoffs.
    private val s2024 = HistorySeason(
        2024,
        listOf(t(1, "Ann's Team", "{A}", 10, 4, 1600.0, rank = 1, seed = 1), t(2, "Bo Knows", "{B}", 4, 10, 1200.0, rank = 4, seed = 4), t(3, "Cy", "{C}", 7, 7, 1400.0, rank = 2, seed = 2)),
        mapOf("{A}" to "Ann", "{B}" to "Bo"),
        listOf(g(1, 1, 2, 150.0, 60.0), g(2, 2, 1, 130.5, 120.0), g(2, 3, 3, 0.0, 0.0, winner = "UNDECIDED"), g(16, 3, 1, 100.0, 110.0, playoff = true)),
        playoffTeams = 2,
    )

    // 2025: no final ranks; Bo, now "Bo Again" under a new display name, wins the last bracket game.
    private val s2025 = HistorySeason(
        2025,
        listOf(t(1, "Ann 2", "{A}", 8, 6, 1500.0, seed = 2), t(2, "Bo Again", "{B}", 9, 5, 1550.0, seed = 1), t(4, "No Owner", null, 1, 13, 900.0)),
        mapOf("{A}" to "Ann", "{B}" to "Bobby"),
        listOf(g(1, 1, 2, 90.0, 95.0), g(17, 2, 1, 120.0, 118.0, playoff = true), g(16, 4, 1, 50.0, 49.0, playoff = true)),
        playoffTeams = 2,
    )

    // 2026, in progress: no ranks, a semifinal decided.
    private val s2026 = HistorySeason(
        2026,
        listOf(t(1, "Ann 3", "{A}", 3, 1, 480.0), t(2, "Bo Again", "{B}", 1, 3, 400.0)),
        mapOf("{A}" to "Ann", "{B}" to "Bobby"),
        listOf(g(15, 1, 2, 100.0, 90.0, playoff = true)),
        playoffTeams = 2,
    )

    private val tables = LeagueHistory.of(listOf(s2024, s2025, s2026), me = "{A}")

    @Test
    fun `champion from final rank, else the last bracket game, else in progress`() {
        val bySeason = tables.seasons.associateBy { it.season }
        assertEquals(listOf(2026, 2025, 2024), tables.seasons.map { it.season })
        assertEquals("Ann", bySeason.getValue(2024).champion?.name)
        assertEquals("Ann's Team", bySeason.getValue(2024).championTeam)
        assertEquals("Bobby", bySeason.getValue(2025).champion?.name)
        assertNull(bySeason.getValue(2026).champion)
        assertEquals(listOf("Ann's Team", "Cy", "Bo Knows"), bySeason.getValue(2024).standings.map { it.teamName })
    }

    @Test
    fun `all-time sums regular seasons and counts titles and playoff trips`() {
        val ann = tables.allTime.single { it.manager.key == "{A}" }
        assertEquals(3, ann.seasons)
        assertEquals(21, ann.wins)
        assertEquals(11, ann.losses)
        assertEquals(1, ann.titles)
        assertEquals(2, ann.playoffs)
        assertEquals(1600.0 + 1500.0 + 480.0, ann.pointsFor, 1e-9)
        assertEquals(21.0 / 32, ann.winPct, 1e-9)
        // Titles first: Ann and Bobby have one each; Ann's win % is higher.
        assertEquals(listOf("{A}", "{B}"), tables.allTime.take(2).map { it.manager.key })
    }

    @Test
    fun `a renamed manager is one row under the latest name`() {
        val bo = tables.allTime.filter { it.manager.key == "{B}" }
        assertEquals(1, bo.size)
        assertEquals("Bobby", bo.single().manager.name)
    }

    @Test
    fun `a team without an owner is its own manager`() {
        val none = tables.allTime.single { it.manager.key == "team:2025:4" }
        assertEquals("No Owner", none.manager.name)
        assertEquals(1, none.seasons)
    }

    @Test
    fun `head-to-head counts decided games against each rival`() {
        val bo = tables.headToHead.single { it.rival.key == "{B}" }
        // 2024: won wk1, lost wk2; 2025: lost wk1, lost the final; 2026: won the semifinal.
        assertEquals(2, bo.wins)
        assertEquals(3, bo.losses)
        assertEquals(150.0 + 120.0 + 90.0 + 118.0 + 100.0, bo.pointsFor, 1e-9)
        assertEquals(listOf("{B}", "{C}", "team:2025:4"), tables.headToHead.map { it.rival.key })
        assertEquals(emptyList<RivalRow>(), LeagueHistory.of(listOf(s2024), me = null).headToHead)
    }

    @Test
    fun `records name manager, season and week`() {
        val records = tables.records.associateBy { it.label }
        val high = records.getValue("Highest week")
        assertEquals(150.0, high.value, 1e-9)
        assertEquals("Ann", high.manager.name)
        assertEquals(2024, high.season)
        assertEquals(1, high.week)
        assertEquals(49.0, records.getValue("Lowest week").value, 1e-9)
        assertEquals(90.0, records.getValue("Biggest win").value, 1e-9)
        assertEquals(10.0 / 14, records.getValue("Best record").value, 1e-9)
        assertEquals(1600.0, records.getValue("Most points in a season").value, 1e-9)
    }

    @Test
    fun `undecided games count nowhere`() {
        // 2024's undecided 0-0 game isn't the lowest week; Bo's 60 is.
        val records = LeagueHistory.of(listOf(s2024), me = "{C}").records
        assertEquals(60.0, records.single { it.label == "Lowest week" }.value, 1e-9)
        assertEquals(listOf(1), LeagueHistory.of(listOf(s2024), me = "{C}").headToHead.map { it.wins + it.losses + it.ties })
    }

    @Test
    fun `average finish skips the unfinished season`() {
        val ann = tables.allTime.single { it.manager.key == "{A}" }
        // 2024 final rank 1; 2025 has no final ranks; 2026 in progress.
        assertEquals(1.0, ann.averageFinish!!, 1e-9)
        assertNull(tables.allTime.single { it.manager.key == "team:2025:4" }.averageFinish)
    }
}
