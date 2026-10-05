package dev.gridiron.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WidgetStateTest {
    @Test
    fun `the state survives a round trip and the widget's lines read from it`() {
        val at = Instant.parse("2026-10-11T16:30:00Z")
        val state = WidgetState("Wk 6 · 112.4 vs Rivals 104.8 · 64% to win", at, "Kay Sea is Out. Start Eagle (11.0 pts) at WR instead.", at)
        assertEquals(state, WidgetState.decode(state.encode()))
        val (lineup, alert) = LineupWidget.lines(state, ZoneOffset.UTC)
        assertEquals("Wk 6 · 112.4 vs Rivals 104.8 · 64% to win · Sun 4:30 PM", lineup)
        assertEquals("⚠ Kay Sea is Out. Start Eagle (11.0 pts) at WR instead.", alert)
    }

    @Test
    fun `an empty or missing file reads as nothing yet`() {
        assertEquals(WidgetState.EMPTY, WidgetState.decode(""))
        assertEquals("Open My lineup to see your week here" to "No lineup problems found", LineupWidget.lines(WidgetState.EMPTY))
    }
}
