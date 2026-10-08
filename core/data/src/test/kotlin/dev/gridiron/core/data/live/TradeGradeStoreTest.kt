package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TradeGradeStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a trade keeps its first grade under each profile, across reopenings`() {
        val file = File(dir, "grades.txt")
        assertEquals(mapOf("t1" to mapOf(1 to 5.5, 2 to -5.5)), TradeGradeStore(file).keep("ppr", mapOf("t1" to mapOf(1 to 5.5, 2 to -5.5))))
        val later = TradeGradeStore(file).keep("ppr", mapOf("t1" to mapOf(1 to -9.0, 2 to 9.0), "t2" to mapOf(3 to 1.0)))
        assertEquals(mapOf(1 to 5.5, 2 to -5.5), later["t1"])
        assertEquals(mapOf(3 to 1.0), later["t2"])
        assertEquals(mapOf(1 to -9.0, 2 to 9.0), TradeGradeStore(file).keep("half", mapOf("t1" to mapOf(1 to -9.0, 2 to 9.0)))["t1"])
    }
}
