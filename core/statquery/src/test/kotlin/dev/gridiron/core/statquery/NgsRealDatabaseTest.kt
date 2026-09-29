package dev.gridiron.core.statquery

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn.NGS_CUSHION
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE_PER_ATT
import dev.gridiron.core.statquery.StatColumn.NGS_SEPARATION
import dev.gridiron.core.statquery.StatColumn.NGS_TIME_TO_THROW
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

/**
 * A Grid column over a week range is the weighted average of NGS's own weekly
 * rows, computed here straight from the published CSV, not the mean of the
 * weekly averages. Needs the ETL's download cache (`~/.cache/gridiron`, or
 * GRIDIRON_CACHE), which the Python build fills; skipped without it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class NgsRealDatabaseTest {
    private lateinit var conn: Connection

    @BeforeAll
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite:file:${System.getenv("GRIDIRON_STATS_DB")}?mode=ro")
    }

    @AfterAll
    fun close() = conn.close()

    private val season = 2025
    private val weeks = WeekRange(1, 8)

    private fun csvRows(name: String): List<Map<String, String>> {
        val cache = System.getenv("GRIDIRON_CACHE")?.let(::File) ?: File(System.getProperty("user.home"), ".cache/gridiron")
        val file = File(cache, name)
        assumeTrue(file.isFile, "$file isn't cached; run the Python build once")
        return GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
            val it = lines.iterator()
            val header = it.next().split(',')
            // NGS's fields here hold no quoted commas apart from names, so split on the columns we read by position.
            it.asSequence().map { line -> header.zip(line.split(',')).toMap() }.toList()
        }
    }

    private fun gridValue(playerId: String, column: StatColumn): Double? {
        val spec = StatQuerySpec(season = season, weeks = weeks, columns = listOf(column), playerIds = setOf(playerId))
        val q = StatQueryBuilder.grid(spec)
        val rows = conn.prepareStatement(q.query.sql).use { ps ->
            ps.bindAll(q.query.binds)
            ps.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
            }
        }
        return rows.singleOrNull()?.let { GridRow(it, q.layout).value(column) }
    }

    /** The player with the most weeks in 1..8 in [rows], and his weeks. */
    private fun busiest(rows: List<Map<String, String>>): List<Map<String, String>> =
        rows.filter { it["season"] == "$season" && it["week"]!!.toInt() in 1..8 && it["season_type"] == "REG" }
            .groupBy { it["player_gsis_id"] }
            .filterKeys { !it.isNullOrEmpty() }
            .values.maxBy { it.size }

    private fun weighted(weeks: List<Map<String, String>>, value: String, weight: String): Double =
        weeks.sumOf { it.getValue(value).toDouble() * it.getValue(weight).toDouble() } / weeks.sumOf { it.getValue(weight).toDouble() }

    @Test
    fun `a receiver's separation and cushion over weeks 1-8 are targets-weighted averages of NGS's rows`() {
        val player = busiest(csvRows("ngs_receiving.csv.gz"))
        val id = player.first().getValue("player_gsis_id")
        assertEquals(weighted(player, "avg_separation", "targets"), gridValue(id, NGS_SEPARATION)!!, 1e-6)
        assertEquals(weighted(player, "avg_cushion", "targets"), gridValue(id, NGS_CUSHION)!!, 1e-6)
    }

    @Test
    fun `a passer's time to throw over weeks 1-8 is attempts-weighted`() {
        val player = busiest(csvRows("ngs_passing.csv.gz"))
        val id = player.first().getValue("player_gsis_id")
        assertEquals(weighted(player, "avg_time_to_throw", "attempts"), gridValue(id, NGS_TIME_TO_THROW)!!, 1e-6)
    }

    @Test
    fun `a rusher's yards over expected per carry is the summed RYOE over the summed carries`() {
        val player = busiest(csvRows("ngs_rushing.csv.gz"))
        val id = player.first().getValue("player_gsis_id")
        val expected = player.sumOf { it.getValue("rush_yards_over_expected").toDouble() } /
            player.sumOf { it.getValue("rush_attempts").toDouble() }
        assertEquals(expected, gridValue(id, NGS_RYOE_PER_ATT)!!, 1e-6)
    }
}
