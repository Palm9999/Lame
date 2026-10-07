package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FtnPacksTest {
    @Test
    fun `FTN Passing leads with play-action rate and ranks by dropbacks`() {
        assertEquals("FTN Passing", StatPack.FTN_PASSING.label)
        assertEquals(
            listOf(
                StatColumn.FTN_PLAY_ACTION_RATE, StatColumn.FTN_BLITZ_RATE, StatColumn.FTN_OUT_OF_POCKET_RATE,
                StatColumn.FTN_THROWAWAY_RATE, StatColumn.FTN_INT_WORTHY_RATE, StatColumn.FTN_SCREEN_RATE,
                StatColumn.FTN_RPO_RATE, StatColumn.FTN_MOTION_RATE, StatColumn.FTN_NO_HUDDLE_RATE, StatColumn.DROPBACKS,
            ),
            StatPack.FTN_PASSING.columns,
        )
        assertEquals(StatColumn.FTN_PLAY_ACTION_RATE, StatPack.FTN_PASSING.defaultSort)
        assertEquals(StatColumn.DROPBACKS, StatPack.FTN_PASSING.population)
    }

    @Test
    fun `FTN Receiving leads with catchable rate and ranks by targets`() {
        assertEquals("FTN Receiving", StatPack.FTN_RECEIVING.label)
        assertEquals(
            listOf(
                StatColumn.FTN_CATCHABLE_RATE, StatColumn.FTN_DROP_RATE, StatColumn.FTN_CONTESTED_RATE,
                StatColumn.FTN_DROPS, StatColumn.FTN_CREATED_REC, StatColumn.FTN_SCREEN_TARGET_RATE,
                StatColumn.FTN_MOTION_TARGET_RATE, StatColumn.TARGETS,
            ),
            StatPack.FTN_RECEIVING.columns,
        )
        assertEquals(StatColumn.FTN_CATCHABLE_RATE, StatPack.FTN_RECEIVING.defaultSort)
        assertEquals(StatColumn.TARGETS, StatPack.FTN_RECEIVING.population)
    }

    @Test
    fun `FTN Rushing ranks the box count by carries`() {
        assertEquals(listOf(StatColumn.FTN_AVG_BOX, StatColumn.CARRIES), StatPack.FTN_RUSHING.columns)
        assertEquals(StatColumn.CARRIES, StatPack.FTN_RUSHING.population)
    }

    @Test
    fun `the offense's chips offer the FTN packs and K and D-ST do not`() {
        for (chip in listOf(PositionFilter.ALL, PositionFilter.QB, PositionFilter.FLEX)) {
            assertEquals(true, StatPack.FTN_PASSING in chip.packs && StatPack.FTN_RECEIVING in chip.packs, chip.name)
        }
        assertEquals(false, StatPack.FTN_PASSING in PositionFilter.K.packs || StatPack.FTN_PASSING in PositionFilter.DST.packs)
    }
}
