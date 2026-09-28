package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ProjectionListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val loaded = ProjectionListState.Loaded(
        week = 4,
        builtAt = null,
        weekRows = listOf(
            ProjectionRow("w", "Wide Out", "WR", "KC", 16.2, 9.1, 25.4),
            ProjectionRow("q", "Quarter Back", "QB", "KC", 21.0, 14.0, 29.0),
        ),
        rosRows = listOf(ProjectionRow("w", "Wide Out", "WR", "KC", 180.0, 140.0, 220.0)),
    )

    @Test
    fun `shows FLEX rows for the week and switches to rest of season`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}) } }

        compose.onNodeWithText("Projections for week 4").assertIsDisplayed()
        compose.onNodeWithText("16.2").assertIsDisplayed()
        compose.onNodeWithText("9.1–25.4").assertIsDisplayed()
        compose.onNodeWithText("Rest of season").performClick()
        compose.onNodeWithText("180.0").assertIsDisplayed()
    }

    @Test
    fun `an Out player reads Out, and a row opens the player`() {
        var opened: String? = null
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, mapOf("w" to "O"), onPlayer = { opened = it }, onBack = {}) } }

        compose.onNodeWithText("Out").assertIsDisplayed()
        compose.onNodeWithText("Wide Out").performClick()
        assertEquals("w", opened)
    }

    @Test
    fun `a team defense's row reads D-ST`() {
        val defense = ProjectionListState.Loaded(4, null, listOf(ProjectionRow("DST_KC", "Kansas City D/ST", "DST", "KC", 7.0, 1.0, 14.0)), emptyList())
        compose.setContent { GridironTheme { ProjectionListScreen(defense, emptyMap(), onPlayer = {}, onBack = {}) } }

        compose.onNodeWithText("D/ST").performClick()
        compose.onNodeWithText("D/ST · KC").assertIsDisplayed()
    }

    @Test
    fun `an unavailable forecast says why`() {
        compose.setContent {
            GridironTheme { ProjectionListScreen(ProjectionListState.Unavailable("No upcoming games in 2026."), emptyMap(), onPlayer = {}, onBack = {}) }
        }
        compose.onNodeWithText("No upcoming games in 2026.").assertIsDisplayed()
    }
}
