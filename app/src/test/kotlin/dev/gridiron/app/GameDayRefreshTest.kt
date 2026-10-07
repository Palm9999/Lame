package dev.gridiron.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class GameDayRefreshTest {
    private fun next(now: String) = nextGameDayRefresh(ZonedDateTime.parse(now)).toString()

    @Test
    fun `the next refresh is Saturday 10 pm or Sunday 9 am, whichever comes first`() {
        // Tuesday 2026-10-06 → Saturday 10-10 at 22:00.
        assertEquals("2026-10-10T22:00-04:00[America/New_York]", next("2026-10-06T12:00-04:00[America/New_York]"))
        // Saturday 23:00 → Sunday 9:00.
        assertEquals("2026-10-11T09:00-04:00[America/New_York]", next("2026-10-10T23:00-04:00[America/New_York]"))
        // Sunday 9:00 exactly → next Saturday.
        assertEquals("2026-10-17T22:00-04:00[America/New_York]", next("2026-10-11T09:00-04:00[America/New_York]"))
    }
}
