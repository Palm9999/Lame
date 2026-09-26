package dev.gridiron.core.ingest.cli

import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.IngestProgress
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * Builds stats.db on a desktop JVM with exactly the code the phone runs:
 *
 *     ./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/stats.db
 *
 * An unpublished season is skipped with a note; any other failure exits non-zero.
 */
public fun main(args: Array<String>) {
    val seasons = mutableListOf<Int>()
    var out: File? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--seasons" -> while (i + 1 < args.size && !args[i + 1].startsWith("--")) seasons += args[++i].toInt()
            "--out" -> out = File(args[++i])
            else -> error("unknown argument ${args[i]}")
        }
        i++
    }
    val target = requireNotNull(out) { "--out is required" }
    require(seasons.isNotEmpty()) { "--seasons is required" }

    val scratch = Files.createTempDirectory("gridiron-ingest").toFile()
    try {
        val pipeline = IngestPipeline(HttpFetcher(), File(scratch, "work"), File(scratch, "players.csv.gz"))
        val report = runBlocking {
            pipeline.build(seasons, previous = null, out = target) { p -> if (p !is IngestProgress.Downloading) println(p) }
        }
        report.warnings.forEach { println("WARNING $it") }
        report.skipped.forEach { (season, why) -> println("SKIPPED $season: $why") }
        println("built ${report.built}, ${report.facts} facts -> $target")
    } finally {
        scratch.deleteRecursively()
    }
}
