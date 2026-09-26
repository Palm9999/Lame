package dev.gridiron.app

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.FakePrefsSource
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Drives the real navigation graph (Grid -> Compare -> back, Grid -> scoring
 * list -> editor) over the real database, so the wiring between screens is
 * proven end to end, not just each screen in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class NavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var executor: JdbcQueryExecutor
    private lateinit var deps: Deps

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        executor = JdbcQueryExecutor(StatsDb.path!!)
        val stats = StatsRepository(executor)
        val prefs = FakePrefsSource()
        deps = Deps(
            stats = stats,
            compare = CompareRepository(executor),
            scoring = ScoringRepository(prefs),
            tray = CompareTrayRepository(prefs),
            projections = ProjectionsRepository(executor),
            accuracy = AccuracyRepository(executor),
            teams = TeamsRepository(executor),
        )
    }

    @After
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private fun firstTwoPlayerNames(): List<String> {
        val stats = deps.stats
        val catalog = runBlocking { stats.catalog() }
        val season = catalog.latest
        val request = GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY, scoring = ScoringPresets.PPR)
        return runBlocking { stats.grid(request, catalog) }.rows.take(2).map { it.name }
    }

    /**
     * Robolectric's Looper stays paused: a real coroutine `delay()` (the
     * Grid's search debounce, a Snackbar's auto-dismiss timer) needs the
     * shadow looper explicitly fast-forwarded, or it never fires and
     * `waitForIdle()` alone spins forever waiting on it.
     */
    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        compose.waitForIdle()
    }

    @Test
    fun gridToCompareAndBackKeepsTheTray() {
        val (first, second) = firstTwoPlayerNames()
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithContentDescription(first, substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(second, substring = true).performTouchInput { longClick() }
        compose.waitForIdle()

        // A real touch, straight after the second long-press: nothing (such as
        // a snackbar) may sit over the tray's Compare button.
        compose.onNodeWithTag("compareButton").performClick()
        settle()

        compose.onNodeWithText("Compare").assertExists()
        compose.onNodeWithText(first).assertExists()
        compose.onNodeWithText(second).assertExists()

        Espresso.pressBack()
        settle()

        compose.onNodeWithTag("compareButton").assertExists()
        compose.onNodeWithText("Compare 2").assertExists()

        compose.onNodeWithContentDescription("Remove $first").performClick()
        settle()
        compose.onNodeWithText("Compare 1").assertExists()
    }

    @Test
    fun editProfilesFromTheChipOpensTheListThenTheEditor() {
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithTag("profileChip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Edit profiles…").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Scoring profiles").assertExists()

        compose.onNodeWithTag("duplicate:preset:ppr").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("field:name").assertExists()
        compose.onNodeWithText("PPR copy").assertExists()
    }

    @Test
    fun aFreshInstallShowsLoadStatsUntilTheFirstBuildLands() {
        val refresher = FakeRefresher(hasStats = false)
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Load stats").performClick()
        assertEquals(1, refresher.refreshes)

        refresher.hasStats.value = true
        settle()
        compose.onNodeWithTag("grid").assertExists()
    }

    @Test
    fun statsFromAnOlderAppPromptARefresh() {
        val refresher = FakeRefresher(hasStats = true, legacy = true)
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Refresh now").performClick()
        settle()

        assertEquals(1, refresher.refreshes)
        compose.onNodeWithText("Refresh now").assertDoesNotExist()
    }

    @Test
    fun aRunningRefreshShowsItsProgressUnderTheGrid() {
        val refresher = FakeRefresher()
        refresher.state.value = RefreshState.Running("Crunching 2026…")
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Crunching 2026…").assertExists()
        compose.onNodeWithTag("grid").assertExists()
    }
}
