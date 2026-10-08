package dev.gridiron.feature.projections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.live.LiveWinChance
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(id: String, name: String) = ProjectionRow(id, name, "WR", "KC", 12.0, 6.0, 20.0)

    @Test
    fun theWatchListIsTheLineupsDesignatedPlayersStartersFirst() {
        val view = LineupView(
            "Aces", 6, 100.0,
            starters = listOf(LineupLine("WR", row("a", "Healthy")), LineupLine("FLEX", row("q", "Iffy"))),
            bench = listOf(row("o", "Hurt")),
            unlisted = emptyList(),
            defaultSlots = false,
        )
        val lines = hurtLines(view, mapOf("q" to "Q", "o" to "O", "a" to "A"))
        assertEquals(listOf("q" to "FLEX", "o" to "BE"), lines.map { it.playerId to it.slot })
    }

    @Test
    fun liveScoresLeadAndTheDaysLineShows() {
        var lineup = 0
        val home = HomeState(
            teamName = "Aces", week = 6, myTotal = 104.2, rivalName = "Rivals", rivalTotal = 98.0, chance = 0.6,
            live = LiveWinChance(61.2, 40.0, 101.5, 90.3, 0.72), line = listOf(0.6, 0.66, 0.72),
            hurt = listOf(HurtLine("q", "Iffy", "KC", "Q", "FLEX")),
        )
        compose.setContent {
            GridironTheme { HomeScreen(home, loading = false, noTeam = false, onPlayer = {}, onLineup = { lineup++ }, onMatchups = {}, onLeague = {}) }
        }
        compose.onNodeWithText("61.2").assertExists()
        compose.onNodeWithTag("home:chance").assertExists()
        compose.onNodeWithText("72% to win").assertExists()
        compose.onNodeWithTag("home:line").assertExists()
        compose.onNodeWithTag("home:hurt:q").assertExists()
        compose.onNodeWithTag("home:lineup").performClick()
        assertEquals(1, lineup)
    }

    @Test
    fun withoutATeamItPointsToTheLeagueScreen() {
        var league = 0
        compose.setContent {
            GridironTheme { HomeScreen(null, loading = false, noTeam = true, onPlayer = {}, onLineup = {}, onMatchups = {}, onLeague = { league++ }) }
        }
        compose.onNodeWithTag("retry").performClick()
        assertEquals(1, league)
    }
}
