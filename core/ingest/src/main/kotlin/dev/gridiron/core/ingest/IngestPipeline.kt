package dev.gridiron.core.ingest

import dev.gridiron.core.forecast.FORECAST_OK
import dev.gridiron.core.forecast.FORECAST_VERSION
import dev.gridiron.core.forecast.Forecast
import dev.gridiron.core.forecast.PropsOutcome
import dev.gridiron.core.forecast.PropsSnapshot
import dev.gridiron.core.forecast.SeasonCopy
import dev.gridiron.core.ingest.csv.MissingColumnsException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/** What a build is doing, for a progress line. */
public sealed interface IngestProgress {
    public data class Checking(public val season: Int?) : IngestProgress
    public data class Downloading(public val season: Int?, public val what: String, public val bytes: Long, public val total: Long) : IngestProgress
    public data class Crunching(public val season: Int) : IngestProgress
    public data object Validating : IngestProgress
    public data class Projecting(public val season: Int, public val week: Int) : IngestProgress
}

public data class IngestReport(
    public val built: List<Int>,
    public val reused: List<Int>,
    public val skipped: Map<Int, String>,
    public val warnings: List<String>,
    public val facts: Long,
    /** "ok", or why the new database has no projections (the stats are fine either way). */
    public val forecast: String = FORECAST_OK,
    /** How props went in the forecast; null when none were given, or the forecast failed. */
    public val props: PropsOutcome? = null,
) {
    public val projectionsOk: Boolean get() = forecast == FORECAST_OK
}

private val SEASON_INPUTS = listOf(Input.PBP, Input.SNAP_COUNTS, Input.INJURIES, Input.EXPECTED)

/**
 * Builds stats.db from nflverse and ffopportunity: the on-device replacement
 * for the Python ETL. A season whose inputs haven't changed since `previous`
 * was built is copied from it instead of downloaded and recomputed.
 *
 * [playersFile] and [gamesFile] (the player list and the schedule, kept
 * between builds so an unchanged one needn't be downloaded again) must live
 * outside [workDir], which is emptied when a build ends.
 */
public class IngestPipeline(
    private val fetcher: Fetcher,
    private val workDir: File,
    private val playersFile: File,
    private val gamesFile: File = playersFile.resolveSibling("games.csv"),
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Writes a complete, validated database to [out], or throws and leaves no
     * [out] behind. [previous] is only ever read. [props], when given, are
     * blended into the upcoming week's projections.
     */
    public suspend fun build(
        seasons: List<Int>,
        previous: File?,
        out: File,
        props: PropsSnapshot? = null,
        onProgress: (IngestProgress) -> Unit = {},
    ): IngestReport = withContext(Dispatchers.IO) {
        require(previous == null || previous.canonicalFile != out.canonicalFile) { "out must differ from previous" }
        val prior = previous?.let(::readMeta)?.takeIf { it["ingest_version"] == INGEST_VERSION.toString() }
        out.delete()
        workDir.mkdirs()
        try {
            Run(prior, previous, props, coroutineContext.job, onProgress).build(seasons.distinct().sorted(), out)
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
        private val props: PropsSnapshot?,
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
                writer.writeGames(readSchedule((built + reused).toSet()))
                writer.finish(built + reused, meta, now())
                onProgress(IngestProgress.Validating)
                val problems = validateDatabase(writer.connection)
                if (problems.isNotEmpty()) throw ValidationException(problems)
                val (forecast, propsOutcome) = forecast(writer)
                IngestReport(built.sorted(), reused.sorted(), skipped.toMap(), warnings.toList(), writer.factCount(), forecast, propsOutcome)
            }
        }

        /** Projections for the new database, and how props went. A failure leaves none and says why; it never fails the build. */
        private fun forecast(writer: StatsDbWriter): Pair<String, PropsOutcome?> = try {
            val report = Forecast.run(writer.connection, now(), forecastCopy(), props) { season, week ->
                job.ensureActive()
                onProgress(IngestProgress.Projecting(season, week))
            }
            report.status to report.props
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "unknown error"
            Forecast.fail(writer.connection, now(), reason)
            "failed: $reason" to null
        }

        /**
         * Past seasons whose projections can be copied from the previous
         * database: reused seasons, from the oldest up to the first rebuilt
         * one (a season's projections depend on every season before it),
         * never the latest, and only when the previous build projected them
         * with this forecast version from exactly the same earlier seasons.
         */
        private fun forecastCopy(): SeasonCopy? {
            val p = prior ?: return null
            val prev = previous ?: return null
            if (p["forecast_version"] != FORECAST_VERSION.toString() || p["forecast_status"] != FORECAST_OK) return null
            val copyable = (built + reused).sorted().dropLast(1).takeWhile { it in reused }
            if (copyable.isEmpty() || priorSeasons.sorted().takeWhile { it <= copyable.last() } != copyable) return null
            return SeasonCopy(prev, copyable.toSet())
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

        /**
         * Null, with a warning, when there's no schedule: projections need it,
         * stats don't. A download that fails falls back to the kept copy.
         */
        private suspend fun fetchGames(): File? {
            val key = Sources.metaKey(Input.GAMES)
            val known = prior?.get(key)?.let(Validators::decode)?.takeIf { gamesFile.isFile }
            val result = try {
                fetch(Input.GAMES, null, known, gamesFile)
            } catch (e: IOException) {
                if (known == null) {
                    warnings += "couldn't download nflverse's schedule (${e.message}); no projections this time"
                    return null
                }
                warnings += "couldn't download nflverse's schedule (${e.message}); using the last one"
                meta[key] = checkNotNull(prior).getValue(key)
                return gamesFile
            }
            return when (val r = result) {
                is FetchResult.Downloaded -> gamesFile.also { meta[key] = r.validators.encode() }
                FetchResult.NotModified -> gamesFile.also { meta[key] = checkNotNull(prior).getValue(key) }
                FetchResult.NotPublished -> {
                    warnings += "nflverse's schedule isn't available right now; no projections this time"
                    null
                }
            }
        }

        private suspend fun readSchedule(seasons: Set<Int>): List<GameRow> {
            val file = fetchGames() ?: return emptyList()
            return try {
                openInput(file).use { readGames(it, file.name, seasons) }
            } catch (e: IOException) {
                warnings += "the schedule file is unreadable (${e.message}); no projections this time"
                emptyList()
            } catch (e: MissingColumnsException) {
                warnings += "${e.message}; no projections this time"
                emptyList()
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
                if (knownSeason) {
                    // nflverse replaces a release file by deleting and re-uploading it: never drop a built season over a brief 404.
                    warnings += "$season: play-by-play is unavailable right now; kept the last build's stats"
                    reuse(season, writer)
                } else {
                    skipped[season] = "play-by-play isn't published yet"
                }
                return
            }
            val unchanged = knownSeason && first.all { (input, r) ->
                r == FetchResult.NotModified ||
                    (r == FetchResult.NotPublished && prior?.containsKey(Sources.metaKey(input, season)) != true)
            }
            if (unchanged) {
                try {
                    reuse(season, writer)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A damaged previous database would otherwise fail every refresh: rebuild the season instead.
                    warnings += "$season: couldn't copy it from the last build (${e.message}); rebuilt it"
                }
            }
            crunch(season, first, writer, crosswalk)
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
            try {
                openInput(pbp).use { input ->
                    readPlays(input, pbp.name) { play ->
                        if (++n % 5_000 == 0) job.ensureActive()
                        players.add(play)
                        defense.add(play)
                    }
                }
            } catch (e: IOException) {
                throw IOException("$season play-by-play (${pbp.name}) is unreadable: ${e.message}", e)
            }
            val weekly = players.rows().onEach { it.derive() }

            val snapsFile = files[Input.SNAP_COUNTS]
            if (snapsFile == null) warnings += "$season: no snap counts published yet"
            val snaps = snapsFile?.let { readOptional(season, "snap counts", it) { f -> openInput(f).use { s -> readSnaps(s, f.name) } } }
            if (snaps != null) attachSnapShare(weekly, snaps, crosswalk)

            val expectedFile = files[Input.EXPECTED]
            val expected = expectedFile?.let { readOptional(season, "expected points", it) { f -> openInput(f).use { s -> readExpected(s, f.name) } } }
            val (through, coverage) = expectedCoverage(season, weekly, expected)
            meta["expected_through_week:$season"] = through.toString()
            coverage?.let { warnings += it }
            if (expected != null) {
                val problems = crossCheck(weekly, expected, warnings) + fantasyContract(weekly, expected, warnings)
                if (problems.isNotEmpty()) throw ValidationException(problems.map { "$season: $it" })
                writer.writeFacts(toFacts(expected.map { it.toPlayerWeek() }))
            } else if (expectedFile == null) {
                warnings += "$season: no expected-points data yet; xFP will be missing"
            }

            writer.writeFacts(toFacts(weekly))
            writer.writeTeamDefense(defense.rows())
            val injuriesFile = files[Input.INJURIES]
            if (injuriesFile == null) {
                warnings += if (season < currentSeason(now().atZone(ZoneOffset.UTC).toLocalDate())) {
                    "$season: nflverse has no injury report for this season"
                } else {
                    "$season: no injury report published yet"
                }
            }
            injuriesFile?.let { readOptional(season, "injury report", it) { f -> openInput(f).use { s -> readInjuries(s, f.name) } } }
                ?.let(writer::writeInjuries)
            files.values.forEach { it?.delete() }
            built += season
        }

        /**
         * Reads an optional file, leaving it out with a warning when it won't
         * decompress: nflverse has shipped truncated gzips (snap_counts_2012).
         */
        private fun <T> readOptional(season: Int, what: String, file: File, read: (File) -> T): T? =
            try {
                read(file)
            } catch (e: IOException) {
                warnings += "$season: $what file is unreadable (${e.message}); left out"
                null
            }
    }
}
