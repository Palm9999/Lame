package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.gridiron.core.data.BoardPlayer
import dev.gridiron.core.data.DraftBoardResult
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.ScoringPresets
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

class DraftPicksTest {
    @Test
    fun `picks toggle between mine, taken and back, and survive a round trip`() {
        val picks = DraftPicks().toggleMine("a").toggleTaken("b").toggleTaken("a")
        assertEquals(DraftPicks(emptyList(), setOf("b", "a")), picks)
        assertEquals(picks, DraftPicks.decode(picks.encode()))
        assertEquals(DraftPicks(listOf("b"), setOf("a")), picks.toggleMine("b"))
    }

    @Test
    fun `a row reads ADP, position, team, bye and last season`() {
        assertEquals(
            "ADP 12.4 · D/ST · DAL · bye 6 · 8.2/g last year",
            draftLine(BoardPlayer("k", "Dallas Defense", "DST", "DAL", 12.4, 6, "DST_DAL", 8.2)),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class DraftScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val board = DraftBoardResult(
        listOf(
            BoardPlayer("1", "Wide One", "WR", "CIN", 1.2, 10),
            BoardPlayer("2", "Run One", "RB", "ATL", 2.5, 5),
            BoardPlayer("3", "Quarter One", "QB", "BUF", 20.0, 7),
        ),
        3, null,
    )

    @Test
    fun `tapping takes a player off the board, a long press makes him yours, and the picks are saved`() {
        val file = File(tmp.root, "draft.txt")
        compose.setContent {
            GridironTheme {
                DraftScreen(2026, { _, _, _ -> board }, flowOf(ScoringPresets.PPR), teams = 10, slots = null, file = file, onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("draft:a:1").performClick()
        compose.onNodeWithTag("draft:a:2").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("draft:m:2").assertExists()
        compose.onNodeWithText("Your team (1)").assertExists()
        assertEquals(DraftPicks(listOf("2"), setOf("1")), DraftPicks.decode(file.readText()))
    }
}
