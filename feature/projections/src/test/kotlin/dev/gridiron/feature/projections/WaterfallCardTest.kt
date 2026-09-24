package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.SimulationResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The waterfall card built directly from its inputs (no ViewModel, no
 * database). Sized like a Galaxy S24 Ultra at its default display settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class WaterfallCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the baseline, each factor, and the final total`() {
        compose.setContent {
            GridironTheme {
                WaterfallCard(
                    baseline = 12.8,
                    factors = listOf(
                        AttributedFactor("matchup", 1.4, "28th vs slot WRs by YPRR allowed"),
                        AttributedFactor("weather", -0.9, "18 mph wind, outdoor"),
                    ),
                    final = 13.3,
                    floorCeiling = SimulationResult(p10 = 6.1, p25 = 9.0, p50 = 13.3, p90 = 24.8),
                )
            }
        }

        compose.onNodeWithText("12.8", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+1.4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("matchup", substring = true).assertIsDisplayed()
        compose.onNodeWithText("-0.9", substring = true).assertIsDisplayed()
        compose.onNodeWithText("weather", substring = true).assertIsDisplayed()
        compose.onNodeWithText("13.3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Floor", substring = true).assertIsDisplayed()
        compose.onNodeWithText("6.1", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ceiling", substring = true).assertIsDisplayed()
        compose.onNodeWithText("24.8", substring = true).assertIsDisplayed()

        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projections_1_waterfall.png")
    }
}
