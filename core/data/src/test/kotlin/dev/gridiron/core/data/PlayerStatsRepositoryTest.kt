package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The Player page's stats against the real ETL-built database. */
class PlayerStatsRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var repo: PlayerStatsRepository
    private lateinit var catalog: Catalog

    @BeforeEach
    fun setUp() = runTest {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        repo = PlayerStatsRepository(executor, Locale.US)
        catalog = stats.catalog()
    }

    @AfterEach
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private val ppr = ScoringPresets.PPR

    private suspend fun topId(pack: StatPack, filter: PositionFilter): String {
        val s = catalog.season(2025)
        return stats.grid(GridRequest(s, s.defaultWeeks, pack, filter), catalog).rows.first().playerId
    }

    /** The game log's cells for [column] add up to the season line's total, one row per game, oldest week first. */
    private suspend fun assertLogAddsUp(id: String, position: Position, column: StatColumn) {
        val s = repo.stats(id, position, ppr, season = 2025)
        assertEquals(2025, s.season)
        val index = PlayerStatSets.logColumns(position).indexOf(column)
        assertTrue(index >= 0, "$column is not in $position's log")
        val fromLog = s.log.sumOf { it.cells[index].toDouble() }
        val line = s.line.first { it.column == column }
        assertEquals(line.total.toDouble(), fromLog, 0.0, "$position $column")
        assertEquals(s.games, s.log.size, "$position: one log row per game")
        assertEquals(s.log.map { it.week }.sorted(), s.log.map { it.week })
        assertEquals(PlayerStatSets.logColumns(position).size, s.logHeaders.size)
    }

    @Test
    fun `a receivers season line has totals, per-game values and percentiles`() = runTest {
        val id = topId(StatPack.RECEIVING, PositionFilter.WR)
        val s = repo.stats(id, Position.WR, ppr, season = 2025)
        assertTrue(2025 in s.seasons)
        assertTrue(s.ranked)
        assertTrue(s.bar!!.startsWith("min "), s.bar)
        val targets = s.line.first { it.column == StatColumn.TARGETS }
        assertTrue(targets.total.toInt() > 0)
        assertTrue('.' in targets.perGame, targets.perGame)
        assertTrue(targets.percentile!! in 0f..1f)
        val share = s.line.first { it.column == StatColumn.TARGET_SHARE }
        assertEquals("", share.perGame)
        assertTrue(share.total.endsWith("%"), share.total)
    }

    @Test
    fun `a receivers game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.RECEIVING, PositionFilter.WR), Position.WR, StatColumn.TARGETS)
    }

    @Test
    fun `a backs game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.RUSHING, PositionFilter.RB), Position.RB, StatColumn.CARRIES)
    }

    @Test
    fun `a quarterbacks game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.PASSING, PositionFilter.QB), Position.QB, StatColumn.PASSING_YARDS)
    }

    @Test
    fun `a kickers line has no expected points, and his game log adds up`() = runTest {
        val id = topId(StatPack.KICKING, PositionFilter.K)
        assertLogAddsUp(id, Position.K, StatColumn.FG_ATT)
        val s = repo.stats(id, Position.K, ppr, season = 2025)
        assertTrue(s.line.none { it.column == StatColumn.EXPECTED_FANTASY_POINTS || it.column == StatColumn.FPOE })
        assertTrue(s.line.any { it.column == StatColumn.FG_MADE })
    }

    @Test
    fun `a defenses log has an opponent and a result every week, and adds up`() = runTest {
        val id = topId(StatPack.DEFENSE, PositionFilter.DST)
        assertLogAddsUp(id, Position.DST, StatColumn.POINTS_ALLOWED)
        assertLogAddsUp(id, Position.DST, StatColumn.YARDS_ALLOWED)
        val s = repo.stats(id, Position.DST, ppr, season = 2025)
        assertNull(s.bar)
        assertTrue(s.ranked)
        assertTrue(s.log.all { it.opponent != "–" && it.result != null }, s.log.toString())
    }

    @Test
    fun `a light-usage player shows values but no percentiles`() = runTest {
        val light = executor.query(
            SqlQuery(
                "SELECT s.player_id FROM player_week_stat s JOIN player p USING (player_id) " +
                    "WHERE s.metric_id = ? AND s.season = ? AND p.position = ? " +
                    "GROUP BY s.player_id HAVING SUM(s.value) BETWEEN 1 AND 5 LIMIT 1",
                listOf(Bind.Text("targets"), Bind.Integer(2025), Bind.Text("WR")),
            ),
        ) { it.text(0) }.single()
        val s = repo.stats(light, Position.WR, ppr, season = 2025)
        assertFalse(s.ranked)
        val targets = s.line.first { it.column == StatColumn.TARGETS }
        assertNotEquals("–", targets.total)
        assertNull(targets.percentile)
    }

    @Test
    fun `a season he has no games in falls back to his latest`() = runTest {
        val id = topId(StatPack.RECEIVING, PositionFilter.WR)
        val s = repo.stats(id, Position.WR, ppr, season = 1999)
        assertEquals(s.seasons.last(), s.season)
    }

    @Test
    fun `an unknown player has no stats`() = runTest {
        assertEquals(PlayerStats.EMPTY, repo.stats("00-0000000", Position.WR, ppr))
    }
}
