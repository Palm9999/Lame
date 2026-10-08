package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.BreakoutRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.CompsRepository
import dev.gridiron.core.data.DraftRepository
import dev.gridiron.core.data.DynastyRepository
import dev.gridiron.core.data.GridDisplayRepository
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.InjuryReturnRepository
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
import dev.gridiron.core.data.TdRegressionRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.kickoffs
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.InjuryAlertChecker
import dev.gridiron.core.data.live.LastResult
import dev.gridiron.core.data.live.LineupAlert
import dev.gridiron.core.data.live.LineupAlerts
import dev.gridiron.core.data.live.LiveDb
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.NewsAlertChecker
import dev.gridiron.core.data.live.NextMatch
import dev.gridiron.core.data.live.PropsRepository
import dev.gridiron.core.data.live.UrlConnectionHttpClient
import dev.gridiron.core.data.live.UrlConnectionHttpGet
import dev.gridiron.core.data.live.WaiverTrendsRepository
import dev.gridiron.core.data.live.WeekPlayer
import dev.gridiron.core.data.live.WeeklySummary
import dev.gridiron.core.data.live.liftQuestionable
import dev.gridiron.core.data.live.starterIds
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.projections.projectedScore
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

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
    internal val widget by lazy { WidgetStore(this) }
    private val scoring by lazy { ScoringRepository(prefs) }
    private val scores by lazy { ScoresRepository(executor, UrlConnectionHttpGet()) }
    private val league by lazy { FantasyLeagueRepository(prefs, UrlConnectionHttpGet(), players, noBackupFilesDir) }

    /**
     * The active league's current week (ESPN's, re-synced first when [syncFirst] or the snapshot is over
     * [WEEK_SYNC_MILLIS] old) and its games, kickoffs from ESPN's scoreboard; null without a league and team.
     */
    internal suspend fun upcomingWeek(syncFirst: Boolean = false): ScoresWeek? {
        // ESPN's current week, not the last stats build's: that one lags until the next refresh.
        league.myTeam.first() ?: return null
        var snapshot = league.league.value ?: return null
        if (syncFirst || System.currentTimeMillis() - snapshot.fetchedAtMillis > WEEK_SYNC_MILLIS) {
            league.sync(snapshot.season)
            snapshot = league.league.value ?: return null
        }
        return scores.week(snapshot.season, snapshot.week)
    }

    /**
     * [LineupAlertWorker]'s check for the games kicking off at [window]: the league re-synced (the last snapshot is kept
     * if that fails), this week's projections under the active profile, and ESPN's injury list just fetched.
     */
    internal suspend fun lineupAlerts(window: Instant): List<LineupAlert> {
        val week = upcomingWeek(syncFirst = true) ?: return emptyList()
        val team = league.myTeam.first()?.takeIf { it.season == week.season } ?: return emptyList()
        val profile = scoring.active.first()
        val ids = team.players.mapNotNull { it.playerId }.toSet()
        val projected = projectionsRepo.weekAll(week.season, week.week).filter { it.playerId in ids }.associateBy { it.playerId }
        // A week past the stats build's upcoming one: its rest-of-season row ranks the bench.
        val later = if (projected.isEmpty()) {
            projectionsRepo.rosWeeks(week.season).filter { it.playerId in ids }.associate { it.playerId to it.points(profile)[week.week] }
        } else {
            emptyMap()
        }
        if (live.refresh().injuriesError != null) return emptyList()
        val status = live.injuries().mapNotNull { i -> i.playerId?.let { it to i.abbr } }.toMap()
        // The check runs after inactives post: a Questionable player still listed is playing, so his discount is lifted.
        val posted = week.kickoffs(Instant.now()).inactivesPosted
        val questionable = projectionsRepo.questionable(week.season, week.week)
        val players = ids.mapNotNull { id ->
            val h = this.players.header(id) ?: return@mapNotNull null
            val pos = h.position ?: return@mapNotNull null
            val points = projected[id]?.let { projectedScore(it.components, profile, Position.fromCode(pos)) } ?: later[id] ?: 0.0
            WeekPlayer(id, h.name, pos, h.team, liftQuestionable(points, questionable[id], h.team in posted, status[id]))
        }.associateBy { it.playerId }
        return LineupAlerts.check(team, players, status, week, window)
    }

    /**
     * [InjuryAlertWorker]'s Tuesday summary, once a week from 9 local: last week's ESPN result, the report card's overall
     * place and the coming week's win chance, each left out when it can't be read; null when not due or nothing is known.
     */
    internal suspend fun weeklySummaryIfDue(now: ZonedDateTime = ZonedDateTime.now()): String? {
        if (now.dayOfWeek != DayOfWeek.TUESDAY || now.hour < 9) return null
        val week = upcomingWeek(syncFirst = true) ?: return null
        val key = "${week.season}:${week.week}"
        val sent = File(noBackupFilesDir, "summary-sent.txt")
        if (!WeeklySummary.due(now, sent.takeIf { it.isFile }?.readText()?.trim(), key)) return null
        val snapshot = league.league.value ?: return null
        val names = snapshot.teams.associate { it.id to it.name }
        val profile = scoring.active.first()
        val previous = week.week - 1
        val last = if (previous < 1) null else league.myMatchup(week.season, previous, profile).let { m ->
            val mine = m.mine ?: return@let null
            val theirs = m.theirs ?: return@let null
            LastResult(previous, mine.espnTotal, theirs.espnTotal, names[theirs.teamId] ?: "Team ${theirs.teamId}")
        }
        val place = if (previous < 1) null else league.lineupReview(week.season, previous).let { r ->
            r.reportCards.firstOrNull { it.teamId == r.myTeamId }?.let { it.overall to r.reportCards.size }
        }
        val next = nextMatch(week.season, week.week, profile)
        val text = WeeklySummary.text(last, place, next) ?: return null
        sent.writeText(key)
        return text
    }

    /** The coming week's opponent and win chance from both best lineups' projections; null without either. */
    private suspend fun nextMatch(season: Int, week: Int, profile: ScoringProfile): NextMatch? {
        val mine = league.myTeam.first()?.takeIf { it.season == season } ?: return null
        val theirs = league.opponent(season, week).team ?: return null
        val projected = projectionsRepo.weekAll(season, week).associateBy { it.playerId }
        if (projected.isEmpty()) return null
        val sd = HashMap<String, Double>()
        fun roster(team: MyTeam) = team.players.mapNotNull { p ->
            val id = p.playerId ?: return@mapNotNull null
            val listed = projected[id] ?: return@mapNotNull null
            val pos = listed.position ?: return@mapNotNull null
            val pts = projectPoints(listed.components, profile, Position.fromCode(pos), draws = 1_000)
            sd[id] = maxOf(0.0, pts.ceiling - pts.floor) / (2 * 1.2816)
            LineupCandidate(id, pos, pts.points)
        }
        val chance = WeeklySummary.winChance(
            WeeklySummary.strength(mine.slots, roster(mine), sd),
            WeeklySummary.strength(mine.slots, roster(theirs), sd),
        )
        return NextMatch(week, theirs.teamName, chance)
    }

    /** ESPN stories about rostered players since [InjuryAlertWorker]'s last run. */
    /** Shared by Waiver trends and the two-hourly job's daily roster % snapshot. */
    internal val waiverTrends by lazy { WaiverTrendsRepository(liveDb, UrlConnectionHttpGet(), players) }
    internal val newsAlerts by lazy { NewsAlertChecker(live, rosters.rosters, File(noBackupFilesDir, "news-alerts.txt")) }

    /** What [InjuryAlertWorker] checks: rostered players' ESPN status against the last list seen. */
    internal val injuryAlerts by lazy {
        InjuryAlertChecker(live, rosters.rosters, { league.myTeam.first()?.starterIds().orEmpty() }, File(noBackupFilesDir, "injury-alerts.json"))
    }

    private val workDir by lazy { File(noBackupFilesDir, "ingest-work") }

    internal val refresher by lazy {
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
            onStart = { RefreshWorker.enqueue(this) },
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
            onLineupSummary = { widget.lineup(it) },
            onHome = { summary, chance, watch -> widget.home(summary, chance, watch) },
            winLine = WinLineStore(java.io.File(noBackupFilesDir, "winline.txt")),
            draft = DraftRepository(executor, UrlConnectionHttpGet()),
            draftDir = noBackupFilesDir,
            opportunities = OpportunitiesRepository(executor, projectionsRepo, { live.injuries() }),
            breakouts = BreakoutRepository(executor),
            returns = InjuryReturnRepository(executor),
            waiverTrends = waiverTrends,
            dynasty = DynastyRepository(UrlConnectionHttpGet(), players),
            tdRegression = TdRegressionRepository(executor),
            comps = CompsRepository(executor),
            tradeGrades = dev.gridiron.core.data.live.TradeGradeStore(File(noBackupFilesDir, "trade-grades.txt")),
        )
    }

    private companion object {
        /** The two-hourly job re-reads the league for its current week when the snapshot is older than this. */
        const val WEEK_SYNC_MILLIS = 6 * 60 * 60 * 1000L
    }
}
