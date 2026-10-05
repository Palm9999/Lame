package dev.gridiron.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.BreakoutRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.DraftRepository
import dev.gridiron.core.data.GridDisplayRepository
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.InjuryReturnRepository
import dev.gridiron.core.data.Kickoffs
import dev.gridiron.core.data.OpportunitiesRepository
import dev.gridiron.core.data.OpportunitiesResult
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.PlayerStatsRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.RosterRepository
import dev.gridiron.core.data.ScoresRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.kickoffs
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.LineupReviewResult
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.MyMatchup
import dev.gridiron.core.data.live.OpponentResult
import dev.gridiron.core.data.live.PlayoffPictureResult
import dev.gridiron.core.data.live.PropsRepository
import dev.gridiron.core.data.live.TradeOffersResult
import dev.gridiron.core.designsystem.GridironIcons
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.core.model.Roster
import dev.gridiron.feature.compare.CompareRoute
import dev.gridiron.feature.players.GridRoute
import dev.gridiron.feature.projections.AccuracyRoute
import dev.gridiron.feature.projections.BreakoutsRoute
import dev.gridiron.feature.projections.OpportunitiesRoute
import dev.gridiron.feature.projections.ProjectionListRoute
import dev.gridiron.feature.projections.ProjectionsRoute
import dev.gridiron.feature.scoring.ScoringEditRoute
import dev.gridiron.feature.scoring.ScoringListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
    val projections: ProjectionsRepository,
    val accuracy: AccuracyRepository,
    val teams: TeamsRepository,
    /** Player names by id, including players with no stats; null where a test doesn't need them. */
    val players: PlayerDirectory? = null,
    /** ESPN injuries and news. Null in tests: its SQLite driver can't load under Robolectric. */
    val live: LiveRepository? = null,
    val settings: SettingsRepository? = null,
    /** Builds stats on the phone. Null in tests, which read a prebuilt database. */
    val refresher: Refresher? = null,
    val rosters: RosterRepository? = null,
    val gridPresets: GridPresetRepository? = null,
    val gridDisplay: GridDisplayRepository? = null,
    /** Odds API props, for Settings. Null in tests, like [live]. */
    val props: PropsRepository? = null,
    /** The Player page's season stats; null where a test doesn't need them. */
    val playerStats: PlayerStatsRepository? = null,
    /** The user's ESPN fantasy league. Null where a test doesn't need it. */
    val league: FantasyLeagueRepository? = null,
    /** Receives My lineup's one-line summary for the home-screen widget. */
    val onLineupSummary: (String) -> Unit = {},
    /** The draft board's ADP and last-season points; null hides Draft. */
    val draft: DraftRepository? = null,
    /** Where the draft's picks are kept. */
    val draftDir: File? = null,
    /** Week-by-week NFL scores; null where a test doesn't need them. */
    val scores: ScoresRepository? = null,
    /** Who moves up when a starter is hurt; null where a test doesn't need it. */
    val opportunities: OpportunitiesRepository? = null,
    /** Rising roles; null where a test doesn't need it. */
    val breakouts: BreakoutRepository? = null,
    /** Past absences, for the Player page's return outlook; null where a test doesn't need it. */
    val returns: InjuryReturnRepository? = null,
)

/**
 * Pushes [key] unless it is already on top, so a double tap (Compare, Edit
 * profiles, a profile row) opens one screen, not two stacked copies that Back
 * would then have to peel off one by one.
 */
internal fun <T> MutableList<T>.push(key: T) {
    if (lastOrNull() != key) add(key)
}

// Stand-ins when there is no refresher (tests): stats exist, nothing is running.
private val STATS_PRESENT: StateFlow<Boolean> = MutableStateFlow(true)
private val NOT_LEGACY: StateFlow<Boolean> = MutableStateFlow(false)
private val IDLE: StateFlow<RefreshState> = MutableStateFlow(RefreshState.Idle)

@Composable
fun GridironNavHost(deps: Deps) {
    val hasStats by (deps.refresher?.hasStats ?: STATS_PRESENT).collectAsState()
    val refreshState by (deps.refresher?.state ?: IDLE).collectAsState()
    if (hasStats) StatsApp(deps, refreshState) else FirstLoad(deps, refreshState)
}

/** A fresh install: the Load stats screen, and the seasons checklist behind it. */
@Composable
private fun FirstLoad(deps: Deps, state: RefreshState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val settings = deps.settings
    if (settingsOpen && settings != null) {
        SettingsScreen(settings, onBack = { settingsOpen = false }, props = deps.props?.status)
    } else {
        LoadStatsScreen(
            state,
            onLoad = { deps.refresher?.refresh() },
            onSettings = if (settings != null) ({ settingsOpen = true }) else null,
        )
    }
}

@Composable
private fun StatsApp(deps: Deps, refreshState: RefreshState) {
    val refresher = deps.refresher
    val context = LocalContext.current
    // Each finished refresh is announced once, then cleared.
    LaunchedEffect(refreshState) {
        val finished = refreshState as? RefreshState.Finished ?: return@LaunchedEffect
        Toast.makeText(context, finished.message, Toast.LENGTH_LONG).show()
        refresher?.acknowledge()
    }
    val refresh: () -> Unit = {
        if (refresher?.refresh() == false) Toast.makeText(context, "A refresh is already running", Toast.LENGTH_SHORT).show()
    }
    LegacyPrompt(refresher, refreshState, refresh)

    val backStack = rememberNavBackStack(GridKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    val season = currentSeason()
    val more = buildList {
        if (deps.opportunities != null) add(MoreItem("Players", "Opportunities", "Who moves up when a starter is hurt") { backStack.push(OpportunitiesKey(season)) })
        if (deps.breakouts != null) add(MoreItem("Players", "Rising roles", "Roles growing over the last four games") { backStack.push(BreakoutsKey(season)) })
        add(MoreItem("Players", "Injury report", "ESPN's live list and practice") { backStack.push(InjuriesKey(season)) })
        add(MoreItem("Players", "Team defense", "Each defense's season") { backStack.push(DefenseKey(season)) })
        if (deps.league != null) add(MoreItem("League", "ESPN leagues", "Sync, teams and matchups") { backStack.push(LeagueKey) })
        if (deps.rosters != null) add(MoreItem("League", "Rosters", "Your saved rosters") { backStack.push(RostersKey) })
        if (deps.draft != null) add(MoreItem("League", "Draft", "ADP board and picks") { backStack.push(DraftKey) })
        add(MoreItem("App", "Projection accuracy", "How the model did, week by week") { backStack.push(AccuracyKey(season)) })
        if (deps.settings != null) add(MoreItem("App", "Settings", "Seasons, alerts and keys") { backStack.push(SettingsKey) })
        if (refresher != null) add(MoreItem("App", "Refresh stats", "Rebuild from nflverse, ESPN and ffopportunity") { refresh() })
    }
    val tabs = buildList {
        add(Tab("Grid", GridironIcons.Table, GridKey))
        add(Tab("Projections", GridironIcons.Trend, ProjectionListKey(season)))
        if (deps.scores != null) add(Tab("Scores", GridironIcons.Scores, ScoresKey(season)))
        if (deps.live != null) add(Tab("News", GridironIcons.News, NewsKey))
        add(Tab("More", GridironIcons.More, MoreKey))
    }
    Column(Modifier.fillMaxSize()) {
        // The bottom bar below takes the navigation bar's inset: screens above it leave it alone.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
            NavDisplay(
                backStack = backStack,
                onBack = back,
                // Each destination gets its own saved state and ViewModelStore, so
                // leaving Compare clears its view model and returning rebuilds it.
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<GridKey> {
                        GridRoute(
                            deps.stats, deps.scoring, deps.tray,
                            onCompare = { backStack.push(CompareKey) },
                            onEditProfiles = { backStack.push(ScoringListKey) },
                            onPlayer = { id, _, _ -> backStack.push(PlayerKey(id)) },
                            menu = buildList<Pair<String, (Int) -> Unit>> {
                                add("Projections" to { s: Int -> backStack.push(ProjectionListKey(s)) })
                                if (deps.opportunities != null) add("Opportunities" to { s: Int -> backStack.push(OpportunitiesKey(s)) })
                                if (deps.breakouts != null) add("Rising roles" to { s: Int -> backStack.push(BreakoutsKey(s)) })
                                add("Projection accuracy" to { s: Int -> backStack.push(AccuracyKey(s)) })
                                if (deps.live != null) add("News" to { _: Int -> backStack.push(NewsKey) })
                                if (deps.scores != null) add("Scores" to { s: Int -> backStack.push(ScoresKey(s)) })
                                add("Injury report" to { s: Int -> backStack.push(InjuriesKey(s)) })
                                if (deps.league != null) add("ESPN leagues" to { _: Int -> backStack.push(LeagueKey) })
                                if (deps.rosters != null) add("Rosters" to { _: Int -> backStack.push(RostersKey) })
                                if (deps.draft != null) add("Draft" to { _: Int -> backStack.push(DraftKey) })
                                add("Team defense" to { s: Int -> backStack.push(DefenseKey(s)) })
                                if (deps.settings != null) add("Settings" to { _: Int -> backStack.push(SettingsKey) })
                                if (refresher != null) add("Refresh stats" to { _: Int -> refresh() })
                            },
                            badges = deps.live?.badges ?: flowOf(emptyMap()),
                            rosters = deps.rosters?.rosters ?: flowOf(emptyList<Roster>()),
                            presets = deps.gridPresets,
                            display = deps.gridDisplay,
                            leagueRostered = deps.league?.rostered ?: flowOf(null),
                            recovery = buildList {
                                if (refresher != null) add("Refresh stats" to { refresh() })
                                if (deps.settings != null) add("Settings" to { backStack.push(SettingsKey) })
                            },
                        )
                    }
                    entry<CompareKey> {
                        CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.push(ScoringListKey) })
                    }
                    entry<ScoringListKey> {
                        ScoringListRoute(deps.scoring, onEdit = { backStack.push(ScoringEditKey(it)) }, onBack = back)
                    }
                    entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
                    entry<ProjectionsKey> { key ->
                        ProjectionsRoute(
                            key.playerId, key.season, key.week, deps.projections, deps.scoring, deps.players, onBack = back,
                            dataVersion = deps.stats.dataVersion,
                        )
                    }
                    entry<ProjectionListKey> { key ->
                        ProjectionListRoute(
                            key.season, deps.projections, deps.scoring, deps.live?.badges ?: flowOf(emptyMap()),
                            onPlayer = { backStack.push(PlayerKey(it)) }, onBack = back, dataVersion = deps.stats.dataVersion,
                            myTeam = deps.league?.myTeam ?: flowOf(null),
                            leagueRostered = deps.league?.rostered ?: flowOf(null),
                            leagues = deps.league?.leagues ?: flowOf(emptyList()),
                            setLeague = { id -> deps.league?.setActive(id) },
                            opponent = { season, week -> deps.league?.opponent(season, week) ?: OpponentResult(null, "no league") },
                            opportunities = { season, profile ->
                                deps.opportunities?.find(season, profile) ?: OpportunitiesResult(emptyList(), 0, null)
                            },
                            otherTeams = deps.league?.otherTeams ?: flowOf(emptyList()),
                            syncLeague = { s -> deps.league?.sync(s) },
                            kickoffs = { s, w -> deps.scores?.week(s, w)?.kickoffs(java.time.Instant.now()) ?: Kickoffs(emptySet(), emptySet()) },
                            playoffPicture = { s, w -> deps.league?.playoffPicture(s, w) ?: PlayoffPictureResult(null, "no league") },
                            lineupReview = { s, w -> deps.league?.lineupReview(s, w) ?: LineupReviewResult(emptyList(), "no league") },
                            liveWeek = { s, w -> deps.scores?.week(s, w) },
                            myMatchup = { s, w, p -> deps.league?.myMatchup(s, w, p) ?: MyMatchup(null, null, "no league") },
                            onLineupSummary = deps.onLineupSummary,
                            tradeOffers = { s -> deps.league?.tradeOffers(s) ?: TradeOffersResult(emptyList(), "no league") },
                        )
                    }
                    entry<OpportunitiesKey> { key ->
                        deps.opportunities?.let { repository ->
                            OpportunitiesRoute(
                                key.season, repository, deps.scoring,
                                onPlayer = { backStack.push(PlayerKey(it)) }, onBack = back, dataVersion = deps.stats.dataVersion,
                                league = deps.league?.rostered ?: flowOf(null),
                                myTeam = deps.league?.myTeam ?: flowOf(null),
                                injuriesChanged = deps.live?.changes ?: flowOf(0L),
                            )
                        }
                    }
                    entry<BreakoutsKey> { key ->
                        deps.breakouts?.let { repository ->
                            BreakoutsRoute(
                                key.season, repository,
                                onPlayer = { backStack.push(PlayerKey(it)) }, onBack = back, dataVersion = deps.stats.dataVersion,
                                league = deps.league?.rostered ?: flowOf(null),
                                myTeam = deps.league?.myTeam ?: flowOf(null),
                            )
                        }
                    }
                    entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, deps.scoring, onBack = back, dataVersion = deps.stats.dataVersion) }
                    entry<InjuriesKey> { key ->
                        InjuriesRoute(
                            key.season, currentSeason(), deps.teams, deps.live, onBack = back,
                            onPlayer = { backStack.push(PlayerKey(it)) }, dataVersion = deps.stats.dataVersion,
                        )
                    }
                    entry<ScoresKey> { key ->
                        deps.scores?.let { scores ->
                            ScoresRoute(
                                key.season, scores,
                                onGame = { week, home, away -> backStack.push(GameKey(key.season, week, home, away)) },
                                onBack = back, dataVersion = deps.stats.dataVersion,
                            )
                        }
                    }
                    entry<GameKey> { key ->
                        deps.scores?.let { scores ->
                            GameRoute(
                                key.season, key.week, key.home, key.away, scores, deps.scoring,
                                onPlayer = { backStack.push(PlayerKey(it)) }, onBack = back, dataVersion = deps.stats.dataVersion,
                            )
                        }
                    }
                    entry<NewsKey> {
                        deps.live?.let { NewsRoute(it, onBack = back, onPlayer = { id -> backStack.push(PlayerKey(id)) }) }
                    }
                    entry<PlayerKey> { key ->
                        PlayerRoute(
                            key.playerId, deps.players, deps.live, onBack = back,
                            projections = deps.projections, scoring = deps.scoring,
                            onProjection = { season, week -> backStack.push(ProjectionsKey(key.playerId, season, week)) },
                            dataVersion = deps.stats.dataVersion,
                            rosterRepo = deps.rosters,
                            onManageRosters = { backStack.push(RostersKey) },
                            playerStats = deps.playerStats,
                            breakouts = deps.breakouts,
                            league = deps.league,
                            inactivesPosted = { s, w -> deps.scores?.week(s, w)?.kickoffs(java.time.Instant.now())?.inactivesPosted.orEmpty() },
                            returns = deps.returns,
                        )
                    }
                    entry<DefenseKey> { key -> DefenseScreen(key.season, deps.teams, onBack = back, dataVersion = deps.stats.dataVersion) }
                    entry<DraftKey> {
                        val draft = deps.draft
                        val dir = deps.draftDir
                        if (draft != null && dir != null) {
                            val team by (deps.league?.myTeam ?: flowOf(null)).collectAsState(initial = null)
                            val others by (deps.league?.otherTeams ?: flowOf(emptyList())).collectAsState(initial = emptyList())
                            val year = java.time.LocalDate.now().year
                            DraftScreen(
                                year, draft::board, deps.scoring.active,
                                teams = if (team != null && others.isNotEmpty()) others.size + 1 else 12,
                                slots = team?.slots, file = File(dir, "draft-$year.txt"), onBack = back,
                            )
                        }
                    }
                    entry<RostersKey> {
                        deps.rosters?.let { RostersScreen(it, deps.players, onBack = back, onPlayer = { id -> backStack.push(PlayerKey(id)) }) }
                    }
                    entry<LeagueKey> {
                        deps.league?.let {
                            LeagueScreen(
                                it, currentSeason(), onBack = back, onPlayer = { id -> backStack.push(PlayerKey(id)) },
                                onMatchups = { backStack.push(MatchupsKey(currentSeason())) },
                            )
                        }
                    }
                    entry<MatchupsKey> { key ->
                        deps.league?.let {
                            MatchupsRoute(
                                it, deps.scoring, key.season, onPlayer = { id -> backStack.push(PlayerKey(id)) },
                                onBack = back, dataVersion = deps.stats.dataVersion,
                            )
                        }
                    }
                    entry<SettingsKey> { deps.settings?.let { SettingsScreen(it, onBack = back, props = deps.props?.status) } }
                    entry<MoreKey> { MoreScreen(more) }
                },
            )
        }
        (refreshState as? RefreshState.Running)?.let { RefreshBar(it.text) }
        BottomBar(tabs, selected = tabs.firstOrNull { it.key == backStack.getOrNull(1) } ?: tabs.first()) { tab ->
            // A tab opens over the Grid, so Back from any tab returns there; the open tab again goes to its top.
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            if (tab.key != GridKey) backStack.add(tab.key)
        }
    }
}

/** One bottom-bar destination. */
private data class Tab(val label: String, val icon: ImageVector, val key: NavKey)

@Composable
private fun BottomBar(tabs: List<Tab>, selected: Tab, onTab: (Tab) -> Unit) {
    NavigationBar(Modifier.testTag("bottomBar")) {
        for (tab in tabs) {
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.testTag("tab:${tab.label}"),
            )
        }
    }
}

/** Stats that came with an older app version: offer, once per launch, to build fresh ones here. */
@Composable
private fun LegacyPrompt(refresher: Refresher?, state: RefreshState, refresh: () -> Unit) {
    val legacy by (refresher?.legacyData ?: NOT_LEGACY).collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!legacy || dismissed || state is RefreshState.Running) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Stats now build on your phone") },
        text = {
            Text(
                "These stats came with an older version of the app. Refresh to build them from nflverse " +
                    "on this phone, about a minute per season. The current stats stay until then.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                refresh()
            }) { Text("Refresh now") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Later") } },
    )
}

/** A running refresh's progress, under whatever screen is open. */
@Composable
private fun RefreshBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
