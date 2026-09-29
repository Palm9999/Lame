package dev.gridiron.core.statquery

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE_PER_ATT
import dev.gridiron.core.statquery.StatColumn.NGS_SEPARATION
import dev.gridiron.core.statquery.StatColumn.NGS_TIME_TO_THROW
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NgsColumnsTest {
    private lateinit var db: FixtureDb

    private val ngsSepW = Component("ngs_sep_w")
    private val ngsTargets = Component("ngs_targets")
    private val ngsTttW = Component("ngs_ttt_w")
    private val ngsAttempts = Component("ngs_attempts")
    private val ngsRyoe = Component("ngs_ryoe")
    private val ngsCarries = Component("ngs_carries")

    @BeforeEach
    fun setUp() {
        db = FixtureDb()
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun spec(vararg columns: StatColumn) =
        StatQuerySpec(season = 2025, weeks = WeekRange(1, 2), columns = columns.toList())

    @Test
    fun `a two-week range is the weighted average, not the mean of the weekly averages`() {
        db.player("wr1", "Alpha Receiver")
        // Week 1: 2.0 yd separation on 2 targets. Week 2: 4.0 on 8 targets.
        db.week("wr1", 1, ngsSepW to 2.0 * 2, ngsTargets to 2)
        db.week("wr1", 2, ngsSepW to 4.0 * 8, ngsTargets to 8)
        // 36 / 10 = 3.6, where the mean of 2.0 and 4.0 would be 3.0.
        assertEquals(3.6, db.grid(spec(NGS_SEPARATION)).single().value(NGS_SEPARATION)!!, 1e-9)
    }

    @Test
    fun `RYOE per attempt divides the summed RYOE by the summed NGS carries`() {
        db.player("rb1", "Running Back", position = "RB")
        db.week("rb1", 1, ngsRyoe to 10.0, ngsCarries to 20)
        db.week("rb1", 2, ngsRyoe to -4.0, ngsCarries to 10)
        assertEquals(0.2, db.grid(spec(NGS_RYOE_PER_ATT)).single().value(NGS_RYOE_PER_ATT)!!, 1e-9)
    }

    @Test
    fun `a player with no NGS rows has an empty cell, not zero`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week("qb1", 1, Components.ATTEMPTS to 30)
        assertNull(db.grid(spec(NGS_TIME_TO_THROW)).single().value(NGS_TIME_TO_THROW))
    }

    @Test
    fun `NGS ratios use the matching sample columns`() {
        assertEquals(StatColumn.ATTEMPTS, NGS_TIME_TO_THROW.sample)
        assertEquals(StatColumn.CARRIES, NGS_RYOE_PER_ATT.sample)
        assertEquals(StatColumn.TARGETS, NGS_SEPARATION.sample)
    }
}
