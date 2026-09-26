package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ThisWeekCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val card = ProjectionCard(2026, 4, "@ BUF", "KC +2.5 · O/U 47.5", 11.0, 5.2, 18.9, 88.0, 88.0 / 3, out = false)

    @Test
    fun `shows the week's points, range and rest of season, and opens the waterfall`() {
        var opened = false
        compose.setContent { GridironTheme { ThisWeekCard(card, onOpen = { opened = true }) } }

        compose.onNodeWithText("Week 4 · @ BUF · KC +2.5 · O/U 47.5").assertIsDisplayed()
        compose.onNodeWithText("11.0 pts").assertIsDisplayed()
        compose.onNodeWithText("Floor 5.2 · Ceiling 18.9").assertIsDisplayed()
        compose.onNodeWithText("Rest of season 88.0 pts (29.3 per game)").assertIsDisplayed()
        compose.onNodeWithText("See why →").performClick()
        assertTrue(opened)
    }

    @Test
    fun `an Out player reads Out`() {
        compose.setContent { GridironTheme { ThisWeekCard(card.copy(out = true, points = 0.0), onOpen = {}) } }
        compose.onNodeWithText("Out this week").assertIsDisplayed()
    }
}
