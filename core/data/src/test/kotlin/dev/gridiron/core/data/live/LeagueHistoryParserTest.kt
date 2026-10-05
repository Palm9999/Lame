package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Hand-built from memory of ESPN's league views; unverified against a live league. */
class LeagueHistoryParserTest {
    private val season = """
        {"seasonId":2023,
         "settings":{"name":"Sunday League","scheduleSettings":{"playoffTeamCount":4}},
         "members":[{"id":"{A}","displayName":"Ann"},{"id":"{B}","displayName":"Bo"}],
         "teams":[
           {"id":1,"name":"Ann's Team","primaryOwner":"{A}","owners":["{A}","{C}"],"rankCalculatedFinal":1,"playoffSeed":2,
            "record":{"overall":{"wins":9,"losses":5,"ties":0,"pointsFor":1500.5,"pointsAgainst":1400.0}}},
           {"id":2,"name":"Bo Knows","primaryOwner":"{B}","rankCalculatedFinal":0,"playoffSeed":5,
            "record":{"overall":{"wins":5,"losses":8,"ties":1,"pointsFor":1300.0,"pointsAgainst":1450.25}}},
           {"id":3,"name":"Orphans","record":{"overall":{"wins":7,"losses":7,"ties":0,"pointsFor":1400.0,"pointsAgainst":1400.0}}}
         ],
         "schedule":[
           {"matchupPeriodId":1,"winner":"HOME","playoffTierType":"NONE","home":{"teamId":1,"totalPoints":120.5},"away":{"teamId":2,"totalPoints":99.0}},
           {"matchupPeriodId":2,"winner":"UNDECIDED","playoffTierType":"NONE","home":{"teamId":3,"totalPoints":0}},
           {"matchupPeriodId":15,"winner":"AWAY","playoffTierType":"WINNERS_BRACKET","home":{"teamId":2,"totalPoints":88.0},"away":{"teamId":1,"totalPoints":101.0}},
           {"matchupPeriodId":15,"winner":"HOME","playoffTierType":"LOSERS_CONSOLATION_LADDER","home":{"teamId":3,"totalPoints":90.0},"away":{"teamId":2,"totalPoints":80.0}}
         ],
         "status":{"previousSeasons":[2021,2022]}}
    """.trimIndent()

    @Test
    fun `parses teams, owners and final ranks`() {
        val s = EspnHistoryParser.parse(season, 2023)
        assertEquals(2023, s.season)
        assertEquals(4, s.playoffTeams)
        assertEquals(mapOf("{A}" to "Ann", "{B}" to "Bo"), s.members)
        val ann = s.teams.single { it.id == 1 }
        assertEquals(HistoryTeam(1, "Ann's Team", "{A}", 9, 5, 0, 1500.5, 1400.0, 1, 2), ann)
        assertNull(s.teams.single { it.id == 2 }.finalRank)
        assertNull(s.teams.single { it.id == 3 }.ownerId)
        assertNull(s.teams.single { it.id == 3 }.playoffSeed)
    }

    @Test
    fun `games keep regular season and winners bracket only`() {
        val games = EspnHistoryParser.parse(season, 2023).games
        assertEquals(
            listOf(
                HistoryGame(1, 1, 2, 120.5, 99.0, "HOME", playoff = false),
                HistoryGame(2, 3, null, 0.0, 0.0, "UNDECIDED", playoff = false),
                HistoryGame(15, 2, 1, 88.0, 101.0, "AWAY", playoff = true),
            ),
            games,
        )
    }

    @Test
    fun `a pre-2018 array answer parses`() {
        assertEquals(2023, EspnHistoryParser.parse("[$season]", 2023).season)
        assertTrue(EspnHistoryParser.url("42", 2017).contains("/leagueHistory/42?seasonId=2017"))
        assertTrue(EspnHistoryParser.url("42", 2018).contains("/seasons/2018/segments/0/leagues/42?"))
        assertTrue("view=mMatchupScore" in EspnHistoryParser.url("42", 2017) && "view=mStandings" in EspnHistoryParser.url("42", 2018))
    }

    @Test
    fun `no schedule is a season without games`() {
        val s = EspnHistoryParser.parse("""{"seasonId":2019,"teams":[{"id":1,"name":"A"}]}""", 2019)
        assertEquals(emptyList<HistoryGame>(), s.games)
        assertEquals(1, s.teams.size)
        assertThrows<LiveFormatException> { EspnHistoryParser.parse("""{"seasonId":2019}""", 2019) }
        assertThrows<LiveFormatException> { EspnHistoryParser.parse("<html>", 2019) }
    }

    @Test
    fun `previous seasons from status`() {
        assertEquals(listOf(2021, 2022), EspnHistoryParser.previousSeasons(season))
        assertEquals(emptyList<Int>(), EspnHistoryParser.previousSeasons("""{"teams":[]}"""))
        assertEquals(emptyList<Int>(), EspnHistoryParser.previousSeasons("nope"))
    }

    @Test
    fun `json round trip`() {
        val s = EspnHistoryParser.parse(season, 2023)
        assertEquals(s, historySeasonFromJson(s.toJson()))
        assertNull(historySeasonFromJson("garbage"))
    }
}
