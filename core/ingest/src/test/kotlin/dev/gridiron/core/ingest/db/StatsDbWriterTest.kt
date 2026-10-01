package dev.gridiron.core.ingest.db

import dev.gridiron.core.ingest.Fact
import dev.gridiron.core.ingest.GameRow
import dev.gridiron.core.ingest.InjuryRow
import dev.gridiron.core.ingest.METRICS
import dev.gridiron.core.ingest.PlayerInfo
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.ingest.query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class StatsDbWriterTest {
    @TempDir
    lateinit var dir: File

    private val wr1 = PlayerInfo("WR1", "Wide Receiver", "wide receiver", "WR", "AAA", "pWR1", "102")
    private val old = PlayerInfo("OLD1", "Retired Guy", "retired guy", "WR", "XXX", null, "105")

    private fun fact(pid: String, season: Int, metric: String, value: Double) = Fact(pid, season, 1, "AAA", metric, value)

    private fun build(file: File, seasons: List<Int>) {
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            for (s in seasons) {
                w.writeFacts(listOf(fact("WR1", s, "targets", 5.0), fact("WR1", s, "target_share", 0.5)))
                w.writeTeamDefense(listOf(TeamDefenseRow("AAA", s, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0)))
                w.writeInjuries(listOf(InjuryRow("WR1", s, 1, "AAA", "Wide Receiver", "WR", "Questionable", "Knee", "Limited")))
            }
            w.writePlayers(listOf(wr1, old))
            w.finish(seasons, mapOf("expected_through_week:${seasons.last()}" to "3"), Instant.parse("2026-09-25T12:00:00Z"))
        }
    }

    @Test
    fun `a finished database has the schema, rows and provenance`() {
        val file = File(dir, "stats.db")
        build(file, listOf(2025))

        val meta = readMeta(file)!!
        assertEquals("9", meta["schema_version"])
        assertEquals("8", meta["ingest_version"])
        assertEquals("2025", meta["seasons"])
        assertEquals("3", meta["expected_through_week:2025"])
        assertEquals("2026-09-25T12:00:00Z", meta["built_at"])
        assertTrue("ffopportunity" in meta.getValue("source"))
        assertTrue("FTN Data via nflverse" in meta.getValue("source"))

        assertEquals(listOf(listOf("WR1", "Wide Receiver", "wide receiver")), query(file, "SELECT player_id, full_name, search_name FROM player"))
        assertEquals(listOf(listOf("102", "WR1"), listOf("105", "OLD1")), query(file, "SELECT espn_id, player_id FROM player_xref ORDER BY espn_id"))
        assertEquals(listOf(listOf("2")), query(file, "SELECT COUNT(*) FROM player_week_stat"))
        assertEquals(listOf(listOf("143")), query(file, "SELECT COUNT(*) FROM metric"))
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'idx_pws_metric_season_week'").size)
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").size)
        assertEquals(listOf(listOf("Questionable")), query(file, "SELECT status FROM injury_report"))
        assertEquals(listOf(listOf("300.0")), query(file, "SELECT yards_allowed FROM team_week_defense"))
    }

    private fun weekly(season: Int, vararg weeks: Int): List<Fact> = weeks.flatMap { w ->
        listOf(Fact("WR1", season, w, "AAA", "g", 1.0), Fact("WR1", season, w, "AAA", "targets", w.toDouble()))
    }

    private fun windows(file: File, season: Int): Map<String, List<String?>> =
        query(file, "SELECT window, first_week, last_week FROM window_def WHERE season = $season ORDER BY window")
            .associate { it[0]!! to it.drop(1) }

    private fun sums(file: File, season: Int, window: String, metric: String): String? =
        query(file, "SELECT value FROM player_window_stat WHERE season = $season AND window = '$window' AND metric_id = '$metric'")
            .singleOrNull()?.get(0)

    private fun buildWeeks(file: File, season: Int, vararg weeks: Int) {
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeFacts(weekly(season, *weeks))
            w.writePlayers(listOf(wr1))
            w.finish(listOf(season), emptyMap(), Instant.parse("2026-09-25T12:00:00Z"))
        }
    }

    @Test
    fun `windows match the weekly sums`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2025, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertEquals(listOf("1", "10"), windows(file, 2025)["S"])
        assertEquals(listOf("8", "10"), windows(file, 2025)["L3"])
        assertEquals("55.0", sums(file, 2025, "S", "targets"))
        assertEquals("27.0", sums(file, 2025, "L3", "targets"))
        assertEquals("10.0", sums(file, 2025, "S", "g"))
        assertEquals("8.0", sums(file, 2025, "L8", "g"))
    }

    @Test
    fun `the rollup is keyed metric first, so it needs no second index`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2025, 1, 2, 3)
        assertEquals(emptyList<List<String?>>(), query(file, "SELECT name FROM sqlite_master WHERE name = 'idx_pws_window'"))
        val plan = query(file, "EXPLAIN QUERY PLAN SELECT player_id, SUM(value) FROM player_window_stat WHERE metric_id IN ('targets') AND season = 2025 AND window = 'S' GROUP BY player_id")
            .joinToString { it.last().orEmpty() }
        assertTrue("PRIMARY KEY (metric_id=? AND season=? AND window=?)" in plan, plan)
    }

    @Test
    fun `a last window clips to the weeks played`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2025, 1, 2)
        assertEquals(listOf("1", "2"), windows(file, 2025)["L5"])
        assertEquals("3.0", sums(file, 2025, "L5", "targets"))
    }

    @Test
    fun `a bye week inside a window counts no game`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2025, 1, 2, 3, 4, 6, 7)
        assertEquals(listOf("4", "7"), windows(file, 2025)["L4"])
        assertEquals("3.0", sums(file, 2025, "L4", "g"))
        assertEquals("17.0", sums(file, 2025, "L4", "targets"))
    }

    @Test
    fun `playoff weeks stay out of the windows`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2025, 17, 18, 19)
        assertEquals(listOf("1", "18"), windows(file, 2025)["S"])
        assertEquals(listOf("16", "18"), windows(file, 2025)["L3"])
        assertEquals("35.0", sums(file, 2025, "S", "targets"))
    }

    @Test
    fun `a season before 2021 ends its regular season at week 17`() {
        val file = File(dir, "stats.db")
        buildWeeks(file, 2020, 16, 17, 18)
        assertEquals(listOf("1", "17"), windows(file, 2020)["S"])
    }

    @Test
    fun `statistics match the Python ETL's, with no sqlite_stat4 to steer the planner off the metric index`() {
        val file = File(dir, "stats.db")
        build(file, listOf(2025))
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").size)
        assertEquals(emptyList<List<String?>>(), query(file, "SELECT name FROM sqlite_master WHERE name = 'sqlite_stat4'"))
    }

    @Test
    fun `facts for players nflverse doesn't list are dropped`() {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeFacts(listOf(fact("WR1", 2025, "targets", 5.0), fact("GHOST", 2025, "targets", 1.0)))
            assertEquals(1, w.writePlayers(listOf(wr1)))
            assertEquals(1L, w.factCount())
        }
    }

    @Test
    fun `copying a season takes only that season's rows`() {
        val previous = File(dir, "previous.db")
        build(previous, listOf(2024, 2025))
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.copySeasonFrom(previous, 2024)
        }
        assertEquals(listOf(listOf("2024", "2")), query(file, "SELECT season, COUNT(*) FROM player_week_stat GROUP BY season"))
        assertEquals(listOf(listOf("2024")), query(file, "SELECT season FROM team_week_defense"))
        assertEquals(listOf(listOf("2024")), query(file, "SELECT season FROM injury_report"))
    }

    @Test
    fun `creating over an existing file starts fresh`() {
        val file = File(dir, "stats.db")
        build(file, listOf(2025))
        StatsDbWriter.create(file).use { it.writeMetrics(METRICS) }
        assertEquals(listOf(listOf("0")), query(file, "SELECT COUNT(*) FROM player_week_stat"))
    }

    @Test
    fun `meta of something that isn't a stats database is null`() {
        val junk = File(dir, "junk.db").apply { writeText("not a database") }
        assertNull(readMeta(junk))
        assertNull(readMeta(File(dir, "missing.db")))
    }

    @Test
    fun `games are written with nulls for what isn't known yet`(@TempDir dir: File) {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeGames(
                listOf(
                    GameRow("2026_01_NE_SEA", 2026, 1, "REG", "SEA", "NE", 13, 10, 3.0, 44.5, "outdoors", "q1", "q2", "c1", "c2"),
                    GameRow("2026_05_KC_BUF", 2026, 5, "REG", "BUF", "KC", null, null, null, null, null, null, null, null, null),
                ),
            )
        }
        assertEquals(
            listOf(
                listOf("2026_01_NE_SEA", "13", "3.0", "q1", "c2"),
                listOf("2026_05_KC_BUF", null, null, null, null),
            ),
            query(file, "SELECT game_id, home_score, spread_line, home_qb_id, away_coach FROM game ORDER BY game_id"),
        )
    }

    @Test
    fun `schema 7 drops the Python ETL's projection bookkeeping tables`(@TempDir dir: File) {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).close()
        val tables = query(file, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }
        assertTrue("game" in tables)
        assertFalse("projection_snapshot" in tables)
        assertFalse("accuracy_summary" in tables)
    }

    @Test
    fun `each team's defense is a player, kept when it has stats`() {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeTeamDefense(
                listOf(
                    TeamDefenseRow("AAA", 2025, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0),
                    TeamDefenseRow("BBB", 2025, 1, 20.0, 250.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0),
                ),
            )
            w.writeFacts(listOf(Fact("DST_AAA", 2025, 1, "AAA", "dst_sacks", 2.0)))
            assertEquals(0, w.writePlayers(listOf(wr1)))
        }
        assertEquals(
            listOf(listOf("DST_AAA", "AAA D/ST", "aaa dst", "DST", "AAA")),
            query(file, "SELECT player_id, full_name, search_name, position, team FROM player ORDER BY 1"),
        )
    }
}
