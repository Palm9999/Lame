package dev.gridiron.core.data

import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the K and D-ST chips list kickers and team defenses with their own packs`() = runTest {
        val season = catalog.season(2025)
        val kickers = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.KICKING, positions = PositionFilter.K), catalog)
        val defenses = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.DEFENSE, positions = PositionFilter.DST), catalog)

        assertTrue(kickers.rows.size in 25..45, "${kickers.rows.size} kickers")
        assertTrue(kickers.rows.all { it.position == "K" })
        assertEquals(32, defenses.rows.size)
        assertTrue(defenses.rows.all { it.position == "DST" && it.name.endsWith(" D/ST") && it.detail.startsWith("D/ST · ") })
        val points = defenses.rows.map { it.cells.first().text }
        assertTrue(points.isNotEmpty() && points.none { it.isBlank() }, "$points")
    }

    @Test
    fun `every other chip, a search and a roster in the All view leave kickers and team defenses out`() = runTest {
        val season = catalog.season(2025)
        val special = executor.query(
            SqlQuery("SELECT player_id FROM player WHERE position IN ('K', 'DST')", emptyList()),
        ) { it.text(0) }.toSet()
        assertTrue(special.any { it.startsWith("DST_") } && special.any { !it.startsWith("DST_") }, "no kickers or D/STs in the database")

        for (positions in PositionFilter.entries - PositionFilter.K - PositionFilter.DST) {
            val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = positions), catalog)
            assertTrue(page.rows.none { it.playerId in special }, "$positions")
        }
        val roster = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special), catalog)
        val search = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, name = "dst"), catalog)
        assertEquals(emptyList<String>(), roster.rows.map { it.playerId })
        assertTrue(search.rows.none { it.playerId in special }, "${search.rows.map { it.playerId }}")
        assertEquals(0, repo.count(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special)))

        // The same roster under the K chip shows its kickers.
        val rosterKickers = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.KICKING, positions = PositionFilter.K, onlyPlayers = special), catalog)
        assertTrue(rosterKickers.rows.isNotEmpty() && rosterKickers.rows.all { it.position == "K" })
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

    private suspend fun page(pack: StatPack): GridPage {
        val season = catalog.season(2025)
        return repo.grid(GridRequest(season, season.defaultWeeks, pack), catalog)
    }

    @Test
    fun `a percent column's cell shows digits only and keeps its percent sign in text`() = runTest {
        val page = page(StatPack.OPPORTUNITY)
        val i = page.columns.indexOfFirst { it.column == StatColumn.TARGET_SHARE }
        val cells = page.rows.map { it.cells[i] }.filter { it.text != StatFormat.MISSING }
        assertTrue(cells.isNotEmpty())
        cells.forEach {
            assertTrue(it.text.endsWith("%"), it.text)
            assertEquals(it.text.removeSuffix("%"), it.display)
        }
    }

    @Test
    fun `a non-percent column's display equals its text`() = runTest {
        val page = page(StatPack.OPPORTUNITY)
        val i = page.columns.indexOfFirst { it.column == StatColumn.TARGETS }
        page.rows.forEach { assertEquals(it.cells[i].text, it.cells[i].display) }
    }

    @Test
    fun `a percent column's header ends in a percent sign`() = runTest {
        val opportunity = page(StatPack.OPPORTUNITY)
        for (c in opportunity.columns) {
            if (StatFormat.isPercent(c.column)) assertTrue(c.header.endsWith("%"), c.header)
        }
        // RSR has no % in its abbreviation, so one is appended.
        val efficiency = page(StatPack.EFFICIENCY)
        val rsr = efficiency.columns.firstOrNull { it.column == StatColumn.RUSH_SUCCESS_RATE }
        if (rsr != null) assertEquals("RSR %", rsr.header)
    }

    @Test
    fun `an NGS percentage column (already in points) is unchanged`() = runTest {
        val page = page(StatPack.NGS_RUSHING)
        val i = page.columns.indexOfFirst { it.column == StatColumn.NGS_STACKED_BOX_PCT }
        assertEquals("8+ BOX%", page.columns[i].header)
        page.rows.forEach { assertEquals(it.cells[i].text, it.cells[i].display) }
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
    fun `a roster lists exactly its players, qualified or not, and an empty one lists no one`() = runTest {
        val season = catalog.season(2025)
        val base = GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)
        val qualified = repo.grid(base, catalog).rows.map { it.playerId }.toSet()
        val below = repo.grid(base.copy(name = "a"), catalog).rows.first { it.playerId !in qualified }
        val roster = setOf(qualified.first(), below.playerId)

        val page = repo.grid(base.copy(onlyPlayers = roster), catalog)
        assertEquals(roster, page.rows.map { it.playerId }.toSet())
        assertEquals(2, repo.count(base.copy(onlyPlayers = roster)))
        assertEquals(emptyList<GridRowUi>(), repo.grid(base.copy(onlyPlayers = emptySet()), catalog).rows.toList())
        assertEquals(0, repo.count(base.copy(onlyPlayers = emptySet())))
    }

    @Test
    fun `excludePlayers lists everyone else, ranked, and count agrees`() = runTest {
        val season = catalog.season(2025)
        val base = GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)
        val all = repo.grid(base, catalog).rows.map { it.playerId }
        val taken = all.take(3).toSet()

        val rest = repo.grid(base.copy(excludePlayers = taken), catalog)
        assertEquals(all.filterNot { it in taken }, rest.rows.map { it.playerId })
        assertEquals(all.size - 3, repo.count(base.copy(excludePlayers = taken)))
        // Unlike a roster, this keeps the sample bar: the below-bar player a search finds stays out.
        val below = repo.grid(base.copy(name = "a"), catalog).rows.first { it.playerId !in all }
        assertTrue(below.playerId !in rest.rows.map { it.playerId })
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

    @Test
    fun `catalog lists every current team`() {
        assertEquals(32, catalog.teams.size)
        assertEquals(catalog.teams.sorted(), catalog.teams)
        assertTrue("KC" in catalog.teams)
    }

    @Test
    fun `a team filter keeps only that team's players`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, teams = setOf("KC")), catalog)
        assertTrue(page.rows.isNotEmpty())
        assertTrue(page.rows.all { it.detail.contains(" · KC · ") }, page.rows.map { it.detail }.toString())
    }

    @Test
    fun `snap share and advanced filters narrow the page and the count agrees`() = runTest {
        val season = catalog.season(2025)
        val base = GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR)
        val all = repo.grid(base, catalog).rows.size
        val snap = base.copy(minSnapShare = 0.75)
        val snapRows = repo.grid(snap, catalog).rows.size
        assertTrue(snapRows in 1 until all, "snap filter kept $snapRows of $all")
        assertEquals(snapRows, repo.count(snap))

        // A filter on a column the pack doesn't show.
        val carries = base.copy(filters = listOf(Filter(StatColumn.CARRIES, Condition.AtLeast(5.0))))
        val carryRows = repo.grid(carries, catalog).rows.size
        assertTrue(carryRows in 1 until all, "carries filter kept $carryRows of $all")
        assertEquals(carryRows, repo.count(carries))
    }

    @Test
    fun `filters still apply while searching by name`() = runTest {
        // "ma" matches Mahomes, a KC player who has played in 2025; the name search and
        // the team filter must both hold.
        val season = catalog.season(2025)
        val r = GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, name = "ma", teams = setOf("KC"))
        val rows = repo.grid(r, catalog).rows
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.detail.contains(" · KC · ") })
    }

    /** One raw fact, straight from the table, independent of the query builder. */
    private suspend fun fact(playerId: String, week: Int, metric: String): Double? =
        executor.query(
            SqlQuery(
                "SELECT value FROM player_week_stat WHERE player_id = ? AND season = 2025 AND week = ? AND metric_id = ?",
                listOf(Bind.Text(playerId), Bind.Integer(week.toLong()), Bind.Text(metric)),
            ),
        ) { it.double(0) }.singleOrNull()
}
