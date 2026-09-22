package dev.gridiron.feature.players

import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The ViewModel over the real repository and database, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class GridViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var executor: JdbcQueryExecutor

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        Dispatchers.setMain(dispatcher)
        executor = JdbcQueryExecutor(StatsDb.path!!)
    }

    @After
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.ready(vm: GridViewModel): GridUiState.Ready {
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue("expected Ready, was $s", s is GridUiState.Ready)
        return s as GridUiState.Ready
    }

    @Test
    fun `opens on the latest season's opportunity leaders`() = runTest(dispatcher) {
        val vm = GridViewModel(StatsRepository(executor, Locale.US))
        val s = ready(vm)

        assertEquals(s.catalog.latest, s.request.season)
        assertEquals(StatPack.OPPORTUNITY, s.request.pack)
        assertEquals(StatColumn.WOPR, s.request.sort)
        assertFalse("page should have loaded", s.refreshing)
        assertTrue(s.page!!.rows.isNotEmpty())
    }

    @Test
    fun `events flow through to a new page`() = runTest(dispatcher) {
        val vm = GridViewModel(StatsRepository(executor, Locale.US))
        ready(vm)

        vm.onEvent(GridEvent.SeasonSelected(2025))
        vm.onEvent(GridEvent.PackSelected(StatPack.RUSHING))
        vm.onEvent(GridEvent.PositionsSelected(PositionFilter.RB))
        val s = ready(vm)

        val page = checkNotNull(s.page)
        assertEquals(s.request, page.request)
        assertEquals(StatColumn.RUSHING_YARDS, s.request.sort)
        assertTrue(page.rows.all { it.detail.startsWith("RB") })
    }

    @Test
    fun `a burst of events runs one query for the final state`() = runTest(dispatcher) {
        var queries = 0
        val counting = object : dev.gridiron.core.database.QueryExecutor {
            override suspend fun <T> query(
                query: dev.gridiron.core.statquery.SqlQuery,
                map: (dev.gridiron.core.database.ResultRow) -> T,
            ): List<T> {
                queries++
                return executor.query(query, map)
            }
        }
        val vm = GridViewModel(StatsRepository(counting, Locale.US))
        ready(vm)
        val before = queries

        "puka".forEach { vm.onEvent(GridEvent.NameChanged(it.toString())) }
        vm.onEvent(GridEvent.NameChanged("nacua"))
        val s = ready(vm)

        assertEquals("one grid query for five keystrokes", before + 1, queries)
        assertEquals("nacua", s.page!!.request.name)
    }
}

/** State transitions, without coroutines or a database. */
class GridReduceTest {
    private val season = dev.gridiron.core.data.SeasonInfo(2025, lastWeek = 22)
    private val catalog = Catalog(
        kotlinx.collections.immutable.persistentListOf(dev.gridiron.core.data.SeasonInfo(2024, 22), season),
        kotlinx.collections.immutable.persistentMapOf(),
    )
    private val start = dev.gridiron.core.data.GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)

    private fun reduce(vararg events: GridEvent) =
        events.fold(start) { r, e -> GridViewModel.reduce(r, e, catalog) }

    @Test
    fun `tapping the sorted column flips direction`() {
        val once = reduce(GridEvent.SortBy(StatColumn.WOPR))
        assertEquals(Direction.ASCENDING, once.direction)
        assertEquals(Direction.DESCENDING, reduce(GridEvent.SortBy(StatColumn.WOPR), GridEvent.SortBy(StatColumn.WOPR)).direction)
    }

    @Test
    fun `tapping another column sorts it best first`() {
        assertEquals(Direction.DESCENDING, reduce(GridEvent.SortBy(StatColumn.TARGETS)).direction)
        assertEquals(Direction.ASCENDING, reduce(GridEvent.SortBy(StatColumn.INTERCEPTIONS)).direction)
    }

    @Test
    fun `switching packs resets the sort to the new pack's lead stat`() {
        val r = reduce(GridEvent.SortBy(StatColumn.ADOT), GridEvent.PackSelected(StatPack.PASSING))
        assertEquals(StatColumn.PASSING_YARDS, r.sort)
        assertEquals(Direction.DESCENDING, r.direction)
    }

    @Test
    fun `switching seasons resets weeks to that season's default`() {
        val r = reduce(GridEvent.WeeksChanged(WeekRange(3, 5)), GridEvent.SeasonSelected(2024))
        assertEquals(2024, r.season.season)
        assertEquals(WeekRange(1, 18), r.weeks)
    }

    @Test
    fun `weeks label shows played weeks only`() {
        val inProgress = start.copy(season = dev.gridiron.core.data.SeasonInfo(2026, 2), weeks = WeekRange(1, 18))
        assertEquals("Wk 1–2", weeksLabel(inProgress))
        assertEquals("Week 7", weeksLabel(start.copy(weeks = WeekRange(7, 7))))
    }
}
