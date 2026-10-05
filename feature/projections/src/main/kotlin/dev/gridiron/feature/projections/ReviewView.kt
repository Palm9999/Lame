package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.Grade
import dev.gridiron.core.data.live.LeagueRecap
import dev.gridiron.core.data.live.LineupReviewResult
import dev.gridiron.core.data.live.MatchupPlayer
import dev.gridiron.core.data.live.ReportCard
import dev.gridiron.core.data.live.WeekReview
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The lineup review: asked for when the mode opens, being fetched, or read. */
public sealed interface ReviewState {
    public data object Idle : ReviewState

    public data object Loading : ReviewState

    public data class Loaded(val result: LineupReviewResult) : ReviewState
}

/** "Started Pat (3.2) over Sam (18.4)" for one week, or null when the lineup was already the best. */
internal fun swapText(review: WeekReview): String? {
    if (review.shouldHaveStarted.isEmpty()) return null
    fun names(ps: List<MatchupPlayer>) = ps.joinToString(", ") { "${it.name} (${pts(it.espnPoints ?: 0.0)})" }
    return if (review.shouldHaveSat.isEmpty()) {
        "Start ${names(review.shouldHaveStarted)} in your open slot"
    } else {
        "Should have started ${names(review.shouldHaveStarted)} over ${names(review.shouldHaveSat)}"
    }
}

@Composable
internal fun ReviewView(state: ReviewState) {
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    (state as? ReviewState.Loaded)?.result?.let { result ->
        when (sharing) {
            "recap" -> result.recap?.lastWeek?.let { w ->
                SharePreview("gridiron-week-${w.week}.png", onDismiss = { sharing = null }) { WeeklyRecapShareCard(w, result.weeks.firstOrNull { it.week == w.week }) }
            }
            "report" -> SharePreview("gridiron-report-card.png", onDismiss = { sharing = null }) { ReportShareCard(result.reportCards, result.myTeamId) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("review")) {
        when (state) {
            ReviewState.Idle, ReviewState.Loading -> item { Note("Reading your finished weeks from ESPN…") }
            is ReviewState.Loaded -> {
                val weeks = state.result.weeks
                if (weeks.isEmpty()) {
                    item { Note("No review: ${state.result.message ?: "no finished weeks yet"}.") }
                } else {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "${pts(weeks.sumOf { it.left })} points left on your bench",
                                Modifier.testTag("review:total"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Over ${weeks.size} week${if (weeks.size == 1) "" else "s"}, by ESPN's own points; " +
                                    "${weeks.count { it.left < 0.05 }} of them already the best lineup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(weeks, key = { _, w -> "w:${w.week}" }) { _, w ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("review:${w.week}")) {
                            Text(
                                "Week ${w.week}: ${pts(w.scored)} of a possible ${pts(w.best)}" + if (w.left >= 0.05) " (−${pts(w.left)})" else " ✓",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            swapText(w)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                state.result.recap?.let { recap -> recapItems(recap) { sharing = "recap" } }
                reportCardItems(state.result) { sharing = "report" }
            }
        }
    }
}

/** The league recap under the review: the latest week around the league, then luck, record against scores. */
private fun androidx.compose.foundation.lazy.LazyListScope.recapItems(recap: LeagueRecap, onShare: () -> Unit) {
    recap.lastWeek?.let { w ->
        item { LabelWithShare("League, week ${w.week}", "share:recap", onShare) }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("recap:week")) {
                w.highScore?.let { (team, score) -> Line("Top score: $team, ${pts(score)}") }
                w.blowout?.let { g -> Line("Biggest win: ${g.winner} over ${g.loser} by ${pts(g.margin)}") }
                w.closest?.let { g -> Line("Closest: ${g.winner} over ${g.loser} by ${pts(g.margin)}") }
                if (w.topScorers.isNotEmpty()) {
                    Line("Best starters: " + w.topScorers.joinToString { (p, team) -> "${p.name} ${pts(p.espnPoints ?: 0.0)} ($team)" })
                }
            }
        }
    }
    if (recap.luck.isNotEmpty()) {
        item { Label("Luck: record against scores") }
        item {
            Text(
                "All-play wins: the games each team would have won playing every other team each week. Above zero, a better record than its scores earned.",
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(recap.luck, key = { _, t -> "luck:${t.teamId}" }) { _, t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("luck:${t.teamId}")) {
                Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${pts(t.wins)} wins vs ${pts(t.allPlayWins)} all-play · " + (if (t.luck >= 0) "+" else "−") + pts(kotlin.math.abs(t.luck)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Every manager's places, best overall first; a tap shows the numbers behind them. */
private fun androidx.compose.foundation.lazy.LazyListScope.reportCardItems(result: LineupReviewResult, onShare: () -> Unit) {
    val cards = result.reportCards
    if (cards.isEmpty()) return
    item { LabelWithShare("Report card", "share:report", onShare) }
    item {
        Text(
            "Lineups: points scored of the best lineups allowed. Strength: all-play. Luck: wins over all-play. " +
                "Draft: starting points from each team's picks, wherever they start. Moves: starters it didn't draft.",
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    result.draftMessage?.let { why -> item { Note("Draft and Moves: $why.") } }
    itemsIndexed(cards, key = { _, c -> "card:${c.teamId}" }) { _, c -> ReportCardRow(c, cards.size, c.teamId == result.myTeamId) }
}

@Composable
private fun ReportCardRow(card: ReportCard, teams: Int, mine: Boolean) {
    var open by rememberSaveable(card.teamId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 6.dp).testTag("card:${card.teamId}")) {
        Text(
            "${placeText(card.overall)} of $teams · ${card.name}" + if (mine) " (you)" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold,
        )
        Text(
            Grade.entries.mapNotNull { g -> card.places[g]?.let { "${g.label} ${placeText(it)}" } }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Text(
                listOfNotNull(
                    card.lineupShare?.let { "${(it * 100).roundToInt()}% of the best lineups" },
                    "all-play " + String.format(Locale.US, "%.3f", card.allPlay).removePrefix("0"),
                    "luck ${if (card.luck >= 0) "+" else "−"}${pts(abs(card.luck))} wins",
                    card.draftPoints?.let { "draft ${pts(it)}" },
                    card.movesPoints?.let { "moves ${pts(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LabelWithShare(text: String, tag: String, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Label(text) }
        TextButton(onClick = onShare, modifier = Modifier.padding(top = 8.dp).testTag(tag)) { Text("Share") }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
