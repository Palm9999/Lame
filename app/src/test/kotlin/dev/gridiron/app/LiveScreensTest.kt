package dev.gridiron.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.ReturnOutlook
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveInjury
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.data.live.NewsPlayer
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.feature.projections.ProjectionCard
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live screens fed plain data: Robolectric can't open live.db itself. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class LiveScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = Instant.parse("2026-09-25T23:01:03Z")
    private val barkley = NewsItem(
        "1", t, "Barkley to play", "He plans to play through a stinger.", "https://espn.example/1",
        listOf(NewsPlayer("Saquon Barkley", "P1"), NewsPlayer("Unknown Guy", null)),
    )

    @Test
    fun newsLinksOnlyKnownPlayersAndOpensArticles() {
        val opened = mutableListOf<String>()
        val players = mutableListOf<String>()
        compose.setContent {
            GridironTheme { NewsScreen(listOf(barkley), t, null, onBack = {}, onOpen = { opened += it }, onPlayer = { players += it }) }
        }

        compose.onNodeWithTag("chip:P1").performClick()
        compose.onNodeWithText("Barkley to play").performClick()

        assertEquals(listOf("P1"), players)
        assertEquals(listOf("https://espn.example/1"), opened)
        compose.onNodeWithText("Unknown Guy").assertDoesNotExist()
        compose.onNodeWithText("as of", substring = true).assertExists()
    }

    @Test
    fun newsSaysWhenItCouldNotUpdate() {
        compose.setContent { GridironTheme { NewsScreen(listOf(barkley), t, "couldn't reach ESPN", {}, {}, {}) } }
        compose.onNodeWithText("Not updated: couldn't reach ESPN", substring = true).assertExists()
        compose.onNodeWithText("Barkley to play").assertExists()
    }

    @Test
    fun thePlayerPageShowsStatusNotesAndNews() {
        val page = PlayerPage(
            header = PlayerHeader("P1", "Saquon Barkley", "RB", "PHI"),
            status = LiveStatus("Questionable", "Q", "Barkley (neck) is questionable.", null, t),
            notes = listOf(
                InjuryNote(t, "Questionable", "Barkley (neck) is questionable."),
                InjuryNote(t.minusSeconds(86_400), "Questionable", "Barkley (neck) was limited."),
            ),
            news = listOf(barkley),
            asOf = t,
        )
        compose.setContent { GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithTag("playerName").assertTextEquals("Saquon Barkley")
        compose.onNodeWithText("RB").assertExists()
        compose.onNodeWithText("PHI").assertExists()
        compose.onNodeWithText("Questionable").assertExists()
        compose.onNodeWithText("Barkley (neck) was limited.").assertExists()
        compose.onNodeWithText("Barkley to play").assertExists()
    }

    @Test
    fun anOutPlayerShowsHowSoonHeIsLikelyBack() {
        val outlook = ReturnOutlook("Out", "hamstring", 97, 0, 0, listOf(6, 8), listOf(0.0, 0.28), 2024, 2026)
        val page = PlayerPage(
            PlayerHeader("P1", "Saquon Barkley", "RB", "PHI"), LiveStatus("Out", "O", null, null, t), emptyList(), emptyList(), t,
            returnOutlook = outlook,
        )
        compose.setContent { GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithTag("player:return").assertExists()
        compose.onNodeWithContentDescription("Played again by: wk 6 0% · wk 8 28%").assertExists()
        compose.onNodeWithText("28%").assertExists()
        compose.onNodeWithText("From 97 past hamstring absences", substring = true).assertExists()
    }

    @Test
    fun aGrowingRoleShowsOnThePlayerPageWithItsReason() {
        val rising = dev.gridiron.core.data.BreakoutRow("P1", "Saquon Barkley", "RB", "PHI", 72.0, 18.0, 12.0, null, null, 0.0, "Kenneth Gainwell")
        val page = PlayerPage(PlayerHeader("P1", "Saquon Barkley", "RB", "PHI"), null, emptyList(), emptyList(), null, risingRole = rising)
        compose.setContent { GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithTag("risingRole").assertExists()
        compose.onNodeWithText("Role growing").assertExists()
        compose.onNodeWithText("touches 12.0 → 18.0 a game · Kenneth Gainwell out").assertExists()
        compose.onNodeWithText("72").assertExists()
    }

    @Test
    fun noRisingRoleLeavesThePlayerPageAsItWas() {
        val page = PlayerPage(PlayerHeader("P1", "Saquon Barkley", "RB", "PHI"), null, emptyList(), emptyList(), null)
        compose.setContent { GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithTag("risingRole").assertDoesNotExist()
    }

    @Test
    fun aTeamDefensesPageReadsDst() {
        val page = PlayerPage(PlayerHeader("DST_KC", "Kansas City D/ST", "DST", "KC"), null, emptyList(), emptyList(), null)
        compose.setContent { GridironTheme { PlayerScreen("DST_KC", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithText("D/ST").assertExists()
        compose.onNodeWithText("KC").assertExists()
    }

    @Test
    fun aPlayerWithNothingLiveSaysSo() {
        compose.setContent {
            GridironTheme { PlayerScreen("P9", PlayerPage(null, null, emptyList(), emptyList(), null), liveAvailable = true, onBack = {}, onOpen = {}) }
        }
        compose.onNodeWithTag("playerName").assertTextEquals("P9")
        compose.onNodeWithText("No injury designation.").assertExists()
        compose.onNodeWithText("No recent news.").assertExists()
    }

    @Test
    fun theLiveInjuryReportGroupsByTeamWithPractice() {
        val line = InjuryLine(LiveInjury("e1", "P1", "Max Melton", "ARI", "CB", "Questionable", "Q", "Melton (toe) was limited.", t), "Limited · Wk 3")
        val players = mutableListOf<String>()
        compose.setContent {
            GridironTheme {
                LiveInjuriesScreen(
                    listOf(InjuryGroup("ARI", listOf(line))), t, null, onBack = {}, onPlayer = { players += it },
                    outlooks = mapOf("P1" to "Played again by: wk 6 40%"),
                )
            }
        }

        compose.onNodeWithText("ARI").assertExists()
        compose.onNodeWithText("Played again by: wk 6 40%").assertExists()
        compose.onNodeWithText("CB · Practice: Limited · Wk 3").assertExists()
        compose.onNodeWithText("Max Melton").performClick()

        assertEquals(listOf("P1"), players)
    }

    @Test
    fun `the player page shows this week's projection and opens its waterfall`() {
        var opened: Pair<Int, Int>? = null
        val card = ProjectionCard(2026, 4, "@ BUF", null, 11.0, 5.2, 18.9, null, null, out = false)
        val page = PlayerPage(null, null, emptyList(), emptyList(), null, projection = card)
        compose.setContent {
            GridironTheme {
                PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}, onProjection = { s, w -> opened = s to w })
            }
        }

        compose.onNodeWithText("11.0 pts").performClick()
        assertEquals(2026 to 4, opened)
    }
}
