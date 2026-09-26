package dev.gridiron.feature.compare

import dev.gridiron.core.data.CompareGroup
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The Compare view model over the real repositories and database, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var compare: CompareRepository
    private lateinit var prefs: FakePrefsSource

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        Dispatchers.setMain(dispatcher)
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        compare = CompareRepository(executor, Locale.US)
    }

    @After
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(tray: List<CompareSlot>): CompareViewModel {
        prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(tray = tray))
        return CompareViewModel(stats, compare, ScoringRepository(prefs), CompareTrayRepository(prefs))
    }

    /** Top two WR ids, 2025 regular season. */
    private fun twoReceivers(): List<CompareSlot> = runBlocking {
        val catalog = stats.catalog()
        val season = catalog.season(2025)
        stats.grid(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, PositionFilter.WR), catalog)
            .rows.take(2).map { CompareSlot(it.playerId, 2025, season.defaultWeeks) }
    }

    /** Three more slots (two RBs, one QB), so the page mixes positions. */
    private fun threeMore(): List<CompareSlot> = runBlocking {
        val catalog = stats.catalog()
        val season = catalog.season(2025)
        val rbs = stats.grid(GridRequest(season, season.defaultWeeks, StatPack.RUSHING, PositionFilter.RB), catalog).rows.take(2)
        val qbs = stats.grid(GridRequest(season, season.defaultWeeks, StatPack.PASSING, PositionFilter.QB), catalog).rows.take(1)
        (rbs + qbs).map { CompareSlot(it.playerId, 2025, season.defaultWeeks) }
    }

    @Test
    fun loadsTheTrayAndReactsToPerGameAndProfile() = runTest(dispatcher) {
        val vm = viewModel(tray = twoReceivers())
        advanceUntilIdle()
        val ready = vm.state.value as CompareUiState.Ready
        assertEquals(2, ready.page.slots.size)
        val fpTotal = ready.page.groups.first { it.group == CompareGroup.SCORING }.rows.first().cells[0].text
        vm.onEvent(CompareEvent.PerGameToggled)
        advanceUntilIdle()
        val fpPerGame = (vm.state.value as CompareUiState.Ready).page.groups.first { it.group == CompareGroup.SCORING }.rows.first().cells[0].text
        assertNotEquals(fpTotal, fpPerGame)
        vm.onEvent(CompareEvent.ProfileSelected(ScoringPresets.STANDARD.id))
        advanceUntilIdle()
        assertEquals(ScoringPresets.STANDARD, (vm.state.value as CompareUiState.Ready).page.request.scoring)
    }

    @Test
    fun fewerThanTwoSlotsShowsTheEmptyState() = runTest(dispatcher) {
        val vm = viewModel(tray = twoReceivers().take(1))
        advanceUntilIdle()
        assertEquals(CompareUiState.NeedsPlayers, vm.state.value)
    }

    @Test
    fun removingASlotUpdatesTheTray() = runTest(dispatcher) {
        val slots = twoReceivers() + threeMore().take(2) // four: the tray's capacity
        val vm = viewModel(tray = slots)
        advanceUntilIdle()
        vm.onEvent(CompareEvent.RemoveSlot(slots.last()))
        advanceUntilIdle()
        assertEquals(slots.dropLast(1), prefs.current.tray)
    }

    @Test
    fun radarPairDefaultsToTheFirstTwoAndCanChange() = runTest(dispatcher) {
        val vm = viewModel(tray = twoReceivers() + threeMore().take(1))
        advanceUntilIdle()
        assertEquals(0 to 1, (vm.state.value as CompareUiState.Ready).radarPair)
        vm.onEvent(CompareEvent.RadarPairChanged(0, 2))
        assertEquals(0 to 2, (vm.state.value as CompareUiState.Ready).radarPair)
    }

    @Test
    fun radarPairSkipsSlotsExcludedFromCharts() = runTest(dispatcher) {
        val (a, b) = twoReceivers()
        // Slot 0 is for a season that isn't in the database: nothing to chart.
        val vm = viewModel(tray = listOf(CompareSlot(a.playerId, 2003, WeekRange(1, 17)), a, b))
        advanceUntilIdle()
        assertEquals(1 to 2, (vm.state.value as CompareUiState.Ready).radarPair)
    }

    @Test
    fun addingAScatterPointUsesTheScattersSeasonAndRange() = runTest(dispatcher) {
        val slots = twoReceivers()
        // The scatter is drawn for the first slot with data (slot 1), not slot 0's dead season.
        val tray = listOf(CompareSlot(slots[0].playerId, 2003, WeekRange(1, 17))) + slots
        val vm = viewModel(tray = tray)
        advanceUntilIdle()
        val scatter = (vm.state.value as CompareUiState.Ready).page.scatter!!
        assertEquals(slots[0].season, scatter.season)
        val other = scatter.population.first { p -> slots.none { it.playerId == p.playerId } }
        vm.onEvent(CompareEvent.AddPointToCompare(other.playerId, other.name))
        advanceUntilIdle()
        assertEquals(CompareSlot(other.playerId, scatter.season, scatter.weeks), prefs.current.tray.last())
    }

    @Test
    fun `a new data version reloads the catalog and keeps the page`() = runTest(dispatcher) {
        val slots = twoReceivers()
        val version = MutableStateFlow(0L)
        var catalogs = 0
        val counting = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                if (query == CatalogQueries.seasons) catalogs++
                return executor.query(query, map)
            }
        }
        stats = StatsRepository(counting, Locale.US, dataVersion = version)
        val vm = viewModel(slots)
        advanceUntilIdle()
        assertTrue(vm.state.value is CompareUiState.Ready)

        version.value = 1
        advanceUntilIdle()

        assertEquals(2, catalogs)
        assertTrue(vm.state.value is CompareUiState.Ready)
    }
}
