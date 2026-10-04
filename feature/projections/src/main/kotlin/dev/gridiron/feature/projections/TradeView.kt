package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.model.Position
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.TradeIdea
import dev.gridiron.core.projections.TradeOutcome
import dev.gridiron.core.projections.Trades
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** One rostered player in the trade view: his rest-of-season row, or a bare name when he has no projection. */
internal data class TradePlayer(val playerId: String, val name: String, val position: String?, val team: String?, val points: Double)

/** [team]'s matched players with their rest-of-season points from [rosRows] (zero without a projection), best first. */
internal fun tradePlayers(team: MyTeam, rosRows: Map<String, ProjectionRow>): List<TradePlayer> =
    team.players.mapNotNull { p ->
        val id = p.playerId ?: return@mapNotNull null
        val row = rosRows[id]
        TradePlayer(id, row?.name ?: p.name, row?.position, row?.team, row?.points ?: 0.0)
    }.sortedWith(compareByDescending<TradePlayer> { it.points }.thenBy { it.name })

/** The players who can fill a lineup slot: a known position and some rest-of-season points. */
internal fun candidates(players: List<TradePlayer>): List<LineupCandidate> =
    players.mapNotNull { p -> p.position?.takeIf { p.points > 0.0 }?.let { LineupCandidate(p.playerId, it, p.points) } }

/** "Good for both", "Helps you more", ...: how the trade reads from the user's side. */
internal fun verdict(outcome: TradeOutcome): String {
    val mine = outcome.myGain
    val theirs = outcome.theirGain
    val even = Trades.MIN_GAIN
    return when {
        mine >= even && theirs >= even -> "Good for both teams"
        mine >= even -> "Helps you, costs them: they may say no"
        theirs >= even && mine > -even -> "Helps them more than you"
        mine <= -even && theirs <= -even -> "Hurts both lineups"
        mine <= -even -> "Costs your lineup"
        else -> "About even"
    }
}

/** "+25.6" or "−7.8". */
internal fun gainText(value: Double): String =
    if (value >= 0) "+" + String.format(Locale.US, "%.1f", value) else "−" + String.format(Locale.US, "%.1f", -value)

/**
 * The Trade mode: pick a league team, tick players to send and receive, and see what the trade does to both best
 * lineups over the rest of the season; suggested trades that help both sides come first.
 */
@Composable
internal fun TradeView(
    myTeam: MyTeam,
    partners: List<MyTeam>,
    rosRows: List<ProjectionRow>,
) {
    val byId = remember(rosRows) { rosRows.associateBy { it.playerId } }
    val mine = remember(myTeam, byId) { tradePlayers(myTeam, byId) }
    val others = remember(partners, byId) { partners.associate { it.teamName to tradePlayers(it, byId) } }
    var partnerName by rememberSaveable { mutableStateOf(partners.firstOrNull()?.teamName) }
    val partner = partners.firstOrNull { it.teamName == partnerName } ?: partners.first()
    var give by rememberSaveable { mutableStateOf(listOf<String>()) }
    var get by rememberSaveable { mutableStateOf(listOf<String>()) }
    val theirs = others.getValue(partner.teamName)

    val ideas by produceState<List<TradeIdea>?>(null, myTeam, others) {
        value = withContext(Dispatchers.Default) {
            Trades.ideas(myTeam.slots, candidates(mine), others.map { (name, players) -> name to candidates(players) })
        }
    }
    val outcome = remember(give, get, partner, mine, theirs) {
        if (give.isEmpty() && get.isEmpty()) null else Trades.evaluate(myTeam.slots, candidates(mine), candidates(theirs), give.toSet(), get.toSet())
    }
    val names = remember(mine, others) { (mine + others.values.flatten()).associate { it.playerId to it.name } }

    LazyColumn(Modifier.fillMaxSize().testTag("trade")) {
        item {
            Row(
                Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (team in partners) {
                    FilterChip(
                        selected = team.teamName == partner.teamName,
                        onClick = {
                            if (team.teamName != partner.teamName) {
                                partnerName = team.teamName
                                get = emptyList()
                            }
                        },
                        label = { Text(team.teamName) },
                        modifier = Modifier.testTag("partner:${team.teamName}"),
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (outcome == null) {
                    Text(
                        "Tick players to send and receive, or tap a suggestion. Each lineup is valued at its starters' rest-of-season points.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(verdict(outcome), Modifier.testTag("trade:verdict"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Your lineup ${gainText(outcome.myGain)} (${pts(outcome.mineBefore)} → ${pts(outcome.mineAfter)})",
                        Modifier.testTag("trade:mine"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${partner.teamName} ${gainText(outcome.theirGain)} (${pts(outcome.theirsBefore)} → ${pts(outcome.theirsAfter)})",
                        Modifier.testTag("trade:theirs"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { give = emptyList(); get = emptyList() }) { Text("Clear") }
                }
            }
        }
        item { Label("Suggested trades") }
        when (val found = ideas) {
            null -> item { Note("Looking for trades that help both sides…") }
            else -> if (found.isEmpty()) {
                item { Note("No trade found that lifts both lineups by ${pts(Trades.MIN_GAIN)}+ points.") }
            } else {
                itemsIndexed(found, key = { i, _ -> "idea:$i" }) { i, idea ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            partnerName = idea.partner
                            give = idea.give.map { it.playerId }
                            get = idea.get.map { it.playerId }
                        }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("idea:$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Send ${idea.give.joinToString(" + ") { names[it.playerId] ?: it.playerId }} for " +
                                    idea.get.joinToString(" + ") { names[it.playerId] ?: it.playerId },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${idea.partner} · them ${gainText(idea.outcome.theirGain)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(gainText(idea.outcome.myGain), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Label("You send") }
        itemsIndexed(mine, key = { _, p -> "give:${p.playerId}" }) { _, p ->
            PickRow(p, p.playerId in give, "give") { on -> give = if (on) give + p.playerId else give - p.playerId }
        }
        item { Label("You receive from ${partner.teamName}") }
        itemsIndexed(theirs, key = { _, p -> "get:${p.playerId}" }) { _, p ->
            PickRow(p, p.playerId in get, "get") { on -> get = if (on) get + p.playerId else get - p.playerId }
        }
        item {
            Note(
                "Rest of season under your scoring, starters only: bench depth, byes and roster limits aren't counted, " +
                    "and a player with no projection (on IR, say) counts as nothing.",
            )
        }
    }
}

@Composable
private fun PickRow(p: TradePlayer, checked: Boolean, side: String, onCheck: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onCheck)
            .padding(start = 4.dp, end = 16.dp).testTag("$side:${p.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(p.position?.let(Position::label), p.team).joinToString(" · ").ifEmpty { "No projection" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (p.points > 0.0) pts(p.points) else "—", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)
