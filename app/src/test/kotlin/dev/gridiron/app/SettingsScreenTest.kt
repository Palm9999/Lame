package dev.gridiron.app

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.datastore.SeasonChoice
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.testing.FakePrefsSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(prefs: FakePrefsSource) {
        compose.setContent { GridironTheme { SettingsScreen(SettingsRepository(prefs) { 2026 }, onBack = {}) } }
    }

    @Test
    fun theDefaultSeasonsAreChecked() {
        show(FakePrefsSource())
        compose.onNodeWithTag("season:2026").assertIsOn()
        compose.onNodeWithTag("season:2024").assertIsOn()
        compose.onNodeWithTag("season:2023").assertIsOff()
    }

    @Test
    fun checkingASeasonSavesIt() {
        val prefs = FakePrefsSource()
        show(prefs)

        compose.onNodeWithTag("season:2023").performClick()
        compose.waitForIdle()

        assertEquals(SeasonChoice(listOf(2023, 2024, 2025, 2026), 2026), prefs.current.seasons)
        compose.onNodeWithTag("season:2023").assertIsOn()
    }

    @Test
    fun theLastSeasonCantBeUnchecked() {
        val prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(seasons = SeasonChoice(listOf(2026), 2026)))
        show(prefs)

        compose.onNodeWithTag("season:2026").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("season:2026").assertIsOn()
        assertEquals("Keep at least one season", ShadowToast.getTextOfLatestToast())
    }
}
