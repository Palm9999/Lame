package dev.gridiron.feature.players

import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SeasonInfo
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.sparklineWeeks
import dev.gridiron.core.data.weeksLabel
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.FakePrefsSource
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private lateinit var repo: StatsRepository
    private val prefs = FakePrefsSource()

    private fun viewModel() = GridViewModel(repo, ScoringRepository(prefs), CompareTrayRepository(prefs), debounceMillis = 150)

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        Dispatchers.setMain(dispatcher)
        executor = JdbcQueryExecutor(StatsDb.path!!)
        repo = StatsRepository(executor, Locale.US)
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

    private fun trackingExecutor(onQuery: (SqlQuery) -> Unit): QueryExecutor = object : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
            onQuery(query)
            return executor.query(query, map)
        }
    }

    @Test
    fun `sparklines load for the current page`() = runTest(dispatcher) {
        val vm = viewModel()
        val s = ready(vm)
        val page = s.page!!
        assertEquals(page.rows.map { it.playerId }.toSet(), s.sparklines.keys)
        val window = sparklineWeeks(page.request.season, page.request.weeks)
        assertTrue(s.sparklines.values.all { it.weeks == window })
    }

    @Test
    fun `a sparkline failure leaves the Grid alone`() = runTest(dispatcher) {
        // Only the sparkline queries restrict to a player list.
        val failing = trackingExecutor { if ("player_id IN (" in it.sql) error("boom") }
        val vm = GridViewModel(StatsRepository(failing, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        val s = ready(vm)
        assertTrue(s.page!!.rows.isNotEmpty())
        assertNull(s.error)
        assertTrue(s.sparklines.isEmpty())
    }

    @Test
    fun `the draft count is debounced, counts the draft and clears when the sheet closes`() = runTest(dispatcher) {
        var counts = 0
        val counting = trackingExecutor { if (it.sql.contains("SELECT COUNT(*)")) counts++ }
        val vm = GridViewModel(StatsRepository(counting, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)
        val before = counts

        listOf(10.0, 30.0, 60.0).forEach {
            vm.onEvent(GridEvent.FilterDraftChanged(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(it)))))
        }
        runCurrent()
        assertEquals(DraftCount.Counting, (vm.state.value as GridUiState.Ready).draftCount)
        val s = ready(vm)
        assertEquals("one count for three quick edits", before + 1, counts)
        val applied = s.request.copy(filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(60.0))))
        assertEquals(DraftCount.Matches(repo.count(applied)), s.draftCount)
        assertTrue("the draft must not reach the Grid", s.request.filters.isEmpty())

        vm.onEvent(GridEvent.FilterSheetClosed)
        assertNull(ready(vm).draftCount)
    }

    @Test
    fun `applying filters narrows the page and clears the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        // The catalog must be loaded before a season change can take effect.
        ready(vm)
        vm.onEvent(GridEvent.SeasonSelected(2025))
        val before = ready(vm).page!!.rows.size
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(100.0))
        vm.onEvent(GridEvent.FilterDraftChanged(listOf(f)))
        vm.onEvent(GridEvent.FiltersApplied(listOf(f)))
        val s = ready(vm)
        val page = s.page!!
        assertEquals(listOf(f), page.request.filters)
        assertTrue(page.rows.size in 1 until before)
        assertNull(s.draftCount)
    }

    @Test
    fun `a failed count says so without blocking`() = runTest(dispatcher) {
        val failing = trackingExecutor { if (it.sql.contains("SELECT COUNT(*)")) error("boom") }
        val vm = GridViewModel(StatsRepository(failing, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)
        vm.onEvent(GridEvent.FilterDraftChanged(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(10.0)))))
        val s = ready(vm)
        assertEquals(DraftCount.Unavailable, s.draftCount)
        assertNull(s.error)
    }

    @Test
    fun `a stale count never resurrects after the sheet closes`() = runTest(dispatcher) {
        // Gates the count query so it stays in flight past the sheet closing.
        val gate = CompletableDeferred<Unit>()
        val gated = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                if (query.sql.contains("SELECT COUNT(*)")) gate.await()
                return executor.query(query, map)
            }
        }
        val vm = GridViewModel(StatsRepository(gated, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)

        vm.onEvent(GridEvent.FilterDraftChanged(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(10.0)))))
        // The debounce settles and the count query starts, then suspends on the gate.
        advanceTimeBy(300)
        runCurrent()

        vm.onEvent(GridEvent.FilterSheetClosed)

        // The stale query finally completes after the sheet is already closed.
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull((vm.state.value as GridUiState.Ready).draftCount)
    }

    @Test
    fun `an old draft's count never overwrites a newer draft's state`() = runTest(dispatcher) {
        // Only the first COUNT query is gated, so it can finish late, after a newer draft was sent.
        val gateA = CompletableDeferred<Unit>()
        var countCalls = 0
        val gated = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                if (query.sql.contains("SELECT COUNT(*)") && ++countCalls == 1) gateA.await()
                return executor.query(query, map)
            }
        }
        val vm = GridViewModel(StatsRepository(gated, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)

        val a = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(10.0)))
        val b = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(20.0)))
        vm.onEvent(GridEvent.FilterDraftChanged(a))
        advanceTimeBy(300)
        runCurrent()
        // Draft A's count query is now running, suspended on gateA.

        vm.onEvent(GridEvent.FilterDraftChanged(b))
        // A's query finally completes while B is the current draft, but B's own
        // debounce hasn't settled yet, so mapLatest hasn't cancelled A's block.
        gateA.complete(Unit)
        runCurrent()

        assertEquals(
            "A's stale result must not be shown once B is the draft",
            DraftCount.Counting,
            (vm.state.value as GridUiState.Ready).draftCount,
        )

        val s = ready(vm)
        val applied = s.request.copy(filters = b)
        assertEquals(DraftCount.Matches(repo.count(applied)), s.draftCount)
    }

    @Test
    fun `opens on the latest season's opportunity leaders`() = runTest(dispatcher) {
        val vm = viewModel()
        val s = ready(vm)

        assertEquals(s.catalog.latest, s.request.season)
        assertEquals(StatPack.OPPORTUNITY, s.request.pack)
        assertEquals(StatColumn.WOPR, s.request.sort)
        assertFalse("page should have loaded", s.refreshing)
        assertTrue(s.page!!.rows.isNotEmpty())
    }

    @Test
    fun `events flow through to a new page`() = runTest(dispatcher) {
        val vm = viewModel()
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
        // Sparklines run their own queries after each page, so only count grid queries.
        val counting = trackingExecutor { if (!("player_id IN (" in it.sql)) queries++ }
        val vm = GridViewModel(StatsRepository(counting, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs), debounceMillis = 150)
        ready(vm)
        val before = queries

        "puka".forEach { vm.onEvent(GridEvent.NameChanged(it.toString())) }
        vm.onEvent(GridEvent.NameChanged("nacua"))
        val s = ready(vm)

        assertEquals("one grid query for five keystrokes", before + 1, queries)
        assertEquals("nacua", s.page!!.request.name)
    }

    @Test
    fun switchingProfilesRescoresTheFantasyPack() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        // A full season, not the current in-progress one: enough games that PPR
        // and Standard actually disagree on who tops the list.
        vm.onEvent(GridEvent.SeasonSelected(2025))
        vm.onEvent(GridEvent.PackSelected(StatPack.FANTASY))
        advanceUntilIdle()
        val ppr = (vm.state.value as GridUiState.Ready).page!!.rows.first().cells.first().text
        vm.onEvent(GridEvent.ProfileSelected(ScoringPresets.STANDARD.id))
        advanceUntilIdle()
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(ScoringPresets.STANDARD, ready.request.scoring)
        assertNotEquals(ppr, ready.page!!.rows.first().cells.first().text)
    }

    @Test
    fun longPressAddsToTheTrayQuietlyAndExplainsARepeat() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val first = (vm.state.value as GridUiState.Ready).page!!.rows.first()
        vm.onEvent(GridEvent.AddToCompare(first.playerId, first.name))
        advanceUntilIdle()
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(first.playerId, ready.tray.single().slot.playerId)
        assertEquals(first.name, ready.tray.single().name)
        // The haptic and the new chip confirm an add; a snackbar would cover the tray's Compare button.
        assertNull(ready.message)
        vm.onEvent(GridEvent.AddToCompare(first.playerId, first.name))
        advanceUntilIdle()
        assertEquals("${first.name} is already in compare", (vm.state.value as GridUiState.Ready).message)
    }

    @Test
    fun editingATraySlotFollowsAcceptedChangesAndClosesOnARejectedOne() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val (a, b) = (vm.state.value as GridUiState.Ready).page!!.rows.take(2)
        vm.onEvent(GridEvent.AddToCompare(a.playerId, a.name))
        vm.onEvent(GridEvent.AddToCompare(b.playerId, b.name))
        advanceUntilIdle()
        val (slotA, slotB) = (vm.state.value as GridUiState.Ready).tray.map { it.slot }

        vm.onEvent(GridEvent.EditTraySlot(slotA))
        advanceUntilIdle()
        assertEquals(slotA, (vm.state.value as GridUiState.Ready).editingSlot)

        val narrowed = slotA.copy(weeks = WeekRange(1, 4))
        vm.onEvent(GridEvent.ReplaceTraySlot(slotA, narrowed))
        advanceUntilIdle()
        assertEquals(narrowed, (vm.state.value as GridUiState.Ready).editingSlot)

        // Same player and range as slot B: rejected, so the sheet mustn't keep editing a slot that isn't in the tray.
        vm.onEvent(GridEvent.ReplaceTraySlot(narrowed, slotB))
        advanceUntilIdle()
        val rejected = (vm.state.value as GridUiState.Ready)
        assertEquals(listOf(narrowed, slotB), rejected.tray.map { it.slot })
        assertNull(rejected.editingSlot)
        assertEquals("That player and range is already in compare", rejected.message)

        vm.onEvent(GridEvent.EditTraySlot(slotB))
        vm.onEvent(GridEvent.TraySlotEditClosed)
        advanceUntilIdle()
        assertNull((vm.state.value as GridUiState.Ready).editingSlot)
    }

    @Test
    fun aFullTrayRefusesAFifthPlayer() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val rows = (vm.state.value as GridUiState.Ready).page!!.rows.take(5)
        rows.forEach { vm.onEvent(GridEvent.AddToCompare(it.playerId, it.name)); advanceUntilIdle() }
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(4, ready.tray.size)
        assertEquals("Compare holds 4 players. Remove one first.", ready.message)
    }

    @Test
    fun aCorruptPrefsResetIsAnnouncedOnce() = runTest(dispatcher) {
        val flagged = FakePrefsSource(UserPrefs.DEFAULT.copy(resetNotice = true))
        val vm = GridViewModel(repo, ScoringRepository(flagged), CompareTrayRepository(flagged))
        advanceUntilIdle()
        assertEquals("Saved scoring profiles couldn't be read, so they were reset.", (vm.state.value as GridUiState.Ready).message)
        assertFalse(flagged.current.resetNotice)
    }

    private val catalogQueries = setOf(CatalogQueries.seasons, CatalogQueries.metrics, CatalogQueries.teams)

    @Test
    fun `a new data version reloads the catalog and re-runs the page, keeping the user's choices`() = runTest(dispatcher) {
        val version = MutableStateFlow(0L)
        val log = mutableListOf<SqlQuery>()
        repo = StatsRepository(trackingExecutor { log += it }, Locale.US, dataVersion = version)
        val vm = viewModel()
        ready(vm)
        vm.onEvent(GridEvent.PackSelected(StatPack.RUSHING))
        val chosen = ready(vm).request
        val before = log.size

        version.value = 1
        val after = ready(vm)

        val since = log.drop(before)
        assertEquals(1, since.count { it == CatalogQueries.seasons })
        assertTrue("expected the page to re-run", since.any { it !in catalogQueries })
        assertEquals(chosen, after.request)
    }

    @Test
    fun `rebase keeps the season and lets default weeks follow new data`() {
        val old = SeasonInfo(2026, 2)
        val catalog = Catalog(persistentListOf(SeasonInfo(2025, 22), SeasonInfo(2026, 3)), persistentMapOf())
        val r = GridRequest(old, old.defaultWeeks, StatPack.OPPORTUNITY)

        assertEquals(WeekRange(1, 3), GridViewModel.rebase(r, catalog).weeks)
        val custom = r.copy(weeks = WeekRange(2, 2))
        assertEquals(WeekRange(2, 2), GridViewModel.rebase(custom, catalog).weeks)
        assertEquals(SeasonInfo(2026, 3), GridViewModel.rebase(custom, catalog).season)
    }

    @Test
    fun `rebase moves to the latest season when the old one was dropped`() {
        val gone = SeasonInfo(2023, 22)
        val catalog = Catalog(persistentListOf(SeasonInfo(2025, 22), SeasonInfo(2026, 3)), persistentMapOf())
        val rebased = GridViewModel.rebase(GridRequest(gone, gone.defaultWeeks, StatPack.OPPORTUNITY), catalog)

        assertEquals(SeasonInfo(2026, 3), rebased.season)
        assertEquals(WeekRange(1, 3), rebased.weeks)
    }

    @Test
    fun `injury badges reach the state and follow live updates`() = runTest(dispatcher) {
        val badges = MutableStateFlow<Map<String, String>>(emptyMap())
        val vm = GridViewModel(repo, ScoringRepository(prefs), CompareTrayRepository(prefs), badges = badges)
        val first = ready(vm).page!!.rows.first().playerId

        badges.value = mapOf(first to "Q")
        assertEquals(mapOf(first to "Q"), ready(vm).badges)

        badges.value = emptyMap()
        assertEquals(emptyMap<String, String>(), ready(vm).badges)
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
        assertEquals("Wk 1–2", weeksLabel(inProgress.season, inProgress.weeks))
        val single = start.copy(weeks = WeekRange(7, 7))
        assertEquals("Week 7", weeksLabel(single.season, single.weeks))
    }

    @Test
    fun `team, snap and advanced filters survive pack and season changes`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(50.0))
        val r = reduce(
            GridEvent.TeamsSelected(setOf("KC")),
            GridEvent.MinSnapShareSelected(0.5),
            GridEvent.FiltersApplied(listOf(f)),
            GridEvent.PackSelected(StatPack.RUSHING),
            GridEvent.SeasonSelected(2024),
        )
        assertEquals(setOf("KC"), r.teams)
        assertEquals(0.5, r.minSnapShare)
        assertEquals(listOf(f), r.filters)
    }

    @Test
    fun `draft edits and closing the sheet never touch the request`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(50.0))
        assertEquals(start, reduce(GridEvent.FilterDraftChanged(listOf(f)), GridEvent.FilterSheetClosed))
    }
}
