package dev.gridiron.core.datastore

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UserPrefsStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "user_prefs.json")

    /** Opens a store, runs [block], and closes the store so the file can be reopened. */
    private fun <T> withStore(block: suspend (UserPrefsStore) -> T): T = runBlocking {
        val job = Job()
        try {
            block(UserPrefsStore.create(file, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    private val espnLeague = ScoringPresets.PPR.copy(
        id = "u1",
        name = "ESPN league",
        receptionByPosition = mapOf(Position.TE to 1.5),
        yardageBonuses = listOf(YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0)),
        basedOn = ScoringPresets.PPR.id,
    )

    @Test
    fun `a missing file reads as the defaults`() {
        val prefs = withStore { it.prefs.first() }
        assertEquals(UserPrefs.DEFAULT, prefs)
        assertEquals(ScoringPresets.PPR, prefs.active)
    }

    @Test
    fun `profiles, active id and tray survive a reopen`() {
        val slot = CompareSlot("00-0039337", 2025, WeekRange(1, 8))
        withStore { store ->
            store.update { it.copy(profiles = listOf(espnLeague), activeProfileId = "u1", tray = listOf(slot, slot.copy(season = 2024))) }
        }
        val reread = withStore { it.prefs.first() }
        assertEquals(listOf(espnLeague), reread.profiles)
        assertEquals(espnLeague, reread.active)
        assertEquals(listOf(slot, slot.copy(season = 2024)), reread.tray)
        assertFalse(reread.resetNotice)
    }

    @Test
    fun `a corrupt file resets to defaults, keeps a copy and raises the notice`() {
        file.writeText("{ this is not json")
        val prefs = withStore { it.prefs.first() }
        assertEquals(UserPrefs.DEFAULT.copy(resetNotice = true), prefs)
        val kept = File(dir, "user_prefs.json.corrupt")
        assertTrue(kept.isFile)
        assertEquals("{ this is not json", kept.readText())
    }

    @Test
    fun `unknown rules and invalid entries are dropped, the rest kept`() {
        file.writeText(
            """
            {"formatVersion":1,"activeProfileId":"u2","profiles":[
              {"id":"u1","name":" ","weights":{"PASS_TD":6.0}},
              {"id":"u2","name":"Keeper","weights":{"PASS_TD":6.0,"KICK_FG_50":5.0},
               "receptionByPosition":{"TE":1.5,"K":9.0},
               "bonuses":[{"stat":"PASSING_YARDS","min":300,"points":3.0},{"stat":"PUNT_YARDS","min":1,"points":1.0}]}
            ],"tray":[{"playerId":"p1","season":2025,"firstWeek":1,"lastWeek":8},{"playerId":"p2","season":2025,"firstWeek":9,"lastWeek":3}],
            "futureField":true}
            """.trimIndent(),
        )
        val prefs = withStore { it.prefs.first() }
        val keeper = prefs.profiles.single()
        assertEquals("u2", keeper.id)
        assertEquals(mapOf(ScoringRule.PASS_TD to 6.0), keeper.weights)
        assertEquals(mapOf(Position.TE to 1.5), keeper.receptionByPosition)
        assertEquals(listOf(YardageBonus(BonusStat.PASSING_YARDS, 300, null, 3.0)), keeper.yardageBonuses)
        assertEquals(listOf(CompareSlot("p1", 2025, WeekRange(1, 8))), prefs.tray)
        assertEquals(keeper, prefs.active)
    }

    @Test
    fun `an active id that no longer exists falls back to PPR`() {
        val prefs = UserPrefs(profiles = emptyList(), activeProfileId = "gone", tray = emptyList())
        assertEquals(ScoringPresets.PPR, prefs.active)
    }

    @Test
    fun `the seasons choice survives a reopen, and an older file has none`() {
        assertEquals(null, withStore { it.prefs.first() }.seasons)
        withStore { store -> store.update { it.copy(seasons = SeasonChoice(listOf(2026, 2024), 2026)) } }
        assertEquals(SeasonChoice(listOf(2024, 2026), 2026), withStore { it.prefs.first() }.seasons)
    }
}
