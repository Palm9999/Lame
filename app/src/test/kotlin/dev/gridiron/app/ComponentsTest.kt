package dev.gridiron.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.ColumnChart
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.designsystem.ScreenBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared pieces every screen uses: the top bar, the empty state and the column chart's tap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTopBarShowsABackArrowOnlyWhenThereIsSomewhereToGo() {
        var backs = 0
        var canGoBack by mutableStateOf(true)
        compose.setContent { GridironTheme { ScreenBar("News", if (canGoBack) ({ backs++ }) else null) } }
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, backs)
        canGoBack = false
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.onNodeWithText("News").assertExists()
    }

    @Test
    fun anEmptyStateOffersTryAgainOnlyWithARetry() {
        var retries = 0
        var retry by mutableStateOf<(() -> Unit)?>({ retries++ })
        compose.setContent { GridironTheme { EmptyState("Couldn't reach ESPN.", onRetry = retry) } }
        compose.onNodeWithTag("retry").performClick()
        assertEquals(1, retries)
        retry = null
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithText("Couldn't reach ESPN.").assertExists()
    }

    @Test
    fun aChartHintsAtTappingAndKeepsItsBarWhenTheValuesChange() {
        var values by mutableStateOf(listOf<Double?>(10.0, 20.0))
        compose.setContent {
            GridironTheme { ColumnChart(listOf("W1", "W2"), values, { "%.0f".format(it) }, "points") }
        }
        compose.onNodeWithTag("bar:hint").assertExists()
        compose.onNodeWithTag("bar:1").performClick()
        compose.onNodeWithText("W2: 20").assertExists()
        compose.onNodeWithTag("bar:hint").assertDoesNotExist()
        // A profile switch rescores the same weeks: the selection stays on week 2.
        values = listOf(12.0, 25.0)
        compose.onNodeWithText("W2: 25").assertExists()
        // A week that loses its value drops the selection.
        values = listOf(12.0, null)
        compose.onNodeWithTag("bar:detail").assertDoesNotExist()
    }
}
