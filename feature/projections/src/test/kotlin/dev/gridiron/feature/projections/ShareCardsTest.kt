package dev.gridiron.feature.projections

import dev.gridiron.core.ui.ShareCardFrame
import dev.gridiron.core.ui.ImageShare
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.gridiron.core.data.live.Grade
import dev.gridiron.core.data.live.MatchupPlayer
import dev.gridiron.core.data.live.RecapGame
import dev.gridiron.core.data.live.ReportCard
import dev.gridiron.core.data.live.WeekRecap
import dev.gridiron.core.data.live.WeekReview
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.projections.TradeOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class ShareCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { GridironTheme { content() } }
    }

    @Test
    fun `the weekly recap card names your result and the league's week`() {
        val recap = WeekRecap(
            5,
            listOf(MatchupPlayer("1", "Star Back", "RB", 31.2) to "Ace"),
            "Ace" to 140.5,
            RecapGame("Ace", 140.5, "Bee", 80.0),
            RecapGame("Cee", 101.0, "Dee", 100.5),
        )
        show { ShareCardFrame { WeeklyRecapShareCard(recap, WeekReview(5, 120.0, 131.0, emptyList(), emptyList())) } }
        compose.onNodeWithText("Week 5 around the league").assertIsDisplayed()
        compose.onNodeWithText("You scored 120.0 of a possible 131.0").assertIsDisplayed()
        compose.onNodeWithText("Top score: Ace, 140.5").assertIsDisplayed()
        compose.onNodeWithText("Star Back 31.2 (Ace)").assertIsDisplayed()
        compose.onNodeWithText("Gridiron · data: nflverse, ESPN").assertIsDisplayed()
    }

    @Test
    fun `the report card share lists places and marks you`() {
        val all = Grade.entries
        val cards = listOf(
            ReportCard(2, "Mine", 0.94, 0.64, 1.25, 812.0, 233.4, all.associateWith { 1 }, 1.0, 1),
            ReportCard(1, "Rivals", 0.81, 0.36, -1.25, 640.0, 100.0, all.associateWith { 2 }, 2.0, 2),
        )
        show { ReportShareCard(cards, myTeamId = 2) }
        compose.onNodeWithText("Report card").assertIsDisplayed()
        compose.onNodeWithText("1st · Mine (you)").assertIsDisplayed()
        compose.onNodeWithText("2nd · Rivals").assertIsDisplayed()
    }

    @Test
    fun `the player card shows this week and rest of season`() {
        val card = ProjectionCard(2026, 6, "vs DAL", "KC −3.5 · O/U 47.5", 18.44, 9.1, 29.9, 160.0, 14.5, out = false, tdChance = 0.42)
        show { PlayerShareCard("Star Back", "RB · KC", card) }
        compose.onNodeWithText("Star Back").assertIsDisplayed()
        compose.onNodeWithText("Week 6 · vs DAL: 18.4 pts").assertIsDisplayed()
        compose.onNodeWithText("Likely 9.1–29.9 · TD 42%").assertIsDisplayed()
        compose.onNodeWithText("Rest of season 160.0 pts (14.5 per game)").assertIsDisplayed()
    }

    @Test
    fun `the trade card names both sides and the verdict`() {
        val outcome = TradeOutcome(1000.0, 1020.5, 980.0, 990.0)
        show { TradeShareCard("Rivals", listOf("Old Back"), listOf("New Receiver", "Kicker"), outcome) }
        compose.onNodeWithText("Good for both teams").assertIsDisplayed()
        compose.onNodeWithText("You send: Old Back").assertIsDisplayed()
        compose.onNodeWithText("You get: New Receiver, Kicker").assertIsDisplayed()
        compose.onNodeWithText("You +20.5 · Rivals +10.0 rest of season").assertIsDisplayed()
    }

    @Test
    fun `a card image is written as a png under cache exports`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val file = ImageShare.write(context, "gridiron-test.png", bitmap)
        assertNotNull(file)
        assertTrue(file!!.path.endsWith("cache/exports/gridiron-test.png"))
        val back = BitmapFactory.decodeFile(file.path)
        assertEquals(40, back.width)
        assertEquals(20, back.height)
    }

    @Test
    fun `a shared card is scaled to 1080 pixels wide whatever the screen`() {
        val small = android.graphics.Bitmap.createBitmap(360, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val out = dev.gridiron.core.ui.ImageShare.fixedWidth(small)
        assertEquals(1080, out.width)
        assertEquals(600, out.height)
    }
}
