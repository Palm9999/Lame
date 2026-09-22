package dev.gridiron.core.statquery

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.WeekRange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Pins this module to the database the ETL actually produces.
 *
 * Runs only when GRIDIRON_STATS_DB points at a built stats.db:
 *
 *     GRIDIRON_STATS_DB=/path/to/stats.db ./gradlew :core:statquery:test
 *
 * The Kotlin registry and formulas duplicate definitions that live in Python.
 * These tests are what stop the two from drifting apart silently.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class RealDatabaseContractTest {
    private lateinit var conn: Connection

    @BeforeAll
    fun open() {
        val path = System.getenv("GRIDIRON_STATS_DB")
        check(File(path).isFile) { "GRIDIRON_STATS_DB=$path is not a file" }
        conn = DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro")
    }

    @AfterAll
    fun close() = conn.close()

    private fun <T> query(sql: String, vararg args: Any, row: (java.sql.ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }

    private fun run(q: SqlQuery): List<List<Any?>> = conn.prepareStatement(q.sql).use { ps ->
        ps.bindAll(q.binds)
        ps.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }

    private val seasons: List<Int> by lazy {
        query("SELECT DISTINCT season FROM player_week_stat ORDER BY season") { it.getInt(1) }
    }

    private data class MetricRow(val internal: Boolean, val facts: Int)

    private val metrics: Map<String, MetricRow> by lazy {
        query(
            """SELECT m.id, m.internal,
                      (SELECT COUNT(*) FROM player_week_stat s WHERE s.metric_id = m.id)
               FROM metric m""",
        ) { it.getString(1) to MetricRow(it.getInt(2) == 1, it.getInt(3)) }.toMap()
    }

    @Test
    fun `every component is a registered metric with data`() {
        val components = StatColumn.entries.flatMap { it.aggregate.components } + Components.GAMES
        for (c in components.toSet()) {
            val m = metrics[c.id]
            assertTrue(m != null, "component ${c.id} is not in the metric table")
            assertTrue(m!!.facts > 0, "component ${c.id} has no facts")
        }
    }

    @Test
    fun `every column is a visible metric and every denominator is internal`() {
        for (column in StatColumn.entries) {
            val m = metrics[column.metricId]
            assertTrue(m != null, "${column.metricId} is not in the metric table")
            assertFalse(m!!.internal, "${column.metricId} is a column but flagged internal")
        }
        val displayed = StatColumn.entries.map { it.metricId }.toSet()
        val internalOnly = StatColumn.entries.flatMap { it.aggregate.components }.map { it.id }.toSet() - displayed
        for (id in internalOnly + Components.GAMES.id) {
            assertTrue(metrics.getValue(id).internal, "$id is only a denominator but not flagged internal")
        }
    }

    /**
     * Over a single week, a recomputed column must equal the weekly value the
     * ETL computed and stored. This checks every column, for every player-week,
     * in every season in the database.
     */
    @Test
    fun `single-week recomputation reproduces every stored weekly value`() {
        var checked = 0
        for (season in seasons) {
            val stored = HashMap<String, Double>()
            query("SELECT player_id, week, metric_id, value FROM player_week_stat WHERE season = ?", season) {
                stored["${it.getString(1)}|${it.getInt(2)}|${it.getString(3)}"] = it.getDouble(4)
            }
            val weeks = query("SELECT DISTINCT week FROM player_week_stat WHERE season = ? ORDER BY week", season) {
                it.getInt(1)
            }
            for (week in weeks) {
                val spec = StatQuerySpec(
                    season, WeekRange.single(week), StatColumn.entries,
                    minGames = 0, limit = StatQuerySpec.MAX_LIMIT,
                )
                val q = StatQueryBuilder.grid(spec)
                val rows = run(q.query)
                val expectedPlayers = query(
                    "SELECT COUNT(*) FROM player_week_stat WHERE season = ? AND week = ? AND metric_id = 'g'",
                    season, week,
                ) { it.getInt(1) }.single()
                assertTrue(rows.size < StatQuerySpec.MAX_LIMIT, "week $week truncated at the limit")
                assertEquals(expectedPlayers, rows.size, "$season week $week: player count")

                for (row in rows) {
                    val pid = row[GridLayout.PLAYER_ID] as String
                    for (column in StatColumn.entries) {
                        val derived = (row[q.layout.valueIndex(column)] as Number?)?.toDouble()
                        val expected = stored["$pid|$week|${column.metricId}"]
                        val where = "$season wk$week $pid ${column.metricId}"
                        if (expected == null) {
                            assertEquals(null, derived, "$where: stored null, derived $derived")
                        } else {
                            assertTrue(derived != null, "$where: stored $expected, derived null")
                            // Snap share is published rounded to 0.01; everything
                            // else is the same arithmetic on the same doubles.
                            val tol = if (column == StatColumn.SNAP_SHARE) 0.011 else 1e-9 * max(1.0, abs(expected))
                            assertTrue(abs(derived!! - expected) <= tol, "$where: stored $expected, derived $derived")
                        }
                        checked++
                    }
                }
            }
        }
        println("single-week contract: $checked player-week-column values matched across seasons $seasons")
        assertTrue(checked > 100_000, "suspiciously few values checked: $checked")
    }

    @Test
    fun `search normalization matches the ETL for every player`() {
        val mismatches = query("SELECT full_name, search_name FROM player") { it.getString(1) to it.getString(2) }
            .filter { (name, stored) -> normalizeSearch(name) != stored }
        assertTrue(mismatches.isEmpty(), "Kotlin and Python normalize differently: ${mismatches.take(10)}")
    }

    @Test
    fun `the grid query is served by the metric index, never a fact table scan`() {
        val spec = StatQuerySpec(
            seasons.last(), WeekRange(1, 8),
            listOf(StatColumn.TARGET_SHARE, StatColumn.WOPR, StatColumn.ADOT, StatColumn.SNAP_SHARE),
            positions = setOf(Position.WR), percentiles = true,
        )
        val q = StatQueryBuilder.grid(spec).query
        val plan = conn.prepareStatement("EXPLAIN QUERY PLAN ${q.sql}").use { ps ->
            ps.bindAll(q.binds)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
        }
        println("query plan:\n  " + plan.joinToString("\n  "))
        assertTrue(plan.any { "idx_pws_metric_season_week" in it }, "metric index unused: $plan")
        assertFalse(plan.any { Regex("^SCAN (s|player_week_stat)\\b").containsMatchIn(it) }, "fact table scanned: $plan")
    }

    @Test
    fun `a full-season, many-column grid is fast`() {
        val spec = StatQuerySpec(
            seasons.last(), WeekRange.regularSeason(seasons.last()),
            listOf(
                StatColumn.TARGETS, StatColumn.TARGET_SHARE, StatColumn.AIR_YARDS_SHARE, StatColumn.WOPR,
                StatColumn.ADOT, StatColumn.RACR, StatColumn.CATCH_RATE, StatColumn.RECEIVING_YARDS,
                StatColumn.RZ_TARGETS, StatColumn.EZ_TARGETS, StatColumn.SNAP_SHARE, StatColumn.TOTAL_EPA,
            ),
            positions = Position.FLEX, percentiles = true, minGames = 4,
            sort = listOf(Sort(StatColumn.WOPR)),
        )
        val q = StatQueryBuilder.grid(spec).query
        repeat(3) { run(q) } // warm up
        val times = (1..10).map {
            val t0 = System.nanoTime()
            run(q)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        println("full-season 12-column FLEX grid with percentiles: median %.1f ms (min %.1f, max %.1f)"
            .format(median, times.first(), times.last()))
        assertTrue(median < 1_000, "median $median ms")
    }
}
