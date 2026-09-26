package dev.gridiron.feature.players

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The real Grid screen rendered on the JVM with real data: the actual
 * repository and query builder over the real database. Screenshots land in
 * build/outputs/roborazzi when run with recordRoborazziDebug.
 *
 * Sized like a Galaxy S24 Ultra at its default display settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class GridScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var executor: JdbcQueryExecutor
    private lateinit var repo: StatsRepository
    private lateinit var catalog: Catalog

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        executor = JdbcQueryExecutor(StatsDb.path!!)
        repo = StatsRepository(executor, Locale.US)
        catalog = runBlocking { repo.catalog() }
    }

    @After
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private fun ready(request: GridRequest, heat: Boolean = true): GridUiState.Ready =
        GridUiState.Ready(catalog, request, heat, runBlocking { repo.grid(request, catalog) }, error = null)

    private fun show(state: GridUiState, dark: Boolean = false, onEvent: (GridEvent) -> Unit = {}) {
        compose.setContent { GridironTheme(darkTheme = dark) { GridScreen(state, onEvent) } }
    }

    @Test
    fun opportunityLeaders2025() {
        val season = catalog.season(2025)
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1_opportunity_2025.png")
    }

    @Test
    fun currentSeasonRushingPerGameDark() {
        val season = catalog.latest
        val request = GridRequest(season, season.defaultWeeks, StatPack.RUSHING, positions = PositionFilter.RB, perGame = true)
        show(ready(request), dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/2_rushing_${season.season}_dark.png")
    }

    @Test
    fun efficiencyWithSampleFloor() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR, sort = StatColumn.CATCH_RATE)
        show(ready(request))
        compose.onNodeWithText("min 54 targets", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/3_catch_rate_floor_2025.png")
    }

    @Test
    fun everyControlIsOnScreenWithoutScrolling() {
        val season = catalog.season(2025)
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)))
        for (label in listOf(
            "2025 ▾", "Wk 1–18 ▾", "PPR ▾", "Per game", "Heat", "All", "FLEX", "Opportunity", "Fantasy",
            "All teams", "Any snaps", "Filters", "Export",
        )) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun tappingAHeaderSortsByIt() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)), onEvent = { events += it })

        compose.onNodeWithTag("header:${StatColumn.TARGETS.name}").performClick()

        assertEquals(listOf<GridEvent>(GridEvent.SortBy(StatColumn.TARGETS)), events)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun holdingAHeaderExplainsTheStat() {
        val season = catalog.season(2025)
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY)))

        compose.onNodeWithTag("header:${StatColumn.WOPR.name}").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText("Weighted Opportunity Rating").assertExists()
        compose.onNodeWithText("1.5 * target_share + 0.7 * air_yards_share").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/4_wopr_definition.png")
    }

    @Test
    fun holdingARowAsksToAddThePlayer() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        val state = ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY))
        show(state, onEvent = { events += it })
        val first = state.page!!.rows.first()
        compose.onNodeWithContentDescription(first.name, substring = true).performTouchInput { longClick() }
        assertEquals(listOf<GridEvent>(GridEvent.AddToCompare(first.playerId, first.name)), events)
    }

    @Test
    fun fantasyPackWithTray2025() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = PositionFilter.FLEX)
        val page = runBlocking { repo.grid(request, catalog) }
        val tray = page.rows.take(2).map { TraySlotUi(CompareSlot(it.playerId, 2025, season.defaultWeeks), it.name, "2025 · Wk 1–18") }
        show(ready(request).copy(tray = tray.toImmutableList()))
        compose.onNodeWithTag("compareButton").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/5_fantasy_tray_2025.png")
    }

    @Test
    fun aTrayChipsRemoveButtonIsAFullSizeTouchTarget() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY)
        val row = runBlocking { repo.grid(request, catalog) }.rows.first()
        val slot = CompareSlot(row.playerId, 2025, season.defaultWeeks)
        val events = mutableListOf<GridEvent>()
        show(ready(request).copy(tray = listOf(TraySlotUi(slot, row.name, "2025 · Wk 1–18")).toImmutableList()), onEvent = { events += it })
        val remove = compose.onNodeWithContentDescription("Remove ${row.name}")
        remove.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        remove.performClick()
        assertEquals(listOf<GridEvent>(GridEvent.RemoveFromTray(slot)), events)
    }

    @Test
    fun fantasyPackWithTrayDark() {
        val season = catalog.latest
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY)
        val page = runBlocking { repo.grid(request, catalog) }
        val row = page.rows.first()
        val tray = listOf(
            TraySlotUi(CompareSlot(row.playerId, season.season, season.defaultWeeks), row.name, "${season.season} · Wk 1–${season.lastWeek}"),
        )
        show(ready(request).copy(tray = tray.toImmutableList()), dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/6_fantasy_tray_dark.png")
    }

    @Test
    fun filtersInTheChipsAndSummary() {
        val season = catalog.season(2025)
        val request = GridRequest(
            season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR,
            teams = setOf("KC"), minSnapShare = 0.5,
            filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(40.0))),
        )
        show(ready(request))
        compose.onNodeWithText("KC").assertIsDisplayed()
        compose.onNodeWithText("50%+ snaps").assertIsDisplayed()
        compose.onNodeWithText("Filters (1)").assertIsDisplayed()
        compose.onNodeWithText("TGT ≥ 40", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/7_filters_chips.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun teamSheetTogglesATeam() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING)), onEvent = { events += it })
        compose.onNodeWithTag("chip:teams").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/8_team_sheet.png")
        compose.onNodeWithTag("team:KC").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.TeamsSelected(setOf("KC"))), events)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun filterSheetMarksBadRowsAndAppliesOnlyCompleteOnes() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        val applied = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(40.0)))
        show(
            ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = applied))
                .copy(draftCount = DraftCount.Matches(57)),
            onEvent = { events += it },
        )
        compose.onNodeWithTag("chip:filters").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filter:add").performClick()
        compose.onNodeWithTag("filter:value:1").performTextInput("abc") // incomplete: shown in error, skipped
        compose.onNodeWithText("57 players match").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/9_filter_sheet.png")
        compose.onNodeWithTag("filter:apply").performClick()
        assertEquals(GridEvent.FiltersApplied(applied), events.last())
    }

    @Test
    fun errorStaysVisibleAlongsideFilters() {
        val season = catalog.season(2025)
        val request = GridRequest(
            season, season.defaultWeeks, StatPack.RECEIVING,
            filters = listOf(
                Filter(StatColumn.TARGETS, Condition.AtLeast(40.0)),
                Filter(StatColumn.CATCH_RATE, Condition.AtLeast(0.5)),
                Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(0.3)),
            ),
        )
        show(ready(request).copy(error = "boom"))
        // Existence alone isn't enough: Compose's semantics text holds the full,
        // untruncated string regardless of maxLines/overflow, so a substring
        // match would pass even if the error sat after the filters and got
        // clipped off the visible two lines. Check order instead.
        val summary = compose.onNodeWithTag("summary").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val errorIndex = summary.indexOf("Error: boom")
        val filterIndex = summary.indexOf("TGT ≥")
        assertTrue("expected \"Error: boom\" before \"TGT ≥\" in \"$summary\"", errorIndex in 0 until filterIndex)
    }

    @Test
    fun sparklinesDrawInRowsDark() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = PositionFilter.WR)
        val state = ready(request)
        val lines = runBlocking { repo.sparklines(state.page!!) }
        show(state.copy(sparklines = lines.toImmutableMap()), dark = true)
        // Grid rows use clearAndSetSemantics, which drops descendant semantics
        // (including the spark: testTag), and every row's content description
        // matches this substring, so we check the first match exists rather
        // than a single tagged node.
        compose.onAllNodesWithContentDescription("Last 6 weeks:", substring = true).onFirst().assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/10_sparklines_dark.png")
    }

    @Test
    fun anInjuredPlayerShowsTheirBadge() {
        val season = catalog.season(2025)
        val state = ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY))
        val first = state.page!!.rows.first()
        show(state.copy(badges = persistentMapOf(first.playerId to "Q")))

        // Rows expose one merged description; the badge is part of it.
        compose.onNodeWithContentDescription("${first.name} (injury status Q)", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/11_injury_badge.png")
    }
}
