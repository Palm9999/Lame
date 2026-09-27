package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screens that read stats.db load again when a refresh swaps in a new one, without being reopened. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReloadOnSwapTest {
    @get:Rule
    val compose = createComposeRule()

    private var reads = 0
    private val executor = object : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
            reads++
            return emptyList()
        }
    }
    private val version = MutableStateFlow(0L)

    private fun readsAfterASwap(): Pair<Int, Int> {
        compose.waitForIdle()
        val before = reads
        version.value = 1L
        compose.waitForIdle()
        return before to reads
    }

    @Test
    fun thePlayerPageReloadsWhenNewStatsArrive() {
        compose.setContent {
            GridironTheme { PlayerRoute("P1", PlayerDirectory(executor), live = null, onBack = {}, dataVersion = version) }
        }
        val (before, after) = readsAfterASwap()
        assertTrue("read $before times, then $after", before > 0 && after > before)
    }

    @Test
    fun teamDefenseReloadsWhenNewStatsArrive() {
        compose.setContent { GridironTheme { DefenseScreen(2026, TeamsRepository(executor), onBack = {}, dataVersion = version) } }
        val (before, after) = readsAfterASwap()
        assertTrue("read $before times, then $after", before > 0 && after > before)
    }

    @Test
    fun theOfficialInjuryReportReloadsWhenNewStatsArrive() {
        compose.setContent {
            GridironTheme {
                InjuriesRoute(2024, 2026, TeamsRepository(executor), live = null, onBack = {}, onPlayer = {}, dataVersion = version)
            }
        }
        val (before, after) = readsAfterASwap()
        assertTrue("read $before times, then $after", before > 0 && after > before)
    }
}
