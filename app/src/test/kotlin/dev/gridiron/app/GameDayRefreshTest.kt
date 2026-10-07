package dev.gridiron.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class GameDayRefreshTest {
    private fun next(now: String) = nextGameDayRefresh(ZonedDateTime.parse(now)).toString()

    @Test
    fun `the next refresh is Thursday 3 pm, Saturday 10 pm, Sunday 9 am or Monday 3 pm, whichever comes first`() {
        // Tuesday 2026-10-06 → Thursday 10-08 at 15:00.
        assertEquals("2026-10-08T15:00-04:00[America/New_York]", next("2026-10-06T12:00-04:00[America/New_York]"))
        // Thursday 16:00 → Saturday 22:00.
        assertEquals("2026-10-10T22:00-04:00[America/New_York]", next("2026-10-08T16:00-04:00[America/New_York]"))
        // Saturday 23:00 → Sunday 9:00.
        assertEquals("2026-10-11T09:00-04:00[America/New_York]", next("2026-10-10T23:00-04:00[America/New_York]"))
        // Sunday 9:00 exactly → Monday 15:00.
        assertEquals("2026-10-12T15:00-04:00[America/New_York]", next("2026-10-11T09:00-04:00[America/New_York]"))
        // Monday 15:00 exactly → Thursday.
        assertEquals("2026-10-15T15:00-04:00[America/New_York]", next("2026-10-12T15:00-04:00[America/New_York]"))
    }

    @Test
    fun `picked times replace the defaults`() {
        val times = mapOf(java.time.DayOfWeek.SUNDAY to java.time.LocalTime.of(11, 30))
        // Tuesday → Sunday 11:30, the only time left.
        assertEquals("2026-10-11T11:30-04:00[America/New_York]", nextGameDayRefresh(ZonedDateTime.parse("2026-10-06T12:00-04:00[America/New_York]"), times).toString())
    }

    @Test
    fun `the offseason waits for September`() {
        // Mid-February still runs; a March date waits for the first Thursday from September 1 (2027-09-02).
        assertEquals("2027-02-14T09:00-05:00[America/New_York]", next("2027-02-13T23:00-05:00[America/New_York]"))
        assertEquals("2027-09-02T15:00-04:00[America/New_York]", next("2027-03-02T12:00-05:00[America/New_York]"))
    }
}
