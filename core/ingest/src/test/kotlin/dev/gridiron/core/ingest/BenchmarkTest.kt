package dev.gridiron.core.ingest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class BenchmarkTest {
    @Test
    fun `times a season's download and crunch and keeps nothing`(@TempDir dir: File) = runTest {
        val fetcher = FakeFetcher()
        val plays = listOf(
            Fixtures.pbp("receiver_player_id" to "WR1", "passer_player_id" to "QB1", "pass_attempt" to 1, "air_yards" to 10),
            Fixtures.pbp("play_type" to "run", "rusher_player_id" to "RB1", "rushing_yards" to 4),
            Fixtures.pbp("play_type" to "kickoff"),
        )
        fetcher.serve(Sources.url(Input.PBP, 2025), Fixtures.gzip(Fixtures.pbpCsv(plays)), "v1")

        val result = benchmarkSeason(fetcher, 2025, dir)

        assertEquals(2025, result.season)
        assertEquals(3, result.plays)
        assertEquals(3, result.playerWeeks) // WR1, QB1 and RB1; the kickoff is filtered out
        assertTrue(result.bytes > 0)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `an unpublished season fails with a clear message`(@TempDir dir: File) = runTest {
        val e = runCatching { benchmarkSeason(FakeFetcher(), 2030, dir) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("2030 play-by-play isn't published"), "got $e")
    }

    @Test
    fun `a season is current from September`() {
        assertEquals(2026, currentSeason(LocalDate.of(2026, 9, 1)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 8, 31)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 1, 15)))
    }
}
