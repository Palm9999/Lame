package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.Kickoffs
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.OpportunitiesResult
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueChoice
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.LineupReviewResult
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.OpponentResult
import dev.gridiron.core.data.live.PlayoffPictureResult
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.Lineups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

/** ☰ → Projections: the upcoming week or rest of season, by position, scored with the active profile. */
@Composable
public fun ProjectionListRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    badges: Flow<Map<String, String>>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the list loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** The user's ESPN team for "My lineup"; null (or another season's) hides the mode. */
    myTeam: Flow<MyTeam?> = flowOf(null),
    /** Your opponent for the week, fetched each time My lineup opens; the default says there is none. */
    opponent: suspend (season: Int, week: Int) -> OpponentResult = { _, _ -> OpponentResult(null, "not available") },
    /** Everyone on a league team, so the rest can be offered as pickups; null hides the pickups. */
    leagueRostered: Flow<LeagueRostered?> = flowOf(null),
    /** The ESPN leagues the user added; two or more show a switcher, and [onLeague] makes one active. */
    leagues: Flow<List<LeagueChoice>> = flowOf(emptyList()),
    setLeague: suspend (String) -> Unit = {},
    /** Who moves up because a starter is hurt this week; asked when My lineup opens, to mark the pickups. */
    opportunities: suspend (season: Int, profile: ScoringProfile) -> OpportunitiesResult = { _, _ -> OpportunitiesResult(emptyList(), 0, null) },
    /** The league's other teams, for Trade; empty hides the mode. */
    otherTeams: Flow<List<MyTeam>> = flowOf(emptyList()),
    /** Re-reads the league from ESPN; My lineup calls it when the snapshot is older than [LEAGUE_STALE_MILLIS]. */
    syncLeague: suspend (season: Int) -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
    /** NFL teams whose game this week has kicked off (their players are locked) or whose inactives are posted, asked when My lineup opens. */
    kickoffs: suspend (season: Int, week: Int) -> Kickoffs = { _, _ -> Kickoffs(emptySet(), emptySet()) },
    /** The league and its games left, asked when Playoff odds opens. */
    playoffPicture: suspend (season: Int, week: Int) -> PlayoffPictureResult = { _, _ -> PlayoffPictureResult(null, "not available") },
    /** The user's finished weeks against their best lineups, asked when Review opens. */
    lineupReview: suspend (season: Int, throughWeek: Int) -> LineupReviewResult = { _, _ -> LineupReviewResult(emptyList(), "not available") },
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val taken by leagueRostered.collectAsStateWithLifecycle(initialValue = null)
    val choices by leagues.collectAsStateWithLifecycle(initialValue = emptyList())
    val others by otherTeams.collectAsStateWithLifecycle(initialValue = emptyList())
    var rival by remember { mutableStateOf<OpponentState>(OpponentState.Idle) }
    var movingUp by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var locked by remember { mutableStateOf(Kickoffs(emptySet(), emptySet())) }
    var playoffs by remember { mutableStateOf<PlayoffState>(PlayoffState.Idle) }
    var review by remember { mutableStateOf<ReviewState>(ReviewState.Idle) }
    val scope = rememberCoroutineScope()
    suspend fun readKickoffs(week: Int) {
        locked = try {
            kickoffs(season, week)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Kickoffs(emptySet(), emptySet())
        }
    }
    // The week list, Start/sit and My lineup all lift a confirmed-active Questionable player's discount.
    val loadedWeek = (state as? ProjectionListState.Loaded)?.week
    LaunchedEffect(season, loadedWeek) { loadedWeek?.let { readKickoffs(it) } }
    ProjectionListScreen(
        state, injuries, onPlayer, onBack, team?.takeIf { it.season == season }, rival,
        rostered = taken?.takeIf { it.season == season }?.playerIds,
        owners = taken?.takeIf { it.season == season }?.owners,
        starterOut = movingUp,
        leagues = choices,
        onLeague = { id -> scope.launch { setLeague(id) } },
        partners = others.filter { it.season == season },
        started = locked.started,
        inactivesPosted = locked.inactivesPosted,
        playoffs = playoffs,
        review = review,
        onReviewOpened = {
            val week = (state as? ProjectionListState.Loaded)?.week
            if (week != null && review != ReviewState.Loading) {
                review = ReviewState.Loading
                scope.launch {
                    review = ReviewState.Loaded(
                        try {
                            lineupReview(season, week - 1)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LineupReviewResult(emptyList(), "couldn't read your weeks")
                        },
                    )
                }
            }
        },
        onPlayoffsOpened = {
            val week = (state as? ProjectionListState.Loaded)?.week
            if (week != null && playoffs != PlayoffState.Loading) {
                playoffs = PlayoffState.Loading
                scope.launch {
                    val result = try {
                        playoffPicture(season, week)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        PlayoffPictureResult(null, "couldn't read the schedule")
                    }
                    playoffs = result.picture?.let(PlayoffState::Loaded) ?: PlayoffState.Unavailable(result.message ?: "no schedule")
                }
            }
        },
        onLineupOpened = {
            (state as? ProjectionListState.Loaded)?.week?.let { week -> scope.launch { readKickoffs(week) } }
            val fetched = taken?.fetchedAtMillis
            if (fetched != null && now() - fetched > LEAGUE_STALE_MILLIS) {
                scope.launch {
                    try {
                        syncLeague(season)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The saved snapshot stays; ESPN leagues shows sync errors.
                    }
                }
            }
            profile?.let { p ->
                scope.launch {
                    val found = try {
                        opportunities(season, p).rows
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    movingUp = found.mapNotNull { r -> injuredNote(r)?.let { r.beneficiary.player.playerId to it } }.toMap()
                }
            }
            val week = (state as? ProjectionListState.Loaded)?.week
            if (week != null && rival != OpponentState.Loading) {
                rival = OpponentState.Loading
                scope.launch {
                    val result = opponent(season, week)
                    rival = result.team?.let(OpponentState::Loaded) ?: OpponentState.Unavailable(result.message ?: "no opponent found")
                }
            }
        },
    )
}

private enum class ListMode { WEEK, ROS, LINEUP, TRADE, START_SIT, PLAYOFFS, REVIEW, PLANNER }

/** My lineup re-syncs the league when its snapshot is older than this: lineups, waivers and trades move during the week. */
internal const val LEAGUE_STALE_MILLIS: Long = 30 * 60 * 1000L

@Composable
public fun ProjectionListScreen(
    state: ProjectionListState,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    myTeam: MyTeam? = null,
    opponent: OpponentState = OpponentState.Idle,
    /** My lineup was opened: the route fetches the opponent. */
    onLineupOpened: () -> Unit = {},
    /** Everyone on a league team; null when unknown, which hides the waiver pickups. */
    rostered: Set<String>? = null,
    /** Which league team has each rostered player, for the handcuffs; null when unknown. */
    owners: Map<String, String>? = null,
    /** Pickups moving up because a starter is hurt, by player id: "RB1 Name is Doubtful". */
    starterOut: Map<String, String> = emptyMap(),
    /** The user's ESPN leagues: with two or more, a chip each switches the league My lineup follows. */
    leagues: List<LeagueChoice> = emptyList(),
    onLeague: (String) -> Unit = {},
    /** The league's other teams: with your team known, a Trade mode weighs trades with them. */
    partners: List<MyTeam> = emptyList(),
    /** NFL teams whose game this week has started: My lineup keeps their players where ESPN has them. */
    started: Set<String> = emptySet(),
    /** NFL teams whose inactives are posted: the week's lists drop the Questionable discount of their players ESPN doesn't rule out. */
    inactivesPosted: Set<String> = emptySet(),
    playoffs: PlayoffState = PlayoffState.Idle,
    /** Playoff odds was opened: the route reads the league's schedule. */
    onPlayoffsOpened: () -> Unit = {},
    review: ReviewState = ReviewState.Idle,
    /** Review was opened: the route reads the finished weeks. */
    onReviewOpened: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FLEX) }
    var chosen by rememberSaveable { mutableStateOf(ListMode.WEEK) }
    val canTrade = myTeam != null && partners.isNotEmpty()
    val mode = when {
        (chosen == ListMode.LINEUP || chosen == ListMode.REVIEW || chosen == ListMode.PLANNER) && myTeam == null -> ListMode.WEEK
        (chosen == ListMode.TRADE || chosen == ListMode.PLAYOFFS) && !canTrade -> ListMode.WEEK
        else -> chosen
    }
    LaunchedEffect(mode, myTeam?.teamName) {
        if (mode == ListMode.LINEUP) onLineupOpened()
        if (mode == ListMode.PLAYOFFS) onPlayoffsOpened()
        if (mode == ListMode.REVIEW) onReviewOpened()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Projections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                ProjectionListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ProjectionListState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is ProjectionListState.Loaded -> {
                    Text(
                        statusLine(state.week, state.builtAt),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (leagues.size > 1) {
                        Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (l in leagues) {
                                FilterChip(selected = l.active, onClick = { onLeague(l.leagueId) }, label = { Text(l.name) }, modifier = Modifier.testTag("league:${l.leagueId}"))
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == ListMode.WEEK, onClick = { chosen = ListMode.WEEK }, label = { Text("Week ${state.week}") })
                        FilterChip(selected = mode == ListMode.ROS, onClick = { chosen = ListMode.ROS }, label = { Text("Rest of season") })
                        if (myTeam != null) {
                            FilterChip(
                                selected = mode == ListMode.LINEUP,
                                onClick = { chosen = ListMode.LINEUP },
                                label = { Text("My lineup") },
                                modifier = Modifier.testTag("chip:lineup"),
                            )
                            FilterChip(
                                selected = mode == ListMode.PLANNER,
                                onClick = { chosen = ListMode.PLANNER },
                                label = { Text("Planner") },
                                modifier = Modifier.testTag("chip:planner"),
                            )
                            FilterChip(
                                selected = mode == ListMode.REVIEW,
                                onClick = { chosen = ListMode.REVIEW },
                                label = { Text("Review") },
                                modifier = Modifier.testTag("chip:review"),
                            )
                        }
                        FilterChip(
                            selected = mode == ListMode.START_SIT,
                            onClick = { chosen = ListMode.START_SIT },
                            label = { Text("Start/sit") },
                            modifier = Modifier.testTag("chip:startsit"),
                        )
                        if (canTrade) {
                            FilterChip(
                                selected = mode == ListMode.TRADE,
                                onClick = { chosen = ListMode.TRADE },
                                label = { Text("Trade") },
                                modifier = Modifier.testTag("chip:trade"),
                            )
                            FilterChip(
                                selected = mode == ListMode.PLAYOFFS,
                                onClick = { chosen = ListMode.PLAYOFFS },
                                label = { Text("Playoff odds") },
                                modifier = Modifier.testTag("chip:playoffs"),
                            )
                        }
                    }
                    // Once a team's inactives are posted, its Questionable players ESPN hasn't ruled out are playing.
                    val weekRows = remember(state, badges, inactivesPosted) { state.weekRows.map { confirmedActive(it, badges, inactivesPosted) } }
                    if (mode == ListMode.START_SIT) {
                        StartSitView(weekRows, badges)
                    } else if (mode == ListMode.PLANNER && myTeam != null) {
                        PlannerView(myTeam, state, rostered, onPlayer)
                    } else if (mode == ListMode.REVIEW) {
                        ReviewView(review)
                    } else if (mode == ListMode.PLAYOFFS) {
                        PlayoffsView(playoffs, state.rosRows, state.rosWeekly)
                    } else if (mode == ListMode.TRADE && myTeam != null) {
                        TradeView(myTeam, partners, state.rosRows, state.rosWeekly)
                    } else if (mode == ListMode.LINEUP && myTeam != null) {
                        val rival = (opponent as? OpponentState.Loaded)?.let { lineupView(it.team, state.week, weekRows, badges, started) }
                        val pickups = if (rostered == null) null else remember(myTeam, weekRows, badges, rostered, starterOut, started) { waiverPickups(myTeam, weekRows, badges, rostered, starterOut, started) }
                        // Rest of season keeps an injured player's projection, as its list does.
                        val stashes = if (rostered == null) null else remember(myTeam, state, rostered) { rosAdds(myTeam, state.rosRows, rostered, state.rosWeekly) }
                        val mine = lineupView(myTeam, state.week, weekRows, badges, started)
                        val cuffs = remember(myTeam, weekRows, owners) { handcuffs(myTeam, weekRows, owners.orEmpty()) }
                        LineupList(
                            mine, rival, opponent, pickups, badges, onPlayer, stashes, lineupCheck(myTeam, mine, weekRows, badges), faabText(myTeam),
                            cuffs, ownersKnown = owners != null,
                        )
                    } else {
                        Row(
                            Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (t in PositionTab.entries) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                        }
                        val rows = visibleRows(if (mode == ListMode.WEEK) weekRows else state.rosRows, tab, badges, mode == ListMode.WEEK)
                        // League size and slots from the synced league, else a standard twelve-team league.
                        val teams = if (myTeam != null && partners.isNotEmpty()) partners.size + 1 else 12
                        val slots = myTeam?.slots ?: Lineups.DEFAULT_SLOTS
                        val valued = remember(rows, tab, teams, slots) { if (tab == PositionTab.VALUE) valueRows(rows, teams, slots) else null }
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            if (valued != null) {
                                item {
                                    Text(
                                        "Points over the best player left at his position once $teams teams fill their starting slots.",
                                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                itemsIndexed(valued, key = { _, (row, _) -> row.playerId }) { i, (row, value) ->
                                    ProjectionListRow("${i + 1}", row, badges[row.playerId], onPlayer, note = valueText(value))
                                }
                            } else {
                                itemsIndexed(rows, key = { _, row -> row.playerId }) { i, row ->
                                    ProjectionListRow("${i + 1}", row, badges[row.playerId], onPlayer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineupList(
    view: LineupView,
    rival: LineupView?,
    opponent: OpponentState,
    pickups: List<PickupLine>?,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
    /** Free agents ranked by how far each lifts the lineup over the rest of the season; null hides them. */
    stashes: List<PickupLine>? = null,
    /** The lineup set in ESPN against the best one; null when the snapshot has none set. */
    check: LineupCheck? = null,
    /** "$63 of $100 FAAB left…" under the rest-of-season adds; null when the league doesn't bid. */
    faab: String? = null,
    handcuffs: List<HandcuffLine> = emptyList(),
    /** Whether a handcuff's owner is known (the league is synced): otherwise it isn't said. */
    ownersKnown: Boolean = false,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("lineup:list")) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${view.teamName} · week ${view.week}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Projected ${points(view.total)} pts",
                    Modifier.testTag("lineup:total"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (view.spread > 0.0) {
                    Text(
                        "Likely ${points(view.low)}–${points(view.high)}",
                        Modifier.testTag("lineup:range"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val versus = when {
                    rival != null ->
                        "vs ${rival.teamName}: ${points(rival.total)} pts · ${matchupLine(view.total, rival.total)} · " +
                            winLine(winChance(view, rival))
                    opponent == OpponentState.Loading -> "Checking your opponent…"
                    opponent is OpponentState.Unavailable -> "No comparison: ${opponent.message}."
                    else -> null
                }
                versus?.let {
                    Text(it, Modifier.testTag("lineup:vs"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                check?.let { c ->
                    val text = if (c.gain < 0.05) {
                        "Your ESPN lineup is already the best (as of your last sync)."
                    } else {
                        buildString {
                            append("Your ESPN lineup: ${points(c.current)} pts. Best: +${points(c.gain)}.")
                            if (c.start.isNotEmpty()) append(" Start ${c.start.joinToString { it.name }}.")
                            if (c.sit.isNotEmpty()) append(" Sit ${c.sit.joinToString { it.name + if (it.points == null) " (no projection)" else "" }}.")
                        }
                    }
                    Text(
                        text,
                        Modifier.testTag("lineup:check"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.gain < 0.05) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                        fontWeight = if (c.gain < 0.05) FontWeight.Normal else FontWeight.SemiBold,
                    )
                }
                if (view.defaultSlots) {
                    Text(
                        "Using the usual slots (QB, 2 RB, 2 WR, TE, FLEX, K, D/ST). Sync your league again to use yours.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(view.starters, key = { i, line -> "s:$i:${line.slot}" }) { _, line ->
            if (line.row != null) {
                ProjectionListRow(if (line.locked) "${line.slot} 🔒" else line.slot, line.row, badges[line.row.playerId], onPlayer, LeadWidth)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.slot, Modifier.width(LeadWidth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No one can fill this slot", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (view.bench.isNotEmpty()) {
            item { SectionLabel("Bench") }
            itemsIndexed(view.bench, key = { _, row -> "b:${row.playerId}" }) { _, row ->
                ProjectionListRow("BE", row, badges[row.playerId], onPlayer, LeadWidth)
            }
        }
        if (pickups != null) {
            item { SectionLabel("Waiver pickups") }
            if (pickups.isEmpty()) {
                item {
                    Text(
                        "No pickup helps this week.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(pickups, key = { _, p -> "p:${p.add.playerId}" }) { _, pick -> PickupRow(pick, onPlayer) }
            }
        }
        if (!stashes.isNullOrEmpty()) {
            item { SectionLabel("Rest-of-season adds") }
            faab?.let { text ->
                item {
                    Text(
                        text,
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("faab"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(stashes, key = { _, p -> "r:${p.add.playerId}" }) { _, pick -> PickupRow(pick, onPlayer, "ros:") }
        }
        if (handcuffs.isNotEmpty()) {
            item { SectionLabel("Handcuffs") }
            itemsIndexed(handcuffs, key = { _, h -> "h:${h.starter.playerId}" }) { _, h ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPlayer(h.backup.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("handcuff:${h.starter.playerId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${h.backup.name} backs up ${h.starter.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                "${points(h.backup.points)} pts now",
                                when {
                                    !ownersKnown -> null
                                    h.owner == null -> "free agent"
                                    h.owner == "you" -> "yours"
                                    else -> "on ${h.owner}"
                                },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(points(h.ifOut), Modifier.testTag("handcuff:if:${h.starter.playerId}"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("if he sits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (view.unlisted.isNotEmpty()) {
            item { SectionLabel("Not projected") }
            itemsIndexed(view.unlisted, key = { i, u -> "u:$i:${u.name}" }) { _, u ->
                Text(
                    "${u.name} · ${if (u.matched) "no projection this week" else "not matched to the app's players"}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PickupRow(pick: PickupLine, onPlayer: (String) -> Unit, tagPrefix: String = "") {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(pick.add.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("${tagPrefix}pickup:${pick.add.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Add ${pick.add.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(Position.label(pick.add.position), pick.add.team).joinToString(" · ") +
                    " · ${points(pick.add.points)} pts · starts at ${pick.slot}" + (pick.replaces?.let { ", replacing ${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pick.starterOut?.let {
                Text("▲ $it", Modifier.testTag("pickup:out:${pick.add.playerId}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            pick.drop?.let {
                Text("Drop ${it.name} (${points(it.points)} pts)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pick.playoffPoints?.let {
                Text("Playoff weeks: ${points(it)} pts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("+${points(pick.gain)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            pick.bid?.let {
                Text("Bid $$it", Modifier.testTag("${tagPrefix}bid:${pick.add.playerId}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val LeadWidth = 64.dp

@Composable
private fun ProjectionListRow(lead: String, row: ProjectionRow, badge: String?, onPlayer: (String) -> Unit, leadWidth: Dp = 32.dp, note: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(row.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lead, Modifier.width(leadWidth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                badge?.takeIf { it != "A" }?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it in setOf("O", "IR", "D")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                listOfNotNull(Position.label(row.position), row.team, row.tdChance?.takeIf { !row.out }?.let(::tdText), note).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (row.out) "Out" else points(row.points), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (!row.out) {
                Text(
                    "${points(row.floor)}–${points(row.ceiling)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun points(value: Double): String = String.format(Locale.US, "%.1f", value)
