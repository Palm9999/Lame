package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PositionTest {
    @Test
    fun `a team defense reads D-ST and every other code reads as stored`() {
        assertEquals("D/ST", Position.label("DST"))
        assertEquals(listOf("QB", "RB", "FB", "WR", "TE", "K"), listOf("QB", "RB", "FB", "WR", "TE", "K").map(Position::label))
        assertEquals("LS", Position.label("LS"))
    }
}
