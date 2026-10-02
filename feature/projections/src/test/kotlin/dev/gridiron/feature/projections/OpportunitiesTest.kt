package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.OpportunitiesResult
import dev.gridiron.core.data.OpportunityRow
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.projections.Beneficiary
import dev.gridiron.core.projections.InjuredStarter
import dev.gridiron.core.projections.UsageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class OpportunitiesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(id: String, name: String, from: Int, to: Int, projected: Double?, recent: Double?, abbr: String = "D") =
        OpportunityRow(
            Beneficiary(
                UsageRow(id, name, "RB", "KC", 40.0, 4, recent),
                from, to, listOf(InjuredStarter("star", "Star Back", 1, abbr)),
            ),
            projected, recent,
        )

    private val rows = listOf(
        row("r2", "Second Back", 2, 1, 14.1, 7.8),
        row("r3", "Third Back", 3, 2, 8.0, 3.0),
    )
    private val league = LeagueRostered(setOf("r2", "mine"), 2026, 1L, mapOf("r2" to "Rivals", "mine" to "Mine"))

    @Test
    fun `owner is free agent, yours or the team that has him, and unknown without a league`() {
        assertEquals(Owner.FreeAgent, ownerOf("r3", league, setOf("mine")))
        assertEquals(Owner.Yours, ownerOf("mine", league, setOf("mine")))
        assertEquals(Owner.Other("Rivals"), ownerOf("r2", league, setOf("mine")))
        assertNull(ownerOf("r3", null, emptySet()))
    }

    @Test
    fun `status words and the note name the hurt starter`() {
        assertEquals("Doubtful", statusWord("D"))
        assertEquals("Questionable", statusWord("Q"))
        assertEquals("RB1 Star Back is Doubtful", injuredNote(rows[0]))
    }

    @Test
    fun `a result with rows loads, and one with only a message is unavailable`() {
        assertTrue(OpportunitiesResult(rows, 4, null).toState() is OpportunitiesState.Loaded)
        assertEquals(OpportunitiesState.Unavailable("No injuries."), OpportunitiesResult(emptyList(), 4, "No injuries.").toState())
    }

    @Test
    fun `rows show the move, the hurt starter, the projection against recent play and the owner`() {
        var opened: String? = null
        compose.setContent {
            GridironTheme { OpportunitiesScreen(OpportunitiesState.Loaded(4, rows), league, setOf("mine"), onPlayer = { opened = it }, onBack = {}) }
        }
        compose.onNodeWithText("Second Back").assertIsDisplayed()
        compose.onNodeWithText("RB · KC · moves up to RB1 (was RB2)").assertIsDisplayed()
        compose.onAllNodesWithText("RB1 Star Back is Doubtful").assertCountEquals(2)
        compose.onNodeWithText("Projected 14.1 · last four 7.8").assertIsDisplayed()
        compose.onNodeWithText("+6.3").assertIsDisplayed()
        compose.onNodeWithText("On Rivals").assertIsDisplayed()
        compose.onNodeWithText("Free agent").assertIsDisplayed()
        compose.onNodeWithText("Second Back").performClick()
        assertEquals("r2", opened)
    }

    @Test
    fun `the free agents chip keeps only players on no team`() {
        compose.setContent {
            GridironTheme { OpportunitiesScreen(OpportunitiesState.Loaded(4, rows), league, emptySet(), onPlayer = {}, onBack = {}) }
        }
        compose.onNodeWithTag("chip:free").performClick()
        compose.onNodeWithText("Third Back").assertIsDisplayed()
        compose.onNodeWithTag("opp:r2").assertDoesNotExist()
    }

    @Test
    fun `no league, no owner tag and no free agents chip`() {
        compose.setContent {
            GridironTheme { OpportunitiesScreen(OpportunitiesState.Loaded(4, rows), null, emptySet(), onPlayer = {}, onBack = {}) }
        }
        compose.onNodeWithTag("chip:free").assertDoesNotExist()
        compose.onNodeWithText("Free agent").assertDoesNotExist()
    }

    @Test
    fun `an unavailable result says why`() {
        compose.setContent {
            GridironTheme { OpportunitiesScreen(OpportunitiesState.Unavailable("No injury list from ESPN yet. Refresh stats."), null, emptySet(), {}, {}) }
        }
        compose.onNodeWithText("No injury list from ESPN yet. Refresh stats.").assertIsDisplayed()
    }
}
