package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.live.LeagueMatchup
import dev.gridiron.core.data.live.MatchupPlayer
import dev.gridiron.core.data.live.MatchupSide
import dev.gridiron.core.data.live.MatchupsResult
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
class MatchupsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val star = MatchupPlayer("222", "Star WR", "WR", 21.3, playerId = "00-0001", appPoints = 19.8)
    private val bench = MatchupPlayer("333", "Bench Guy", "BE", 3.0, playerId = "00-0002", appPoints = 2.0)
    private val unknown = MatchupPlayer("444", "Nobody Known", "RB", 14.0)

    private val mine = MatchupSide(2, 112.4, listOf(star, bench), appTotal = 19.8)
    private val rival = MatchupSide(1, 98.2, listOf(unknown))
    private val result = MatchupsResult(listOf(LeagueMatchup(4, mine, rival), LeagueMatchup(4, MatchupSide(3, 60.0, emptyList()), null)), 0, null)
    private val names = mapOf(1 to "Rivals", 2 to "Mine", 3 to "Lonely")

    private fun show(
        result: MatchupsResult? = this.result,
        onWeek: (Int) -> Unit = {},
        onPlayer: (String) -> Unit = {},
    ) = compose.setContent {
        GridironTheme { MatchupsScreen(2026, 4, result, names, 2, "PPR", onWeek, {}, onPlayer, {}) }
    }

    @Test
    fun theListShowsEspnTotalsWithTheAppTotalInParentheses() {
        show()
        compose.onNodeWithText("112.4").assertExists()
        compose.onNodeWithText("(app 19.8)").assertExists()
        compose.onNodeWithText("Mine ★").assertExists()
        compose.onNodeWithText("Bye").assertExists()
    }

    @Test
    fun aMissingAppNumberShowsADashAndTheNote() {
        show()
        // The rival and the bye side have no app total.
        compose.onAllNodesWithText("(app –)").assertCountEquals(2)
        compose.onNodeWithText("App points are a dash", substring = true).assertExists()
    }

    @Test
    fun theStepperChangesTheWeek() {
        var week = 0
        show(onWeek = { week = it })
        compose.onNodeWithText("›").performClick()
        assertEquals(5, week)
        compose.onNodeWithText("‹").performClick()
        assertEquals(3, week)
    }

    @Test
    fun tappingAMatchupOpensBothLineupsWithAnAppColumn() {
        show()
        compose.onNodeWithTag("matchup-0").performClick()
        compose.onNodeWithTag("lineups").assertExists()
        compose.onNodeWithText("Star WR").assertExists()
        compose.onNodeWithText("Nobody Known").assertExists()
        compose.onAllNodesWithText("ESPN").assertCountEquals(2)
        compose.onNodeWithText("19.8").assertExists()
        compose.onNodeWithText("21.3").assertExists()
        compose.onNodeWithText("← Matchups").performClick()
        compose.onNodeWithTag("matchups-list").assertExists()
    }

    @Test
    fun aMatchedPlayerOpensThePlayerPageAndAnUnmatchedOneDoesNot() {
        var opened: String? = null
        show(onPlayer = { opened = it })
        compose.onNodeWithTag("matchup-0").performClick()
        compose.onNodeWithText("Nobody Known").performClick()
        assertEquals(null, opened)
        compose.onNodeWithText("Star WR").performClick()
        assertEquals("00-0001", opened)
    }

    @Test
    fun anErrorKeepsTheMatchupsAndSaysWhy() {
        show(result.copy(error = "couldn't reach ESPN"))
        compose.onNodeWithText("couldn't reach ESPN").assertExists()
        compose.onNodeWithText("112.4").assertExists()
    }
}
