package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.WaiverTrend
import dev.gridiron.core.data.live.WaiverTrendsResult
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
class WaiverTrendsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun trend(espnId: String, name: String, position: String, owned: Double, espn: Double, week: Double?, playerId: String? = "p$espnId") =
        WaiverTrend(espnId, playerId, name, position, "MIA", owned, espn, week)

    private val trends = listOf(
        trend("1", "Riser", "WR", 40.0, 1.0, 20.0),
        trend("2", "Small riser", "RB", 10.0, 3.0, 5.0),
        trend("3", "Faller", "TE", 50.0, -2.0, -15.0),
        trend("4", "Flat", "QB", 99.0, 0.0, 0.0),
        trend("5", "Unknown", "K", 5.0, 0.5, 4.0, playerId = null),
    )
    private val points = TrendPoints(6, mapOf("p1" to 12.34), mapOf("p1" to 140.0))

    @Test
    fun `weekly lists rank by the week's change, ESPN's otherwise`() {
        val weekly = WaiverTrendsResult(trends, weekly = true, error = null)
        assertEquals(listOf("Riser", "Small riser", "Unknown"), trendRows(weekly, points, added = true, position = null).map { it.trend.name })
        assertEquals(listOf("Faller"), trendRows(weekly, points, added = false, position = null).map { it.trend.name })

        val espn = weekly.copy(weekly = false)
        assertEquals(listOf("Small riser", "Riser", "Unknown"), trendRows(espn, points, added = true, position = null).map { it.trend.name })
        assertEquals(3.0, trendRows(espn, points, added = true, position = null).first().change, 1e-9)
    }

    @Test
    fun `a position narrows the list and points join by player id`() {
        val rows = trendRows(WaiverTrendsResult(trends, true, null), points, added = true, position = "WR")
        assertEquals(1, rows.size)
        assertEquals(12.34, rows.single().weekPoints!!, 1e-9)
        assertEquals(140.0, rows.single().rosPoints!!, 1e-9)
    }

    @Test
    fun `at most twenty-five rows`() {
        val many = (1..40).map { trend("$it", "P$it", "WR", 10.0, it.toDouble(), null) }
        assertEquals(TREND_ROWS, trendRows(WaiverTrendsResult(many, false, null), TrendPoints.NONE, true, null).size)
    }

    @Test
    fun `the screen shows the risers, flips to fallers and tags free agents`() {
        var opened: String? = null
        val league = LeagueRostered(setOf("p3"), 2026, 0L, mapOf("p3" to "Team Two"))
        compose.setContent {
            GridironTheme {
                WaiverTrendsScreen(
                    WaiverTrendsState.Loaded(WaiverTrendsResult(trends, true, "couldn't reach ESPN"), points),
                    league, emptySet(), onPlayer = { opened = it }, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Riser").assertIsDisplayed()
        compose.onNodeWithText("+20.0").assertIsDisplayed()
        compose.onNodeWithText("Week 6 12.3 · rest of season 140.0").assertIsDisplayed()
        compose.onNodeWithText("Not updated: couldn't reach ESPN.").assertIsDisplayed()
        compose.onAllNodesWithText("Free agent").assertCountEquals(2)
        compose.onNodeWithTag("trend:1").performClick()
        assertEquals("p1", opened)

        compose.onNodeWithTag("chip:dropped").performClick()
        compose.onNodeWithText("Faller").assertIsDisplayed()
        compose.onNodeWithText("On Team Two").assertIsDisplayed()
        compose.onAllNodesWithText("Riser").assertCountEquals(0)
    }
}
