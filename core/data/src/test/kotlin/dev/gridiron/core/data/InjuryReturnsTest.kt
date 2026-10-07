package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class InjuryReturnsTest {

    @Test
    fun `normalize keeps one body part and merges names`() {
        assertEquals("knee", InjuryReturns.normalize("Knee, Ankle"))
        assertEquals("rib", InjuryReturns.normalize("Ribs"))
        assertEquals("quadricep", InjuryReturns.normalize("Quad"))
        assertEquals("non-injury", InjuryReturns.normalize("Not injury related - personal matter"))
        assertEquals("non-injury", InjuryReturns.normalize("Illness"))
        assertNull(InjuryReturns.normalize(" "))
    }

    @Test
    fun `an absence counts games missed until he plays, censored past the last game`() {
        val played = mapOf((2025 to "KC") to listOf(1, 2, 3, 5, 6))
        val appeared = setOf(
            Triple("a", 2025, 1), Triple("a", 2025, 5), // out weeks 2 and 3, the bye skipped
            Triple("b", 2025, 2), // out from week 3 to the end
            Triple("c", 2025, 1), Triple("c", 2025, 2), // listed Doubtful for week 2 and played
        )
        val listings = listOf(
            InjuryListing("a", 2025, 2, "KC", "Out", "Hamstring"),
            InjuryListing("a", 2025, 3, "KC", "Out", "Hamstring"), // the same absence: no game played before it
            InjuryListing("b", 2025, 3, "KC", "Out", "Knee"),
            InjuryListing("c", 2025, 2, "KC", "Doubtful", null),
            InjuryListing("d", 2025, 4, "KC", "Out", null), // the bye week: no game
        )
        val absences = InjuryReturns.absences(listings, appeared, played)
        assertEquals(
            listOf(
                Absence(2025, "Out", "hamstring", 2, censored = false),
                Absence(2025, "Out", "knee", 3, censored = true),
                Absence(2025, "Doubtful", null, 0, censored = false),
            ),
            absences,
        )
    }

    @Test
    fun `lasting drops a censored absence after the games it was seen to miss`() {
        val absences = listOf(
            Absence(2025, "Out", null, 1, false),
            Absence(2025, "Out", null, 2, false),
            Absence(2025, "Out", null, 1, true),
            Absence(2025, "Out", null, 3, false),
        )
        val (g, atRisk) = InjuryReturns.lasting(absences, 3)
        // The censored one is out of the count from game 1: he was never seen to play or miss it.
        assertEquals(listOf(4, 3, 2, 1), atRisk.toList())
        assertEquals(1.0, g[1], 1e-9)
        assertEquals(2.0 / 3, g[2], 1e-9)
        assertEquals(1.0 / 3, g[3], 1e-9)
    }

    private fun assertClose(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size, "$actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-9, "$actual") }
    }

    private fun many(status: String, injury: String?, missed: List<Int>, times: Int) =
        List(times) { missed.map { m -> Absence(2025, status, injury, m, false) } }.flatten()

    @Test
    fun `the outlook uses the body part with enough cases, else every injury`() {
        val absences = many("Out", "knee", listOf(1, 2, 3), 10) + many("Out", "ankle", listOf(1, 1), 5) +
            many("Doubtful", null, listOf(0), 20)
        val knee = InjuryReturns.outlook(absences, "Out", "Knee", 0, listOf(6, 8, 9, 10, 11))!!
        assertEquals("knee", knee.injury)
        assertEquals(30, knee.cases)
        assertEquals(listOf(6, 8, 9, 10), knee.weeks)
        assertClose(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), knee.chances)
        val ankle = InjuryReturns.outlook(absences, "Out", "Ankle", 0, listOf(6))!!
        assertNull(ankle.injury, "10 ankle cases are too few")
        assertEquals(40, ankle.cases)
    }

    @Test
    fun `games already missed condition on absences that lasted as long, whatever their first status`() {
        val absences = many("Out", "knee", listOf(1, 2, 3), 10) + many("Doubtful", "knee", listOf(4), 10)
        val o = InjuryReturns.outlook(absences, "Out", "Knee", 2, listOf(7, 8))!!
        assertEquals(30, o.cases) // 10 lasting 2, 10 lasting 3, 10 lasting 4
        assertClose(listOf(1.0 / 3, 2.0 / 3), o.chances)
    }

    @Test
    fun `an IR stint can't end before its minimum`() {
        val absences = many("Out", null, listOf(1, 2, 4, 5, 6), 10)
        val o = InjuryReturns.outlook(absences, "Out", null, 1, listOf(5, 6, 7, 8), minimum = 4)!!
        // Games 2-4 of the stint can't be played; then a third of those out 4+ are back for game 5 (index 4).
        assertClose(listOf(0.0, 0.0, 0.0, 1.0 / 3), o.chances)
        assertEquals(30, o.cases)
    }

    @Test
    fun `no next game or too little history gives no outlook`() {
        assertNull(InjuryReturns.outlook(many("Out", null, listOf(1), 20), "Out", null, 0, emptyList()))
        assertNull(InjuryReturns.outlook(many("Out", null, listOf(1), 5), "Out", null, 0, listOf(3)))
    }

    @Test
    fun `statusOf reads ESPN's designations`() {
        assertEquals("Out", InjuryReturns.statusOf("O"))
        assertEquals("Out", InjuryReturns.statusOf("IR"))
        assertEquals("Doubtful", InjuryReturns.statusOf("D"))
        assertNull(InjuryReturns.statusOf("Q"))
        assertNull(InjuryReturns.statusOf(null))
    }

    private fun fixture(vararg inserts: String): JdbcQueryExecutor {
        val file = File.createTempFile("returns-fixture", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player (player_id TEXT, full_name TEXT, position TEXT, team TEXT)")
                st.executeUpdate("CREATE TABLE player_week_stat (player_id TEXT, season INTEGER, week INTEGER, team TEXT, metric_id TEXT, value REAL)")
                st.executeUpdate(
                    """CREATE TABLE injury_report (player_id TEXT, season INTEGER, week INTEGER, team TEXT,
                         name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT)""",
                )
                st.executeUpdate(
                    """CREATE TABLE game (game_id TEXT, season INTEGER, week INTEGER, game_type TEXT, home_team TEXT,
                         away_team TEXT, home_score INTEGER, away_score INTEGER)""",
                )
                inserts.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `the repository reads past absences and the player's own state`() = runTest {
        val inserts = buildList {
            // 2025: twelve hamstring absences of one game each on KC (played week 1, out week 2, back week 3).
            for (w in 1..3) add("INSERT INTO game VALUES ('g$w', 2025, $w, 'REG', 'KC', 'BUF', 20, 17)")
            for (i in 1..12) {
                add("INSERT INTO player VALUES ('h$i', 'H$i', 'WR', 'KC')")
                add("INSERT INTO injury_report VALUES ('h$i', 2025, 2, 'KC', 'H$i', 'WR', 'Out', 'Hamstring', 'DNP')")
                add("INSERT INTO player_week_stat VALUES ('h$i', 2025, 1, 'KC', 'g', 1)")
                add("INSERT INTO player_week_stat VALUES ('h$i', 2025, 3, 'KC', 'g', 1)")
            }
            // 2026: KC played weeks 1-2, plays 3 and 5 next; p played week 1, missed week 2, is out again.
            add("INSERT INTO game VALUES ('n1', 2026, 1, 'REG', 'KC', 'BUF', 20, 17)")
            add("INSERT INTO game VALUES ('n2', 2026, 2, 'REG', 'DEN', 'KC', 20, 17)")
            add("INSERT INTO game VALUES ('n3', 2026, 3, 'REG', 'KC', 'LV', NULL, NULL)")
            add("INSERT INTO game VALUES ('n5', 2026, 5, 'REG', 'KC', 'LAC', NULL, NULL)")
            add("INSERT INTO player VALUES ('p', 'P', 'RB', 'KC')")
            add("INSERT INTO player_week_stat VALUES ('p', 2026, 1, 'KC', 'g', 1)")
            add("INSERT INTO injury_report VALUES ('p', 2026, 2, 'KC', 'P', 'RB', 'Out', 'Hamstring', 'DNP')")
            add("INSERT INTO injury_report VALUES ('p', 2026, 3, 'KC', 'P', 'RB', 'Out', NULL, 'DNP')")
            add("INSERT INTO player VALUES ('q', 'Q', 'RB', 'KC')")
            add("INSERT INTO player_week_stat VALUES ('q', 2026, 2, 'KC', 'g', 1)")
        }
        val repo = InjuryReturnRepository(fixture(*inserts.toTypedArray()))
        // p's own 2026 absence is still going (censored at 1), so 12 of the 13 lasting a game are back after it.
        val p = repo.outlook("p", 2026, abbr = null)!!
        assertEquals("Out", p.status)
        assertEquals(1, p.missedSoFar)
        assertEquals(listOf(3, 5), p.weeks)
        assertEquals(null, p.injury, "12 hamstring cases are under MIN_CASES")
        assertEquals(12, p.cases)
        assertEquals(1.0, p.chances[0], 1e-9)
        // q played the last game and isn't listed: no outlook unless ESPN says he's out.
        assertNull(repo.outlook("q", 2026, abbr = null))
        assertEquals(0, repo.outlook("q", 2026, abbr = "O")!!.missedSoFar)
    }

    @Test
    fun `on the real database an Out player is rarely back the next week and mostly within four`() = runTest {
        val path = StatsDb.path
        assumeTrue(path != null, "GRIDIRON_STATS_DB not set")
        val absences = InjuryReturnRepository(JdbcQueryExecutor(path!!)).absences(0L)
        assertTrue(absences.size > 200, "absences: ${absences.size}")
        val o = InjuryReturns.outlook(absences, "Out", null, 0, listOf(1, 2, 3, 4))!!
        assertTrue(o.chances[0] < 0.05, "back the week he is listed Out: ${o.chances}")
        assertTrue(o.chances[1] in 0.15..0.40, "back the next game: ${o.chances}")
        assertTrue(o.chances[3] in 0.5..0.85, "back within four: ${o.chances}")
    }

    @Test
    fun `texts read the chances by week and where they come from`() {
        val o = ReturnOutlook("Out", "hamstring", 97, 0, 0, listOf(6, 8, 9), listOf(0.001, 0.28, 1.0), 2024, 2026)
        assertEquals("wk 6 1% · wk 8 28% · wk 9 100%", o.chancesText())
        assertEquals("From 97 past hamstring absences (players listed Out), 2024–2026. QB, RB, WR, TE and K; byes skipped.", o.basisText())
        val ir = o.copy(injury = null, missedSoFar = 1, minimum = 4, firstSeason = 2025, lastSeason = 2025)
        assertEquals("IR: at least 4 games out. From 97 past absences that had lasted 4 games, 2025. QB, RB, WR, TE and K; byes skipped.", ir.basisText())
    }
}
