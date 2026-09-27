package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KickingTest {
    private fun kicks(plays: List<Play>): List<PlayerWeek> = KickingAggregator().apply { plays.forEach(::add) }.rows()

    @Test
    fun `field goals land in their distance bucket, edges included`() {
        val r = kicks(
            listOf(
                fieldGoal("K1", 39.0, "made"), fieldGoal("K1", 40.0, "made"), fieldGoal("K1", 49.0, "made"),
                fieldGoal("K1", 50.0, "made"), fieldGoal("K1", 63.0, "missed"),
            ),
        ).row("K1")
        assertEquals(listOf(1.0, 2.0, 2.0), listOf(r["fg_att_0_39"], r["fg_att_40_49"], r["fg_att_50"]))
        assertEquals(listOf(1.0, 2.0, 1.0), listOf(r["fg_made_0_39"], r["fg_made_40_49"], r["fg_made_50"]))
        assertEquals(1.0, r["fg_missed"])
        assertEquals(listOf(5.0, 4.0), listOf(r["fg_att"], r["fg_made"]))
        assertEquals(1.0, r["g"])
    }

    @Test
    fun `a blocked field goal is a miss, and a kick with no distance counts as short`() {
        val r = kicks(listOf(fieldGoal("K1", 45.0, "blocked"), fieldGoal("K1", null, "made"))).row("K1")
        assertEquals(1.0, r["fg_att_40_49"])
        assertEquals(0.0, r["fg_made_40_49"])
        assertEquals(1.0, r["fg_missed"])
        assertEquals(1.0, r["fg_att_0_39"])
        assertEquals(1.0, r["fg_made_0_39"])
    }

    @Test
    fun `an extra point is made only when it's good`() {
        val r = kicks(
            listOf(
                extraPoint("K1", "good"), extraPoint("K1", "good"), extraPoint("K1", "failed"),
                extraPoint("K1", "blocked"), extraPoint("K1", "aborted"),
            ),
        ).row("K1")
        assertEquals(listOf(5.0, 2.0, 3.0), listOf(r["xp_att"], r["xp_made"], r["xp_missed"]))
    }

    @Test
    fun `preseason kicks, kicks with no kicker and kickoffs don't count`() {
        val rows = kicks(
            listOf(
                fieldGoal("K1", 30.0, "made", seasonType = "PRE"),
                play(playType = "field_goal", fieldGoalAttempt = 1.0, kickDistance = 30.0, fieldGoalResult = "made"),
                play(playType = "kickoff", kicker = "K1"),
            ),
        )
        assertEquals(emptyList<PlayerWeek>(), rows)
    }

    @Test
    fun `each kicker's week is keyed by his team`() {
        val rows = kicks(
            listOf(
                fieldGoal("K1", 30.0, "made"),
                play(
                    playType = "field_goal", posteam = "BBB", defteam = "AAA", kicker = "K2",
                    fieldGoalAttempt = 1.0, kickDistance = 30.0, fieldGoalResult = "made",
                ),
            ),
        )
        assertEquals(mapOf("K1" to "AAA", "K2" to "BBB"), rows.associate { it.playerId to it.team })
    }
}
