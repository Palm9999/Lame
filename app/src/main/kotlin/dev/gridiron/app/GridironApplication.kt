package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.benchmarkSeason
import dev.gridiron.core.ingest.currentSeason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The app's object graph, by hand. Four repositories over one database
 * connection and one preferences file still don't justify Hilt.
 */
class GridironApplication : Application() {
    // Outlives every screen; the preferences file is written on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val executor by lazy {
        ReopenableQueryExecutor { SqliteQueryExecutor.openReadOnly(StatsDbInstaller(this).install().path) }
    }
    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }

    val deps: Deps by lazy {
        Deps(
            StatsRepository(executor),
            CompareRepository(executor),
            ScoringRepository(prefs),
            CompareTrayRepository(prefs),
            ProjectionsRepository(executor),
            AccuracyRepository(executor),
            TeamsRepository(executor),
            refresh = { StatsDbInstaller(this).refresh() },
            benchmark = { benchmark() },
        )
    }

    /** Last complete season: the worst case for a single season's build. */
    private suspend fun benchmark(): Result<String> = runCatching {
        val r = benchmarkSeason(HttpFetcher(), currentSeason() - 1, File(cacheDir, "benchmark"))
        "${r.season} play-by-play: ${"%.1f".format(r.bytes / 1e6)} MB downloaded in ${r.downloadMs / 1000.0} s, " +
            "${r.plays} plays crunched into ${r.playerWeeks} player-weeks in ${r.crunchMs / 1000.0} s."
    }
}
