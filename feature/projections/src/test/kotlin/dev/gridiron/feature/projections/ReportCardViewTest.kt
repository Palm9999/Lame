package dev.gridiron.feature.projections

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.gridiron.core.data.live.Grade
import dev.gridiron.core.data.live.LineupReviewResult
import dev.gridiron.core.data.live.ReportCard
import dev.gridiron.core.data.live.WeekReview
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
class ReportCardViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val all = Grade.entries
    private val cards = listOf(
        ReportCard(2, "Mine", 0.94, 0.64, 1.25, 812.0, 233.4, all.associateWith { 1 }, 1.0, 1),
        ReportCard(1, "Rivals", 0.81, 0.36, -1.25, 640.0, 100.0, all.associateWith { 2 }, 2.0, 2),
    )

    private fun show(result: LineupReviewResult) {
        compose.setContent { GridironTheme { ReviewView(ReviewState.Loaded(result)) } }
    }

    private val weeks = listOf(WeekReview(1, 100.0, 100.0, emptyList(), emptyList()))

    @Test
    fun `the report card places every manager and marks mine`() {
        show(LineupReviewResult(weeks, null, reportCards = cards, myTeamId = 2))
        compose.onNodeWithTag("review").performScrollToNode(hasTestTag("card:1"))
        compose.onNodeWithText("1st of 2 · Mine (you)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Lineups 1st · Strength 1st · Luck 1st · Draft 1st · Moves 1st", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2nd of 2 · Rivals", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `tapping a card shows the numbers behind it`() {
        show(LineupReviewResult(weeks, null, reportCards = cards, myTeamId = 2))
        compose.onNodeWithTag("review").performScrollToNode(hasTestTag("card:2"))
        compose.onNodeWithTag("card:2").performClick()
        compose.onNodeWithText("94% of the best lineups · all-play .640 · luck +1.3 wins · draft 812.0 · moves 233.4", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no draft says so`() {
        val noDraft = cards.map { it.copy(draftPoints = null, movesPoints = null, places = it.places - Grade.DRAFT - Grade.MOVES) }
        show(LineupReviewResult(weeks, null, reportCards = noDraft, myTeamId = 2, draftMessage = "no draft found"))
        compose.onNodeWithTag("review").performScrollToNode(hasTestTag("card:2"))
        compose.onNodeWithText("Draft and Moves: no draft found.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Lineups 1st · Strength 1st · Luck 1st", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `share opens a preview of the report card`() {
        show(LineupReviewResult(weeks, null, reportCards = cards, myTeamId = 2))
        compose.onNodeWithTag("review").performScrollToNode(hasTestTag("share:report"))
        compose.onNodeWithTag("share:report").performClick()
        compose.onNodeWithTag("share:send").assertExists()
        compose.onNodeWithText("1st · Mine (you)", useUnmergedTree = true).assertExists()
    }
}
