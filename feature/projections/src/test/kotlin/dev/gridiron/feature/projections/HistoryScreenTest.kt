package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.live.HistoryGame
import dev.gridiron.core.data.live.HistoryResult
import dev.gridiron.core.data.live.HistorySeason
import dev.gridiron.core.data.live.HistoryTeam
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val season = HistorySeason(
        2025,
        listOf(
            HistoryTeam(1, "Ann's Team", "{A}", 10, 4, 0, 1600.0, 1400.0, 1, 1),
            HistoryTeam(2, "Bo Knows", "{B}", 4, 10, 0, 1200.0, 1500.0, 2, 2),
        ),
        mapOf("{A}" to "Ann", "{B}" to "Bo"),
        listOf(HistoryGame(1, 1, 2, 150.0, 60.0, "HOME", playoff = false)),
        playoffTeams = 2,
    )

    private fun show(result: HistoryResult?) {
        compose.setContent { GridironTheme { HistoryScreen(result?.let(::historyState) ?: HistoryState.Loading, onBack = {}) } }
    }

    @Test
    fun `seasons tab names champions`() {
        show(HistoryResult(listOf(season), emptyList(), me = "{A}", error = null))
        compose.onNodeWithText("2025 · Champion: Ann (Ann's Team)").assertIsDisplayed()
        compose.onNodeWithText("2nd · Bo Knows (Bo) · 4–10 · 1200.0").assertIsDisplayed()
    }

    @Test
    fun `all-time marks you`() {
        show(HistoryResult(listOf(season), emptyList(), me = "{A}", error = null))
        compose.onNodeWithTag("tab:alltime").performClick()
        compose.onNodeWithText("Ann (you)").assertIsDisplayed()
        compose.onNodeWithText("10–4 · .714 · 1 title · 1 playoffs · avg finish 1.0").assertIsDisplayed()
    }

    @Test
    fun `head-to-head lists rivals`() {
        show(HistoryResult(listOf(season), emptyList(), me = "{A}", error = null))
        compose.onNodeWithTag("tab:h2h").performClick()
        compose.onNodeWithText("Bo").assertIsDisplayed()
        compose.onNodeWithText("1–0 · 150.0 to 60.0").assertIsDisplayed()
    }

    @Test
    fun `records tab`() {
        show(HistoryResult(listOf(season), emptyList(), me = "{A}", error = null))
        compose.onNodeWithTag("tab:records").performClick()
        compose.onNodeWithText("Highest week: 150.0").assertIsDisplayed()
        compose.onNodeWithText("Ann · 2025, week 1 · against Bo").assertIsDisplayed()
    }

    @Test
    fun `skipped seasons are named`() {
        show(HistoryResult(listOf(season), listOf(2019 to "HTTP 500"), me = "{A}", error = null))
        compose.onNodeWithText("Couldn't read 2019: HTTP 500.").assertIsDisplayed()
    }

    @Test
    fun `no league says sync one`() {
        show(HistoryResult(emptyList(), emptyList(), me = null, error = "no league id set"))
        compose.onNodeWithText("No history: no league id set.").assertIsDisplayed()
    }
}
