package dev.gridiron.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.FantasyLeague
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.LeagueTeam
import kotlinx.coroutines.launch

/**
 * The user's ESPN fantasy league: where to read it from, the standings, and
 * each team's roster. Syncing saves the user's own team as a roster.
 */
@Composable
fun LeagueScreen(
    repo: FantasyLeagueRepository,
    season: Int,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
    /** Opens the week's matchups; null hides the button. */
    onMatchups: (() -> Unit)? = null,
) {
    val config by repo.config.collectAsState(initial = null)
    val league by repo.league.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.load() }
    var idDraft by remember(config?.leagueId) { mutableStateOf(config?.leagueId.orEmpty()) }
    var s2Draft by remember(config?.espnS2) { mutableStateOf(config?.espnS2.orEmpty()) }
    var swidDraft by remember(config?.swid) { mutableStateOf(config?.swid.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("ESPN league", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "The league id is the number in your league's ESPN URL. A private league also needs the espn_s2 and SWID " +
                                "cookies from a logged-in browser. They are sent only to ESPN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(idDraft, { idDraft = it }, Modifier.fillMaxWidth().testTag("leagueId"), label = { Text("League id") }, singleLine = true)
                        OutlinedTextField(
                            s2Draft, { s2Draft = it }, Modifier.fillMaxWidth().testTag("leagueS2"),
                            label = { Text("espn_s2 (private leagues)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(swidDraft, { swidDraft = it }, Modifier.fillMaxWidth().testTag("leagueSwid"), label = { Text("SWID (private leagues)") }, singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        syncing = true
                                        status = try {
                                            repo.setConfig(idDraft, s2Draft, swidDraft)
                                            if (idDraft.isBlank()) "League removed." else repo.sync(season).message
                                        } catch (_: IllegalArgumentException) {
                                            "A league id is digits only."
                                        } finally {
                                            syncing = false
                                        }
                                    }
                                },
                                enabled = !syncing,
                                modifier = Modifier.testTag("leagueSync"),
                            ) { Text(if (syncing) "Syncing…" else "Save and sync") }
                            status?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                league?.let { l -> leagueItems(l, config?.teamId, open, { open = if (open == it) null else it }, { id -> scope.launch { repo.chooseTeam(id) } }, onPlayer, onMatchups) }
            }
        }
    }
}

private fun LazyListScope.leagueItems(
    league: FantasyLeague,
    myTeam: Int?,
    open: Int?,
    toggle: (Int) -> Unit,
    choose: (Int) -> Unit,
    onPlayer: (String) -> Unit,
    onMatchups: (() -> Unit)?,
) {
    item {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${league.name} · ${league.season}, week ${league.week}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (onMatchups != null) TextButton(onClick = onMatchups, Modifier.testTag("leagueMatchups")) { Text("Matchups") }
        }
    }
    for (team in league.teams) {
        item(key = "t:${team.id}") {
            Column(Modifier.fillMaxWidth().clickable { toggle(team.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row {
                    Text("${team.rank}. ${team.name}${if (team.id == myTeam) " ★" else ""}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(record(team))
                }
                Text(
                    listOfNotNull(team.owner, "PF ${"%.1f".format(team.pointsFor)}", "PA ${"%.1f".format(team.pointsAgainst)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open == team.id) {
            item(key = "c:${team.id}") {
                if (team.id != myTeam) TextButton(onClick = { choose(team.id) }, Modifier.padding(start = 8.dp)) { Text("This is my team") }
            }
            items(team.players.size, key = { "p:${team.id}:${team.players[it].espnId}" }) { i ->
                val p = team.players[i]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = p.playerId != null) { p.playerId?.let(onPlayer) }.padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(p.slot, Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun record(team: LeagueTeam): String = "${team.wins}-${team.losses}" + if (team.ties > 0) "-${team.ties}" else ""
