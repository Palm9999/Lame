package dev.gridiron.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LineupAlertScheduleTest {
    private val kickoff = Instant.parse("2026-10-11T17:00:00Z")

    @Test
    fun aWindowAheadWithNoCheckYetGetsOne() {
        assertTrue(LineupAlertWorker.needsCheck(kickoff, kickoff.minusSeconds(6 * 3600), known = false))
        // Late (inside the 90 minutes) but never checked: checked now.
        assertTrue(LineupAlertWorker.needsCheck(kickoff, kickoff.minusSeconds(30 * 60), known = false))
    }

    @Test
    fun aWindowAlreadyCheckedIsNotCheckedAgainBeforeKickoff() {
        assertFalse(LineupAlertWorker.needsCheck(kickoff, kickoff.minusSeconds(30 * 60), known = true))
    }

    @Test
    fun aWindowUnderWayGetsNoCheck() {
        assertFalse(LineupAlertWorker.needsCheck(kickoff, kickoff, known = false))
        assertFalse(LineupAlertWorker.needsCheck(kickoff, kickoff.plusSeconds(60), known = false))
    }
}
