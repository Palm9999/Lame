package dev.gridiron.feature.projections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.gridiron.core.data.TdRegressionBoard
import dev.gridiron.core.data.TdRegressionRow
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class TdRegressionOwnersTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rowsCarryOwnerTagsAndFreeAgentsNarrowsTheList() {
        val board = TdRegressionBoard(
            5,
            listOf(TdRegressionRow("a", "Alpha", "WR", "KC", 6.0, 2.0), TdRegressionRow("b", "Bravo", "RB", "BUF", 5.0, 2.5), TdRegressionRow("c", "Charlie", "TE", "DET", 4.0, 2.0)),
        )
        val league = LeagueRostered(setOf("a", "b"), 2026, 1L, mapOf("a" to "Rivals"))
        compose.setContent { GridironTheme { TdRegressionScreen(Result.success(board), {}, {}, league, mine = setOf("b")) } }

        compose.onNodeWithText("WR · KC · 6 TDs on 2.0 expected (+4.0) · On Rivals").assertExists()
        compose.onNodeWithText("RB · BUF · 5 TDs on 2.5 expected (+2.5) · Yours").assertExists()
        compose.onNodeWithTag("chip:free").performScrollTo().performClick()
        compose.onNodeWithText("Charlie").assertExists()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Bravo").assertDoesNotExist()
    }
}
