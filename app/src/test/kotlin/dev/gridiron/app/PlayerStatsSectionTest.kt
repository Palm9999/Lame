package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.GameLogRow
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.Place
import dev.gridiron.core.data.SeasonLineRow
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Player page's Season stats section, fed plain data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class PlayerStatsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun stats(ranked: Boolean = true, games: Int = 2) = PlayerStats(
        season = 2025,
        seasons = persistentListOf(2024, 2025),
        games = games,
        bar = "min 3 targets per game, 4+ games",
        ranked = ranked,
        line = persistentListOf(
            SeasonLineRow(StatColumn.TARGETS, "Targets", "120", "8.0", if (ranked) Place(12, 62) else null),
            SeasonLineRow(StatColumn.TARGET_SHARE, "Target share", "27.5%", "", if (ranked) Place(1, 62) else null),
        ),
        logHeaders = persistentListOf("FPTS", "TAR"),
        log = persistentListOf(
            GameLogRow(1, "vs BAL", "W 27–20", persistentListOf("18.4", "9"), persistentListOf(0.72, 0.18)),
            GameLogRow(2, "@ DAL", "L 17–24", persistentListOf("7.1", "5"), persistentListOf(0.91, null)),
        ),
        usageHeaders = persistentListOf("Snap Share", "Target Share"),
    )

    private fun show(page: PlayerPage, onSeason: (Int) -> Unit = {}) {
        compose.setContent {
            GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}, onSeason = onSeason) }
        }
    }

    private fun page(stats: PlayerStats? = null, unavailable: Boolean = false) = PlayerPage(
        header = PlayerHeader("P1", "Test Player", "WR", "KC"),
        status = null,
        notes = emptyList(),
        news = emptyList(),
        asOf = null,
        stats = stats,
        statsUnavailable = unavailable,
    )

    @Test
    fun theSectionShowsChipsTheSeasonLineAndTheGameLog() {
        show(page(stats()))
        compose.onNodeWithText("Season stats").assertExists()
        compose.onNodeWithTag("season:2024").assertExists()
        compose.onNodeWithTag("season:2025").assertExists()
        compose.onNodeWithText("2 games · min 3 targets per game, 4+ games").assertExists()
        compose.onNodeWithTag("seasonLine:Targets").assertExists()
        compose.onNodeWithText("120").assertExists()
        compose.onNodeWithText("27.5%").assertExists()
        compose.onNodeWithText("12th").assertExists()
        compose.onNodeWithText("1st").assertExists()
        compose.onNodeWithTag("gameLog:1").assertExists()
        // Snap share has two weeks, so it charts; target share has one, so it doesn't.
        compose.onNodeWithText("Snap Share by week").assertExists()
        compose.onNodeWithText("91%").assertExists()
        compose.onNodeWithTag("usage:Target Share").assertDoesNotExist()
        compose.onNodeWithText("vs BAL W 27–20").assertExists()
        compose.onNodeWithText("@ DAL L 17–24").assertExists()
        // The best week reads in the log and above its bar in the points chart.
        compose.onAllNodesWithText("18.4").assertCountEquals(2)
        compose.onNodeWithTag("pointsByWeek").assertExists()
    }

    @Test
    fun tappingABarReadsItsWeekAndTappingAgainClears() {
        show(page(stats()))
        val bar = compose.onNode(hasTestTag("bar:0") and hasAnyAncestor(hasTestTag("pointsByWeek")), useUnmergedTree = true)
        bar.performClick()
        compose.onNodeWithText("Week 1: 18.4 pts").assertExists()
        bar.performClick()
        compose.onNodeWithText("Week 1: 18.4 pts").assertDoesNotExist()
    }

    @Test
    fun aPlayerBelowTheBarSaysSo() {
        show(page(stats(ranked = false)))
        compose.onNodeWithText("Below the ranking bar").assertExists()
        compose.onNodeWithText("120").assertExists()
    }

    @Test
    fun aSeasonWithNoGamesShowsOnlyTheChips() {
        show(page(stats(games = 0).copy(line = persistentListOf(), log = persistentListOf())))
        compose.onNodeWithTag("season:2025").assertExists()
        compose.onNodeWithText("Targets").assertDoesNotExist()
        compose.onNodeWithTag("gameLog:1").assertDoesNotExist()
    }

    @Test
    fun aPlayerWithNoGamesAnywhereSaysSo() {
        show(page(PlayerStats.EMPTY))
        compose.onNodeWithText("No games in the built seasons.").assertExists()
        compose.onNodeWithTag("season:2025").assertDoesNotExist()
    }

    @Test
    fun aFailedLoadSaysSoAndTheRestOfThePageStays() {
        show(page(unavailable = true))
        compose.onNodeWithText("Season stats aren't available.").assertExists()
        compose.onNodeWithText("No injury designation.").assertExists()
    }

    @Test
    fun noStatsRepositoryMeansNoSection() {
        show(page())
        compose.onNodeWithText("Season stats").assertDoesNotExist()
    }

    @Test
    fun switchingSeasonsAsksForTheChosenOne() {
        val asked = mutableListOf<Int>()
        show(page(stats()), onSeason = { asked += it })
        compose.onNodeWithTag("season:2024").performClick()
        assertEquals(listOf(2024), asked)
    }
}
