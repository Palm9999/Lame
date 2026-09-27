package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy
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
class AccuracyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val loaded = AccuracyState.Loaded(
        season = 2025,
        seasons = listOf(2024, 2025),
        profile = "PPR",
        positions = listOf(
            PositionAccuracy(
                "WR", 1203,
                model = ErrorStats(5.40, -1.03, 0.31),
                seasonAverage = ErrorStats(5.79, 0.28, 0.22),
                lastFour = ErrorStats(6.02, 0.43, 0.18),
                calibration = 0.79,
            ),
        ),
    )

    @Test
    fun `shows each predictor's error and how often floor to ceiling held`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = {}, onBack = {}) } }

        compose.onNodeWithText("Scored with PPR").assertIsDisplayed()
        compose.onNodeWithText("WR · 1203 player-weeks").assertIsDisplayed()
        compose.onNodeWithText("Floor to ceiling held 79% of scores (target about 80%)").assertIsDisplayed()
        compose.onNodeWithText("5.4").assertIsDisplayed()
        compose.onNodeWithText("-1.0").assertIsDisplayed()
        compose.onNodeWithText("0.31").assertIsDisplayed()
        compose.onNodeWithText("5.8").assertIsDisplayed()
        compose.onNodeWithText("+0.3").assertIsDisplayed()
        compose.onNodeWithText("6.0").assertIsDisplayed()
    }

    @Test
    fun `a season chip asks for that season`() {
        var asked: Int? = null
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = { asked = it }, onBack = {}) } }

        compose.onNodeWithText("2024").performClick()

        assertEquals(2024, asked)
    }

    @Test
    fun `a season with nothing to measure says so and still offers the others`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded.copy(positions = emptyList()), onSeason = {}, onBack = {}) } }

        compose.onNodeWithText("No player-weeks to measure in 2025.").assertIsDisplayed()
        compose.onNodeWithText("2024").assertIsDisplayed()
    }

    @Test
    fun `a position with nothing to count says so instead of vanishing`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = {}, onBack = {}) } }

        compose.onNodeWithText("Nothing to measure at QB, RB, TE, K, D/ST.").assertExists()
    }

    @Test
    fun `the oldest built season warns that it starts without last season`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded.copy(season = 2024), onSeason = {}, onBack = {}) } }
        compose.onNodeWithText("2024 is the oldest season built", substring = true).assertExists()
    }

    @Test
    fun `a later season has no such warning`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = {}, onBack = {}) } }
        compose.onNodeWithText("is the oldest season built", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an unavailable forecast says why`() {
        compose.setContent {
            GridironTheme { AccuracyScreen(AccuracyState.Unavailable("No finished weeks have been projected yet."), onSeason = {}, onBack = {}) }
        }

        compose.onNodeWithText("No finished weeks have been projected yet.").assertIsDisplayed()
    }
}
