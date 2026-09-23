package dev.gridiron.feature.compare

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareRequest
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The real Compare screen rendered on the JVM with real data: the actual
 * repository and query builder over the real database.
 *
 * Sized like a Galaxy S24 Ultra at its default display settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class CompareScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var compare: CompareRepository
    private lateinit var catalog: Catalog

    @Before
    fun setUp() {
        assumeTrue("GRIDIRON_STATS_DB not set", StatsDb.path != null)
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        compare = CompareRepository(executor, Locale.US)
        catalog = runBlocking { stats.catalog() }
    }

    @After
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private fun topIds(pack: StatPack, filter: PositionFilter, n: Int, season: Int = 2025): List<String> {
        val s = catalog.season(season)
        return runBlocking { stats.grid(GridRequest(s, s.defaultWeeks, pack, filter), catalog) }.rows.take(n).map { it.playerId }
    }

    private val season2025 get() = catalog.season(2025).defaultWeeks

    private fun ready(
        vararg slots: CompareSlot,
        perGame: Boolean = false,
        tab: CompareTab = CompareTab.BARS,
        onlyDifferences: Boolean = false,
        radarPair: Pair<Int, Int> = 0 to 1,
        selectedPoint: String? = null,
    ): CompareUiState.Ready {
        val request = CompareRequest(slots.toList(), ScoringPresets.PPR, perGame)
        val page = runBlocking { compare.compare(request, catalog) }
        return CompareUiState.Ready(
            page = page,
            catalog = catalog,
            profiles = ScoringPresets.all.toImmutableList(),
            tab = tab,
            onlyDifferences = onlyDifferences,
            radarPair = radarPair,
            selectedPoint = selectedPoint,
        )
    }

    private fun show(state: CompareUiState, dark: Boolean = false, onEvent: (CompareEvent) -> Unit = {}) {
        compose.setContent { GridironTheme(darkTheme = dark) { CompareScreen(state, onEvent) } }
    }

    @Test
    fun barsTwoReceivers() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_1_bars.png")
    }

    @Test
    fun barsDark() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_2_bars_dark.png")
    }

    @Test
    fun tableFourMixedOnlyDifferences() {
        val qb = topIds(StatPack.PASSING, PositionFilter.QB, 1).single()
        val rb = topIds(StatPack.RUSHING, PositionFilter.RB, 1).single()
        val (wr1, wr2) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(
            ready(
                CompareSlot(qb, 2025, season2025),
                CompareSlot(rb, 2025, season2025),
                CompareSlot(wr1, 2025, season2025),
                CompareSlot(wr2, 2025, season2025),
                tab = CompareTab.TABLE,
                onlyDifferences = true,
            ),
        )
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_3_table.png")
    }

    @Test
    fun radarChoosesTwoOfThree() {
        val (a, b, c) = topIds(StatPack.RECEIVING, PositionFilter.WR, 3)
        show(
            ready(
                CompareSlot(a, 2025, season2025),
                CompareSlot(b, 2025, season2025),
                CompareSlot(c, 2025, season2025),
                tab = CompareTab.RADAR,
            ),
        )
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_4_radar.png")
    }

    @Test
    fun scatterWithSelection() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val state = ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025), tab = CompareTab.SCATTER)
        val other = state.page.scatter!!.population.first { p -> p.playerId != a && p.playerId != b }
        show(state.copy(selectedPoint = other.playerId))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_5_scatter.png")
    }

    @Test
    fun selfComparison() {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val state = ready(CompareSlot(id, 2025, season2025), CompareSlot(id, 2025, WeekRange(1, 8)))
        show(state)
        assertEquals(2, state.page.slots.size)
        assertEquals(2, compose.onAllNodesWithTag("compareSlotHeader").fetchSemanticsNodes().size)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_6_self.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w892dp-h412dp-land-xxhdpi")
    fun landscapeBarsAndTable() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_7_landscape.png")
    }

    @Test
    fun noSeasonSlot() {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        show(ready(CompareSlot(id, 2025, season2025), CompareSlot(id, 2003, WeekRange(1, 17))))
        compose.onNodeWithText("No 2003 data").assertExists()
        // The slot with no data is excluded from the bars: one bar per row, no "—, not ranked" one beside it.
        val rows = compose.onAllNodes(hasContentDescription("percentile", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ContentDescription].single() }
        assertTrue(rows.isNotEmpty())
        assertFalse(rows.joinToString("\n"), rows.any { it.contains("—, not ranked") })
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compare_8_missing_season.png")
    }

    @Test
    fun noGamesSlotSaysSoAndIsLeftOffTheRadar() {
        // A 2025 receiver with no 2024 rows at all, compared in 2024.
        val rookie = runBlocking {
            executor.query(
                SqlQuery(
                    "SELECT s.player_id FROM player_week_stat s JOIN player p USING (player_id) " +
                        "WHERE s.metric_id = ? AND s.season = ? AND p.position = ? AND s.player_id NOT IN " +
                        "(SELECT player_id FROM player_week_stat WHERE season = ?) " +
                        "GROUP BY s.player_id ORDER BY SUM(s.value) DESC LIMIT 1",
                    listOf(Bind.Text("targets"), Bind.Integer(2025), Bind.Text("WR"), Bind.Integer(2024)),
                ),
            ) { it.text(0) }.single()
        }
        val star = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val state = ready(
            CompareSlot(star, 2025, season2025),
            CompareSlot(rookie, 2024, catalog.season(2024).defaultWeeks),
            tab = CompareTab.RADAR,
        )
        show(state)
        compose.onNodeWithText("No games in this range").assertExists()
        // Header only for the rookie; header plus radar legend for the star.
        assertEquals(1, compose.onAllNodesWithText(state.page.slots[1].name).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText(state.page.slots[0].name).fetchSemanticsNodes().size)
    }

    @Test
    fun holdingAStatLabelShowsItsDefinition() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))

        compose.onNodeWithText("Targets").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText(catalog.metrics.getValue("targets").definition).assertExists()
    }

    @Test
    fun noRefreshingIndicatorOnAFreshLoad() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))
        compose.onNodeWithTag("compareRefreshing").assertDoesNotExist()
    }

    @Test
    fun refreshingIndicatorShowsWhileARequeryIsInFlight() {
        // The view model marks Ready.refreshing while a per-game toggle, profile
        // switch, or tray change re-runs compare.compare(...) with the old page
        // still on screen (CompareViewModel.recompute()); asserted here directly
        // against the state -> UI mapping, since the real ~1 s DB query doesn't
        // pause at a point a virtual-time test dispatcher could catch.
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val refreshing = ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)).copy(refreshing = true)
        show(refreshing)
        compose.onNodeWithTag("compareRefreshing").assertExists()
        // The old page's data is still the one on screen, not a blank/loading page.
        compose.onNodeWithText(refreshing.page.slots[0].name).assertExists()
    }
}
