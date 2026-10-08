package dev.gridiron.app

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WinLineStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun aWeeksLineKeepsEachNewChanceAndANewWeekStartsAgain() {
        val store = WinLineStore(dir.newFile("winline.txt"))
        store.record(2026, 6, 0.61)
        store.record(2026, 6, 0.61) // the same chance again adds nothing
        store.record(2026, 6, null) // a read
        assertEquals(listOf(0.61, 0.72), store.record(2026, 6, 0.72))
        assertEquals(listOf(0.40), store.record(2026, 7, 0.40))
        assertEquals(emptyList<Double>(), store.record(2026, 6, null))
    }
}
