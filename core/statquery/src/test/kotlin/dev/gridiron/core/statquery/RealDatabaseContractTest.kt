package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
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
 * 250 ms is the spec's full-season scoring budget: a phone-experience target,
 * measured on this project's reference machine. GitHub Actions' shared
 * runners measured ~2.6x slower here, too noisy to hold to that target, so a
 * CI run (CI is set) instead checks [SCORING_SPEED_CI_BUDGET_MS] — the same
 * coarse regression ceiling `a full-season, many-column grid is fast` already
 * uses below — while still printing the real median either way.
 */
private const val SCORING_SPEED_BUDGET_MS = 250
private const val SCORING_SPEED_CI_BUDGET_MS = 1_000

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

    /**
     * The most recent season with a full regular season on record. `seasons.last()`
     * can be the season in progress (as few as its first week or two), which
     * doesn't have enough players to exercise "every player" contract checks;
     * the speed and index tests don't need this and use `seasons.last()` directly.
     */
    private val lastCompleteSeason: Int by lazy {
        seasons.last { s ->
            val maxWeek = query("SELECT MAX(week) FROM player_week_stat WHERE season = ?", s) { it.getInt(1) }.single()
            maxWeek >= WeekRange.lastRegularSeasonWeek(s)
        }
    }

    private data class MetricRow(val internal: Boolean, val facts: Int, val computed: Boolean)

    private val metrics: Map<String, MetricRow> by lazy {
        query(
            """SELECT m.id, m.internal,
                      (SELECT COUNT(*) FROM player_week_stat s WHERE s.metric_id = m.id),
                      m.computed
               FROM metric m""",
        ) { it.getString(1) to MetricRow(it.getInt(2) == 1, it.getInt(3), it.getInt(4) == 1) }.toMap()
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
            assertEquals(column.isFantasy, m.computed, "${column.metricId}: computed flag must match isFantasy")
        }
        val displayed = StatColumn.entries.map { it.metricId }.toSet()
        val internalOnly = StatColumn.entries.flatMap { it.aggregate.components }.map { it.id }.toSet() - displayed
        for (id in internalOnly + Components.GAMES.id) {
            assertTrue(metrics.getValue(id).internal, "$id is only a denominator but not flagged internal")
        }
    }

    @Test
    fun `every scoring component is a registered internal metric with data`() {
        for (c in SCORING_COMPONENTS) {
            val m = metrics[c.id]
            assertTrue(m != null, "scoring component ${c.id} is not in the metric table")
            assertTrue(m!!.facts > 0, "scoring component ${c.id} has no facts")
            if (c.id !in StatColumn.entries.map { it.metricId }) {
                assertTrue(m.internal, "scoring-only component ${c.id} must be internal")
            }
        }
    }

    @Test
    fun `computed metrics have no facts`() {
        for (column in StatColumn.entries.filter { it.isFantasy }) {
            assertEquals(0, metrics.getValue(column.metricId).facts, "${column.metricId} has stored facts")
        }
    }

    /**
     * Over a single week, a recomputed column must equal the weekly value the
     * ETL computed and stored. This checks every column, for every player-week,
     * in every season in the database.
     */
    @Test
    fun `single-week recomputation reproduces every stored weekly value`() {
        // Fantasy columns are computed from a scoring profile at query time and
        // have no stored weekly facts to compare against; they're covered by
        // the independent-scorer test below instead.
        val storedColumns = StatColumn.entries.filterNot { it.isFantasy }
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
                    season, WeekRange.single(week), storedColumns,
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
                    for (column in storedColumns) {
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

    /** Exercises every rule, a position override and overlapping, open and closed bonuses. */
    private val everyRule = ScoringProfile(
        id = "contract",
        name = "Every rule",
        weights = ScoringRule.entries.withIndex().associate { (i, rule) -> rule to (i % 7 - 3) * 0.25 + 0.05 },
        receptionByPosition = mapOf(Position.TE to 1.75, Position.RB to 0.4),
        yardageBonuses = listOf(
            YardageBonus(BonusStat.RUSH_REC_YARDS, 100, 150, 2.0),
            YardageBonus(BonusStat.RUSH_REC_YARDS, 150, null, 4.0),
            YardageBonus(BonusStat.PASSING_YARDS, 300, null, 3.0),
            YardageBonus(BonusStat.RECEIVING_YARDS, 100, 200, 1.5),
        ),
    )

    /** Written independently of `Scoring.kt`: metric ids by hand, per rule. */
    private fun referenceWeek(profile: ScoringProfile, position: String?, s: Map<String, Double>): Pair<Double, Double> {
        fun v(id: String) = s[id] ?: 0.0
        val pos = position?.let(Position::fromCode)
        val rec = profile.receptionWeight(pos)
        fun w(r: ScoringRule) = profile.weight(r)
        var fp = w(ScoringRule.PASS_YARD) * v("passing_yards") + w(ScoringRule.PASS_TD) * v("passing_tds") +
            w(ScoringRule.INTERCEPTION) * v("interceptions") + w(ScoringRule.PASS_2PT) * v("passing_2pt") +
            w(ScoringRule.COMPLETION) * v("completions") +
            w(ScoringRule.INCOMPLETION) * (v("attempts") - v("completions")) +
            w(ScoringRule.PASS_FIRST_DOWN) * v("passing_first_downs") + w(ScoringRule.SACK_TAKEN) * v("sacks_taken") +
            w(ScoringRule.PASS_TD_40) * v("passing_tds_40") + w(ScoringRule.PASS_TD_50) * v("passing_tds_50") +
            w(ScoringRule.RUSH_YARD) * v("rushing_yards") + w(ScoringRule.RUSH_TD) * v("rushing_tds") +
            w(ScoringRule.RUSH_2PT) * v("rushing_2pt") + w(ScoringRule.CARRY) * v("carries") +
            w(ScoringRule.RUSH_FIRST_DOWN) * v("rushing_first_downs") +
            w(ScoringRule.RUSH_TD_40) * v("rushing_tds_40") + w(ScoringRule.RUSH_TD_50) * v("rushing_tds_50") +
            rec * v("receptions") + w(ScoringRule.REC_YARD) * v("receiving_yards") +
            w(ScoringRule.REC_TD) * v("receiving_tds") + w(ScoringRule.REC_2PT) * v("receiving_2pt") +
            w(ScoringRule.REC_FIRST_DOWN) * v("receiving_first_downs") +
            w(ScoringRule.REC_TD_40) * v("receiving_tds_40") + w(ScoringRule.REC_TD_50) * v("receiving_tds_50") +
            w(ScoringRule.FUMBLE_LOST) * v("fumbles_lost")
        for (b in profile.yardageBonuses) {
            val yards = when (b.stat) {
                BonusStat.PASSING_YARDS -> v("passing_yards")
                BonusStat.RUSHING_YARDS -> v("rushing_yards")
                BonusStat.RECEIVING_YARDS -> v("receiving_yards")
                BonusStat.RUSH_REC_YARDS -> v("rushing_yards") + v("receiving_yards")
            }
            if (b.applies(yards)) fp += b.points
        }
        val xfp = w(ScoringRule.PASS_YARD) * v("x_passing_yards") + w(ScoringRule.PASS_TD) * v("x_passing_tds") +
            w(ScoringRule.INTERCEPTION) * v("x_interceptions") + w(ScoringRule.PASS_2PT) * v("x_passing_2pt") +
            w(ScoringRule.COMPLETION) * v("x_completions") +
            w(ScoringRule.PASS_FIRST_DOWN) * v("x_passing_first_downs") +
            w(ScoringRule.RUSH_YARD) * v("x_rushing_yards") + w(ScoringRule.RUSH_TD) * v("x_rushing_tds") +
            w(ScoringRule.RUSH_2PT) * v("x_rushing_2pt") + w(ScoringRule.RUSH_FIRST_DOWN) * v("x_rushing_first_downs") +
            rec * v("x_receptions") + w(ScoringRule.REC_YARD) * v("x_receiving_yards") +
            w(ScoringRule.REC_TD) * v("x_receiving_tds") + w(ScoringRule.REC_2PT) * v("x_receiving_2pt") +
            w(ScoringRule.REC_FIRST_DOWN) * v("x_receiving_first_downs")
        return fp to xfp
    }

    @Test
    fun `builder fantasy points match an independent per-week scorer for every player`() {
        val season = lastCompleteSeason
        val weeks = WeekRange.regularSeason(season)
        val positions = query("SELECT player_id, position FROM player") { it.getString(1) to it.getString(2) }.toMap()
        val facts = query(
            "SELECT player_id, week, metric_id, value FROM player_week_stat WHERE season = ? AND week BETWEEN ? AND ?",
            season, weeks.first, weeks.last,
        ) { Triple(it.getString(1) to it.getInt(2), it.getString(3), it.getDouble(4)) }
        val byWeek = facts.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }
        // The SQL scores every player-week that has any scoring input (a
        // zero-yard week still meets a bonus whose minimum is 0), so the
        // reference does the same.
        val scoringIds = SCORING_COMPONENTS.map { it.id }.toSet()
        val expected = mutableMapOf<String, Pair<Double, Double>>()
        for ((key, stats) in byWeek) {
            if (stats.keys.none { it in scoringIds }) continue
            val (fp, xfp) = referenceWeek(everyRule, positions[key.first], stats)
            val (f0, x0) = expected[key.first] ?: (0.0 to 0.0)
            expected[key.first] = (f0 + fp) to (x0 + xfp)
        }

        var offset = 0
        var checked = 0
        while (true) {
            val spec = StatQuerySpec(
                season, weeks,
                listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE),
                scoring = everyRule, limit = StatQuerySpec.MAX_LIMIT, offset = offset,
            )
            val q = StatQueryBuilder.grid(spec)
            val rows = run(q.query)
            for (r in rows) {
                val id = r[GridLayout.PLAYER_ID] as String
                val (fp, xfp) = expected[id] ?: (0.0 to 0.0)
                val got = { c: StatColumn -> (r[q.layout.valueIndex(c)] as Number).toDouble() }
                assertEquals(fp, got(StatColumn.FANTASY_POINTS), 1e-6, "fantasy points for $id")
                assertEquals(xfp, got(StatColumn.EXPECTED_FANTASY_POINTS), 1e-6, "xFP for $id")
                assertEquals(fp - xfp, got(StatColumn.FPOE), 1e-6, "FPOE for $id")
                checked++
            }
            if (rows.size < StatQuerySpec.MAX_LIMIT) break
            offset += rows.size
        }
        println("scored and checked $checked players for $season")
        assertTrue(checked > 400, "only $checked players checked")
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

    /** The spec both the speed test and its index-plan sibling below run. */
    private fun scoredSpec(): StatQuerySpec {
        // A complete season, not seasons.last(): the season in progress has far
        // fewer weeks and players, so it wouldn't exercise the full-season
        // volume these tests exist to measure.
        val season = lastCompleteSeason
        return StatQuerySpec(
            season, WeekRange.regularSeason(season),
            listOf(
                StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE,
                StatColumn.TARGETS, StatColumn.CARRIES, StatColumn.TARGET_SHARE,
                StatColumn.CARRY_SHARE, StatColumn.SNAP_SHARE,
            ),
            scoring = everyRule, percentiles = true, limit = StatQuerySpec.MAX_LIMIT,
            sort = listOf(Sort(StatColumn.FANTASY_POINTS)),
        )
    }

    @Test
    fun `a scored grid is served by the metric index, never a fact table scan`() {
        val q = StatQueryBuilder.grid(scoredSpec()).query
        val plan = conn.prepareStatement("EXPLAIN QUERY PLAN ${q.sql}").use { ps ->
            ps.bindAll(q.binds)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
        }
        println("scored query plan:\n  " + plan.joinToString("\n  "))
        assertTrue(plan.any { "idx_pws_metric_season_week" in it }, "metric index unused: $plan")
        assertFalse(plan.any { Regex("^SCAN (s|player_week_stat)\\b").containsMatchIn(it) }, "fact table scanned: $plan")
    }

    @Test
    fun `scoring a full season for every player is fast`() {
        val q = StatQueryBuilder.grid(scoredSpec()).query
        repeat(3) { run(q) }
        val times = (1..10).map {
            val t0 = System.nanoTime()
            run(q)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        val ci = System.getenv("CI") != null
        val budget = if (ci) SCORING_SPEED_CI_BUDGET_MS else SCORING_SPEED_BUDGET_MS
        println(
            "full-season scored Fantasy pack, all players: median %.1f ms (min %.1f, max %.1f, budget %d ms%s)"
                .format(median, times.first(), times.last(), budget, if (ci) ", CI" else ""),
        )
        assertTrue(median < budget, "median $median ms (budget ${budget}ms)")
    }
}
