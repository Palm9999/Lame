package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
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

    private val team = MyTeam(
        "Sunday Squad", 2026,
        listOf(LeaguePlayer("1", "Wide Out", "WR", "w"), LeaguePlayer("2", "Quarter Back", "QB", "q"), LeaguePlayer("3", "Mystery Man", "BE", null)),
        mapOf("QB" to 1, "WR" to 1, "K" to 1),
        slotsAreDefault = false,
    )

    @Test
    fun `my lineup lists the starters, the total and who is left out`() {
        var opened: String? = null
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = { opened = it }, onBack = {}, myTeam = team) } }

        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithTag("lineup:total").assertTextEquals("Projected 37.2 pts")
        compose.onNodeWithText("Sunday Squad · week 4").assertIsDisplayed()
        compose.onNodeWithText("No one can fill this slot").assertIsDisplayed()
        compose.onNodeWithText("Mystery Man · not matched to the app's players").assertIsDisplayed()
        compose.onNodeWithText("Wide Out").performClick()
        assertEquals("w", opened)
    }

    private val rival = MyTeam("Rivals", 2026, listOf(LeaguePlayer("9", "Quarter Back", "QB", "q")), team.slots, slotsAreDefault = false)

    @Test
    fun `my lineup asks for the opponent and shows the margin`() {
        var asked = 0
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team, opponent = OpponentState.Loaded(rival), onLineupOpened = { asked++ })
            }
        }
        assertEquals(0, asked)
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
        // Mine: QB 21.0 + WR 16.2; theirs: QB 21.0 only.
        compose.onNodeWithTag("lineup:vs").assertTextEquals("vs Rivals: 21.0 pts · You lead by 16.2 · 94% to win")
        compose.onNodeWithTag("lineup:range").assertExists()
        compose.onNodeWithTag("lineup:check").assertTextEquals("Your ESPN lineup is already the best (as of your last sync).")
    }

    @Test
    fun `an unavailable opponent says why and keeps the lineup`() {
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team, opponent = OpponentState.Unavailable("you have a bye in week 4"))
            }
        }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithTag("lineup:vs").assertTextEquals("No comparison: you have a bye in week 4.")
        compose.onNodeWithTag("lineup:total").assertTextEquals("Projected 37.2 pts")
    }

    @Test
    fun `waiver pickups list the best free agent with his gain`() {
        var opened: String? = null
        val withFree = loaded.copy(weekRows = loaded.weekRows + ProjectionRow("w2", "Free Agent WR", "WR", "DEN", 20.0, 12.0, 28.0))
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(withFree, emptyMap(), onPlayer = { opened = it }, onBack = {}, myTeam = team, rostered = setOf("w", "q"))
            }
        }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithText("Waiver pickups").assertIsDisplayed()
        compose.onNodeWithText("Add Free Agent WR").assertIsDisplayed()
        compose.onNodeWithText("+3.8").assertIsDisplayed()
        compose.onNodeWithText("Add Free Agent WR").performClick()
        assertEquals("w2", opened)
    }

    @Test
    fun `rest-of-season adds rank free agents by their rest-of-season lift`() {
        // The team's lineup holds one WR and one QB; w (180) is rostered, so a free WR at 240 lifts it by 60.
        val withStash = loaded.copy(rosRows = loaded.rosRows + ProjectionRow("w3", "Stash WR", "WR", "SEA", 240.0, 200.0, 280.0))
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(withStash, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team, rostered = setOf("w", "q"))
            }
        }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithTag("lineup:list").performScrollToNode(hasTestTag("ros:pickup:w3"))
        compose.onNodeWithText("Rest-of-season adds").assertIsDisplayed()
        compose.onNodeWithTag("ros:pickup:w3").assertIsDisplayed()
    }

    @Test
    fun `a pickup moving up because a starter is hurt says so`() {
        val withFree = loaded.copy(weekRows = loaded.weekRows + ProjectionRow("w2", "Free Agent WR", "WR", "DEN", 20.0, 12.0, 28.0))
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(
                    withFree, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team, rostered = setOf("w", "q"),
                    starterOut = mapOf("w2" to "WR1 Star Receiver is Doubtful"),
                )
            }
        }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithTag("pickup:out:w2", useUnmergedTree = true).assertTextEquals("▲ WR1 Star Receiver is Doubtful")
    }

    @Test
    fun `no pickups when none helps, and no section without the league's rosters`() {
        compose.setContent {
            GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team, rostered = setOf("w", "q")) }
        }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithText("No pickup helps this week.").assertIsDisplayed()
    }

    @Test
    fun `no waiver section when the league's rosters are unknown`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team) } }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithText("Waiver pickups").assertDoesNotExist()
    }

    @Test
    fun `no team, no lineup chip`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}) } }
        compose.onNodeWithTag("chip:lineup").assertDoesNotExist()
    }

    @Test
    fun `the usual slots are announced when the league's are unknown`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}, myTeam = team.copy(slotsAreDefault = true)) } }
        compose.onNodeWithTag("chip:lineup").performClick()
        compose.onNodeWithText("Using the usual slots", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unavailable forecast says why`() {
        compose.setContent {
            GridironTheme { ProjectionListScreen(ProjectionListState.Unavailable("No upcoming games in 2026."), emptyMap(), onPlayer = {}, onBack = {}) }
        }
        compose.onNodeWithText("No upcoming games in 2026.").assertIsDisplayed()
    }

    private val tradeState = ProjectionListState.Loaded(
        week = 4,
        builtAt = null,
        weekRows = emptyList(),
        rosRows = listOf(
            ProjectionRow("q1", "Mine QB", "QB", "KC", 200.0, 0.0, 0.0),
            ProjectionRow("r1", "Mine RB One", "RB", "KC", 150.0, 0.0, 0.0),
            ProjectionRow("r2", "Mine RB Two", "RB", "KC", 140.0, 0.0, 0.0),
            ProjectionRow("w1", "Mine WR", "WR", "KC", 60.0, 0.0, 0.0),
            ProjectionRow("q2", "Their QB", "QB", "BUF", 190.0, 0.0, 0.0),
            ProjectionRow("w2", "Their WR One", "WR", "BUF", 150.0, 0.0, 0.0),
            ProjectionRow("w3", "Their WR Two", "WR", "BUF", 140.0, 0.0, 0.0),
            ProjectionRow("r3", "Their RB", "RB", "BUF", 50.0, 0.0, 0.0),
        ),
    )

    private fun roster(name: String, vararg ids: String) =
        MyTeam(name, 2026, ids.map { LeaguePlayer("e$it", "Player $it", "BE", it) }, mapOf("QB" to 1, "RB" to 1, "WR" to 1), slotsAreDefault = false)

    @Test
    fun `trade weighs ticked players and suggests a trade that helps both`() {
        compose.setContent {
            GridironTheme {
                ProjectionListScreen(
                    tradeState, emptyMap(), onPlayer = {}, onBack = {},
                    myTeam = roster("Mine", "q1", "r1", "r2", "w1"),
                    partners = listOf(roster("Rivals", "q2", "w2", "w3", "r3")),
                )
            }
        }
        compose.onNodeWithTag("chip:trade").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("give:r2").performClick()
        compose.onNodeWithTag("get:w3").performClick()
        compose.onNodeWithTag("trade:verdict").assertTextEquals("Good for both teams")
        compose.onNodeWithTag("trade:mine").assertTextEquals("Your lineup +80.0 (410.0 → 490.0)")
        compose.onNodeWithTag("trade:theirs").assertTextEquals("Rivals +90.0 (390.0 → 480.0)")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("idea:0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("idea:0").performClick()
        compose.onNodeWithTag("trade:mine").assertTextEquals("Your lineup +90.0 (410.0 → 500.0)")
    }

    @Test
    fun `trade needs your team and another team`() {
        compose.setContent {
            GridironTheme { ProjectionListScreen(tradeState, emptyMap(), onPlayer = {}, onBack = {}, myTeam = roster("Mine", "q1")) }
        }
        compose.onNodeWithTag("chip:trade").assertDoesNotExist()
    }

    @Test
    fun `start-sit names the pick between two ticked players`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}) } }
        compose.onNodeWithTag("chip:startsit").performClick()
        compose.onNodeWithTag("ss:w").performClick()
        compose.onNodeWithTag("sstab:QB").performScrollTo().performClick()
        compose.onNodeWithTag("ss:q").performClick()
        compose.onNodeWithTag("startsit:pick").assertTextEquals("Start Quarter Back")
    }
}
