package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale

/** The repository against the real ETL-built database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class StatsRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var repo: StatsRepository
    private lateinit var catalog: Catalog

    @BeforeAll
    fun open() = runTest {
        executor = JdbcQueryExecutor(checkNotNull(StatsDb.path))
        repo = StatsRepository(executor, Locale.US)
        catalog = repo.catalog()
    }

    @AfterAll
    fun close() = executor.close()

    @Test
    fun `catalog lists seasons with their last played week and every visible column's metadata`() {
        assertTrue(catalog.seasons.map { it.season }.containsAll(listOf(2024, 2025)))
        assertEquals(22, catalog.season(2025).lastWeek)
        for (column in StatColumn.entries) {
            assertTrue(column.metricId in catalog.metrics, "no metadata for ${column.metricId}")
        }
    }

    @Test
    fun `every pack renders a complete, sorted page`() = runTest {
        for (pack in StatPack.entries) {
            for (perGame in listOf(false, true)) {
                val season = catalog.season(2025)
                val page = repo.grid(GridRequest(season, season.defaultWeeks, pack, perGame = perGame), catalog)
                val where = "${pack.name} perGame=$perGame"

                assertTrue(page.rows.size > 20, "$where: only ${page.rows.size} rows")
                assertEquals(pack.columns, page.columns.map { it.column }, where)
                page.rows.forEach { row ->
                    assertEquals(pack.columns.size, row.cells.size, where)
                    row.cells.forEach { c -> c.heat?.let { assertTrue(it in -1f..1f, "$where: heat $it") } }
                }
                // Sorted by the pack's lead column, best first.
                val lead = page.rows.mapNotNull { it.cells.first().text.toDoubleOrNull() }
                assertEquals(lead.sortedDescending(), lead, "$where: not sorted")
            }
        }
    }

    @Test
    fun `a rate sort carries its sample floor and shows it`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(
            GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, sort = StatColumn.CATCH_RATE),
            catalog,
        )
        assertEquals("min 54 targets", page.threshold)
        val targets = page.columns.indexOfFirst { it.column == StatColumn.TARGETS }
        assertTrue(page.rows.all { it.cells[targets].text.toInt() >= 54 })
    }

    @Test
    fun `lower-is-better sorts put the fewest first`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(
            GridRequest(season, season.defaultWeeks, StatPack.PASSING, sort = StatColumn.INTERCEPTIONS)
                .copy(direction = Direction.ASCENDING),
            catalog,
        )
        val ints = page.columns.indexOfFirst { it.column == StatColumn.INTERCEPTIONS }
        val values = page.rows.map { it.cells[ints].text.toInt() }
        assertEquals(values.sorted(), values)
    }

    @Test
    fun `name search narrows the grid`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, name = "nacua"), catalog)
        assertEquals(listOf("Puka Nacua"), page.rows.map { it.name })
    }

    @Test
    fun `heat spans the scale once ranking is among qualified players`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY), catalog)
        val heats = page.rows.flatMap { r -> r.cells.mapNotNull { it.heat } }
        // Both ends of the scale are in use: qualified starters are ranked
        // against each other, not against backups.
        assertTrue(heats.any { it < -0.5f }, "no clearly below-median cells")
        assertTrue(heats.any { it > 0.5f }, "no clearly above-median cells")
    }

    @Test
    fun `search finds players below the qualifying bar, unranked`() = runTest {
        val season = catalog.season(2025)
        val base = GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)
        val qualified = repo.grid(base, catalog).rows.map { it.playerId }.toSet()
        // Anyone in the database who doesn't qualify: search for them by name.
        val below = repo.grid(base.copy(name = "a"), catalog).rows.first { it.playerId !in qualified }

        val found = repo.grid(base.copy(name = below.name), catalog).rows.first { it.playerId == below.playerId }
        assertTrue(found.cells.all { it.heat == null }, "an unqualified player shouldn't be ranked")
    }

    @Test
    fun `the fantasy pack ranks by points under the requested profile`() = runTest {
        val season = catalog.season(2025)
        val ppr = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, scoring = ScoringPresets.PPR), catalog)
        val std = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, scoring = ScoringPresets.STANDARD), catalog)
        assertEquals(StatColumn.FANTASY_POINTS, ppr.columns.first().column)
        assertEquals("FPTS", ppr.columns.first().header)
        val points = ppr.rows.map { it.cells.first().text.replace(",", "").toDouble() }
        assertEquals(points.sortedDescending(), points)
        assertTrue(ppr.rows.first().cells.first().text.matches(Regex("""\d+\.\d""")))
        // Receptions are worth a point in PPR and nothing in Standard.
        assertTrue(points.first() > std.rows.first().cells.first().text.replace(",", "").toDouble())
    }

    @Test
    fun `players are looked up by id`() = runTest {
        val some = repo.grid(GridRequest(catalog.latest, catalog.latest.defaultWeeks, StatPack.OPPORTUNITY), catalog).rows.take(3)
        val found = repo.players(some.map { it.playerId } + "missing")
        assertEquals(some.map { it.playerId }.toSet(), found.keys)
        assertEquals(some.first().name, found.getValue(some.first().playerId).name)
    }
}
