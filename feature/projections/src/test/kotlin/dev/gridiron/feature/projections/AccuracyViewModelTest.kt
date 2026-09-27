package dev.gridiron.feature.projections

import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the backtest's queries by what they read. */
private class AccuracyExecutor(
    private val meta: List<Pair<String, String>>,
    private val firstWeeks: List<List<Any?>> = emptyList(),
    private val projected: List<List<Any?>> = emptyList(),
    private val facts: List<List<Any?>> = emptyList(),
) : QueryExecutor {
    /** How many backtests read the played weeks: one per computation. */
    var statReads = 0

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        if ("FROM player_week_stat" in query.sql) statReads++
        val rows = when {
            "schema_meta" in query.sql -> meta.map { listOf(it.first, it.second) }
            "MIN(week)" in query.sql -> firstWeeks
            "FROM player_week_projection" in query.sql -> projected
            "FROM player_week_stat" in query.sql -> facts
            else -> emptyList()
        }
        return rows.map { map(FakeRow(it)) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccuracyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val ok = listOf("forecast_status" to "ok")

    // A WR projected for 6 catches in week 2, who caught 5 in week 1 and 8 in week 2.
    private val projected = listOf(
        listOf("w", "WR", 2, "receptions", 6.0, 0.0, null),
        listOf("w", "WR", 2, "receiving_yards", 60.0, 0.0, null),
    )
    private val facts = listOf(
        listOf("w", 2025, 1, "g", 1.0),
        listOf("w", 2025, 1, "receptions", 5.0),
        listOf("w", 2025, 2, "g", 1.0),
        listOf("w", 2025, 2, "receptions", 8.0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `measures the season under the active profile`() = runTest(dispatcher) {
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2025, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as AccuracyState.Loaded
        assertEquals(2025, loaded.season)
        assertEquals(listOf(2025), loaded.seasons)
        assertEquals("PPR", loaded.profile)
        val wr = loaded.positions.single()
        assertEquals(1, wr.playerWeeks)
        assertEquals(4.0, wr.model.mae, 1e-9) // 12 projected, 8 scored
    }

    @Test
    fun `a season with no finished week shows the latest one that has some`() = runTest(dispatcher) {
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2024, 2), listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2026, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as AccuracyState.Loaded
        assertEquals(2025, loaded.season)
        assertEquals(listOf(2024, 2025), loaded.seasons)
    }

    @Test
    fun `asking again for the season on screen keeps it without recomputing`() = runTest(dispatcher) {
        // Rotating the phone re-emits the active profile, and the route asks again.
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2025, ScoringPresets.PPR)
        advanceUntilIdle()
        vm.load(2025, ScoringPresets.PPR)
        assertEquals(2025, (vm.state.value as AccuracyState.Loaded).season)
        advanceUntilIdle()

        assertEquals(1, executor.statReads)
        // The Grid's season fell back to 2025; tapping the 2025 chip is the same season.
        val fellBack = AccuracyViewModel(AccuracyRepository(executor), dispatcher)
        fellBack.load(2026, ScoringPresets.PPR)
        advanceUntilIdle()
        fellBack.load(2025, ScoringPresets.PPR)
        advanceUntilIdle()
        assertEquals(2, executor.statReads)
    }

    @Test
    fun `a superseded load is cancelled, not left running`() = runTest(dispatcher) {
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2024, 2), listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2024, ScoringPresets.PPR)
        vm.load(2025, ScoringPresets.PPR)
        advanceUntilIdle()

        assertEquals(1, executor.statReads)
        assertEquals(2025, (vm.state.value as AccuracyState.Loaded).season)
    }

    @Test
    fun `says why there's nothing to measure`() = runTest(dispatcher) {
        suspend fun message(meta: List<Pair<String, String>>): String {
            val vm = AccuracyViewModel(AccuracyRepository(AccuracyExecutor(meta)), dispatcher)
            vm.load(2026, ScoringPresets.PPR)
            advanceUntilIdle()
            return (vm.state.value as AccuracyState.Unavailable).message
        }

        assertEquals("No projections yet. Refresh stats to build them.", message(emptyList()))
        assertEquals("Projections unavailable: no schedule.", message(listOf("forecast_status" to "no schedule")))
        assertEquals("No finished weeks have been projected yet.", message(ok))
    }

    @Test
    fun `numbers read cleanly`() {
        assertEquals("5.4", fixed(5.4049, 1))
        assertEquals("0.0", fixed(-0.04, 1))
        assertEquals("+0.4", signed(0.44))
        assertEquals("-1.2", signed(-1.24))
        assertEquals("0.0", signed(-0.01))
        assertEquals("0.31", r2Text(0.314))
        assertEquals("—", r2Text(null))
        assertEquals("79%", percent(0.794))
    }
}
