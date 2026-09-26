package dev.gridiron.app

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
class LoadStatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleOffersToLoadAndToChooseSeasons() {
        var loads = 0
        var settings = 0
        compose.setContent { GridironTheme { LoadStatsScreen(RefreshState.Idle, onLoad = { loads++ }, onSettings = { settings++ }) } }

        compose.onNodeWithText("Load stats").performClick()
        compose.onNodeWithText("Choose seasons").performClick()

        assertEquals(1, loads)
        assertEquals(1, settings)
    }

    @Test
    fun runningShowsProgressInsteadOfTheButton() {
        compose.setContent {
            GridironTheme { LoadStatsScreen(RefreshState.Running("Downloading 2026 play-by-play 12/19 MB"), onLoad = {}, onSettings = {}) }
        }
        compose.onNodeWithText("Downloading 2026 play-by-play 12/19 MB").assertExists()
        compose.onNodeWithText("Load stats").assertDoesNotExist()
    }

    @Test
    fun aFailureSaysWhyAndOffersRetry() {
        var loads = 0
        compose.setContent {
            GridironTheme { LoadStatsScreen(RefreshState.Finished("No connection.", ok = false), onLoad = { loads++ }, onSettings = null) }
        }
        compose.onNodeWithText("No connection.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, loads)
    }
}
