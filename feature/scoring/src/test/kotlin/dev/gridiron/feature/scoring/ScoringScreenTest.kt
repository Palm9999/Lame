package dev.gridiron.feature.scoring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.YardageBonus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scoring list and editor screens, built directly from their state (no
 * ViewModel, no database). Screenshots land in build/outputs/roborazzi when
 * run with recordRoborazziDebug. Sized like a Galaxy S24 Ultra portrait.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ScoringScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val custom = ScoringPresets.PPR.copy(
        id = "u1",
        name = "My League",
        basedOn = ScoringPresets.PPR.id,
        receptionByPosition = mapOf(Position.TE to 1.5),
        yardageBonuses = listOf(YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0)),
    )

    @Test
    fun listLight() {
        val state = ListState(profiles = (ScoringPresets.all + custom).toImmutableList(), activeId = custom.id)
        compose.setContent { GridironTheme(darkTheme = false) { ScoringListScreen(state, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scoring_1_list.png")
    }

    @Test
    fun editorDark() {
        val state = custom.toEditing(readOnly = false)
        compose.setContent { GridironTheme(darkTheme = true) { ScoringEditScreen(state, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scoring_2_editor_dark.png")
    }

    @Test
    fun editorShowsAnInlineError() {
        val editing = custom.toEditing(readOnly = false)
        val state = editing.copy(weights = (editing.weights + (ScoringRule.PASS_TD to "-")).toImmutableMap())
        compose.setContent { GridironTheme(darkTheme = false) { ScoringEditScreen(state, {}, {}) } }

        compose.onNodeWithText("Enter a number").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scoring_3_error.png")
    }

    @Test
    fun presetIsReadOnly() {
        val state = ScoringPresets.PPR.toEditing(readOnly = true)
        compose.setContent { GridironTheme(darkTheme = false) { ScoringEditScreen(state, {}, {}) } }

        compose.onNodeWithText("Presets can't be edited. Duplicate this one from the list to make your own.").assertIsDisplayed()
        compose.onNodeWithTag("field:PASS_TD").assertIsNotEnabled()
    }
}
