package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
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
}
