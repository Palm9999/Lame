package dev.gridiron.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveInjury
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.data.live.NewsPlayer
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

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
        compose.onNodeWithText("RB · PHI").assertExists()
        compose.onNodeWithText("Questionable").assertExists()
        compose.onNodeWithText("Barkley (neck) was limited.").assertExists()
        compose.onNodeWithText("Barkley to play").assertExists()
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
            GridironTheme { LiveInjuriesScreen(listOf(InjuryGroup("ARI", listOf(line))), t, null, onBack = {}, onPlayer = { players += it }) }
        }

        compose.onNodeWithText("ARI").assertExists()
        compose.onNodeWithText("CB · Practice: Limited · Wk 3").assertExists()
        compose.onNodeWithText("Max Melton").performClick()

        assertEquals(listOf("P1"), players)
    }
}
