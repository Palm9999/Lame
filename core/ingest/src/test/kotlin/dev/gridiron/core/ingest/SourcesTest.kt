package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourcesTest {
    @Test
    fun `urls point at nflverse and ffopportunity, never at this repository`() {
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/pbp/play_by_play_2025.csv.gz", Sources.url(Input.PBP, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/snap_counts/snap_counts_2025.csv.gz", Sources.url(Input.SNAP_COUNTS, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/injuries/injuries_2025.csv.gz", Sources.url(Input.INJURIES, 2025))
        assertEquals("https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_2025.csv", Sources.url(Input.EXPECTED, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/nextgen_stats/ngs_passing.csv.gz", Sources.url(Input.NGS_PASSING))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/nextgen_stats/ngs_rushing.csv.gz", Sources.url(Input.NGS_RUSHING))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/nextgen_stats/ngs_receiving.csv.gz", Sources.url(Input.NGS_RECEIVING))
        assertEquals("source:ngs_passing.csv.gz", Sources.metaKey(Input.NGS_PASSING))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/players/players.csv.gz", Sources.url(Input.PLAYERS))
    }

    @Test
    fun `FTN charting is one uncompressed csv per season`() {
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/ftn_charting/ftn_charting_2025.csv", Sources.url(Input.FTN, 2025))
        assertEquals("ftn_charting_2022.csv", Sources.fileName(Input.FTN, 2022))
        assertEquals("source:ftn_charting_2025.csv", Sources.metaKey(Input.FTN, 2025))
    }

    @Test
    fun `file names and meta keys`() {
        assertEquals("play_by_play_2024.csv.gz", Sources.fileName(Input.PBP, 2024))
        assertEquals("source:players.csv.gz", Sources.metaKey(Input.PLAYERS))
    }

    @Test
    fun `validators survive encoding`() {
        val v = Validators("\"0x8DF\"", "Thu, 13 Aug 2026 12:26:09 GMT")
        assertEquals(v, Validators.decode(v.encode()))
        assertEquals(Validators("\"e\"", null), Validators.decode(Validators("\"e\"", null).encode()))
        assertNull(Validators.decode("\n"))
    }
}
