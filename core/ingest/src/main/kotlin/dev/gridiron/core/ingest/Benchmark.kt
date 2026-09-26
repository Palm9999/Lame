package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.openInput
import dev.gridiron.core.ingest.pbp.PlayerWeekAggregator
import dev.gridiron.core.ingest.pbp.derive
import dev.gridiron.core.ingest.pbp.readPlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

public data class BenchmarkResult(
    public val season: Int,
    public val bytes: Long,
    public val downloadMs: Long,
    public val crunchMs: Long,
    public val plays: Int,
    public val playerWeeks: Int,
)

/**
 * Times the expensive half of an on-device build for one season: downloading
 * its play-by-play and streaming it into weekly player stats. Nothing is kept.
 */
public suspend fun benchmarkSeason(fetcher: Fetcher, season: Int, workDir: File): BenchmarkResult =
    withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val dest = File(workDir, Sources.fileName(Input.PBP, season))
        try {
            val start = System.nanoTime()
            val fetched = fetcher.fetch(Sources.url(Input.PBP, season), dest, previous = null) { _, _ -> }
            check(fetched is FetchResult.Downloaded) { "$season play-by-play isn't published" }
            val downloaded = System.nanoTime()
            var plays = 0
            val aggregator = PlayerWeekAggregator()
            openInput(dest).use { input ->
                readPlays(input, dest.name) {
                    plays++
                    aggregator.add(it)
                }
            }
            val weeks = aggregator.rows().onEach { it.derive() }.size
            val done = System.nanoTime()
            BenchmarkResult(season, dest.length(), (downloaded - start) / 1_000_000, (done - downloaded) / 1_000_000, plays, weeks)
        } finally {
            dest.delete()
        }
    }

/** The NFL season in progress on [today]: a season is current from September. */
public fun currentSeason(today: LocalDate = LocalDate.now()): Int =
    if (today.monthValue >= 9) today.year else today.year - 1
