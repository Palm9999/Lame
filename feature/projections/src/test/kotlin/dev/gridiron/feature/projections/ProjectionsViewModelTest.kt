package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CompletableDeferred
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

/**
 * A hand-rolled [ResultRow] over a fixed list of column values, mirroring the
 * shape [ProjectionsRepository.projections] reads: text/double columns only,
 * matching [dev.gridiron.core.projections.ProjectionQueries]'s own SELECT lists.
 */
private class FakeResultRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** The ViewModel over a real [ProjectionsRepository], on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a superseded load never overwrites state with the stale player's result`() = runTest(dispatcher) {
        // P1's queries are gated so they resolve only after P2's load has
        // already completed and been rendered -- reproducing the
        // out-of-order return that a naive last-write-wins ViewModel would
        // get wrong. Both of P1's queries (weekly, then factors) carry a
        // Bind.Text("P1") among their binds, so gating on that bind value
        // (not SQL text, which is identical between P1 and P2's calls) gates
        // both.
        val p1Gate = CompletableDeferred<Unit>()
        val gatedExecutor = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                if (query.binds.contains(Bind.Text("P1"))) p1Gate.await()
                val playerId = (query.binds.first() as Bind.Text).value
                val rows: List<ResultRow> = if (query.sql.contains("player_week_projection_factor")) {
                    emptyList()
                } else {
                    listOf(
                        FakeResultRow(listOf(playerId, "targets", "baseline", 6.0, 0.0)),
                        FakeResultRow(listOf(playerId, "targets", "final", 7.2, 4.0)),
                    )
                }
                return rows.map(map)
            }
        }
        val viewModel = ProjectionsViewModel(ProjectionsRepository(gatedExecutor), dispatcher)

        viewModel.load("P1", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        viewModel.load("P2", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        // P2's (ungated) load resolves first.
        advanceUntilIdle()
        val afterP2 = viewModel.state.value

        p1Gate.complete(Unit) // now let the stale P1 load finish
        advanceUntilIdle()

        val afterStaleP1Resolves = viewModel.state.value
        assertEquals("a stale, later-resolving request must not overwrite state", afterP2, afterStaleP1Resolves)
        val loaded = afterStaleP1Resolves as ProjectionsUiState.Loaded
        assertEquals("P2", loaded.playerId)
    }

    @Test
    fun `a component with no final-stage row still counts toward the final score, driving td dependence`() = runTest(dispatcher) {
        // `receiving_tds` only ever ships a baseline-stage row from today's
        // ETL (no final-stage override yet), same as most non-`targets`
        // metrics. It must still be merged forward into the final score --
        // and, being a TD-scoring component, it should drive TD dependence
        // toward 1.0 when it is the only thing contributing fantasy points.
        val executor = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                val rows: List<ResultRow> = if (query.sql.contains("player_week_projection_factor")) {
                    emptyList()
                } else {
                    listOf(
                        // `targets` isn't read directly by any scoring rule,
                        // so it contributes nothing to fantasy points -- it's
                        // here only to prove a *scored* baseline-only metric
                        // (receiving_tds, below) is the one driving the result.
                        FakeResultRow(listOf("P1", "targets", "baseline", 6.0, 0.0)),
                        FakeResultRow(listOf("P1", "targets", "final", 7.2, 4.0)),
                        FakeResultRow(listOf("P1", "receiving_tds", "baseline", 0.5, 0.1)),
                    )
                }
                return rows.map(map)
            }
        }
        val viewModel = ProjectionsViewModel(ProjectionsRepository(executor), dispatcher)

        viewModel.load("P1", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        advanceUntilIdle()

        val loaded = viewModel.state.value as ProjectionsUiState.Loaded
        // PPR REC_TD is worth 6.0 pts; 0.5 baseline receiving_tds carried
        // forward into the final map is 3.0 points, and it's the only
        // scoring component present, so it must equal both baseline and
        // final totals and fully explain TD dependence.
        assertEquals(3.0, loaded.baseline, 1e-9)
        assertEquals(3.0, loaded.final, 1e-9)
        assertEquals(1.0, loaded.tdDependence, 1e-9)
    }

    @Test
    fun `an empty result set surfaces Empty instead of spinning forever`() = runTest(dispatcher) {
        val executor = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
        }
        val viewModel = ProjectionsViewModel(ProjectionsRepository(executor), dispatcher)

        viewModel.load("P1", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        advanceUntilIdle()

        assertEquals(ProjectionsUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `a repository failure surfaces Failed instead of crashing`() = runTest(dispatcher) {
        val executor = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
                throw IllegalStateException("no such table: player_week_projection")
        }
        val viewModel = ProjectionsViewModel(ProjectionsRepository(executor), dispatcher)

        viewModel.load("P1", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(true, state is ProjectionsUiState.Failed)
        assertEquals("no such table: player_week_projection", (state as ProjectionsUiState.Failed).message)
    }
}
