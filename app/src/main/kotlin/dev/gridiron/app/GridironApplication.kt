package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.BreakoutRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridDisplayRepository
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.OpportunitiesRepository
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.PlayerStatsRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.RosterRepository
import dev.gridiron.core.data.ScoresRepository
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.InjuryAlertChecker
import dev.gridiron.core.data.live.LineupAlert
import dev.gridiron.core.data.live.LineupAlerts
import dev.gridiron.core.data.live.LiveDb
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.PropsRepository
import dev.gridiron.core.data.live.UrlConnectionHttpClient
import dev.gridiron.core.data.live.UrlConnectionHttpGet
import dev.gridiron.core.data.live.WeekPlayer
import dev.gridiron.core.data.live.starterIds
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.core.model.Position
import dev.gridiron.core.projections.projectedScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant

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
    internal val settings by lazy { SettingsRepository(prefs) { currentSeason() } }
    private val players by lazy { PlayerDirectory(executor) }
    // One connection to live.db, shared by ESPN's feeds and the props.
    private val liveDb by lazy { LiveDb(File(noBackupFilesDir, "live.db")) }
    private val live by lazy { LiveRepository(liveDb, UrlConnectionHttpGet(), players) }
    private val projectionsRepo by lazy { ProjectionsRepository(executor) }
    private val propsRepo by lazy { PropsRepository(liveDb, UrlConnectionHttpClient()) }
    private val rosters by lazy { RosterRepository(prefs) }
    private val scoring by lazy { ScoringRepository(prefs) }
    private val scores by lazy { ScoresRepository(executor, UrlConnectionHttpGet()) }
    private val league by lazy { FantasyLeagueRepository(prefs, UrlConnectionHttpGet(), players, noBackupFilesDir) }

    /** The latest season's upcoming week and its games (kickoffs from ESPN's scoreboard); null off-season or without stats. */
    internal suspend fun upcomingWeek(): ScoresWeek? {
        val (season, week) = projectionsRepo.status().upcoming.maxByOrNull { it.key }?.toPair() ?: return null
        return scores.week(season, week)
    }

    /**
     * [LineupAlertWorker]'s check for the games kicking off at [window]: the league re-synced (the last snapshot is kept
     * if that fails), this week's projections under the active profile, and ESPN's injury list just fetched.
     */
    internal suspend fun lineupAlerts(window: Instant): List<LineupAlert> {
        val week = upcomingWeek() ?: return emptyList()
        league.sync(week.season)
        val team = league.myTeam.first()?.takeIf { it.season == week.season } ?: return emptyList()
        val profile = scoring.active.first()
        val ids = team.players.mapNotNull { it.playerId }.toSet()
        val projected = projectionsRepo.weekAll(week.season, week.week).filter { it.playerId in ids }.associateBy { it.playerId }
        val players = ids.mapNotNull { id ->
            projected[id]?.let { p ->
                val pos = p.position ?: return@let null
                WeekPlayer(id, p.name, pos, p.team, projectedScore(p.components, profile, Position.fromCode(pos)))
            } ?: this.players.header(id)?.let { h -> h.position?.let { WeekPlayer(id, h.name, it, h.team, 0.0) } }
        }.associateBy { it.playerId }
        if (live.refresh().injuriesError != null) return emptyList()
        val status = live.injuries().mapNotNull { i -> i.playerId?.let { it to i.abbr } }.toMap()
        return LineupAlerts.check(team, players, status, week, window)
    }

    /** What [InjuryAlertWorker] checks: rostered players' ESPN status against the last list seen. */
    internal val injuryAlerts by lazy {
        InjuryAlertChecker(live, rosters.rosters, { league.myTeam.first()?.starterIds().orEmpty() }, File(noBackupFilesDir, "injury-alerts.json"))
    }

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
            scoring,
            CompareTrayRepository(prefs),
            projectionsRepo,
            AccuracyRepository(executor),
            TeamsRepository(executor),
            players = players,
            live = live,
            settings = settings,
            refresher = refresher,
            rosters = rosters,
            gridPresets = GridPresetRepository(prefs),
            gridDisplay = GridDisplayRepository(prefs),
            props = propsRepo,
            playerStats = PlayerStatsRepository(executor),
            scores = scores,
            league = league,
            opportunities = OpportunitiesRepository(executor, projectionsRepo, { live.injuries() }),
            breakouts = BreakoutRepository(executor),
        )
    }
}
