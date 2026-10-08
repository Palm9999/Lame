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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.DynastyResult
import dev.gridiron.core.data.DynastyValue
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.DraftResult
import dev.gridiron.core.data.live.FantasyLeague
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.datastore.KeeperRule
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.model.ScoringProfile
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** More → Dynasty & keepers: FantasyCalc's dynasty values, and which of your players to keep by draft-round cost. */
@Composable
public fun DynastyRoute(
    season: Int,
    dynasty: suspend (ScoringProfile) -> DynastyResult,
    draft: suspend (Int) -> DraftResult?,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    league: Flow<FantasyLeague?> = flowOf(null),
    rostered: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
    keeperRule: Flow<KeeperRule?> = flowOf(null),
    setKeeperRule: suspend (KeeperRule) -> Unit = {},
) {
    val vm: DynastyViewModel = viewModel(factory = DynastyViewModel.factory(dynasty, draft))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val synced by league.collectAsStateWithLifecycle(initialValue = null)
    val taken by rostered.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val rule by keeperRule.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val current = synced?.takeIf { it.season == season }
    LaunchedEffect(season, profile, current?.leagueId, current?.fetchedAtMillis) { profile?.let { vm.load(season, it) } }
    DynastyScreen(
        state,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season },
        rule,
        teams = current?.teams?.size?.takeIf { it > 0 } ?: 12,
        leagueRounds = current?.undraftedRound ?: 16,
        onRule = { r -> scope.launch { setKeeperRule(r) } },
        onPlayer = onPlayer,
        onBack = onBack,
    )
}

private val DYNASTY_POSITIONS = listOf(null, "QB", "RB", "WR", "TE")

@Composable
public fun DynastyScreen(
    state: DynastyState,
    league: LeagueRostered?,
    team: MyTeam?,
    rule: KeeperRule?,
    teams: Int,
    leagueRounds: Int,
    onRule: (KeeperRule) -> Unit,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var keepersTab by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Dynasty & keepers", onBack)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !keepersTab, onClick = { keepersTab = false }, label = { Text("Dynasty") }, modifier = Modifier.testTag("tab:dynasty"))
                FilterChip(selected = keepersTab, onClick = { keepersTab = true }, label = { Text("Keepers") }, modifier = Modifier.testTag("tab:keepers"))
            }
            when (state) {
                DynastyState.Loading -> LoadingRows()
                is DynastyState.Loaded -> if (keepersTab) {
                    KeepersTab(state, team, rule, teams, leagueRounds, onRule, onPlayer)
                } else {
                    DynastyTab(state.dynasty, league, team?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(), onPlayer)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false, tag: String? = null) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DynastyTab(result: DynastyResult, league: LeagueRostered?, mine: Set<String>, onPlayer: (String) -> Unit) {
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    Note("FantasyCalc's dynasty values, from real trades, in your league's format.")
    result.error?.let { Note("Not updated: $it.", error = true) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in DYNASTY_POSITIONS) {
            FilterChip(selected = position == p, onClick = { position = p }, label = { Text(p ?: "All") }, modifier = Modifier.testTag("pos:${p ?: "all"}"))
        }
        if (league != null) {
            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free agents") }, modifier = Modifier.testTag("chip:free"))
        }
    }
    val rows = result.values
        .filter { position == null || it.position == position }
        .map { it to it.playerId?.let { id -> ownerOf(id, league, mine) } }
        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
    if (rows.isEmpty()) Note("None to show.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { (v, _) -> "${v.rank}:${v.name}" }) { (v, owner) -> DynastyRow(v, owner, onPlayer) }
    }
}

@Composable
private fun DynastyRow(v: DynastyValue, owner: Owner?, onPlayer: (String) -> Unit) {
    val id = v.playerId
    Row(
        Modifier.fillMaxWidth().then(if (id != null) Modifier.playerClick(id, onPlayer) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("dyn:${v.rank}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${v.rank}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull("${v.position}${v.positionRank}", v.team, v.age?.let { "age ${String.format(Locale.US, "%.1f", it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            owner?.let { OwnerTag(it) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${v.value}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "30 days ${if (v.trend30 > 0) "+" else ""}${v.trend30}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OwnerTag(owner: Owner) {
    Text(
        when (owner) {
            Owner.FreeAgent -> "Free agent"
            Owner.Yours -> "Yours"
            is Owner.Other -> "On ${owner.team}"
        },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (owner == Owner.FreeAgent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KeepersTab(
    state: DynastyState.Loaded,
    team: MyTeam?,
    rule: KeeperRule?,
    teams: Int,
    leagueRounds: Int,
    onRule: (KeeperRule) -> Unit,
    onPlayer: (String) -> Unit,
) {
    if (team == null || rule == null || state.draft == null) {
        Note("Sync an ESPN league and choose your team to see keepers.", tag = "keepers:none")
        return
    }
    val undrafted = rule.undraftedRound ?: leagueRounds
    Note("Cost: the round he was drafted (a keeper pick ${rule.penalty} earlier); worth: where his redraft value goes in a $teams-team draft.")
    state.dynasty.error?.let { Note("Values not updated: $it.", error = true) }
    when {
        state.draft.error != null -> Note("Draft not read: ${state.draft.error}. Every player costs round $undrafted.", error = true)
        state.draft.picks.isEmpty() -> Note("No draft found: every player costs round $undrafted.", tag = "keepers:nodraft")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Stepper("Keep", rule.keepers, min = 0, tag = "rule:keepers") { onRule(rule.copy(keepers = it)) }
        Stepper("Penalty", rule.penalty, min = 0, tag = "rule:penalty") { onRule(rule.copy(penalty = it)) }
        Stepper("Undrafted round", undrafted, min = 1, tag = "rule:undrafted") { onRule(rule.copy(undraftedRound = it)) }
    }
    val rows = keeperRows(team, state.draft.picks, state.dynasty.values, rule, teams, leagueRounds)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.row.playerId }) { r ->
            KeeperListItem(r, editing == r.row.playerId, rule, onRule, onEdit = { editing = if (editing == it) null else it }, onPlayer)
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, tag: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.testTag("$tag:minus")) { Text("−") }
        Text("$value", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("$tag:value"))
        TextButton(onClick = { onChange(value + 1) }, modifier = Modifier.testTag("$tag:plus")) { Text("+") }
    }
}

@Composable
private fun KeeperListItem(
    r: KeeperListRow,
    editing: Boolean,
    rule: KeeperRule,
    onRule: (KeeperRule) -> Unit,
    onEdit: (String) -> Unit,
    onPlayer: (String) -> Unit,
) {
    val row = r.row
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("keeper:${row.playerId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).playerClick(row.playerId, onPlayer)) {
                Text(
                    if (row.keep) "${r.name} · Keep" else r.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (row.keep) FontWeight.Bold else FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        r.position,
                        row.worth?.let { "worth round $it" } ?: "not valued",
                        row.dynasty?.let { "dynasty $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Cost round ${row.cost}${if (row.playerId in rule.overrides) " (set)" else ""}",
                    Modifier.clickable { onEdit(row.playerId) }.testTag("cost:${row.playerId}"),
                    style = MaterialTheme.typography.labelMedium,
                )
                row.surplus?.let { Text("${if (it > 0) "+" else ""}$it rounds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            }
        }
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("Cost round", row.cost, min = 1, tag = "override:${row.playerId}") {
                    onRule(rule.copy(overrides = rule.overrides + (row.playerId to it)))
                }
                if (row.playerId in rule.overrides) {
                    TextButton(onClick = { onRule(rule.copy(overrides = rule.overrides - row.playerId)) }, modifier = Modifier.testTag("override:${row.playerId}:clear")) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}
