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
import dev.gridiron.core.data.live.TradeOffer
import dev.gridiron.core.model.Position
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.TradeIdea
import dev.gridiron.core.projections.TradeOutcome
import dev.gridiron.core.projections.PartnerFit
import dev.gridiron.core.projections.TradePartners
import dev.gridiron.core.projections.Trades
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One rostered player in the trade view: his rest-of-season row, or a bare name when he has no projection. [weekly]
 * is his rest of season week by week (empty on an older database).
 */
internal data class TradePlayer(
    val playerId: String,
    val name: String,
    val position: String?,
    val team: String?,
    val points: Double,
    val weekly: Map<Int, Double> = emptyMap(),
)

/** [team]'s matched players with their rest-of-season points from [rosRows] (zero without a projection), best first. */
internal fun tradePlayers(team: MyTeam, rosRows: Map<String, ProjectionRow>, rosWeekly: Map<String, Map<Int, Double>> = emptyMap()): List<TradePlayer> =
    team.players.mapNotNull { p ->
        val id = p.playerId ?: return@mapNotNull null
        val row = rosRows[id]
        TradePlayer(id, row?.name ?: p.name, row?.position, row?.team, row?.points ?: 0.0, rosWeekly[id].orEmpty())
    }.sortedWith(compareByDescending<TradePlayer> { it.points }.thenBy { it.name })

/**
 * Everyone with a known position, as lineup candidates: a player without a projection scores nothing but still holds a
 * roster spot, so he is the first cut when a trade brings in more players than it sends.
 */
internal fun candidates(players: List<TradePlayer>): List<LineupCandidate> =
    players.mapNotNull { p -> p.position?.let { LineupCandidate(p.playerId, it, p.points, p.weekly) } }

/** [candidates] valued over [weeks] alone: each one's points in those weeks. */
internal fun onlyWeeks(candidates: List<LineupCandidate>, weeks: Collection<Int>): List<LineupCandidate> =
    candidates.map { c ->
        val kept = c.weekly.filterKeys { it in weeks }
        c.copy(points = kept.values.sum(), weekly = kept)
    }

/** "weeks 15–17", or "week 16" for one. */
internal fun weeksText(weeks: List<Int>): String =
    if (weeks.size == 1) "week ${weeks.single()}" else "weeks ${weeks.first()}–${weeks.last()}"

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
/** "Rivals · has RB Pat (120.0) · short at WR: you offer Chris (140.0)". */
internal fun partnerText(fit: PartnerFit, names: Map<String, String>): String {
    fun list(ps: List<LineupCandidate>) = ps.joinToString { "${it.position} ${names[it.playerId] ?: it.playerId} (${pts(it.points)})" }
    return listOfNotNull(
        fit.partner,
        fit.theyHave.takeIf { it.isNotEmpty() }?.let { "has ${list(it)}" },
        fit.youOffer.takeIf { it.isNotEmpty() }?.let { "short at ${fit.theyNeed.joinToString("/")}: you offer ${list(it)}" },
    ).joinToString(" · ")
}

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
    rosWeekly: Map<String, Map<Int, Double>> = emptyMap(),
    /** Pending trades involving the user's team, from ESPN; graded like any trade. */
    offers: List<TradeOffer> = emptyList(),
) {
    val byId = remember(rosRows) { rosRows.associateBy { it.playerId } }
    val mine = remember(myTeam, byId, rosWeekly) { tradePlayers(myTeam, byId, rosWeekly) }
    val others = remember(partners, byId, rosWeekly) { partners.associate { it.teamName to tradePlayers(it, byId, rosWeekly) } }
    val playoffs = myTeam.playoffWeeks
    var partnerName by rememberSaveable { mutableStateOf(partners.firstOrNull()?.teamName) }
    val partner = partners.firstOrNull { it.teamName == partnerName } ?: partners.first()
    var give by rememberSaveable { mutableStateOf(listOf<String>()) }
    var get by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Whom the user would rather cut when the trade brings in more players than it sends; else the lowest go.
    var cuts by rememberSaveable { mutableStateOf(listOf<String>()) }
    val theirs = others.getValue(partner.teamName)

    var searchMillis by remember { mutableStateOf<Long?>(null) }
    val ideas by produceState<List<TradeIdea>?>(null, myTeam, others) {
        val started = System.nanoTime()
        value = withContext(Dispatchers.Default) {
            Trades.ideas(myTeam.slots, candidates(mine), others.map { (name, players) -> name to candidates(players) })
        }
        searchMillis = (System.nanoTime() - started) / 1_000_000
    }
    val outcome = remember(give, get, cuts, partner, mine, theirs) {
        if (give.isEmpty() && get.isEmpty()) null else Trades.evaluate(myTeam.slots, candidates(mine), candidates(theirs), give.toSet(), get.toSet(), cuts.toSet())
    }
    // The same trade over the league's playoff weeks alone; null before weekly projections exist.
    val playoffOutcome = remember(give, get, cuts, partner, mine, theirs, rosWeekly) {
        if (outcome == null || rosWeekly.isEmpty()) {
            null
        } else {
            Trades.evaluate(myTeam.slots, onlyWeeks(candidates(mine), playoffs), onlyWeeks(candidates(theirs), playoffs), give.toSet(), get.toSet(), cuts.toSet())
        }
    }
    val names = remember(mine, others) { (mine + others.values.flatten()).associate { it.playerId to it.name } }
    val fits = remember(myTeam, mine, others) {
        TradePartners.rank(myTeam.slots, candidates(mine), others.map { (name, players) -> name to candidates(players) })
            .filter { f -> partners.any { it.teamName == f.partner } }
    }
    var sharing by remember { mutableStateOf(false) }
    if (sharing && outcome != null) {
        SharePreview("gridiron-trade.png", onDismiss = { sharing = false }) {
            TradeShareCard(partner.teamName, give.map { names[it] ?: it }, get.map { names[it] ?: it }, outcome)
        }
    }

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
                                cuts = emptyList()
                            }
                        },
                        label = { Text(team.teamName) },
                        modifier = Modifier.testTag("partner:${team.teamName}"),
                    )
                }
                if (outcome != null) TextButton(onClick = { sharing = true }, modifier = Modifier.testTag("share:trade")) { Text("Share") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (outcome == null) {
                    Text(
                        "Tick players to send and receive, or tap a suggestion. Each roster is valued at its best lineup's rest-of-season points.",
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
                    playoffOutcome?.let { p ->
                        Text(
                            "Playoffs (${weeksText(playoffs)}): you ${gainText(p.myGain)}, them ${gainText(p.theirGain)}",
                            Modifier.testTag("trade:playoffs"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (outcome.myDrops.isNotEmpty()) {
                        Text("You cut ${outcome.myDrops.joinToString { names[it.playerId] ?: it.playerId }} to make room.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Anyone staying on your roster can be the cut instead: a tap swaps him in for the oldest choice.
                        val room = outcome.myDrops.size
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).testTag("trade:cuts"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Cut instead:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (p in mine.filter { it.playerId !in give }.sortedBy { it.points }) {
                                val cut = outcome.myDrops.any { it.playerId == p.playerId }
                                FilterChip(
                                    selected = cut,
                                    onClick = { if (!cut) cuts = (cuts.filter { it !in give } + p.playerId).takeLast(room) },
                                    label = { Text(p.name) },
                                    modifier = Modifier.testTag("cut:${p.playerId}"),
                                )
                            }
                        }
                    }
                    if (outcome.theirDrops.isNotEmpty()) {
                        Text("${partner.teamName} cuts ${outcome.theirDrops.joinToString { names[it.playerId] ?: it.playerId }} to make room.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { give = emptyList(); get = emptyList(); cuts = emptyList() }) { Text("Clear") }
                }
            }
        }
        val graded = offers.mapNotNull { o ->
            val partnerTeam = partners.firstOrNull { it.teamName == o.partner } ?: return@mapNotNull null
            val theirRoster = others[partnerTeam.teamName] ?: return@mapNotNull null
            o to Trades.evaluate(myTeam.slots, candidates(mine), candidates(theirRoster), o.give.toSet(), o.get.toSet())
        }
        if (graded.isNotEmpty()) {
            item { Label("Pending offers (from ESPN)") }
            itemsIndexed(graded, key = { _, (o, _) -> "offer:${o.id}" }) { _, (o, out) ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        partnerName = o.partner
                        give = o.give
                        get = o.get
                        cuts = emptyList()
                    }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("offer:${o.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${if (o.fromMe) "You offered ${o.partner}" else "${o.partner} offers"}: " +
                                "${o.give.joinToString(" + ") { names[it] ?: it }.ifEmpty { "nothing" }} for ${o.get.joinToString(" + ") { names[it] ?: it }.ifEmpty { "nothing" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("${verdict(out)} · them ${gainText(out.theirGain)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(gainText(out.myGain), Modifier.testTag("offer:gain:${o.id}"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                            cuts = emptyList()
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
        searchMillis?.let { ms -> item { Note("Searched every team in ${String.format(Locale.US, "%.1f", ms / 1000.0)} s.") } }
        item { Label("You send") }
        itemsIndexed(mine, key = { _, p -> "give:${p.playerId}" }) { _, p ->
            PickRow(p, p.playerId in give, "give", playoffs) { on -> give = if (on) give + p.playerId else give - p.playerId }
        }
        item { Label("You receive from ${partner.teamName}") }
        itemsIndexed(theirs, key = { _, p -> "get:${p.playerId}" }) { _, p ->
            PickRow(p, p.playerId in get, "get", playoffs) { on -> get = if (on) get + p.playerId else get - p.playerId }
        }
        item {
            Note(
                if (rosWeekly.isEmpty()) {
                    "Rest of season under your scoring, on season totals: refresh stats to count byes week by week. " +
                        "A tenth of the best bench player counts as depth; a side that gets more players cuts its lowest."
                } else {
                    "Rest of season under your scoring, week by week, so byes count. A tenth of each week's best bench " +
                        "player counts as depth; a side that gets more players cuts its lowest (you can pick yours). A player with no " +
                        "projection (on IR, say) counts as nothing."
                },
            )
        }
        if (fits.isNotEmpty()) {
            item { Label("Best partners: deep where you're thin, short where you're deep") }
            itemsIndexed(fits, key = { _, f -> "fit:${f.partner}" }) { _, f ->
                Text(
                    partnerText(f, names),
                    Modifier.fillMaxWidth().clickable { partnerName = f.partner; get = emptyList(); cuts = emptyList() }
                        .padding(horizontal = 16.dp, vertical = 6.dp).testTag("fit:${f.partner}"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PickRow(p: TradePlayer, checked: Boolean, side: String, playoffs: List<Int>, onCheck: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onCheck)
            .padding(start = 4.dp, end = 16.dp).testTag("$side:${p.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    p.position?.let(Position::label),
                    p.team,
                    p.weekly.takeIf { it.isNotEmpty() }?.let { w -> "playoffs ${pts(playoffs.sumOf { w[it] ?: 0.0 })}" },
                ).joinToString(" · ").ifEmpty { "No projection" },
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
