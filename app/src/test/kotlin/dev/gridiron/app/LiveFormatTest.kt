package dev.gridiron.app

import dev.gridiron.core.data.InjuryRow
import dev.gridiron.core.data.live.LiveInjury
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class LiveFormatTest {
    @Test
    fun timesReadInThePhonesZone() {
        assertEquals("Sep 25, 7:01 PM", formatWhen(Instant.parse("2026-09-25T23:01:03Z"), ZoneId.of("America/New_York"), Locale.US))
    }

    @Test
    fun theReportGroupsByTeamAndAddsTheLatestOfficialPractice() {
        fun live(name: String, team: String?, playerId: String?) =
            LiveInjury("e-$name", playerId, name, team, "WR", "Questionable", "Q", null, null)

        val groups = injuryReport(
            listOf(live("Amy", "KC", "P1"), live("Bo", "BUF", "P2"), live("Cy", null, null), live("Di", "KC", "P3")),
            listOf(
                InjuryRow("P1", "Amy", "KC", "WR", 2, "Questionable", "Ankle", "Limited"),
                InjuryRow("P1", "Amy", "KC", "WR", 3, "Questionable", "Ankle", "Full"),
                InjuryRow("P3", "Di", "KC", "WR", 3, "Out", "Knee", null),
            ),
        )

        assertEquals(listOf("BUF", "KC", "—"), groups.map { it.team })
        assertEquals(listOf("Amy", "Di"), groups[1].lines.map { it.injury.name })
        assertEquals(listOf("Full · Wk 3", null), groups[1].lines.map { it.practice })
        assertEquals(listOf<String?>(null), groups[0].lines.map { it.practice })
    }
}
