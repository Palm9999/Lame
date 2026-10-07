package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.ProjectionComponent
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
class DifferScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** [rec] receptions and [yds] receiving yards: PPR points = rec + yds / 10. */
    private fun wr(id: String, name: String, rec: Double, yds: Double, pos: String = "WR") =
        ListedProjection(id, name, pos, "KC", listOf(ProjectionComponent("receptions", rec, 0.0), ProjectionComponent("receiving_yards", yds, 0.0)))

    private val app = listOf(wr("a", "App Likes", 6.0, 90.0), wr("b", "Espn Likes", 3.0, 40.0), wr("c", "Same", 4.0, 50.0), wr("t", "Tight End", 5.0, 60.0, "TE"), wr("k", "No Espn", 9.0, 90.0))
    private val espn = listOf(wr("a", "App Likes", 4.0, 60.0), wr("b", "Espn Likes", 5.0, 70.0), wr("c", "Same", 4.0, 50.0), wr("t", "Tight End", 4.0, 50.0, "TE"))

    @Test
    fun `rows rank the gap each way and skip players ESPN doesn't project`() {
        val above = differRows(app, espn, ScoringPresets.PPR, above = true, position = null)
        assertEquals(listOf("a", "t"), above.map { it.playerId })
        assertEquals(15.0 - 10.0, above.first().gap, 1e-9)
        assertEquals(listOf("b"), differRows(app, espn, ScoringPresets.PPR, above = false, position = null).map { it.playerId })
        assertEquals(listOf("t"), differRows(app, espn, ScoringPresets.PPR, above = true, position = "TE").map { it.playerId })
    }

    @Test
    fun `kickers and D-STs are compared too, at their own chips`() {
        fun k(id: String, xp: Double) = ListedProjection(id, "Kicker $id", "K", "KC", listOf(ProjectionComponent("xp_made", xp, 0.0)))
        val rows = differRows(app + k("k1", 4.0), espn + k("k1", 2.0), ScoringPresets.PPR, above = true, position = "K")
        assertEquals(listOf("k1"), rows.map { it.playerId })
        assertEquals(2.0, rows.single().gap, 1e-9)
    }

    @Test
    fun `the screen flips between above and below`() {
        var opened: String? = null
        compose.setContent {
            GridironTheme { DifferScreen(DifferState.Loaded(6, app, espn), ScoringPresets.PPR, null, emptySet(), { opened = it }, onBack = {}) }
        }
        compose.onNodeWithText("App Likes").assertIsDisplayed()
        compose.onNodeWithText("App 15.0 · ESPN 10.0").assertIsDisplayed()
        compose.onNodeWithText("+5.0").assertIsDisplayed()
        compose.onNodeWithText("App Likes").performClick()
        assertEquals("a", opened)
        compose.onNodeWithTag("differ:below").performClick()
        compose.onNodeWithText("Espn Likes").assertIsDisplayed()
        compose.onAllNodesWithText("App Likes").assertCountEquals(0)
    }

    @Test
    fun `model alone compares the model's own number, and hides without one`() {
        val model = listOf(wr("b", "Espn Likes", 8.0, 100.0))
        compose.setContent {
            GridironTheme { DifferScreen(DifferState.Loaded(6, app, espn, model), ScoringPresets.PPR, null, emptySet(), {}, onBack = {}) }
        }
        compose.onNodeWithText("App Likes").assertIsDisplayed()
        compose.onNodeWithTag("differ:model").performClick()
        compose.onNodeWithText("Model 18.0 · ESPN 12.0").assertIsDisplayed()
        compose.onAllNodesWithText("App Likes").assertCountEquals(0)
    }

    @Test
    fun `without a stored model there is no model chip`() {
        compose.setContent {
            GridironTheme { DifferScreen(DifferState.Loaded(6, app, espn), ScoringPresets.PPR, null, emptySet(), {}, onBack = {}) }
        }
        compose.onAllNodesWithTag("differ:model").assertCountEquals(0)
    }

    @Test
    fun `no ESPN week says so`() {
        compose.setContent {
            GridironTheme { DifferScreen(DifferState.Loaded(6, app, emptyList()), ScoringPresets.PPR, null, emptySet(), {}, onBack = {}) }
        }
        compose.onNodeWithText("No ESPN projections for week 6 in this build. Refresh stats.").assertIsDisplayed()
    }
}
