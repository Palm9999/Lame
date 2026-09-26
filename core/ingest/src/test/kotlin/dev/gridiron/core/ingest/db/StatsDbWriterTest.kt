package dev.gridiron.core.ingest.db

import dev.gridiron.core.ingest.Fact
import dev.gridiron.core.ingest.InjuryRow
import dev.gridiron.core.ingest.METRICS
import dev.gridiron.core.ingest.PlayerInfo
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.ingest.query
import org.junit.jupiter.api.Assertions.assertEquals
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
                w.writeTeamDefense(listOf(TeamDefenseRow("AAA", s, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0)))
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
        assertEquals("6", meta["schema_version"])
        assertEquals("1", meta["ingest_version"])
        assertEquals("2025", meta["seasons"])
        assertEquals("3", meta["expected_through_week:2025"])
        assertEquals("2026-09-25T12:00:00Z", meta["built_at"])
        assertTrue("ffopportunity" in meta.getValue("source"))

        assertEquals(listOf(listOf("WR1", "Wide Receiver", "wide receiver")), query(file, "SELECT player_id, full_name, search_name FROM player"))
        assertEquals(listOf(listOf("102", "WR1"), listOf("105", "OLD1")), query(file, "SELECT espn_id, player_id FROM player_xref ORDER BY espn_id"))
        assertEquals(listOf(listOf("2")), query(file, "SELECT COUNT(*) FROM player_week_stat"))
        assertEquals(listOf(listOf("81")), query(file, "SELECT COUNT(*) FROM metric"))
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'idx_pws_metric_season_week'").size)
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").size)
        assertEquals(listOf(listOf("Questionable")), query(file, "SELECT status FROM injury_report"))
        assertEquals(listOf(listOf("300.0")), query(file, "SELECT yards_allowed FROM team_week_defense"))
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
}
