package dev.gridiron.core.datastore

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ESPN_POINTS_ALLOWED
import dev.gridiron.core.model.ESPN_YARDS_ALLOWED
import dev.gridiron.core.model.PointsAllowedTier
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.ScoringTier
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
import org.junit.jupiter.api.Assertions.assertNull
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
            {"formatVersion":2,"activeProfileId":"u2","profiles":[
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

    @Test
    fun `rosters survive a reopen, and an older file has none`() {
        assertEquals(emptyList<Roster>(), withStore { it.prefs.first() }.rosters)
        val rosters = listOf(Roster("r1", "Home league", listOf("p1", "p2")), Roster("r2", "Work", emptyList()))
        withStore { store -> store.update { it.copy(rosters = rosters) } }
        assertEquals(rosters, withStore { it.prefs.first() }.rosters)
    }

    @Test
    fun `invalid rosters are dropped and duplicate players collapsed`() {
        file.writeText(
            """
            {"formatVersion":1,"rosters":[
              {"id":"r1","name":" ","playerIds":["p1"]},
              {"id":"r2","name":"Home","playerIds":["p1","p1"," ","p2"]},
              {"id":"r2","name":"Copy","playerIds":[]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(Roster("r2", "Home", listOf("p1", "p2"))), withStore { it.prefs.first() }.rosters)
    }

    @Test
    fun `the Odds API key survives a restart`() {
        withStore { it.update { p -> p.copy(oddsApiKey = "abc123") } }
        assertEquals("abc123", withStore { it.prefs.first().oddsApiKey })
    }

    @Test
    fun `a file from before the key existed reads as no key`() {
        file.writeText("""{"formatVersion": 1}""")
        assertNull(withStore { it.prefs.first().oddsApiKey })
    }

    @Test
    fun `the Odds API key never shows when prefs are printed`() {
        val prefs = UserPrefs.DEFAULT.copy(oddsApiKey = "abc123secret")
        assertFalse("abc123secret" in prefs.toString(), prefs.toString())
        assertTrue("oddsApiKey=…" in prefs.toString(), prefs.toString())
        assertTrue("oddsApiKey=null" in UserPrefs.DEFAULT.toString())
    }

    @Test
    fun `a profile saved before kicking and defense scoring gets the defaults once`() {
        file.writeText(
            """{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league", "weights": {"PASS_TD": 6.0}}], "activeProfileId": "u1"}""",
        )
        val migrated = withStore { it.prefs.first() }.profiles.single()

        assertEquals(6.0, migrated.weight(ScoringRule.PASS_TD))
        for ((rule, value) in ScoringPresets.KICKING_AND_DEFENSE) assertEquals(value, migrated.weight(rule), rule.name)
        assertEquals(ESPN_POINTS_ALLOWED, migrated.pointsAllowedTiers)
    }

    @Test
    fun `after the migration, a zeroed rule and no tiers stay that way`() {
        file.writeText("""{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league"}], "activeProfileId": "u1"}""")
        withStore { store ->
            store.update { p ->
                val old = p.profiles.single()
                p.copy(profiles = listOf(old.copy(weights = old.weights + (ScoringRule.FG_MISSED to 0.0) - ScoringRule.DST_SAFETY, pointsAllowedTiers = emptyList())))
            }
        }
        val reread = withStore { it.prefs.first() }.profiles.single()

        assertEquals(0.0, reread.weight(ScoringRule.FG_MISSED))
        assertEquals(0.0, reread.weight(ScoringRule.DST_SAFETY))
        assertEquals(emptyList<PointsAllowedTier>(), reread.pointsAllowedTiers)
        assertTrue(file.readText().contains("\"formatVersion\":3"), file.readText())
    }

    @Test
    fun `a profile's tiers survive a reopen, and invalid tiers are dropped with the profile kept`() {
        val tiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(21, 0.0))
        withStore { store -> store.update { it.copy(profiles = listOf(espnLeague.copy(pointsAllowedTiers = tiers))) } }
        assertEquals(tiers, withStore { it.prefs.first() }.profiles.single().pointsAllowedTiers)

        file.writeText(
            """{"formatVersion": 2, "profiles": [{"id": "u1", "name": "Odd", "pointsAllowed": [{"min": 7, "points": 3.0}]}]}""",
        )
        val odd = withStore { it.prefs.first() }.profiles.single()
        assertEquals("Odd", odd.name)
        assertEquals(emptyList<PointsAllowedTier>(), odd.pointsAllowedTiers)
    }

    @Test
    fun `a version-2 profile gets ESPN's yards tiers once, and a saved empty list stays empty at version 3`() {
        file.writeText(
            """{"formatVersion": 2, "profiles": [{"id": "u1", "name": "League", "pointsAllowed": [{"min": 0, "points": 8.0}]}], "activeProfileId": "u1"}""",
        )
        val migrated = withStore { it.prefs.first() }.profiles.single()
        assertEquals(ESPN_YARDS_ALLOWED, migrated.yardsAllowedTiers)
        assertEquals(listOf(ScoringTier(0, 8.0)), migrated.pointsAllowedTiers) // points tiers untouched

        withStore { store -> store.update { p -> p.copy(profiles = listOf(migrated.copy(yardsAllowedTiers = emptyList()))) } }
        assertTrue(file.readText().contains("\"formatVersion\":3"), file.readText())
        assertEquals(emptyList<ScoringTier>(), withStore { it.prefs.first() }.profiles.single().yardsAllowedTiers)
    }

    @Test
    fun `a version-1 profile gets both tier lists, and yards tiers survive a reopen`() {
        file.writeText("""{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league"}], "activeProfileId": "u1"}""")
        val migrated = withStore { it.prefs.first() }.profiles.single()
        assertEquals(ESPN_POINTS_ALLOWED, migrated.pointsAllowedTiers)
        assertEquals(ESPN_YARDS_ALLOWED, migrated.yardsAllowedTiers)

        val tiers = listOf(ScoringTier(0, 6.0), ScoringTier(250, 1.5), ScoringTier(400, -2.0))
        withStore { store -> store.update { it.copy(profiles = listOf(migrated.copy(yardsAllowedTiers = tiers))) } }
        assertEquals(tiers, withStore { it.prefs.first() }.profiles.single().yardsAllowedTiers)
    }

    @Test
    fun `an invalid stored yards list is dropped with the profile kept`() {
        file.writeText(
            """{"formatVersion": 3, "profiles": [{"id": "u1", "name": "Odd", "yardsAllowed": [{"min": 100, "points": 3.0}]}]}""",
        )
        val odd = withStore { it.prefs.first() }.profiles.single()
        assertEquals("Odd", odd.name)
        assertEquals(emptyList<ScoringTier>(), odd.yardsAllowedTiers)
    }

    private val wrView = GridPreset(
        id = "g1",
        name = "Buy-low WRs",
        packId = "FANTASY",
        sort = "FANTASY_POINTS",
        direction = "DESCENDING",
        position = "WR",
        perGame = true,
        teams = setOf("KC", "BUF"),
        minSnapShare = 0.5,
        filters = listOf(
            PresetFilter("TARGETS", PresetFilterKind.AT_LEAST, 20.0),
            PresetFilter("SNAP_SHARE", PresetFilterKind.BETWEEN, 0.5, 0.9),
        ),
        weeks = PresetWeeks.LastN(4),
    )

    @Test
    fun `gridPresets round trip, and an older file has none`() {
        assertEquals(emptyList<GridPreset>(), withStore { it.prefs.first() }.gridPresets)
        val all = listOf(wrView, wrView.copy(id = "g2", name = "Season", weeks = PresetWeeks.WholeSeason, filters = emptyList(), teams = emptySet(), minSnapShare = null))
        withStore { store -> store.update { it.copy(gridPresets = all) } }
        assertEquals(all, withStore { it.prefs.first() }.gridPresets)
        assertTrue(file.readText().contains("\"formatVersion\":3"), file.readText())
    }

    @Test
    fun `a file from before presets existed reads as no presets`() {
        file.writeText("""{"formatVersion": 3}""")
        assertEquals(emptyList<GridPreset>(), withStore { it.prefs.first() }.gridPresets)
    }

    @Test
    fun `bad preset entries are dropped and the rest kept`() {
        file.writeText(
            """
            {"formatVersion":3,"gridPresets":[
              {"id":"a","name":" ","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL","weeks":{"kind":"WHOLE_SEASON"}},
              {"id":"b","name":"Zero","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL","weeks":{"kind":"LAST_N","n":0}},
              {"id":"c","name":"Bad between","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL",
               "filters":[{"column":"TARGETS","kind":"BETWEEN","a":1.0}],"weeks":{"kind":"WHOLE_SEASON"}},
              {"id":"d","name":"Keeper","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL","weeks":{"kind":"LAST_N","n":4}}
            ]}
            """.trimIndent(),
        )
        val kept = withStore { it.prefs.first() }.gridPresets
        assertEquals(listOf("d"), kept.map { it.id })
        assertEquals(PresetWeeks.LastN(4), kept.single().weeks)
    }

    @Test
    fun `duplicate preset names keep the first`() {
        val json = { id: String, name: String ->
            """{"id":"$id","name":"$name","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL","weeks":{"kind":"WHOLE_SEASON"}}"""
        }
        file.writeText("""{"formatVersion":3,"gridPresets":[${json("a", "Deep")},${json("b", "deep")},${json("c", "Other")}]}""")
        assertEquals(listOf("a", "c"), withStore { it.prefs.first() }.gridPresets.map { it.id })
    }

    @Test
    fun `presets past the limit are dropped on read`() {
        val many = (1..MAX_PRESETS + 1).joinToString(",") {
            """{"id":"p$it","name":"View $it","packId":"FANTASY","sort":"FANTASY_POINTS","direction":"DESCENDING","position":"ALL","weeks":{"kind":"WHOLE_SEASON"}}"""
        }
        file.writeText("""{"formatVersion":3,"gridPresets":[$many]}""")
        val kept = withStore { it.prefs.first() }.gridPresets
        assertEquals(MAX_PRESETS, kept.size)
        assertEquals("p$MAX_PRESETS", kept.last().id)
    }
}
