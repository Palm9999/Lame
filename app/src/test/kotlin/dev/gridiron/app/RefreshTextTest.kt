package dev.gridiron.app

import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import dev.gridiron.core.ingest.ValidationException
import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class RefreshTextTest {
    @Test
    fun progressLines() {
        assertEquals("Checking for new stats…", progressText(IngestProgress.Checking(null)))
        assertEquals("Checking 2025…", progressText(IngestProgress.Checking(2025)))
        assertEquals(
            "Downloading 2026 play-by-play 12/19 MB",
            progressText(IngestProgress.Downloading(2026, "play-by-play", 12_400_000, 19_000_000)),
        )
        assertEquals(
            "Downloading player list 1.3/2.5 MB",
            progressText(IngestProgress.Downloading(null, "player list", 1_300_000, 2_500_000)),
        )
        assertEquals(
            "Downloading 2026 snap counts 0.4 MB",
            progressText(IngestProgress.Downloading(2026, "snap counts", 400_000, -1)),
        )
        assertEquals("Crunching 2026…", progressText(IngestProgress.Crunching(2026)))
        assertEquals("Checking the new stats…", progressText(IngestProgress.Validating))
    }

    @Test
    fun failureMessagesNameTheCause() {
        assertEquals(
            "The new stats failed a check (range: target_share 1.4). Your current stats are kept.",
            describeFailure(ValidationException(listOf("range: target_share 1.4")), kept = true),
        )
        assertEquals(
            "nflverse changed column(s) epa in play_by_play_2026.csv.gz. Your current stats are kept.",
            describeFailure(MissingColumnsException("play_by_play_2026.csv.gz", listOf("epa")), kept = true),
        )
        assertEquals(
            "No connection. Your current stats are kept.",
            describeFailure(IOException("fetch failed", UnknownHostException("github.com")), kept = true),
        )
        assertEquals(
            "Not enough free storage to build stats. Your current stats are kept.",
            describeFailure(IOException("write failed: ENOSPC (No space left on device)"), kept = true),
        )
        assertEquals(
            "Refresh failed: HTTP 502 from github.com. Your current stats are kept.",
            describeFailure(IOException("HTTP 502 from github.com"), kept = true),
        )
        assertEquals("No connection.", describeFailure(UnknownHostException("github.com"), kept = false))
    }

    @Test
    fun summariesListTheSeasonsAndWhatWasSkipped() {
        val report = IngestReport(listOf(2026), listOf(2024, 2025), mapOf(2027 to "play-by-play isn't published yet"), emptyList(), 1L)
        assertEquals(
            "Stats updated for 2024, 2025, 2026 in 1 min 5 s. 2027 skipped: play-by-play isn't published yet.",
            summary(report, 65_000),
        )
        assertEquals("42 s", formatDuration(41_600))
        assertEquals("0 s", formatDuration(0))
    }
}
