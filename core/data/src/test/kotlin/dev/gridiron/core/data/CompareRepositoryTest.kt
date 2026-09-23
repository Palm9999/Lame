package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The compare repository against the real ETL-built database. */
class CompareRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var compare: CompareRepository
    private lateinit var catalog: Catalog

    @BeforeEach
    fun setUp() = runTest {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        compare = CompareRepository(executor, Locale.US)
        catalog = stats.catalog()
    }

    @AfterEach
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private suspend fun topIds(pack: StatPack, filter: PositionFilter, n: Int, season: Int = 2025): List<String> {
        val s = catalog.season(season)
        return stats.grid(GridRequest(s, s.defaultWeeks, pack, filter), catalog).rows.take(n).map { it.playerId }
    }

    private fun request(vararg slots: CompareSlot, perGame: Boolean = false) =
        CompareRequest(slots.toList(), ScoringPresets.PPR, perGame)

    private val season2025 get() = catalog.season(2025).defaultWeeks

    @Test
    fun `two receivers are ranked in their position with composites that average their rows`() = runTest {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        assertEquals(listOf(SlotStatus.OK, SlotStatus.OK), page.slots.map { it.status })
        assertEquals(CompareGroup.entries, page.groups.map { it.group })
        for (g in page.groups) {
            for (slot in 0..1) {
                val ranked = g.rows.mapNotNull { it.cells[slot].percentile }
                val composite = g.composite[slot]
                if (ranked.isEmpty()) assertNull(composite) else assertEquals(ranked.average(), composite!!.toDouble(), 1e-6)
            }
        }
        val targets = page.groups.first().rows.first { it.column == StatColumn.TARGETS }
        assertNotNull(targets.diff)
        assertTrue(targets.cells.all { it.percentile!! in 0f..1f })
    }

    @Test
    fun `a player can be compared with himself across seasons`() = runTest {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(
            request(CompareSlot(id, 2025, season2025), CompareSlot(id, 2025, WeekRange(1, 8))),
            catalog,
        )
        assertEquals(2, page.slots.size)
        assertNotEquals(page.slots[0].detail, page.slots[1].detail)
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
        val star = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(request(CompareSlot(star, 2025, season2025), CompareSlot(light, 2025, season2025)), catalog)
        assertEquals(SlotStatus.SMALL_SAMPLE, page.slots[1].status)
        val targets = page.groups.first().rows.first { it.column == StatColumn.TARGETS }
        assertNotEquals("—", targets.cells[1].text)
        assertNull(targets.cells[1].percentile)
    }

    @Test
    fun `slots for a season that left the database, or an unknown player, don't break the page`() = runTest {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(
            request(CompareSlot(id, 2025, season2025), CompareSlot(id, 2003, WeekRange(1, 17)), CompareSlot("00-0000000", 2025, season2025)),
            catalog,
        )
        assertEquals(listOf(SlotStatus.OK, SlotStatus.NO_SEASON, SlotStatus.MISSING), page.slots.map { it.status })
        assertEquals("No 2003 data", page.slots[1].detail)
        assertTrue(page.groups.flatMap { it.rows }.all { it.cells[1].text == "—" && it.cells[2].text == "—" })
    }

    @Test
    fun `a player with no games in his slot's range says so and is left out of every chart`() = runTest {
        // A 2025 receiver with no 2024 rows at all: the player and the season both exist.
        val rookie = executor.query(
            SqlQuery(
                "SELECT s.player_id FROM player_week_stat s JOIN player p USING (player_id) " +
                    "WHERE s.metric_id = ? AND s.season = ? AND p.position = ? AND s.player_id NOT IN " +
                    "(SELECT player_id FROM player_week_stat WHERE season = ?) " +
                    "GROUP BY s.player_id ORDER BY SUM(s.value) DESC LIMIT 1",
                listOf(Bind.Text("targets"), Bind.Integer(2025), Bind.Text("WR"), Bind.Integer(2024)),
            ),
        ) { it.text(0) }.single()
        val star = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        for (perGame in listOf(false, true)) {
            val page = compare.compare(
                request(
                    CompareSlot(star, 2025, season2025),
                    CompareSlot(rookie, 2024, catalog.season(2024).defaultWeeks),
                    perGame = perGame,
                ),
                catalog,
            )
            assertEquals(listOf(SlotStatus.OK, SlotStatus.NO_GAMES), page.slots.map { it.status })
            assertEquals("No games in this range", page.slots[1].detail)
            assertEquals(listOf(0), page.chartedSlots)
            assertTrue(page.groups.flatMap { it.rows }.all { it.cells[1].text == "—" && it.cells[1].percentile == null })
            assertTrue(page.groups.all { it.composite[1] == null })
            assertNull(page.scatter!!.slots[1])
        }
    }

    @Test
    fun `a quarterback and a receiver show the union with dashes where a stat doesn't apply`() = runTest {
        val qb = topIds(StatPack.PASSING, PositionFilter.QB, 1).single()
        val wr = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(request(CompareSlot(qb, 2025, season2025), CompareSlot(wr, 2025, season2025)), catalog)
        val rows = page.groups.flatMap { it.rows }.associateBy { it.column }
        assertEquals("—", rows.getValue(StatColumn.DROPBACKS).cells[1].text)
        assertEquals("—", rows.getValue(StatColumn.TARGETS).cells[0].text)
        assertNull(rows.getValue(StatColumn.TARGETS).diff)
    }

    @Test
    fun `the scatter holds the first slot's position and places the compared players`() = runTest {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        val scatter = page.scatter!!
        assertEquals(Position.WR, scatter.position)
        assertTrue(scatter.population.size > 40)
        assertTrue(scatter.population.all { it.fpPerGame in -5.0..45.0 && it.xfpPerGame in 0.0..40.0 })
        assertEquals(a, scatter.slots[0]!!.playerId)
        val radar = page.radar!!
        assertEquals(radar.axes.size, radar.values[0].size)
    }

    /** A slot's per-game fantasy and expected points, queried on its own: the reference for its scatter point. */
    private suspend fun perGamePoints(slot: CompareSlot): Pair<Double, Double> {
        val spec = StatQuerySpec(
            season = slot.season,
            weeks = slot.weeks,
            columns = listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS),
            includeUnqualified = true,
            minGames = 1,
            mode = ValueMode.PER_GAME,
            playerIds = setOf(slot.playerId),
            limit = 1,
            scoring = ScoringPresets.PPR,
        )
        val q = StatQueryBuilder.grid(spec)
        return executor.query(q.query) { r ->
            r.doubleOrNull(q.layout.valueIndex(StatColumn.FANTASY_POINTS))!! to
                r.doubleOrNull(q.layout.valueIndex(StatColumn.EXPECTED_FANTASY_POINTS))!!
        }.single()
    }

    @Test
    fun `each slot's scatter point is its own per-game points and expected points, in either mode`() = runTest {
        val qb = topIds(StatPack.PASSING, PositionFilter.QB, 1).single()
        val rb = topIds(StatPack.RUSHING, PositionFilter.RB, 1).single()
        val (wr1, wr2) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val slots = arrayOf(
            CompareSlot(wr1, 2025, season2025),
            CompareSlot(qb, 2025, season2025),
            CompareSlot(rb, 2025, WeekRange(1, 8)),
            CompareSlot(wr2, 2024, catalog.season(2024).defaultWeeks),
        )
        for (perGame in listOf(false, true)) {
            val scatter = compare.compare(request(*slots, perGame = perGame), catalog).scatter!!
            slots.forEachIndexed { i, slot ->
                val (fp, xfp) = perGamePoints(slot)
                val point = scatter.slots[i]!!
                assertEquals(slot.playerId, point.playerId)
                assertEquals(fp, point.fpPerGame, 1e-9, "fp, slot $i, perGame=$perGame")
                assertEquals(xfp, point.xfpPerGame, 1e-9, "xfp, slot $i, perGame=$perGame")
            }
        }
    }

    /** Counts the queries a compare issues. */
    private class CountingExecutor(private val inner: QueryExecutor) : QueryExecutor {
        var count = 0
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
            count++
            return inner.query(query, map)
        }
    }

    @Test
    fun `slots that share a season, range and position cost one query, with no per-slot scatter queries`() = runTest {
        val ids = topIds(StatPack.RECEIVING, PositionFilter.WR, 4)
        val counting = CountingExecutor(executor)
        CompareRepository(counting, Locale.US).compare(
            request(*ids.map { CompareSlot(it, 2025, season2025) }.toTypedArray()),
            catalog,
        )
        // Player headers, the shared ranked query and the scatter population.
        assertEquals(3, counting.count)
    }
}
