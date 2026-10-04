package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
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
            }
        }
    }
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
