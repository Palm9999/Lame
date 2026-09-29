package dev.gridiron.core.statquery

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn.FTN_BLITZ_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CATCHABLE_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CONTESTED_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CREATED_REC
import dev.gridiron.core.statquery.StatColumn.FTN_DROPS
import dev.gridiron.core.statquery.StatColumn.FTN_DROP_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_INT_WORTHY_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_OUT_OF_POCKET_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_PLAY_ACTION_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_THROWAWAY_RATE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FtnColumnsTest {
    private lateinit var db: FixtureDb

    @BeforeEach
    fun setUp() {
        db = FixtureDb()
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun spec(vararg columns: StatColumn) =
        StatQuerySpec(season = 2025, weeks = WeekRange(1, 2), columns = columns.toList())

    @Test
    fun `a two-week range is the count-weighted rate, not the mean of the weekly rates`() {
        db.player("wr1", "Alpha Receiver")
        // Week 1: 1 drop on 2 targets (50%). Week 2: 1 drop on 8 targets (12.5%). 2 / 10 = 20%, where the mean is 31.25%.
        db.week("wr1", 1, Components.FTN_DROPS to 1, Components.FTN_TARGETS to 2)
        db.week("wr1", 2, Components.FTN_DROPS to 1, Components.FTN_TARGETS to 8)
        assertEquals(0.2, db.grid(spec(FTN_DROP_RATE)).single().value(FTN_DROP_RATE)!!, 1e-9)
    }

    @Test
    fun `QB rates divide the summed flagged dropbacks by the summed FTN dropbacks`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week("qb1", 1, Components.FTN_PA_DB to 3, Components.FTN_DROPBACKS to 10)
        db.week("qb1", 2, Components.FTN_PA_DB to 9, Components.FTN_DROPBACKS to 30)
        assertEquals(0.3, db.grid(spec(FTN_PLAY_ACTION_RATE)).single().value(FTN_PLAY_ACTION_RATE)!!, 1e-9)
    }

    @Test
    fun `interception-worthy rate is per attempt, not per dropback`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week("qb1", 1, Components.FTN_INT_WORTHY to 2, Components.FTN_ATTEMPTS to 20, Components.FTN_DROPBACKS to 25)
        assertEquals(0.1, db.grid(spec(FTN_INT_WORTHY_RATE)).single().value(FTN_INT_WORTHY_RATE)!!, 1e-9)
    }

    @Test
    fun `a week with no drops is a real zero, not an empty cell`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, Components.FTN_DROPS to 0, Components.FTN_TARGETS to 6)
        assertEquals(0.0, db.grid(spec(FTN_DROP_RATE)).single().value(FTN_DROP_RATE)!!, 1e-9)
        assertEquals(0.0, db.grid(spec(FTN_DROPS)).single().value(FTN_DROPS)!!, 1e-9)
    }

    @Test
    fun `a player with no FTN rows has an empty cell, not zero`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, Components.TARGETS to 8)
        assertNull(db.grid(spec(FTN_CATCHABLE_RATE)).single().value(FTN_CATCHABLE_RATE))
    }

    @Test
    fun `the other receiver rates read their own numerators`() {
        db.player("wr1", "Alpha Receiver")
        db.week(
            "wr1", 1,
            Components.FTN_TARGETS to 10, Components.FTN_CATCHABLE to 7, Components.FTN_CONTESTED to 3,
            Components.FTN_CREATED_REC to 2,
        )
        val row = db.grid(spec(FTN_CATCHABLE_RATE, FTN_CONTESTED_RATE, FTN_CREATED_REC)).single()
        assertEquals(0.7, row.value(FTN_CATCHABLE_RATE)!!, 1e-9)
        assertEquals(0.3, row.value(FTN_CONTESTED_RATE)!!, 1e-9)
        assertEquals(2.0, row.value(FTN_CREATED_REC)!!, 1e-9)
    }

    @Test
    fun `blitz, out-of-pocket and throwaway rates use FTN dropbacks`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week(
            "qb1", 1,
            Components.FTN_DROPBACKS to 40, Components.FTN_BLITZ_DB to 12, Components.FTN_OOP_DB to 8,
            Components.FTN_THROWAWAY to 2,
        )
        val row = db.grid(spec(FTN_BLITZ_RATE, FTN_OUT_OF_POCKET_RATE, FTN_THROWAWAY_RATE)).single()
        assertEquals(0.3, row.value(FTN_BLITZ_RATE)!!, 1e-9)
        assertEquals(0.2, row.value(FTN_OUT_OF_POCKET_RATE)!!, 1e-9)
        assertEquals(0.05, row.value(FTN_THROWAWAY_RATE)!!, 1e-9)
    }

    @Test
    fun `FTN ratios use the matching sample columns and direction`() {
        assertEquals(StatColumn.TARGETS, FTN_DROP_RATE.sample)
        assertEquals(StatColumn.TARGETS, FTN_CATCHABLE_RATE.sample)
        assertEquals(StatColumn.TARGETS, FTN_CONTESTED_RATE.sample)
        assertEquals(StatColumn.DROPBACKS, FTN_PLAY_ACTION_RATE.sample)
        assertEquals(StatColumn.DROPBACKS, FTN_BLITZ_RATE.sample)
        assertEquals(StatColumn.DROPBACKS, FTN_OUT_OF_POCKET_RATE.sample)
        assertEquals(StatColumn.DROPBACKS, FTN_THROWAWAY_RATE.sample)
        assertEquals(StatColumn.ATTEMPTS, FTN_INT_WORTHY_RATE.sample)
        assertFalse(FTN_DROP_RATE.higherIsBetter)
        assertFalse(FTN_DROPS.higherIsBetter)
        assertFalse(FTN_THROWAWAY_RATE.higherIsBetter)
        assertFalse(FTN_INT_WORTHY_RATE.higherIsBetter)
        assertTrue(FTN_CATCHABLE_RATE.higherIsBetter)
    }
}
