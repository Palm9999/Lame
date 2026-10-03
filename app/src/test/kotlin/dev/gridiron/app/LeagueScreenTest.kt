package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class LeagueScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val body = """
        {"seasonId":2026,"scoringPeriodId":4,"settings":{"name":"Sunday League"},"members":[],
         "teams":[
          {"id":1,"name":"Rivals","record":{"overall":{"wins":1,"losses":3,"pointsFor":400.0,"pointsAgainst":300.0}},"roster":{"entries":[]}},
          {"id":2,"name":"Mine","record":{"overall":{"wins":3,"losses":1,"pointsFor":420.5,"pointsAgainst":300.0}},
           "roster":{"entries":[{"playerId":-16012,"lineupSlotId":16,"playerPoolEntry":{"player":{}}}]}}
         ]}
    """.trimIndent()

    @Test
    fun saveAndSyncShowsStandingsAndLetsMeChooseMyTeam() {
        val prefs = FakePrefsSource()
        val players = PlayerDirectory(
            object : QueryExecutor {
                override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
            },
        )
        val repo = FantasyLeagueRepository(prefs, { _, _ -> body }, players, tmp.root)
        compose.setContent { GridironTheme { LeagueScreen(repo, 2026, onBack = {}, onPlayer = {}) } }

        compose.onNodeWithTag("leagueId").performTextInput("42")
        compose.onNodeWithTag("leagueSync").performClick()
        compose.waitUntil(5_000) { repo.league.value != null }
        compose.waitForIdle()

        compose.onNodeWithText("1. Mine").assertExists()
        compose.onNodeWithText("2. Rivals").assertExists()

        compose.onNodeWithText("1. Mine").performClick()
        compose.onNodeWithText("This is my team").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Mine"), prefs.current.rosters.map { it.name })
        compose.onNodeWithText("1. Mine ★").assertExists()
    }

    @Test
    fun addingASecondLeagueSwitchesToItAndTheFirstCanBeChosenAgainOrRemoved() {
        val prefs = FakePrefsSource()
        val players = PlayerDirectory(
            object : QueryExecutor {
                override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
            },
        )
        val repo = FantasyLeagueRepository(
            prefs, { url, _ -> if ("/leagues/43" in url) body.replace("Sunday League", "Work League") else body }, players, tmp.root,
        )
        compose.setContent { GridironTheme { LeagueScreen(repo, 2026, onBack = {}, onPlayer = {}) } }

        compose.onNodeWithTag("leagueId").performTextInput("42")
        compose.onNodeWithTag("leagueSync").performClick()
        compose.waitUntil(5_000) { repo.league.value?.leagueId == "42" }
        compose.onNodeWithTag("leagueId").performTextInput("43")
        compose.onNodeWithTag("leagueSync").performClick()
        compose.waitUntil(5_000) { repo.league.value?.leagueId == "43" }
        compose.waitForIdle()
        compose.onNodeWithText("Work League ✓").assertExists()
        compose.onNodeWithText("Sunday League").assertExists()

        compose.onNodeWithTag("league:42").performClick()
        compose.waitUntil(5_000) { repo.league.value?.leagueId == "42" }
        compose.waitForIdle()
        compose.onNodeWithText("Sunday League ✓").assertExists()

        compose.onNodeWithTag("leagueRemove:42").performClick()
        compose.waitUntil(5_000) { repo.league.value?.leagueId == "43" }
        assertEquals(listOf("43"), prefs.current.espnLeagues.map { it.leagueId })
    }
}
