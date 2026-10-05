package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.live.ActivityItem
import dev.gridiron.core.data.live.ActivityKind
import dev.gridiron.core.data.live.ActivityMove
import dev.gridiron.core.data.live.ActivityResult
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
class ActivityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val teams = mapOf(1 to "Rivals", 2 to "Mine")
    private val add = ActivityItem(
        "a1", 3, ActivityKind.ADD, 2, 12, 1L,
        listOf(ActivityMove("101", "p101", "Waiver Back", 0, 2), ActivityMove("102", null, "Cut Guy", 2, 0)),
    )
    private val trade = ActivityItem(
        "t1", 4, ActivityKind.TRADE, 1, null, 2L,
        listOf(ActivityMove("104", "p104", "Old Star", 1, 2), ActivityMove("105", "p105", "Young Gun", 2, 1)),
    )
    private val ros = mapOf("p101" to 40.0, "p104" to 120.0, "p105" to 95.5)

    @Test
    fun `an add reads who added and dropped whom, with the bid`() {
        assertEquals("Mine added Waiver Back for $12 and dropped Cut Guy", activityText(add, teams))
        assertEquals("Rivals traded Old Star to Mine for Young Gun", activityText(trade, teams))
    }

    @Test
    fun `a trade's net is each side's rest of season in less out`() {
        assertEquals(mapOf(1 to -24.5, 2 to 24.5), tradeNet(trade, ros))
    }

    private fun show(onPlayer: (String) -> Unit = {}) {
        compose.setContent {
            GridironTheme { ActivityScreen(ActivityState.Loaded(ActivityResult(listOf(trade, add), 2, teams, null)), ros, onPlayer, onBack = {}) }
        }
    }

    @Test
    fun `the feed groups by week and filters`() {
        var opened: String? = null
        show { opened = it }
        compose.onNodeWithText("Week 4").assertIsDisplayed()
        compose.onNodeWithText("Rivals traded Old Star to Mine for Young Gun").assertIsDisplayed()
        compose.onNodeWithText("Mine +24.5 · Rivals −24.5 rest of season").assertIsDisplayed()
        compose.onNodeWithText("Waiver Back · 40.0 rest of season").assertIsDisplayed()
        compose.onNodeWithText("Waiver Back · 40.0 rest of season").performClick()
        assertEquals("p101", opened)
        compose.onNodeWithTag("filter:trades").performClick()
        compose.onAllNodesWithText("Mine added Waiver Back for $12 and dropped Cut Guy").assertCountEquals(0)
        compose.onNodeWithTag("filter:mine").performClick()
        compose.onNodeWithText("Mine added Waiver Back for $12 and dropped Cut Guy").assertIsDisplayed()
    }

    @Test
    fun `an error with nothing to show says so`() {
        compose.setContent {
            GridironTheme { ActivityScreen(ActivityState.Loaded(ActivityResult(emptyList(), null, emptyMap(), "sync your league first")), emptyMap(), {}, onBack = {}) }
        }
        compose.onNodeWithText("No activity: sync your league first.").assertIsDisplayed()
    }
}
