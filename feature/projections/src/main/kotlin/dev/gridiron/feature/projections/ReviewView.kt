package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.LeagueRecap
import dev.gridiron.core.data.live.LineupReviewResult
import dev.gridiron.core.data.live.MatchupPlayer
import dev.gridiron.core.data.live.WeekReview
import java.util.Locale

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
                state.result.recap?.let { recap -> recapItems(recap) }
            }
        }
    }
}

/** The league recap under the review: the latest week around the league, then luck, record against scores. */
private fun androidx.compose.foundation.lazy.LazyListScope.recapItems(recap: LeagueRecap) {
    recap.lastWeek?.let { w ->
        item { Label("League, week ${w.week}") }
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

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
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
