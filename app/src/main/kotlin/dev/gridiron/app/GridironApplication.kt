package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.live.LiveDb
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.PropsRepository
import dev.gridiron.core.data.live.UrlConnectionHttpClient
import dev.gridiron.core.data.live.UrlConnectionHttpGet
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.currentSeason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * The app's object graph, by hand. Stats are built on the phone into
 * `noBackupFilesDir/stats.db` (an existing install's database stays until the
 * first build replaces it). ESPN's injuries and news, and the Odds API's
 * props, live beside it in `live.db`.
 */
class GridironApplication : Application() {
    // Outlives every screen: preferences are written on it and refreshes run on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val statsFile by lazy { File(noBackupFilesDir, RefreshCoordinator.DB_NAME) }

    private val executor by lazy {
        ReopenableQueryExecutor {
            check(statsFile.isFile) { "No stats yet: load them first" }
            SqliteQueryExecutor.openReadOnly(statsFile.path)
        }
    }
    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }
    private val settings by lazy { SettingsRepository(prefs) { currentSeason() } }
    private val players by lazy { PlayerDirectory(executor) }
    // One connection to live.db, shared by ESPN's feeds and the props.
    private val liveDb by lazy { LiveDb(File(noBackupFilesDir, "live.db")) }
    private val live by lazy { LiveRepository(liveDb, UrlConnectionHttpGet(), players) }
    private val propsRepo by lazy { PropsRepository(liveDb, UrlConnectionHttpClient()) }

    private val workDir by lazy { File(noBackupFilesDir, "ingest-work") }

    private val refresher by lazy {
        RefreshCoordinator(
            dir = noBackupFilesDir,
            executor = executor,
            stats = { seasons, previous, out, props, onProgress ->
                IngestPipeline(HttpFetcher(), workDir, File(noBackupFilesDir, "players.csv.gz"))
                    .build(seasons, previous, out, props, onProgress)
            },
            seasons = { settings.seasons.first() },
            scope = appScope,
            live = { live.refresh() },
            props = {
                val key = settings.oddsApiKey.first()
                if (key == null) {
                    PropsFetch(null, null)
                } else {
                    val error = propsRepo.refresh(key)
                    PropsFetch(propsRepo.snapshot(), error)
                }
            },
            workDir = workDir,
        )
    }

    val deps: Deps by lazy {
        Deps(
            StatsRepository(executor, dataVersion = executor.version),
            CompareRepository(executor),
            ScoringRepository(prefs),
            CompareTrayRepository(prefs),
            ProjectionsRepository(executor),
            AccuracyRepository(executor),
            TeamsRepository(executor),
            players = players,
            live = live,
            settings = settings,
            refresher = refresher,
            props = propsRepo,
        )
    }
}
