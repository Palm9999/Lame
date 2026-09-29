package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.MissingColumnsException
import dev.gridiron.core.ingest.db.StatsDbWriter
import dev.gridiron.core.ingest.pbp.PlayerWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class NgsTest {
    @TempDir
    lateinit var dir: File

    private val passingHeader = listOf(
        "season", "week", "team_abbr", "player_gsis_id", "attempts",
        "avg_time_to_throw", "aggressiveness", "avg_intended_air_yards",
    )
    private val rushingHeader = listOf(
        "season", "week", "team_abbr", "player_gsis_id", "rush_attempts",
        "efficiency", "percent_attempts_gte_eight_defenders", "rush_yards_over_expected",
    )
    private val receivingHeader = listOf(
        "season", "week", "team_abbr", "player_gsis_id", "targets", "receptions",
        "avg_cushion", "avg_separation", "avg_yac_above_expectation",
    )

    private fun stream(header: List<String>, vararg rows: Map<String, Any?>) =
        ByteArrayInputStream(Fixtures.csv(header, rows.toList()).toByteArray())

    private fun passing(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            "season" to 2025, "week" to 3, "team_abbr" to "AAA", "player_gsis_id" to "QB1", "attempts" to 30,
            "avg_time_to_throw" to 2.5, "aggressiveness" to 20.0, "avg_intended_air_yards" to 8.0,
        ) + overrides

    private fun rushing(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            "season" to 2025, "week" to 3, "team_abbr" to "AAA", "player_gsis_id" to "RB1", "rush_attempts" to 20,
            "efficiency" to 3.5, "percent_attempts_gte_eight_defenders" to 25.0, "rush_yards_over_expected" to 6.5,
        ) + overrides

    private fun receiving(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            "season" to 2025, "week" to 3, "team_abbr" to "AAA", "player_gsis_id" to "WR1", "targets" to 10,
            "receptions" to 6, "avg_cushion" to 6.0, "avg_separation" to 3.0, "avg_yac_above_expectation" to 1.5,
        ) + overrides

    @Test
    fun `week 0 season aggregates are skipped`() {
        val rows = readNgsPassing(stream(passingHeader, passing("week" to 0), passing("week" to 1)), "ngs_passing.csv.gz")
        assertEquals(listOf(1), rows.map { it.week })
    }

    @Test
    fun `passing components are each average times attempts`() {
        val row = readNgsPassing(stream(passingHeader, passing()), "ngs_passing.csv.gz").single()
        assertEquals(listOf("QB1", 2025, 3, "AAA"), listOf(row.playerId, row.season, row.week, row.team))
        assertEquals(30.0, row.values["ngs_attempts"])
        assertEquals(75.0, row.values["ngs_ttt_w"])
        assertEquals(600.0, row.values["ngs_aggr_w"])
        assertEquals(240.0, row.values["ngs_iay_w"])
    }

    @Test
    fun `a missing average stores nothing for it and a zero weight stores nothing at all`() {
        val noAverage = readNgsPassing(stream(passingHeader, passing("avg_time_to_throw" to null)), "ngs_passing.csv.gz").single()
        assertNull(noAverage.values["ngs_ttt_w"])
        assertEquals(600.0, noAverage.values["ngs_aggr_w"])
        val noWeight = readNgsPassing(stream(passingHeader, passing("attempts" to 0)), "ngs_passing.csv.gz")
        assertEquals(0, noWeight.size)
    }

    @Test
    fun `rushing stores RYOE directly and the other averages times carries`() {
        val row = readNgsRushing(stream(rushingHeader, rushing()), "ngs_rushing.csv.gz").single()
        assertEquals(20.0, row.values["ngs_carries"])
        assertEquals(70.0, row.values["ngs_eff_w"])
        assertEquals(500.0, row.values["ngs_box_w"])
        assertEquals(6.5, row.values["ngs_ryoe"])
    }

    @Test
    fun `a season without a RYOE model has no RYOE but keeps its other components`() {
        val row = readNgsRushing(stream(rushingHeader, rushing("rush_yards_over_expected" to null)), "ngs_rushing.csv.gz").single()
        assertNull(row.values["ngs_ryoe"])
        assertEquals(70.0, row.values["ngs_eff_w"])
    }

    @Test
    fun `receiving weights separation and cushion by targets and YAC over expected by receptions`() {
        val row = readNgsReceiving(stream(receivingHeader, receiving()), "ngs_receiving.csv.gz").single()
        assertEquals(10.0, row.values["ngs_targets"])
        assertEquals(6.0, row.values["ngs_receptions"])
        assertEquals(30.0, row.values["ngs_sep_w"])
        assertEquals(60.0, row.values["ngs_cush_w"])
        assertEquals(9.0, row.values["ngs_yacoe_w"])
    }

    @Test
    fun `a renamed column fails the read with the column named`() {
        val header = passingHeader.map { if (it == "aggressiveness") "aggression" else it }
        val e = assertThrows(MissingColumnsException::class.java) {
            readNgsPassing(stream(header, passing()), "ngs_passing.csv.gz")
        }
        assertEquals(listOf("aggressiveness"), e.missing)
    }

    private fun week(season: Int, week: Int) = PlayerWeek(season, week, "AAA", "P$week", mutableMapOf("ngs_targets" to 1.0))

    @Test
    fun `NGS week 23 becomes 22 when the season has no week 22`() {
        val out = remapPostseasonWeeks(listOf(week(2025, 21), week(2025, 23), week(2024, 23)))
        assertEquals(listOf(21, 22, 22), out.map { it.week })
    }

    @Test
    fun `a season with both week 22 and week 23 is left alone`() {
        val out = remapPostseasonWeeks(listOf(week(2025, 22), week(2025, 23)))
        assertEquals(listOf(22, 23), out.map { it.week })
    }

    @Test
    fun `a player in two files becomes one row with both sets of components`() {
        val qb = readNgsPassing(stream(passingHeader, passing("player_gsis_id" to "X1")), "ngs_passing.csv.gz")
        val rush = readNgsRushing(stream(rushingHeader, rushing("player_gsis_id" to "X1")), "ngs_rushing.csv.gz")
        val merged = mergeNgs(qb, rush).single()
        assertEquals(30.0, merged.values["ngs_attempts"])
        assertEquals(20.0, merged.values["ngs_carries"])
    }

    @Test
    fun `NGS facts for a player nflverse doesn't list are dropped like any other`() {
        val rows = readNgsReceiving(
            stream(receivingHeader, receiving("player_gsis_id" to "WR1"), receiving("player_gsis_id" to "GHOST")),
            "ngs_receiving.csv.gz",
        )
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeFacts(toFacts(rows))
            w.writePlayers(listOf(PlayerInfo("WR1", "Wide Receiver", "wide receiver", "WR", "AAA", null, null)))
        }
        assertEquals(listOf(listOf("WR1")), query(file, "SELECT DISTINCT player_id FROM player_week_stat"))
    }
}
