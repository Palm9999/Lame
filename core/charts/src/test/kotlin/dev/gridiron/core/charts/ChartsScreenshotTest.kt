package dev.gridiron.core.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.designsystem.SlotColors
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Synthetic-data screenshots of the three charts, plus one interaction test
 * for scatter point selection. Sized like a Galaxy S24 Ultra at its default
 * display settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ChartsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun BarsContent() {
        val c0 = SlotColors.color(0)
        val c1 = SlotColors.color(1)
        Column {
            PercentileBarRow("Targets", persistentListOf(Bar(0.92f, "92", c0), Bar(0.40f, "40", c1)))
            PercentileBarRow("Yards", persistentListOf(Bar(0.05f, "5", c0), Bar(0.61f, "61", c1)))
            PercentileBarRow("Touchdowns", persistentListOf(Bar(null, "—", c0), Bar(0.77f, "77", c1)))
            PercentileBarRow("Catch Rate", persistentListOf(Bar(1.0f, "100", c0), Bar(0.0f, "0", c1)))
        }
    }

    @Test
    fun barsLight() {
        compose.setContent { GridironTheme(darkTheme = false) { BarsContent() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_1_bars.png")
    }

    @Test
    fun barsDark() {
        compose.setContent { GridironTheme(darkTheme = true) { BarsContent() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_2_bars_dark.png")
    }

    @Test
    fun radarTwoPlayers() {
        val axes = persistentListOf("Targets", "Catches", "Yards", "TDs", "YAC", "Air Yards", "Tgt Share")
        compose.setContent {
            GridironTheme {
                val c0 = SlotColors.color(0)
                val c1 = SlotColors.color(1)
                RadarChart(
                    axes = axes,
                    series = persistentListOf(
                        RadarSeries("Ja'Marr Chase", persistentListOf(0.94f, 0.88f, 0.91f, 0.72f, 0.65f, 0.83f, 0.9f), c0),
                        RadarSeries("Jerry Jeudy", persistentListOf(0.4f, 0.52f, 0.44f, 0.3f, null, 0.55f, 0.41f), c1),
                    ),
                    contentDescription = "Radar comparing Ja'Marr Chase and Jerry Jeudy",
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_3_radar.png")
    }

    @Test
    fun scatterWithHighlights() {
        compose.setContent {
            GridironTheme {
                val c0 = SlotColors.color(0)
                val c1 = SlotColors.color(1)
                val random = Random(42)
                val neutral = (1..60).map { i ->
                    val x = 2f + random.nextFloat() * 18f
                    val y = (x + (random.nextFloat() - 0.5f) * 6f).coerceAtLeast(0f)
                    ScatterPoint(id = "n$i", x = x, y = y)
                }
                val above = ScatterPoint("above", x = 9f, y = 15f, color = c0, label = "Ja'Marr Chase")
                val below = ScatterPoint("below", x = 16f, y = 9f, color = c1, label = "Jerry Jeudy")
                val points = (neutral + above + below).toImmutableList()
                ScatterChart(
                    points = points,
                    xLabel = "Expected FP",
                    yLabel = "Actual FP",
                    aboveLabel = "Outscoring",
                    belowLabel = "Due",
                    contentDescription = "Actual versus expected fantasy points",
                    selectedId = "above",
                    onSelect = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_4_scatter.png")
    }

    @Test
    fun tappingNearAPointSelectsIt() {
        val highlighted = ScatterPoint("hi", x = 12f, y = 12f, label = "Hit")
        val points = persistentListOf(
            ScatterPoint("n1", 2f, 3f),
            ScatterPoint("n2", 18f, 5f),
            highlighted,
        )
        val selected = mutableListOf<String?>()
        compose.setContent {
            GridironTheme {
                ScatterChart(
                    points = points,
                    xLabel = "Expected",
                    yLabel = "Actual",
                    aboveLabel = "Above",
                    belowLabel = "Below",
                    contentDescription = "scatter",
                    selectedId = null,
                    onSelect = { selected += it },
                    modifier = Modifier.size(300.dp),
                )
            }
        }
        compose.waitForIdle()

        val d = compose.density
        val bounds = ScatterScale.bounds(points)
        val span = bounds.endInclusive - bounds.start
        val outer = with(d) { 300.dp.toPx() }
        val pad = with(d) { 8.dp.toPx() }
        val plot = outer - 2 * pad
        val gutter = with(d) { 36.dp.toPx() }
        fun toScreen(p: ScatterPoint) = Offset(
            pad + gutter + (p.x - bounds.start) / span * (plot - gutter),
            pad + (plot - gutter) * (1 - (p.y - bounds.start) / span),
        )

        compose.onRoot().performTouchInput { click(toScreen(highlighted)) }
        compose.waitForIdle()
        assertEquals("hi", selected.last())

        // The middle of the whole node, away from any of the three points: empty space.
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().performTouchInput { click(Offset(outer / 2, outer / 2)) }
        compose.waitForIdle()
        assertNull(selected.last())
    }
}
