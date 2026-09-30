package dev.gridiron.feature.players

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.SeasonInfo
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.PresetFilter
import dev.gridiron.core.datastore.PresetFilterKind
import dev.gridiron.core.datastore.PresetWeeks
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
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
    fun aDatabaseThatWontOpenStillOffersARefresh() {
        var refreshed = 0
        compose.setContent {
            GridironTheme {
                GridScreen(
                    GridUiState.Failed("Couldn't open the stats database: file is not a database"),
                    onEvent = {},
                    recovery = listOf("Refresh stats" to { refreshed++ }),
                )
            }
        }
        compose.onNodeWithText("Couldn't open the stats database: file is not a database").assertIsDisplayed()
        compose.onNodeWithText("Refresh stats").performClick()
        assertEquals(1, refreshed)
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
    fun teamDefensesShowTheirOwnPackAndNoSnapChip() {
        val season = catalog.season(2025)
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.DEFENSE, positions = PositionFilter.DST)))
        compose.onNodeWithText("Defense").assertIsDisplayed()
        compose.onNodeWithText("Opportunity").assertDoesNotExist()
        compose.onNodeWithTag("chip:snaps").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/grid_dst.png")
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

    // --- presets ---

    private fun preset(id: String, name: String, packId: String = "RECEIVING") = GridPreset(
        id, name, packId, "RECEIVING_YARDS", "DESCENDING", "WR", true, emptySet(), null,
        listOf(PresetFilter("TARGETS", PresetFilterKind.AT_LEAST, 20.0)), PresetWeeks.LastN(4),
    )

    private fun presetState(sheet: PresetSheet? = PresetSheet.Listing, rows: List<PresetRow> = emptyList()): GridUiState.Ready {
        val season = catalog.season(2025)
        return ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR))
            .copy(presetsEnabled = true, presets = rows.toImmutableList(), presetSheet = sheet)
    }

    private fun row(id: String, name: String, unavailable: String? = null) =
        PresetRow(preset(id, name), presetSummary(preset(id, name)), unavailable)

    @Test
    fun theChipIsOnlyThereWhenPresetsAreOn() {
        val season = catalog.season(2025)
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING)))
        compose.onNodeWithTag("chip:presets").assertDoesNotExist()
    }

    @Test
    fun theChipOpensTheSheet() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = null), onEvent = { events += it })
        compose.onNodeWithTag("chip:presets").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetsOpened), events)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun anEmptySheetShowsTheHint() {
        show(presetState())
        compose.waitForIdle()
        compose.onNodeWithText("Save the view you have open to come back to it.").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/12_presets_empty.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun tappingARowAppliesItAndAnUnavailableRowDoesNot() {
        val events = mutableListOf<GridEvent>()
        show(
            presetState(rows = listOf(row("a", "Buy-low WRs"), row("b", "Deep threats"), row("c", "Old view", unavailable = "Its stat pack is gone."))),
            onEvent = { events += it },
        )
        compose.waitForIdle()
        compose.onAllNodesWithText("WR · Receiving · last 4 wks · 1 filter").onFirst().assertExists()
        compose.onNodeWithText("Its stat pack is gone.").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/roborazzi/13_presets_sheet.png")

        compose.onNodeWithTag("presets:row:a").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetApplied("a")), events)
        compose.onNodeWithTag("presets:row:c").performClick()
        assertEquals("an unavailable row must not apply", 1, events.size)
    }

    @Test
    fun longPressOffersRenameAndDelete() {
        val events = mutableListOf<GridEvent>()
        show(presetState(rows = listOf(row("a", "Buy-low WRs"))), onEvent = { events += it })
        compose.onNodeWithTag("presets:row:a").performTouchInput { longClick() }
        compose.onNodeWithTag("presets:menu:delete").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetDeleted("a")), events)

        compose.onNodeWithTag("presets:row:a").performTouchInput { longClick() }
        compose.onNodeWithTag("presets:menu:rename").performClick()
        assertEquals(GridEvent.PresetRenameRequested("a"), events.last())
    }

    @Test
    fun saveAsksForTheDialog() {
        val events = mutableListOf<GridEvent>()
        show(presetState(), onEvent = { events += it })
        compose.onNodeWithTag("presets:save").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetSaveRequested), events)
    }

    @Test
    fun theSaveDialogSendsTheNameAndTheDefaultRule() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = PresetSheet.Saving(PresetWeeks.LastN(4))), onEvent = { events += it })
        compose.onNodeWithTag("presets:name").performTextInput("  Mine ")
        compose.onNodeWithTag("presets:confirm").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetSaved("  Mine ", PresetWeeks.LastN(4))), events)
    }

    @Test
    fun theWeeksChoiceCanBeChanged() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = PresetSheet.Saving(PresetWeeks.LastN(4))), onEvent = { events += it })
        compose.onNodeWithTag("presets:n:plus").performClick()
        compose.onNodeWithTag("presets:name").performTextInput("A")
        compose.onNodeWithTag("presets:confirm").performClick()
        assertEquals(GridEvent.PresetSaved("A", PresetWeeks.LastN(5)), events.last())

        events.clear()
        compose.onNodeWithTag("presets:weeks:whole").performClick()
        compose.onNodeWithTag("presets:confirm").performClick()
        assertEquals(GridEvent.PresetSaved("A", PresetWeeks.WholeSeason), events.last())
    }

    @Test
    fun aBlankNameCannotBeSaved() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = PresetSheet.Saving(PresetWeeks.WholeSeason)), onEvent = { events += it })
        compose.onNodeWithTag("presets:confirm").performClick()
        assertTrue(events.isEmpty())
    }

    @Test
    fun replacingAUsedNameAsksFirst() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = PresetSheet.ConfirmReplace("Deep", "a", PresetWeeks.WholeSeason)), onEvent = { events += it })
        compose.onNodeWithText("Replace \"Deep\"?").assertIsDisplayed()
        compose.onNodeWithTag("presets:replace").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetReplaceConfirmed), events)
    }

    @Test
    fun renameSendsTheNewName() {
        val events = mutableListOf<GridEvent>()
        show(presetState(sheet = PresetSheet.Renaming("a", "Old"), rows = listOf(row("a", "Old"))), onEvent = { events += it })
        compose.onNodeWithTag("presets:name").performTextReplacement("Older")
        compose.onNodeWithTag("presets:rename:confirm").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetRenamed("a", "Older")), events)
    }

    @Test
    fun aDeletedPresetOffersUndo() {
        val events = mutableListOf<GridEvent>()
        show(presetState().copy(deletedPreset = preset("a", "Buy-low WRs")), onEvent = { events += it })
        compose.onNodeWithText("Deleted \"Buy-low WRs\"").assertIsDisplayed()
        compose.onNodeWithTag("presets:undo").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.PresetDeleteUndone), events)
    }

    @Test
    fun saveIsOffWithANoteAtTheLimit() {
        val rows = (1..30).map { row("g$it", "View $it") }
        val events = mutableListOf<GridEvent>()
        show(presetState(rows = rows), onEvent = { events += it })
        compose.onNodeWithText("You have 30 presets. Delete one to save another.").assertExists()
        compose.onNodeWithTag("presets:save").performClick()
        assertTrue(events.isEmpty())
    }

    @Test
    fun theFormShowsTheSheetsError() {
        show(presetState(sheet = PresetSheet.Renaming("a", "Old"), rows = listOf(row("a", "Old"))).copy(presetError = "Another preset is already called that."))
        compose.onNodeWithText("Another preset is already called that.").assertIsDisplayed()
    }

    @Test
    fun theStepperReachesPastTheWeeksPlayedSoFar() {
        val events = mutableListOf<GridEvent>()
        val early = ready(GridRequest(SeasonInfo(2025, 2), WeekRange(1, 2), StatPack.RECEIVING, positions = PositionFilter.WR))
            .copy(presetsEnabled = true, presetSheet = PresetSheet.Saving(PresetWeeks.LastN(4)))
        show(early, onEvent = { events += it })
        compose.onNodeWithText("Last 4 weeks").assertExists()
        compose.onNodeWithTag("presets:name").performTextInput("A")
        compose.onNodeWithTag("presets:confirm").performClick()
        assertEquals(GridEvent.PresetSaved("A", PresetWeeks.LastN(4)), events.last())
    }

    @Test
    fun oneWeekReadsInTheSingular() {
        show(presetState(sheet = PresetSheet.Saving(PresetWeeks.LastN(1))))
        compose.onNodeWithText("Last 1 week").assertExists()
    }
}
