package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.DynastyResult
import dev.gridiron.core.data.DynastyValue
import dev.gridiron.core.data.live.DraftPick
import dev.gridiron.core.data.live.DraftResult
import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.datastore.KeeperRule
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
class DynastyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun v(rank: Int, id: String?, name: String, pos: String, value: Int, redraft: Int) =
        DynastyValue("e$rank", id, name, pos, "DET", 24.5, value, redraft, rank, rank, 100)

    private val values = listOf(
        v(1, "star", "Star Back", "RB", 11000, 10000),
        v(2, "wr1", "Free Receiver", "WR", 9000, 9500),
        v(3, "late", "Late Steal", "WR", 5000, 8000),
        v(4, "qb", "Old Passer", "QB", 3000, 7000),
    )

    private val team = MyTeam(
        "Mine", 2026,
        listOf(
            LeaguePlayer("1", "Star Back", "RB", "star"),
            LeaguePlayer("3", "Late Steal", "WR", "late"),
            LeaguePlayer("4", "Old Passer", "QB", "qb"),
            LeaguePlayer("9", "Kicker Guy", "K", "kick"),
        ),
        emptyMap(), slotsAreDefault = true,
    )

    // Star drafted round 1; Late Steal round 12; Old Passer a keeper at round 3; the kicker undrafted.
    private val picks = listOf(
        DraftPick("1", "star", 1, 2, keeper = false),
        DraftPick("3", "late", 12, 2, keeper = false),
        DraftPick("4", "qb", 3, 2, keeper = true),
    )

    private val rule = KeeperRule(keepers = 2, penalty = 1)

    @Test
    fun `keeper rows price by pick and rank by surplus`() {
        val rows = keeperRows(team, picks, values, rule, teams = 2, leagueRounds = 16)
        // Worth: redraft ranks 1, 3, 4 in a 2-team draft → rounds 1, 2, 2.
        // Star and Old Passer tie at 0; Star's dynasty value is higher.
        assertEquals(listOf("late", "star", "qb", "kick"), rows.map { it.row.playerId })
        assertEquals(listOf(12, 1, 2, 16), rows.map { it.row.cost })
        assertEquals(listOf(10, 0, 0, null), rows.map { it.row.surplus })
        assertEquals(listOf(true, true, false, false), rows.map { it.row.keep })
    }

    private fun show(state: DynastyState, team: MyTeam? = this.team, rule: KeeperRule? = this.rule, onRule: (KeeperRule) -> Unit = {}, onPlayer: (String) -> Unit = {}) {
        compose.setContent {
            GridironTheme {
                DynastyScreen(
                    state, LeagueRostered(setOf("star", "late", "qb"), 2026, 0L, mapOf("star" to "Mine")),
                    team, rule, teams = 2, leagueRounds = 16, onRule = onRule, onPlayer = onPlayer, onBack = {},
                )
            }
        }
    }

    private val loaded = DynastyState.Loaded(DynastyResult(values, null), DraftResult(picks, null))

    @Test
    fun `dynasty tab ranks and filters by position`() {
        var opened: String? = null
        show(loaded, onPlayer = { opened = it })
        compose.onNodeWithText("Star Back").assertIsDisplayed()
        compose.onNodeWithText("11000").assertIsDisplayed()
        compose.onNodeWithTag("pos:QB").performClick()
        compose.onAllNodesWithText("Star Back").assertCountEquals(0)
        compose.onNodeWithText("Old Passer").assertIsDisplayed()
        compose.onNodeWithTag("dyn:4").performClick()
        assertEquals("qb", opened)
    }

    @Test
    fun `free agents chip narrows`() {
        show(loaded)
        compose.onNodeWithTag("chip:free").performClick()
        compose.onNodeWithText("Free Receiver").assertIsDisplayed()
        compose.onAllNodesWithText("Star Back").assertCountEquals(0)
    }

    @Test
    fun `keepers tab marks keep and shows cost and worth`() {
        show(loaded)
        compose.onNodeWithTag("tab:keepers").performClick()
        compose.onNodeWithText("Late Steal · Keep").assertIsDisplayed()
        compose.onNodeWithText("Star Back · Keep").assertIsDisplayed()
        compose.onNodeWithText("Old Passer").assertIsDisplayed()
        compose.onNodeWithText("Cost round 12").assertIsDisplayed()
        compose.onNodeWithText("+10 rounds").assertIsDisplayed()
        compose.onNodeWithText("not valued").assertIsDisplayed()
    }

    @Test
    fun `no league says sync one`() {
        show(DynastyState.Loaded(DynastyResult(values, null), null), team = null, rule = null)
        compose.onNodeWithTag("tab:keepers").performClick()
        compose.onNodeWithTag("keepers:none").assertIsDisplayed()
    }

    @Test
    fun `no draft found says every player costs the undrafted round`() {
        show(DynastyState.Loaded(DynastyResult(values, null), DraftResult(emptyList(), null)))
        compose.onNodeWithTag("tab:keepers").performClick()
        compose.onNodeWithText("No draft found: every player costs round 16.").assertIsDisplayed()
    }

    @Test
    fun `editing penalty and a cost override reprices`() {
        val rules = mutableListOf<KeeperRule>()
        show(loaded, onRule = { rules += it })
        compose.onNodeWithTag("tab:keepers").performClick()
        compose.onNodeWithTag("rule:penalty:value").assertTextEquals("1")
        compose.onNodeWithTag("rule:penalty:plus").performClick()
        assertEquals(rule.copy(penalty = 2), rules.last())
        compose.onNodeWithTag("cost:star").performClick()
        compose.onNodeWithTag("override:star:plus").performClick()
        assertEquals(rule.copy(overrides = mapOf("star" to 2)), rules.last())
    }
}
