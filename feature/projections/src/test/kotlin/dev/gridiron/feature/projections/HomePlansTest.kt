package dev.gridiron.feature.projections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import dev.gridiron.core.data.live.LeagueMatchup
import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.LeagueTeam
import dev.gridiron.core.data.live.MatchupSide
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
class HomePlansTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(id: String, name: String, position: String, points: Double, team: String = "KC") = ProjectionRow(id, name, position, team, points, points / 2, points * 2)

    private val rows = listOf(
        row("r1", "Star Back", "RB", 15.0), row("r2", "Bench Back", "RB", 8.0), row("w1", "Wideout", "WR", 10.0),
        row("r9", "Free Back", "RB", 11.0), row("k1", "My Kicker", "K", 6.0), row("d1", "MY D/ST", "DST", 5.0),
        row("k2", "Free Kicker", "K", 9.0), row("k3", "Other Kicker", "K", 7.0), row("k4", "Weak Kicker", "K", 5.0),
        row("d2", "Early D/ST", "DST", 8.0, team = "DAL"), row("d3", "Free D/ST", "DST", 6.0),
    )

    private fun team(slots: Map<String, Int>, vararg players: Pair<String, String>) =
        MyTeam("Mine", 2026, players.map { (id, slot) -> LeaguePlayer("e$id", id, slot, id) }, slots, slotsAreDefault = false)

    @Test
    fun aListedStarterGetsTheBenchManWhoReplacesHimAndTheBestAdd() {
        val mine = team(mapOf("RB" to 1, "WR" to 1), "r1" to "RB", "r2" to "BE", "w1" to "WR")
        val plans = replacementPlans(mine, 5, rows, mapOf("r1" to "Q", "r2" to "A"), rostered = setOf("r1", "r2", "w1"))
        assertEquals(1, plans.size)
        assertEquals("If he sits: start Bench Back (8.0) · or add Free Back (+3.0)", planText(plans.single()))
        // A bench player listed Out isn't a starter: no plan.
        assertEquals(emptyList<ReplacementPlan>(), replacementPlans(mine, 5, rows, mapOf("r2" to "O"), rostered = null))
    }

    @Test
    fun streamsAreFreeKickersAndDefensesBeatingYoursOnlyWhereTheLeagueStartsOne() {
        val mine = team(mapOf("K" to 1, "D/ST" to 1), "k1" to "K", "d1" to "D/ST")
        val found = streams(mine, 5, rows, mapOf("k2" to mapOf(6 to 8.4)), emptyMap(), rostered = setOf("k1", "d1"), started = setOf("DAL"))
        assertEquals(listOf("k2" to 3.0, "k3" to 1.0, "d3" to 1.0), found.map { it.row.playerId to it.gain })
        assertEquals("9.0 pts (+3.0 on yours) · next week 8.4", streamText(found.first()))
        assertEquals(emptyList<StreamLine>(), streams(team(mapOf("RB" to 1), "k1" to "BE"), 5, rows, emptyMap(), emptyMap(), emptySet()))
    }

    @Test
    fun scoutingReadsTheRecordAndRecentScoresAgainstTheLeague() {
        fun side(id: Int, total: Double) = MatchupSide(id, total, emptyList())
        val weeks = mapOf(
            5 to listOf(LeagueMatchup(5, side(1, 100.0), side(2, 120.0))),
            4 to listOf(LeagueMatchup(4, side(1, 90.0), side(3, 110.0))),
        )
        assertEquals(listOf(RecentScore(4, 90.0, 100.0), RecentScore(5, 100.0, 110.0)), recentScores(1, weeks))
        assertEquals("Last 2: wk 4 90.0 · wk 5 100.0 (league 105.0 a week)", recentLine(recentScores(1, weeks)))
        val rivals = LeagueTeam(1, "Rivals", null, 4, 2, 1, 612.44, 580.1, 3, emptyList())
        assertEquals("4-2-1 · 3rd of 12 · 612.4 for, 580.1 against", recordLine(rivals, 12))

        val view = lineupView(team(mapOf("RB" to 1, "WR" to 1), "r1" to "RB", "w1" to "WR").copy(teamName = "Rivals"), 6, rows, emptyMap())
        compose.setContent {
            GridironTheme {
                ScoutScreen(ScoutReport(view, rivals, 12, recentScores(1, weeks), moves = listOf("Rivals added Free Back")), message = null, onPlayer = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("scout:record").assertTextEquals("4-2-1 · 3rd of 12 · 612.4 for, 580.1 against")
        compose.onNodeWithTag("scout:total").assertTextEquals("Projected 25.0 this week under your scoring")
        compose.onNodeWithTag("scout").performScrollToNode(hasTestTag("scout:move:0"))
        compose.onNodeWithTag("scout:move:0").assertTextEquals("Rivals added Free Back")
    }
}
