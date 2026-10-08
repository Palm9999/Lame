package dev.gridiron.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.BreakoutRepository
import dev.gridiron.core.data.BreakoutRow
import dev.gridiron.core.charts.ordinal
import dev.gridiron.core.data.COMP_METRICS
import dev.gridiron.core.data.CompSeason
import dev.gridiron.core.data.CompsRepository
import dev.gridiron.core.data.DynastyValue
import dev.gridiron.core.data.InjuryReturnRepository
import dev.gridiron.core.data.TdRegressionRepository
import dev.gridiron.core.data.tdRegressionLine
import dev.gridiron.core.model.ScoringProfile
import java.util.Locale
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.PlayerStatsRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ReturnOutlook
import dev.gridiron.core.data.RosterRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.basisText
import dev.gridiron.core.data.chancesText
import dev.gridiron.core.data.live.DEFAULT_PLAYOFF_WEEKS
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.designsystem.ColumnChart
import dev.gridiron.core.designsystem.StatusBadge
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.Roster
import dev.gridiron.feature.projections.PlayerShareCard
import dev.gridiron.feature.projections.ProjectionCard
import dev.gridiron.core.ui.SharePreview
import dev.gridiron.feature.projections.ThisWeekCard
import dev.gridiron.feature.projections.loadProjectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant

/** Everything the Player page shows. */
data class PlayerPage(
    val header: PlayerHeader?,
    val status: LiveStatus?,
    val notes: List<InjuryNote>,
    val news: List<NewsItem>,
    val asOf: Instant?,
    val projection: ProjectionCard? = null,
    /** His Rising roles signal, when his role is growing; null otherwise. */
    val risingRole: BreakoutRow? = null,
    /** The Season stats section, or null when there is no repository, profile or load yet. */
    val stats: PlayerStats? = null,
    /** The section failed to load: it says so and the rest of the page stays. */
    val statsUnavailable: Boolean = false,
    /** How soon he is likely back, while he is Out, Doubtful or on IR; null otherwise or with too little history. */
    val returnOutlook: ReturnOutlook? = null,
    /** The five closest seasons by other players at his position (`CompsRepository`); empty without enough games. */
    val comps: List<CompSeason> = emptyList(),
    /** "5,200 · 34th of 420 · 8th of 95 RBs · redraft 3,100": his FantasyCalc dynasty value; null when unpriced. */
    val dynasty: String? = null,
    /** His touchdowns against expected (`tdRegressionLine`); null when he isn't on the TD regression board. */
    val tdRegression: String? = null,
)

private val NO_CHANGES: StateFlow<Long> = MutableStateFlow(0L)

@Composable
fun PlayerRoute(
    playerId: String,
    players: PlayerDirectory?,
    live: LiveRepository?,
    onBack: () -> Unit,
    projections: ProjectionsRepository? = null,
    scoring: ScoringRepository? = null,
    onProjection: (season: Int, week: Int) -> Unit = { _, _ -> },
    /** Bumped when a refresh swaps in new stats: the page loads again. */
    dataVersion: Flow<Long> = NO_CHANGES,
    rosterRepo: RosterRepository? = null,
    onManageRosters: () -> Unit = {},
    playerStats: PlayerStatsRepository? = null,
    breakouts: BreakoutRepository? = null,
    /** The active ESPN league, for its playoff weeks; null uses 15-17. */
    league: FantasyLeagueRepository? = null,
    /** NFL teams whose inactives are posted in a week (ESPN's scoreboard): a confirmed-active Questionable player's card loses his discount. */
    inactivesPosted: suspend (season: Int, week: Int) -> Set<String> = { _, _ -> emptySet() },
    /** Past absences, for how soon an injured player is likely back. */
    returns: InjuryReturnRepository? = null,
    /** FantasyCalc's dynasty values under the profile's format; empty leaves the line out. */
    dynastyValues: suspend (profile: ScoringProfile) -> List<DynastyValue> = { emptyList() },
    /** Similar seasons by other players. */
    comps: CompsRepository? = null,
    /** Opens another player's page (a similar season). */
    onPlayer: (String) -> Unit = {},
    /** Touchdowns against expected this season. */
    tdRegression: TdRegressionRepository? = null,
) {
    val rosters by remember(rosterRepo) { rosterRepo?.rosters ?: flowOf(emptyList<Roster>()) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val version by (live?.changes ?: NO_CHANGES).collectAsState()
    val stats by dataVersion.collectAsState(initial = 0L)
    val profile by remember(scoring) { scoring?.active ?: flowOf(null) }.collectAsState(initial = null)
    var page by remember(playerId) { mutableStateOf<PlayerPage?>(null) }
    LaunchedEffect(Unit) { live?.refreshIfStale() }
    LaunchedEffect(playerId, version, stats, profile) {
        val header = players?.header(playerId)
        val status = live?.status(playerId)
        val active = profile
        val card = if (projections != null && active != null) {
            try {
                // The league is only for its playoff weeks: failing to read it never costs the card.
                val playoffWeeks = try {
                    league?.myTeam?.first()?.playoffWeeks
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: DEFAULT_PLAYOFF_WEEKS
                loadProjectionCard(
                    projections, playerId, header?.team, active, header?.position?.let(Position::fromCode), status?.abbr,
                    playoffWeeks = playoffWeeks, inactivesPosted = inactivesPosted,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // a projection that can't be read never blocks the rest of the page
            }
        } else {
            null
        }
        // Only while his role is growing, and only for the season the projection card is about.
        val rising = if (breakouts != null && card != null) breakouts.forPlayer(playerId, card.season)?.takeIf { it.score > 0.0 } else null
        val outlook = try {
            returns?.outlook(playerId, card?.season ?: currentSeason(), status?.abbr, stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // never costs the rest of the page
        }
        val dynasty = try {
            active?.let { dynastyLine(playerId, dynastyValues(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // never costs the rest of the page
        }
        val similar = try {
            comps?.comps(playerId, card?.season ?: currentSeason(), header?.position).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // never costs the rest of the page
        }
        val tds = try {
            tdRegression?.board(card?.season ?: currentSeason())?.let { b ->
                b.rows.firstOrNull { it.playerId == playerId }?.let { tdRegressionLine(it, b.throughWeek) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // never costs the rest of the page
        }
        page = PlayerPage(
            header = header,
            status = status,
            notes = live?.notes(playerId).orEmpty(),
            news = live?.playerNews(playerId).orEmpty(),
            asOf = live?.fetchedAt(),
            projection = card,
            risingRole = rising,
            returnOutlook = outlook,
            comps = similar,
            dynasty = dynasty,
            tdRegression = tds,
        )
    }
    var season by remember(playerId) { mutableStateOf<Int?>(null) }
    var loaded by remember(playerId) { mutableStateOf<PlayerStats?>(null) }
    var loadFailed by remember(playerId) { mutableStateOf(false) }
    val pageHeader = page?.header
    val pageLoaded = page != null
    // Reloads on a refresh, a profile change and a season chip; a newer key cancels an older load.
    LaunchedEffect(playerId, stats, profile, season, pageLoaded, pageHeader) {
        val repo = playerStats
        val active = profile
        if (repo == null || active == null || !pageLoaded) return@LaunchedEffect
        try {
            loaded = repo.stats(playerId, pageHeader?.position?.let(Position::fromCode), active, season)
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loaded = null
            loadFailed = true // never blocks the rest of the page
        }
    }
    val uri = LocalUriHandler.current
    PlayerScreen(
        playerId, page?.copy(stats = loaded, statsUnavailable = loadFailed), liveAvailable = live != null, onBack = onBack, onOpen = { uri.openSafely(it) }, onProjection = onProjection,
        rosters = if (rosterRepo != null) rosters else null,
        onRosterToggle = { id, on ->
            rosterRepo?.let { repo -> scope.launch { if (on) repo.add(id, playerId) else repo.remove(id, playerId) } }
        },
        onManageRosters = onManageRosters,
        onSeason = { season = it },
        onPlayer = onPlayer,
    )
}

@Composable
fun PlayerScreen(
    playerId: String,
    page: PlayerPage?,
    liveAvailable: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onProjection: (season: Int, week: Int) -> Unit = { _, _ -> },
    onSeason: (Int) -> Unit = {},
    /** The user's rosters, or null to hide the section. */
    rosters: List<Roster>? = null,
    onRosterToggle: (rosterId: String, on: Boolean) -> Unit = { _, _ -> },
    onManageRosters: () -> Unit = {},
    onPlayer: (String) -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            var sharing by remember { mutableStateOf(false) }
            val shared = page.projection
            if (sharing && shared != null) {
                val name = page.header?.name ?: playerId
                SharePreview("gridiron-player.png", onDismiss = { sharing = false }) {
                    PlayerShareCard(name, listOfNotNull(page.header?.position?.let(Position::label), page.header?.team).joinToString(" · "), shared)
                }
            }
            LazyColumn(Modifier.testTag("playerPage")) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            page.header?.name ?: playerId,
                            Modifier.testTag("playerName"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val detail = listOfNotNull(page.header?.position?.let(Position::label), page.header?.team)
                        if (detail.isNotEmpty()) {
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                page.projection?.let { card ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionTitle("This week") }
                            TextButton(onClick = { sharing = true }, modifier = Modifier.testTag("share:player")) { Text("Share") }
                        }
                    }
                    item { ThisWeekCard(card, onOpen = { onProjection(card.season, card.week) }) }
                }
                page.risingRole?.let { row ->
                    item { SectionTitle("Rising role") }
                    item { RisingRoleLine(row) }
                }
                page.tdRegression?.let { text ->
                    item { SectionTitle("Touchdowns vs expected") }
                    item { Text(text, Modifier.padding(horizontal = 16.dp).testTag("tdRegression"), style = MaterialTheme.typography.bodyMedium) }
                }
                page.dynasty?.let { text ->
                    item { SectionTitle("Dynasty value") }
                    item { Text(text, Modifier.padding(horizontal = 16.dp).testTag("dynasty"), style = MaterialTheme.typography.bodyMedium) }
                }
                if (page.comps.isNotEmpty()) {
                    item { SectionTitle("Similar seasons") }
                    items(page.comps, key = { "comp:${it.playerId}:${it.season}" }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onPlayer(c.playerId) }.padding(horizontal = 16.dp, vertical = 4.dp).testTag("comp:${c.playerId}")) {
                            Text("${c.name} · ${c.season}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(compLine(page.header?.position, c), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                rosters?.let { list ->
                    item { SectionTitle("Rosters") }
                    item { RosterToggles(playerId, list, onRosterToggle, onManageRosters) }
                }
                page.stats?.let { s -> playerStatsItems(s, onSeason) }
                if (page.statsUnavailable) {
                    item { SectionTitle("Season stats") }
                    item { Text("Season stats aren't available.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                item { SectionTitle("Status") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val s = page.status
                        when {
                            !liveAvailable -> Text("Live injuries and news aren't available.", style = MaterialTheme.typography.bodySmall)
                            s == null -> Text("No injury designation.", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusBadge(s.abbr)
                                    Text(s.status, Modifier.padding(start = 8.dp), color = injuryColor(s.abbr), fontWeight = FontWeight.Bold)
                                }
                                s.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                s.longComment?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        page.returnOutlook?.let { ReturnOutlookLine(it) }
                    }
                }
                if (page.notes.isNotEmpty()) {
                    item { SectionTitle("Injury notes") }
                    items(page.notes) { note ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "${formatWhen(note.notedAt)} · ${note.status}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(note.comment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (liveAvailable) {
                    item { SectionTitle("News") }
                    if (page.news.isEmpty()) {
                        item { Text("No recent news.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(page.news, key = { it.id }) { n ->
                            Column(Modifier.fillMaxWidth().clickable { onOpen(n.url) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(n.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(formatWhen(n.published), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                page.asOf?.let { asOf ->
                    item {
                        Text(
                            "ESPN · as of ${formatWhen(asOf)}",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterToggles(playerId: String, rosters: List<Roster>, onToggle: (String, Boolean) -> Unit, onManage: () -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp)) {
        for (roster in rosters) {
            val on = playerId in roster.playerIds
            FilterChip(
                selected = on,
                onClick = { onToggle(roster.id, !on) },
                label = { Text(roster.name) },
                modifier = Modifier.padding(end = 8.dp).testTag("roster:${roster.id}"),
            )
        }
        TextButton(onClick = onManage) { Text(if (rosters.isEmpty()) "Create a roster" else "Manage") }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

/** "Played again by: wk 7 28% · wk 8 53%…" and where the numbers come from. */
@Composable
private fun ReturnOutlookLine(outlook: ReturnOutlook) {
    Column(Modifier.padding(top = 12.dp).testTag("player:return")) {
        Text("Chance he has played again by", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        ColumnChart(
            labels = outlook.weeks.map { "Wk $it" },
            values = outlook.chances,
            valueText = { "${Math.round(it * 100).coerceIn(0, 100)}%" },
            description = "Played again by: ${outlook.chancesText()}",
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            max = 1.0,
            height = 56.dp,
            detail = { i -> "By week ${outlook.weeks[i]}: ${Math.round(outlook.chances[i] * 100).coerceIn(0, 100)}% chance he has played" },
        )
        Text(outlook.basisText(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Red for Out, IR and Doubtful; the accent for Questionable. */
@Composable
internal fun injuryColor(abbr: String): Color = when (abbr) {
    "O", "IR", "D" -> MaterialTheme.colorScheme.error
    "Q" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

/** "Role growing · 72" over why: the Rising roles signal in one line, tagged for tests. */
@Composable
private fun RisingRoleLine(row: BreakoutRow) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("risingRole"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Role growing", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(String.format(java.util.Locale.US, "%.0f", row.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** [playerId]'s line from FantasyCalc's [values] (best first), places among players and at his position; null when unpriced. */
internal fun dynastyLine(playerId: String, values: List<DynastyValue>): String? {
    val v = values.firstOrNull { it.playerId == playerId } ?: return null
    val atPosition = values.count { it.position == v.position }
    return listOf(
        String.format(Locale.US, "%,d", v.value),
        "${ordinal(v.rank)} of ${values.size}",
        "${ordinal(v.positionRank)} of $atPosition ${v.position}s",
        "redraft ${String.format(Locale.US, "%,d", v.redraftValue)}",
    ).joinToString(" · ")
}

/** "6.1 TGT · 4.2 REC · 61 REC YDS a game": the first three of his position's comp metrics. */
internal fun compLine(position: String?, c: CompSeason): String {
    val names = COMP_METRICS[position].orEmpty().take(3).map { COMP_LABELS[it] ?: it }
    val line = names.zip(c.perGame).joinToString(" · ") { (n, v) -> String.format(Locale.US, if (v >= 20) "%.0f %s" else "%.1f %s", v, n) } + " a game"
    return c.age?.let { String.format(Locale.US, "Age %.0f · %s", Math.floor(it), line) } ?: line
}

private val COMP_LABELS = mapOf(
    "attempts" to "ATT", "passing_yards" to "PASS YDS", "passing_tds" to "PASS TD", "carries" to "CAR",
    "rushing_yards" to "RUSH YDS", "rushing_tds" to "RUSH TD", "targets" to "TGT", "receptions" to "REC", "receiving_yards" to "REC YDS",
    "fg_att" to "FGA", "fg_made" to "FGM", "fg_made_50" to "50+", "points_allowed" to "PA", "yards_allowed" to "YA", "dst_sacks" to "SACK",
)
