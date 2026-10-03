package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.BreakoutRow
import dev.gridiron.core.data.live.LeagueRostered
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
class BreakoutsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(id: String, name: String, position: String, score: Double, note: String? = null) =
        BreakoutRow(id, name, position, "KC", score, 8.0, 5.0, null, null, 0.0, note)

    private val rows = listOf(
        row("w1", "Rising Receiver", "WR", 85.0, "Star Wideout"),
        row("r1", "Rising Back", "RB", 70.0),
        row("w2", "Other Receiver", "WR", 40.0),
    )
    private val league = LeagueRostered(setOf("w1"), 2026, 1L, mapOf("w1" to "Rivals"))

    private fun show(state: BreakoutsState, league: LeagueRostered? = null, onPlayer: (String) -> Unit = {}) =
        compose.setContent { GridironTheme { BreakoutsScreen(state, league, emptySet(), onPlayer, onBack = {}) } }

    @Test
    fun `rows show the reason and score, and a position chip narrows them`() {
        show(BreakoutsState.Loaded(2026, 5, rows))
        compose.onNodeWithText("Rising Receiver").assertIsDisplayed()
        compose.onNodeWithText("targets 5.0 → 8.0 a game · Star Wideout out").assertIsDisplayed()
        compose.onNodeWithText("85").assertIsDisplayed()

        compose.onNodeWithText("RB").performClick()
        compose.onNodeWithText("Rising Back").assertIsDisplayed()
        compose.onNodeWithTag("rise:w1").assertDoesNotExist()
    }

    @Test
    fun `free agents hides rostered players and is offered only with a league`() {
        show(BreakoutsState.Loaded(2026, 5, rows))
        compose.onNodeWithTag("chip:free").assertDoesNotExist()
    }

    @Test
    fun `with a league, the free agents chip hides who is on a team and tags the owner`() {
        show(BreakoutsState.Loaded(2026, 5, rows), league)
        compose.onNodeWithText("On Rivals").assertIsDisplayed()
        compose.onNodeWithTag("chip:free").performClick()
        compose.onNodeWithTag("rise:w1").assertDoesNotExist()
        compose.onNodeWithTag("rise:w2").assertIsDisplayed()
    }

    @Test
    fun `tapping a row opens the player, and a message shows when there are no rows`() {
        var opened: String? = null
        show(BreakoutsState.Loaded(2026, 5, rows)) { opened = it }
        compose.onNodeWithTag("rise:r1").performClick()
        assertEquals("r1", opened)
    }

    @Test
    fun `an unavailable state shows its message`() {
        show(BreakoutsState.Unavailable("Refresh stats to build Rising roles."))
        compose.onNodeWithText("Refresh stats to build Rising roles.").assertIsDisplayed()
    }
}
