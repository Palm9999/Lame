package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
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

private class ListRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the list's queries by table: `schema_meta`, then the week's and rest of season's rows. */
private class ListExecutor(
    private val meta: List<Pair<String, String>>,
    private val week: List<List<Any?>> = emptyList(),
    private val ros: List<List<Any?>> = emptyList(),
    private val rosWeeks: List<List<Any?>> = emptyList(),
) : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val rows = when {
            "schema_meta" in query.sql -> meta.map { listOf(it.first, it.second) }
            "FROM player_week_projection" in query.sql -> week
            "FROM player_ros_projection" in query.sql -> ros
            "FROM player_ros_week" in query.sql -> rosWeeks
            else -> emptyList()
        }
        return rows.map { map(ListRow(it)) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionListViewModelTest {
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
    fun `lists the upcoming week and rest of season`() = runTest(dispatcher) {
        val executor = ListExecutor(
            meta = listOf("forecast_status" to "ok", "forecast_week:2026" to "4"),
            week = listOf(listOf("w", "Wide Out", "WR", "KC", "receptions", 5.0, 5.0, "binomial")),
            ros = listOf(listOf("w", "Wide Out", "WR", "KC", "receptions", 60.0, 50.0, "binomial")),
        )
        val vm = ProjectionListViewModel(ProjectionsRepository(executor), dispatcher)

        vm.load(2026, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as ProjectionListState.Loaded
        assertEquals(4, loaded.week)
        assertEquals(5.0, loaded.weekRows.single().points, 1e-9)
        assertEquals(60.0, loaded.rosRows.single().points, 1e-9)
    }

    @Test
    fun `weekly rest of season follows the list, scored with the profile`() = runTest(dispatcher) {
        val executor = ListExecutor(
            meta = listOf("forecast_status" to "ok", "forecast_week:2026" to "4"),
            ros = listOf(listOf("w", "Wide Out", "WR", "KC", "receptions", 60.0, 50.0, "binomial")),
            rosWeeks = listOf(listOf("w", "WR", 5L, "receptions", 6.0, 1.0), listOf("w", "WR", 7L, "receptions", 4.0, 1.0)),
        )
        val vm = ProjectionListViewModel(ProjectionsRepository(executor), dispatcher)
        vm.load(2026, ScoringPresets.HALF_PPR)
        advanceUntilIdle()
        assertEquals(mapOf("w" to mapOf(5 to 3.0, 7 to 2.0)), (vm.state.value as ProjectionListState.Loaded).rosWeekly)
    }

    @Test
    fun `says why there's nothing to list`() = runTest(dispatcher) {
        suspend fun message(meta: List<Pair<String, String>>, season: Int = 2026): String {
            val vm = ProjectionListViewModel(ProjectionsRepository(ListExecutor(meta)), dispatcher)
            vm.load(season, ScoringPresets.PPR)
            advanceUntilIdle()
            return (vm.state.value as ProjectionListState.Unavailable).message
        }

        assertEquals("No projections yet. Refresh stats to build them.", message(emptyList()))
        assertEquals("Projections unavailable: no schedule.", message(listOf("forecast_status" to "no schedule")))
        assertEquals("No upcoming games in 2026.", message(listOf("forecast_status" to "ok")))
    }
}
