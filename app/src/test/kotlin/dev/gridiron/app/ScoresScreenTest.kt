package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.GameDetail
import dev.gridiron.core.data.GamePlayer
import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.ScoreGame
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ScoresScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val final = ScoreGame("KC", "BAL", 37, 20, GameState.FINAL, null, null, -2.5, 48.5)
    private val live = ScoreGame("ATL", "WAS", 17, 13, GameState.LIVE, Instant.parse("2025-09-28T17:00:00Z"), "4:14 - 3rd", null, null)
    private val later = ScoreGame("SF", "DEN", null, null, GameState.SCHEDULED, Instant.parse("2025-09-28T20:25:00Z"), "x", 3.0, 44.0)

    @Test
    fun `week labels name the playoff rounds`() {
        assertEquals(listOf("Week 1", "Week 18", "Wild Card", "Divisional", "Conference", "Super Bowl"), listOf(1, 18, 19, 20, 21, 22).map(::weekLabel))
    }

    @Test
    fun `a game's status is its clock while live, final once over, else the kickoff in the phone's zone`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("4:14 - 3rd", statusText(live, zone))
        assertEquals("Final", statusText(final, zone))
        assertEquals("Sun 4:25 PM", statusText(later, zone))
        assertEquals("Scheduled", statusText(later.copy(kickoff = null), zone))
    }

    @Test
    fun `the week shows each game with score, status, spread and the byes, and a tap opens the game`() {
        var opened: Triple<Int, String, String>? = null
        compose.setContent {
            GridironTheme {
                ScoresScreen(
                    2025, listOf(3, 4, 5), 4, ScoresWeek(2025, 4, listOf(final, live, later), listOf("BUF", "NYJ"), null),
                    failed = false, onWeek = {}, onRefresh = {}, onGame = { w, h, a -> opened = Triple(w, h, a) }, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Week 4").assertExists()
        compose.onNodeWithText("Final").assertExists()
        compose.onNodeWithText("BAL -2.5 · O/U 48.5").assertExists()
        compose.onNodeWithText("4:14 - 3rd").assertExists()
        compose.onNodeWithText("Bye: BUF, NYJ").assertExists()
        compose.onNodeWithText("37").assertExists()
        compose.onNodeWithText("BAL").performClick()
        assertEquals(Triple(4, "KC", "BAL"), opened)
    }

    @Test
    fun `an ESPN failure is shown above the table's games`() {
        compose.setContent {
            GridironTheme {
                ScoresScreen(
                    2025, listOf(4), 4, ScoresWeek(2025, 4, listOf(later), emptyList(), "Couldn't reach ESPN: timeout"),
                    failed = false, onWeek = {}, onRefresh = {}, onGame = { _, _, _ -> }, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Not updated: Couldn't reach ESPN: timeout.").assertExists()
    }

    @Test
    fun `a game lists each team's players with fantasy points, and a tap opens the player`() {
        var opened: String? = null
        compose.setContent {
            GridironTheme {
                GameScreen(
                    2025, 4, "KC", "BAL", final,
                    GameDetail(
                        home = listOf(GamePlayer("p1", "Patrick Mahomes", "QB", 24.46)),
                        away = listOf(GamePlayer("p2", "Derrick Henry", "RB", null)),
                    ),
                    "PPR", failed = false, onPlayer = { opened = it }, onBack = {},
                )
            }
        }
        compose.onNodeWithText("BAL @ KC · Week 4 2025").assertExists()
        compose.onNodeWithText("BAL 20 – 37 KC · Final · BAL -2.5").assertExists()
        compose.onNodeWithText("24.5").assertExists()
        compose.onNodeWithText("–").assertExists()
        compose.onNodeWithText("Patrick Mahomes").performClick()
        assertEquals("p1", opened)
    }
}
