package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.openInput
import dev.gridiron.core.ingest.db.INGEST_VERSION
import dev.gridiron.core.ingest.db.StatsDbWriter
import dev.gridiron.core.ingest.db.readMeta
import dev.gridiron.core.ingest.pbp.PlayerWeekAggregator
import dev.gridiron.core.ingest.pbp.TeamDefenseAggregator
import dev.gridiron.core.ingest.pbp.derive
import dev.gridiron.core.ingest.pbp.readPlays
import dev.gridiron.core.ingest.validate.crossCheck
import dev.gridiron.core.ingest.validate.expectedCoverage
import dev.gridiron.core.ingest.validate.fantasyContract
import dev.gridiron.core.ingest.validate.validateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** What a build is doing, for a progress line. */
public sealed interface IngestProgress {
    public data class Checking(public val season: Int?) : IngestProgress
    public data class Downloading(public val season: Int?, public val what: String, public val bytes: Long, public val total: Long) : IngestProgress
    public data class Crunching(public val season: Int) : IngestProgress
    public data object Validating : IngestProgress
}

public data class IngestReport(
    public val built: List<Int>,
    public val reused: List<Int>,
    public val skipped: Map<Int, String>,
    public val warnings: List<String>,
    public val facts: Long,
)

private val SEASON_INPUTS = listOf(Input.PBP, Input.SNAP_COUNTS, Input.INJURIES, Input.EXPECTED)

/**
 * Builds stats.db from nflverse and ffopportunity: the on-device replacement
 * for the Python ETL. A season whose inputs haven't changed since `previous`
 * was built is copied from it instead of downloaded and recomputed.
 *
 * [playersFile] (the player list, kept between builds) must live outside
 * [workDir], which is emptied when a build ends.
 */
public class IngestPipeline(
    private val fetcher: Fetcher,
    private val workDir: File,
    private val playersFile: File,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Writes a complete, validated database to [out], or throws and leaves no
     * [out] behind. [previous] is only ever read.
     */
    public suspend fun build(
        seasons: List<Int>,
        previous: File?,
        out: File,
        onProgress: (IngestProgress) -> Unit = {},
    ): IngestReport = withContext(Dispatchers.IO) {
        require(previous == null || previous.canonicalFile != out.canonicalFile) { "out must differ from previous" }
        val prior = previous?.let(::readMeta)?.takeIf { it["ingest_version"] == INGEST_VERSION.toString() }
        out.delete()
        workDir.mkdirs()
        try {
            Run(prior, previous, coroutineContext.job, onProgress).build(seasons.distinct().sorted(), out)
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }
    }

    private inner class Run(
        private val prior: Map<String, String>?,
        private val previous: File?,
        private val job: Job,
        private val onProgress: (IngestProgress) -> Unit,
    ) {
        private val priorSeasons = prior?.get("seasons").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()
        private val meta = LinkedHashMap<String, String>()
        private val warnings = mutableListOf<String>()
        private val built = mutableListOf<Int>()
        private val reused = mutableListOf<Int>()
        private val skipped = LinkedHashMap<Int, String>()

        suspend fun build(seasons: List<Int>, out: File): IngestReport {
            onProgress(IngestProgress.Checking(null))
            fetchPlayers()
            val players = openInput(playersFile).use { readPlayers(it, playersFile.name) }
            val crosswalk = players.filter { it.pfrPlayerId != null }.groupBy({ it.pfrPlayerId!! }, { it.playerId })
            return StatsDbWriter.create(out).use { writer ->
                writer.writeMetrics(METRICS)
                for (season in seasons) {
                    job.ensureActive()
                    season(season, writer, crosswalk)
                }
                check(built.isNotEmpty() || reused.isNotEmpty()) { "none of the seasons $seasons has published play-by-play" }
                writer.writePlayers(players)
                writer.finish(built + reused, meta, now())
                onProgress(IngestProgress.Validating)
                val problems = validateDatabase(writer.connection)
                if (problems.isNotEmpty()) throw ValidationException(problems)
                IngestReport(built.sorted(), reused.sorted(), skipped.toMap(), warnings.toList(), writer.factCount())
            }
        }

        private suspend fun fetch(input: Input, season: Int?, known: Validators?, dest: File = File(workDir, Sources.fileName(input, season))): FetchResult =
            fetcher.fetch(Sources.url(input, season), dest, known) { read, total ->
                onProgress(IngestProgress.Downloading(season, input.label, read, total))
            }

        private suspend fun fetchPlayers() {
            val key = Sources.metaKey(Input.PLAYERS)
            val known = prior?.get(key)?.let(Validators::decode)?.takeIf { playersFile.isFile }
            when (val r = fetch(Input.PLAYERS, null, known, playersFile)) {
                is FetchResult.Downloaded -> meta[key] = r.validators.encode()
                FetchResult.NotModified -> meta[key] = checkNotNull(prior).getValue(key)
                FetchResult.NotPublished -> error("nflverse's player list isn't available")
            }
        }

        private suspend fun season(season: Int, writer: StatsDbWriter, crosswalk: Map<String, List<String>>) {
            onProgress(IngestProgress.Checking(season))
            val knownSeason = season in priorSeasons
            val first = SEASON_INPUTS.associateWith { input ->
                val known = if (knownSeason) prior?.get(Sources.metaKey(input, season))?.let(Validators::decode) else null
                fetch(input, season, known)
            }
            if (first.getValue(Input.PBP) == FetchResult.NotPublished) {
                skipped[season] = "play-by-play isn't published yet"
                return
            }
            val unchanged = knownSeason && first.all { (input, r) ->
                r == FetchResult.NotModified ||
                    (r == FetchResult.NotPublished && prior?.containsKey(Sources.metaKey(input, season)) != true)
            }
            if (unchanged) reuse(season, writer) else crunch(season, first, writer, crosswalk)
        }

        private fun reuse(season: Int, writer: StatsDbWriter) {
            val p = checkNotNull(prior)
            writer.copySeasonFrom(checkNotNull(previous), season)
            for (input in SEASON_INPUTS) {
                val key = Sources.metaKey(input, season)
                p[key]?.let { meta[key] = it }
            }
            p["expected_through_week:$season"]?.let { meta["expected_through_week:$season"] = it }
            reused += season
        }

        private suspend fun crunch(
            season: Int,
            first: Map<Input, FetchResult>,
            writer: StatsDbWriter,
            crosswalk: Map<String, List<String>>,
        ) {
            // A season is rebuilt from all of its files, so refetch any the first pass found unchanged.
            val files = first.mapValues { (input, r) ->
                val result = if (r == FetchResult.NotModified) fetch(input, season, known = null) else r
                (result as? FetchResult.Downloaded)?.also { meta[Sources.metaKey(input, season)] = it.validators.encode() }?.file
            }

            onProgress(IngestProgress.Crunching(season))
            val players = PlayerWeekAggregator()
            val defense = TeamDefenseAggregator()
            val pbp = checkNotNull(files[Input.PBP])
            var n = 0
            openInput(pbp).use { input ->
                readPlays(input, pbp.name) { play ->
                    if (++n % 5_000 == 0) job.ensureActive()
                    players.add(play)
                    defense.add(play)
                }
            }
            val weekly = players.rows().onEach { it.derive() }

            val snaps = files[Input.SNAP_COUNTS]
            if (snaps != null) {
                attachSnapShare(weekly, openInput(snaps).use { readSnaps(it, snaps.name) }, crosswalk)
            } else {
                warnings += "$season: no snap counts published yet"
            }

            val expectedFile = files[Input.EXPECTED]
            val expected = expectedFile?.let { f -> openInput(f).use { readExpected(it, f.name) } }
            val (through, coverage) = expectedCoverage(season, weekly, expected)
            meta["expected_through_week:$season"] = through.toString()
            coverage?.let { warnings += it }
            if (expected != null) {
                val problems = crossCheck(weekly, expected, warnings) + fantasyContract(weekly, expected, warnings)
                if (problems.isNotEmpty()) throw ValidationException(problems.map { "$season: $it" })
                writer.writeFacts(toFacts(expected.map { it.toPlayerWeek() }))
            } else {
                warnings += "$season: no expected-points data yet; xFP will be missing"
            }

            writer.writeFacts(toFacts(weekly))
            writer.writeTeamDefense(defense.rows())
            val injuries = files[Input.INJURIES]
            if (injuries != null) {
                writer.writeInjuries(openInput(injuries).use { readInjuries(it, injuries.name) })
            } else {
                warnings += "$season: no injury report published yet"
            }
            files.values.forEach { it?.delete() }
            built += season
        }
    }
}
