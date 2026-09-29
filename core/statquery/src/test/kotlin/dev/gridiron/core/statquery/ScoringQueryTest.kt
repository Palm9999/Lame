package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.PointsAllowedTier
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.ScoringTier
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FPOE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import dev.gridiron.core.statquery.Components as C

private const val EPS = 1e-9

class ScoringQueryTest {
    private lateinit var db: FixtureDb

    @BeforeEach
    fun setUp() {
        db = FixtureDb()
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun fantasy(
        profile: ScoringProfile,
        vararg columns: StatColumn = arrayOf(FANTASY_POINTS),
        weeks: WeekRange = WeekRange(1, 18),
        mode: ValueMode = ValueMode.TOTAL,
        percentiles: Boolean = false,
    ) = StatQuerySpec(
        season = 2025, weeks = weeks, columns = columns.toList(), scoring = profile,
        mode = mode, percentiles = percentiles,
    )

    private fun custom(vararg weights: Pair<ScoringRule, Double>, bonuses: List<YardageBonus> = emptyList(),
                       reception: Map<Position, Double> = emptyMap()) =
        ScoringProfile("u1", "Custom", weights.toMap(), reception, bonuses)

    @Test
    fun `PPR scores a receiver's week`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 6, C.RECEIVING_YARDS to 85, C.RECEIVING_TDS to 1, C.FUMBLES_LOST to 1)
        val row = db.grid(fantasy(ScoringPresets.PPR)).single()
        // 6 + 8.5 + 6 - 2
        assertEquals(18.5, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `reception points follow the player's position`() {
        val profile = custom(ScoringRule.RECEPTION to 1.0, reception = mapOf(Position.TE to 1.5))
        db.player("te1", "Tight End", position = "TE")
        db.player("wr1", "Wide Out", position = "WR")
        db.week("te1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 1, C.RECEPTIONS to 4)
        val rows = db.grid(fantasy(profile)).associateBy { it.playerId }
        assertEquals(6.0, rows.getValue("te1").value(FANTASY_POINTS)!!, EPS)
        assertEquals(4.0, rows.getValue("wr1").value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `incompletions are attempts minus completions`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week("qb1", 1, C.ATTEMPTS to 30, C.COMPLETIONS to 20)
        val row = db.grid(fantasy(custom(ScoringRule.INCOMPLETION to -0.5))).single()
        assertEquals(-5.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `yardage bonuses apply per game at the range edges`() {
        val profile = custom(
            bonuses = listOf(
                YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0),
                YardageBonus(BonusStat.RUSHING_YARDS, 200, null, 6.0),
            ),
        )
        db.player("rb1", "Running Back", position = "RB")
        db.week("rb1", 1, C.RUSHING_YARDS to 99)
        db.week("rb1", 2, C.RUSHING_YARDS to 100)
        db.week("rb1", 3, C.RUSHING_YARDS to 199)
        db.week("rb1", 4, C.RUSHING_YARDS to 200)
        val row = db.grid(fantasy(profile)).single()
        // weeks 2 and 3 earn 3; week 4 earns 6. The 598-yard range total earns nothing extra.
        assertEquals(12.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `combined yardage bonus counts receiving yards when there are no rushing yards`() {
        val profile = custom(bonuses = listOf(YardageBonus(BonusStat.RUSH_REC_YARDS, 100, null, 2.0)))
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEIVING_YARDS to 110)
        assertEquals(2.0, db.grid(fantasy(profile)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `expected points use expected components and FPOE is the difference`() {
        db.player("wr1", "Alpha Receiver")
        db.week(
            "wr1", 1,
            C.RECEPTIONS to 6, C.RECEIVING_YARDS to 85, C.RECEIVING_TDS to 1, C.FUMBLES_LOST to 1,
            C.X_RECEPTIONS to 5.5, C.X_RECEIVING_YARDS to 70, C.X_RECEIVING_TDS to 0.5,
        )
        val row = db.grid(fantasy(ScoringPresets.PPR, FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE)).single()
        assertEquals(18.5, row.value(FANTASY_POINTS)!!, EPS)
        // 5.5 + 7.0 + 3.0; fumbles have no expectation
        assertEquals(15.5, row.value(EXPECTED_FANTASY_POINTS)!!, EPS)
        assertEquals(3.0, row.value(FPOE)!!, EPS)
    }

    @Test
    fun `per game divides fantasy points by games`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 2, C.RECEPTIONS to 8)
        val row = db.grid(fantasy(ScoringPresets.PPR, mode = ValueMode.PER_GAME)).single()
        assertEquals(6.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a player who played but scored nothing has zero points, not null`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.TARGETS to 1)
        assertEquals(0.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `negative points sort and rank below zero`() {
        val profile = custom(ScoringRule.INCOMPLETION to -1.0, ScoringRule.PASS_TD to 4.0)
        db.player("qb1", "Good Passer", position = "QB")
        db.player("qb2", "Bad Passer", position = "QB")
        db.week("qb1", 1, C.PASSING_TDS to 3, C.ATTEMPTS to 30, C.COMPLETIONS to 25) // 12 - 5 = 7
        db.week("qb2", 1, C.ATTEMPTS to 30, C.COMPLETIONS to 10) // -20
        val rows = db.grid(fantasy(profile, percentiles = true))
        assertEquals(listOf("qb1", "qb2"), rows.map { it.playerId })
        assertEquals(-20.0, rows[1].value(FANTASY_POINTS)!!, EPS)
        assertEquals(1.0, rows[0].percentile(FANTASY_POINTS)!!, EPS)
        assertEquals(0.0, rows[1].percentile(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `weeks outside the range are not scored`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 2, C.RECEPTIONS to 100)
        val row = db.grid(fantasy(ScoringPresets.PPR, weeks = WeekRange(1, 1))).single()
        assertEquals(4.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `fantasy columns need a profile`() {
        assertThrows<IllegalArgumentException> {
            StatQuerySpec(2025, WeekRange(1, 18), listOf(StatColumn.TARGETS), sort = listOf(Sort(FANTASY_POINTS)))
        }
    }

    @Test
    fun `every rule is mapped to components`() {
        assertEquals(ScoringRule.entries.toSet(), RULE_INPUTS.keys)
        assertEquals(BonusStat.entries.toSet(), BONUS_INPUTS.keys)
        assertTrue(SCORING_COMPONENTS.none { it == C.GAMES })
    }

    @Test
    fun `count works with a fantasy filter`() {
        db.player("wr1", "Alpha Receiver")
        db.player("wr2", "Beta Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 10)
        db.week("wr2", 1, C.RECEPTIONS to 2)
        val spec = fantasy(ScoringPresets.PPR, StatColumn.TARGETS)
            .copy(filters = listOf(Filter(FANTASY_POINTS, Condition.AtLeast(5.0))))
        assertEquals(1, db.count(spec))
    }

    @Test
    fun `a kicker's week scores field goals by distance, extra points and misses, and no points-allowed tier`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.FG_MADE_40_49 to 1, C.FG_MADE_50 to 1, C.FG_MISSED to 1, C.XP_MADE to 3, C.XP_MISSED to 1)
        // 3 + 4 + 5 - 1 + 3 - 1. A tier for his missing points allowed would add ESPN's 5 for a shutout.
        assertEquals(13.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a team defense scores takeaways, TDs, safeties and each week's points-allowed tier`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.DST_SACKS to 3, C.DST_INTERCEPTIONS to 1, C.DST_FUMBLE_RECOVERIES to 1, C.DST_TDS to 1, C.DST_SAFETIES to 1, C.POINTS_ALLOWED to 10)
        db.week("DST_KC", 2, C.DST_SACKS to 1, C.POINTS_ALLOWED to 46)
        // Week 1: 3 + 2 + 2 + 6 + 2, and 7-13 allowed is 3: 18. Week 2: 1, and 46+ allowed is -5: -4.
        assertEquals(14.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `each week scores its own tier, never the tier of the weeks' total`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 17)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 18)
        db.week("DST_KC", 3, C.POINTS_ALLOWED to 0)
        // 1 + 0 + 5. The total, 35, would be one tier worth -5.
        assertEquals(6.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own tiers score points allowed, and no tiers score none`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 20)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 21)
        val yahoo = custom().copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(21, 0.0)))
        assertEquals(1.0, db.grid(fantasy(yahoo)).single().value(FANTASY_POINTS)!!, EPS)
        assertEquals(0.0, db.grid(fantasy(custom())).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a team defense's week scores its yards tier as well as its points tier`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.DST_SACKS to 3, C.POINTS_ALLOWED to 10, C.YARDS_ALLOWED to 250)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 46, C.YARDS_ALLOWED to 560)
        db.week("DST_KC", 3, C.POINTS_ALLOWED to 0, C.YARDS_ALLOWED to 0)
        // Week 1: 3 sacks, 7-13 allowed is 3, 200-299 yards is 2: 8. Week 2: -5 and -7: -12. Week 3, a shutout of 0 yards: 5 and 5: 10.
        assertEquals(6.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `each week scores its own yards tier, never the tier of the weeks' total`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.YARDS_ALLOWED to 299)
        db.week("DST_KC", 2, C.YARDS_ALLOWED to 300)
        // 2 + 0, and the points-allowed CASE sees NULL and adds nothing. The total, 599, would be one -7 tier.
        assertEquals(2.0, db.grid(fantasy(ScoringPresets.PPR.copy(pointsAllowedTiers = emptyList()))).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `no yards tiers score no yards, and a week without yards scores none`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.player("k1", "Place Kicker", position = "K")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 20, C.YARDS_ALLOWED to 100)
        db.week("k1", 1, C.FG_MADE_0_39 to 1)
        val none = ScoringPresets.PPR.copy(yardsAllowedTiers = emptyList())
        val rows = db.grid(fantasy(none)).associate { it.playerId to it.value(FANTASY_POINTS)!! }
        assertEquals(0.0, rows.getValue("DST_KC"), EPS) // 18-21 points allowed: 0, and no yards tiers
        assertEquals(3.0, rows.getValue("k1"), EPS)
        // With ESPN's tiers a kicker still scores no yards: his pivot column is NULL, not a shutout.
        assertEquals(3.0, db.grid(fantasy(ScoringPresets.PPR)).single { it.playerId == "k1" }.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own yards tiers score yards allowed, and equal profiles give identical SQL`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.YARDS_ALLOWED to 320)
        val own = ScoringPresets.PPR.copy(pointsAllowedTiers = emptyList(), yardsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(300, -2.0)))
        assertEquals(-2.0, db.grid(fantasy(own)).single().value(FANTASY_POINTS)!!, EPS)
        assertEquals(StatQueryBuilder.grid(fantasy(own)), StatQueryBuilder.grid(fantasy(own.copy())))
    }

    @Test
    fun `a kicker who also ran scores both in the same week`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.RUSHING_YARDS to 20)
        // 3 + 2
        assertEquals(5.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own kicking weights replace the presets', zero included`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_50 to 1, C.FG_MISSED to 2)
        val profile = custom(ScoringRule.FG_MADE_50 to 6.0, ScoringRule.FG_MISSED to 0.0)
        assertEquals(6.0, db.grid(fantasy(profile)).single().value(FANTASY_POINTS)!!, EPS)
    }
}
